package com.najah.mealannouncer.data.homeassistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Ouvre des sessions avec Home Assistant.
 *
 * Une session = une connexion WebSocket authentifiée. Quand elle tombe, on en ouvre une autre
 * (voir [ReconnectPolicy]) : une session ne se répare pas.
 */
class HomeAssistantClient @Inject constructor(
    private val transport: WebSocketTransport,
) {
    /**
     * Se connecte à [baseUrl] (l'adresse saisie dans les réglages) avec le jeton longue durée
     * [token], qui n'est jamais journalisé. La lecture des messages tourne dans [scope].
     */
    suspend fun connect(
        scope: CoroutineScope,
        baseUrl: String,
        token: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): HomeAssistantSession {
        val url = HomeAssistantUrl.websocketUrl(baseUrl)
            ?: throw HomeAssistantException(
                HomeAssistantErrorKind.NOT_CONFIGURED,
                "Adresse de Home Assistant invalide",
            )
        if (token.isBlank()) {
            throw HomeAssistantException(HomeAssistantErrorKind.NOT_CONFIGURED, "Aucun jeton Home Assistant")
        }
        val session = HomeAssistantSession()
        session.open(transport, url)
        try {
            session.authenticate(token, timeoutMs)
        } catch (error: Throwable) {
            session.close()
            throw error
        }
        session.startReading(scope)
        return session
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

/**
 * Une connexion authentifiée à Home Assistant, réduite à ce dont le repas a besoin.
 *
 * Toutes les méthodes lèvent [HomeAssistantException] (déjà classée) en cas d'échec ; une
 * fois la connexion tombée, [closedReason] dit pourquoi et tout nouvel appel échoue en
 * [HomeAssistantErrorKind.NETWORK]. Le dialogue bascule alors sur le téléphone (§ 5).
 */
class HomeAssistantSession internal constructor() : HomeVoiceLink {

    private sealed interface Incoming {
        data class Text(val text: String) : Incoming
        data class Closed(val error: Throwable?) : Incoming
    }

    private val incoming = Channel<Incoming>(Channel.UNLIMITED)
    private lateinit var connection: WebSocketConnection
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val subscriptions = ConcurrentHashMap<Int, Channel<JSONObject>>()
    private val closed = AtomicReference<HomeAssistantException?>(null)
    private val players = ConcurrentHashMap<String, String>()
    private val finished = CompletableDeferred<HomeAssistantException>()

    /** Raison de la fin de la connexion, ou `null` tant qu'elle est ouverte. */
    val closedReason: HomeAssistantException?
        get() = closed.get()

    val isOpen: Boolean
        get() = closed.get() == null

    /** Attend la fin de la connexion et en rend la raison. */
    suspend fun awaitClosed(): HomeAssistantException = finished.await()

    internal fun open(transport: WebSocketTransport, url: String) {
        connection = transport.open(
            url,
            object : WebSocketEvents {
                override fun onOpen() = Unit
                override fun onMessage(text: String) {
                    incoming.trySend(Incoming.Text(text))
                }
                override fun onClosed(error: Throwable?) {
                    incoming.trySend(Incoming.Closed(error))
                }
            },
        )
    }

    /** Échange `auth_required` → `auth` → `auth_ok`, avant toute autre chose. */
    internal suspend fun authenticate(token: String, timeoutMs: Long) {
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    when (val message = incoming.receive()) {
                        is Incoming.Closed -> throw networkError(message.error)
                        is Incoming.Text -> when (parse(message.text).optString("type")) {
                            "auth_required" -> sendOrFail(
                                JSONObject().put("type", "auth").put("access_token", token),
                            )
                            "auth_ok" -> return@withTimeout
                            "auth_invalid" -> throw HomeAssistantException(
                                HomeAssistantErrorKind.UNAUTHORIZED,
                                "Home Assistant a refusé le jeton",
                            )
                            else -> Unit
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw HomeAssistantException(
                HomeAssistantErrorKind.TIMEOUT,
                "Home Assistant n'a pas répondu à l'authentification",
                timeout,
            )
        }
    }

    /** Distribue les réponses et les événements, jusqu'à la fin de la connexion. */
    internal fun startReading(scope: CoroutineScope) {
        scope.launch {
            for (message in incoming) {
                when (message) {
                    is Incoming.Closed -> {
                        finish(networkError(message.error))
                        return@launch
                    }
                    is Incoming.Text -> dispatch(message.text)
                }
            }
        }
    }

    private fun dispatch(text: String) {
        val json = try {
            JSONObject(text)
        } catch (_: JSONException) {
            return // Un message illisible ne doit pas couper le lien.
        }
        val id = json.optInt("id", -1)
        when (json.optString("type")) {
            "result" -> pending.remove(id)?.complete(json)
            "event" -> json.optJSONObject("event")?.let { subscriptions[id]?.trySend(it) }
        }
    }

    private fun finish(reason: HomeAssistantException) {
        if (!closed.compareAndSet(null, reason)) return
        pending.values.forEach { it.completeExceptionally(reason) }
        pending.clear()
        subscriptions.values.forEach { it.close() }
        subscriptions.clear()
        finished.complete(reason)
    }

    /** Ferme la connexion volontairement. */
    fun close() {
        if (this::connection.isInitialized) connection.close()
        incoming.trySend(
            Incoming.Closed(HomeAssistantException(HomeAssistantErrorKind.NETWORK, "Connexion fermée par l'appli")),
        )
    }

    // ── Ce que le repas demande à la maison ─────────────────────────────────────────────

    /**
     * Les phrases de repas dites aux enceintes, au fil de l'eau.
     *
     * La valeur présente au moment de l'abonnement est une **ancienne** dictée : elle est
     * ignorée. Le flux se termine quand la connexion tombe.
     */
    suspend fun dictations(): Flow<HomeDictation> {
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val id = nextId.getAndIncrement()
        subscriptions[id] = events
        try {
            request(
                id,
                JSONObject()
                    .put("type", "subscribe_entities")
                    .put("entity_ids", JSONArray().put(HomeExchange.DICTATION_ENTITY)),
                REQUEST_TIMEOUT_MS,
            )
        } catch (error: Throwable) {
            subscriptions.remove(id)
            events.close()
            throw error
        }
        return flow {
            var last: String? = null
            for (event in events) {
                val initial = event.optJSONObject("a")?.optJSONObject(HomeExchange.DICTATION_ENTITY)
                if (initial != null) {
                    last = initial.text("s")
                    continue
                }
                val value = event.optJSONObject("c")
                    ?.optJSONObject(HomeExchange.DICTATION_ENTITY)
                    ?.optJSONObject("+")
                    ?.text("s")
                    ?: continue
                if (value == last) continue
                last = value
                HomeDictation.parse(value)?.let { emit(it) }
            }
        }
    }

    /** Accuse réception d'une dictée : l'enceinte dit « C'est noté, je regarde. » */
    suspend fun acknowledge(dictation: HomeDictation) {
        callService(
            domain = "input_text",
            service = "set_value",
            data = JSONObject()
                .put("entity_id", HomeExchange.ACK_ENTITY)
                .put("value", dictation.session),
        )
    }

    /** Fait dire [text] par l'enceinte [satellite], sans carillon. Revient quand elle a fini. */
    override suspend fun say(satellite: String, text: String) {
        callService(
            domain = "assist_satellite",
            service = "announce",
            data = JSONObject()
                .put("entity_id", satellite)
                .put("message", text)
                .put("preannounce", false),
            timeoutMs = SPEAK_TIMEOUT_MS,
        )
    }

    /**
     * Attend que le lecteur de [satellite] ne soit plus en train de jouer, [QUIET_TIMEOUT_MS] au
     * plus. Si rien ne joue encore, on laisse [QUIET_START_MS] au son pour démarrer.
     */
    override suspend fun awaitQuiet(satellite: String) {
        val player = mediaPlayerOf(satellite)
        val events = Channel<JSONObject>(Channel.UNLIMITED)
        val id = nextId.getAndIncrement()
        subscriptions[id] = events
        try {
            request(
                id,
                JSONObject().put("type", "subscribe_entities").put("entity_ids", JSONArray().put(player)),
                REQUEST_TIMEOUT_MS,
            )
            withTimeoutOrNull(QUIET_TIMEOUT_MS) {
                var playing = false
                var sawState = false
                while (true) {
                    val event = if (sawState && !playing) {
                        // Rien ne joue : on laisse un instant au son pour démarrer, sinon c'est fini.
                        withTimeoutOrNull(QUIET_START_MS) { events.receiveCatching().getOrNull() } ?: break
                    } else {
                        events.receiveCatching().getOrNull() ?: break
                    }
                    val state = event.optJSONObject("a")?.optJSONObject(player)?.text("s")
                        ?: event.optJSONObject("c")?.optJSONObject(player)?.optJSONObject("+")?.text("s")
                        ?: continue
                    if (playing && state != PLAYING) break
                    playing = state == PLAYING
                    sawState = true
                }
            }
        } finally {
            subscriptions.remove(id)
            events.close()
            if (isOpen) {
                runCatching {
                    request(
                        nextId.getAndIncrement(),
                        JSONObject().put("type", "unsubscribe_events").put("subscription", id),
                        REQUEST_TIMEOUT_MS,
                    )
                }
            }
        }
    }

    /**
     * Le lecteur de l'enceinte : l'entité `media_player` du même appareil que [satellite]. À défaut
     * de registre lisible, le nom que l'intégration ESPHome lui donne.
     */
    private suspend fun mediaPlayerOf(satellite: String): String {
        players[satellite]?.let { return it }
        val found = runCatching {
            val entities = request(
                nextId.getAndIncrement(),
                JSONObject().put("type", "config/entity_registry/list_for_display"),
                REQUEST_TIMEOUT_MS,
            ).optJSONArray("entities") ?: JSONArray()
            val all = (0 until entities.length()).mapNotNull { entities.optJSONObject(it) }
            val device = all.firstOrNull { it.optString("ei") == satellite }?.text("di")
            device?.let { d ->
                all.firstOrNull { it.text("di") == d && it.optString("ei").startsWith("media_player.") }
                    ?.optString("ei")
            }
        }.getOrNull() ?: guessMediaPlayer(satellite)
        players[satellite] = found
        return found
    }

    /**
     * Pose [question] par l'enceinte [satellite], qui rouvre son micro d'elle-même, et rend ce
     * qu'elle a entendu. [answers] aide Home Assistant à reconnaître une réponse courte.
     */
    override suspend fun ask(
        satellite: String,
        question: String,
        answers: List<ExpectedAnswer>,
    ): HomeAnswer {
        val data = JSONObject()
            .put("entity_id", satellite)
            .put("question", question)
            .put("preannounce", false)
        if (answers.isNotEmpty()) {
            data.put(
                "answers",
                JSONArray().apply {
                    answers.forEach { answer ->
                        put(
                            JSONObject()
                                .put("id", answer.id)
                                .put("sentences", JSONArray(answer.sentences)),
                        )
                    }
                },
            )
        }
        val result = try {
            callService(
                domain = "assist_satellite",
                service = "ask_question",
                data = data,
                returnResponse = true,
                timeoutMs = ASK_TIMEOUT_MS,
            )
        } catch (error: HomeAssistantException) {
            // Essai du 29/10/2026 : quand l'enceinte n'entend rien, HA refuse l'appel avec
            // « No answer from question ». C'est un silence, pas une liaison perdue.
            if (error.kind == HomeAssistantErrorKind.SERVICE && error.message.contains(NO_ANSWER)) {
                return HomeAnswer(sentence = null)
            }
            throw error
        }
        val response = result.optJSONObject("response") ?: return HomeAnswer(sentence = null)
        return HomeAnswer(sentence = response.text("sentence"), answerId = response.text("id"))
    }

    private suspend fun callService(
        domain: String,
        service: String,
        data: JSONObject,
        returnResponse: Boolean = false,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
    ): JSONObject {
        val message = JSONObject()
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
            .put("service_data", data)
        if (returnResponse) message.put("return_response", true)
        return request(nextId.getAndIncrement(), message, timeoutMs)
    }

    /** Envoie [message] sous le numéro [id] et rend le `result` de la réponse. */
    private suspend fun request(id: Int, message: JSONObject, timeoutMs: Long): JSONObject {
        closed.get()?.let { throw it }
        val answer = CompletableDeferred<JSONObject>()
        pending[id] = answer
        try {
            sendOrFail(message.put("id", id))
            val reply = withTimeout(timeoutMs) { answer.await() }
            if (!reply.optBoolean("success", false)) {
                val error = reply.optJSONObject("error")
                throw HomeAssistantException(
                    HomeAssistantErrorKind.SERVICE,
                    "Home Assistant a refusé « ${message.optString("type")} » : " +
                        "${error?.optString("code").orEmpty()} ${error?.optString("message").orEmpty()}".trim(),
                )
            }
            return reply.optJSONObject("result") ?: JSONObject()
        } catch (timeout: TimeoutCancellationException) {
            throw HomeAssistantException(
                HomeAssistantErrorKind.TIMEOUT,
                "Home Assistant n'a pas répondu à « ${message.optString("type")} »",
                timeout,
            )
        } finally {
            pending.remove(id)
        }
    }

    private fun sendOrFail(message: JSONObject) {
        if (!connection.send(message.toString())) {
            throw HomeAssistantException(HomeAssistantErrorKind.NETWORK, "Connexion à Home Assistant fermée")
        }
    }

    private fun parse(text: String): JSONObject = try {
        JSONObject(text)
    } catch (error: JSONException) {
        throw HomeAssistantException(HomeAssistantErrorKind.INVALID_RESPONSE, "Message illisible", error)
    }

    /**
     * Texte non vide de [key], ou `null`. Sur Android, `optString` d'un JSON `null` rend la
     * chaîne « null » : on teste donc `isNull` d'abord, pour ne jamais dire « null » à voix haute.
     */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key, "").trim().ifEmpty { null }

    private fun networkError(error: Throwable?): HomeAssistantException =
        error as? HomeAssistantException
            ?: HomeAssistantException(HomeAssistantErrorKind.NETWORK, "Connexion à Home Assistant perdue", error)

    companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L

        /** Une annonce revient quand la phrase est dite : on laisse le temps d'une longue phrase. */
        const val SPEAK_TIMEOUT_MS = 45_000L

        /** Question + écoute (jusqu'à ~15 s) + transcription : large, pour ne pas couper une réponse. */
        const val ASK_TIMEOUT_MS = 60_000L

        /** Au-delà, on cesse d'attendre le silence : une longue phrase dure rarement plus. */
        const val QUIET_TIMEOUT_MS = 30_000L

        /** Délai laissé au son pour démarrer quand le lecteur ne joue pas encore. */
        const val QUIET_START_MS = 800L

        private const val PLAYING = "playing"

        /** `assist_satellite.xxx_satellite_assist` → `media_player.xxx_media_player` (ESPHome). */
        internal fun guessMediaPlayer(satellite: String): String =
            "media_player." + satellite.substringAfter('.').removeSuffix("_satellite_assist")
                .removeSuffix("_assist_satellite") + "_media_player"

        /** Refus de `ask_question` quand l'enceinte n'a rien entendu (HA 2026.10). */
        internal const val NO_ANSWER = "No answer from question"
    }
}
