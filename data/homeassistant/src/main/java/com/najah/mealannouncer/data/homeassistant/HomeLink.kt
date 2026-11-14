package com.najah.mealannouncer.data.homeassistant

import com.najah.mealannouncer.core.datastore.HomeVoiceSettings
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Où en est la liaison avec la maison, pour la notification et les réglages. */
sealed interface HomeLinkStatus {
    /** Voix de la maison désactivée, ou service arrêté. */
    data object Off : HomeLinkStatus

    data object Connecting : HomeLinkStatus

    data object Connected : HomeLinkStatus

    /**
     * Coupure ou refus. [retrying] faux : la liaison s'est arrêtée et attend un réglage
     * (jeton refusé, adresse invalide) — réessayer finirait par faire bannir le téléphone.
     */
    data class Failed(val kind: HomeAssistantErrorKind, val retrying: Boolean) : HomeLinkStatus
}

/** Une dictée reçue, en attente de l'écran du repas. */
data class PendingDictation(
    val dictation: HomeDictation,
    val receivedAtMs: Long,
)

/**
 * La liaison permanente avec Home Assistant (`docs/VOIX-MAISON.md` § 3.2, V2).
 *
 * [run] tient la connexion tant que la voix de la maison est activée, se reconnecte selon
 * [ReconnectPolicy] et accuse réception **aussitôt** de chaque dictée (HA n'attend que 5 s avant
 * de dire « Ton téléphone ne répond pas »). La dictée est ensuite posée dans [pending] pour
 * l'écran du repas, qui la prend avec [take].
 *
 * C'est aussi la [HomeVoiceLink] du dialogue : `say` et `ask` passent par la connexion du moment,
 * et échouent en [HomeAssistantErrorKind.NETWORK] s'il n'y en a pas — le dialogue bascule alors
 * sur le téléphone.
 */
@Singleton
class HomeLink @Inject constructor(
    private val client: HomeAssistantClient,
    private val settings: HomeVoiceSettings,
) : HomeVoiceLink {

    private val _status = MutableStateFlow<HomeLinkStatus>(HomeLinkStatus.Off)
    val status: StateFlow<HomeLinkStatus> = _status.asStateFlow()

    private val _pending = MutableStateFlow<PendingDictation?>(null)
    val pending: StateFlow<PendingDictation?> = _pending.asStateFlow()

    @Volatile
    private var session: HomeAssistantSession? = null

    /** Prend la dictée [expected] si elle est toujours en attente ; faux si quelqu'un l'a déjà prise. */
    fun take(expected: PendingDictation): Boolean = _pending.compareAndSet(expected, null)

    override suspend fun say(satellite: String, text: String) = current().say(satellite, text)

    override suspend fun ask(satellite: String, question: String, answers: List<ExpectedAnswer>): HomeAnswer =
        current().ask(satellite, question, answers)

    override suspend fun awaitQuiet(satellite: String) = current().awaitQuiet(satellite)

    private fun current(): HomeAssistantSession =
        session?.takeIf { it.isOpen }
            ?: throw HomeAssistantException(HomeAssistantErrorKind.NETWORK, "Pas de liaison avec la maison")

    /**
     * Tient la liaison jusqu'à ce que la voix de la maison soit désactivée, qu'un réglage manque
     * ou que le jeton soit refusé — ou jusqu'à l'annulation (arrêt du service).
     * [onDictation] est appelé après l'accusé de réception, pour réveiller l'écran.
     */
    suspend fun run(
        clock: () -> Long = System::currentTimeMillis,
        onDictation: (PendingDictation) -> Unit = {},
    ) {
        var attempt = 0
        try {
            while (true) {
                val config = settings.config.value
                if (!config.enabled) {
                    _status.value = HomeLinkStatus.Off
                    return
                }
                val token = settings.readToken()
                if (token.isNullOrBlank() || HomeAssistantUrl.websocketUrl(config.baseUrl) == null) {
                    _status.value = HomeLinkStatus.Failed(HomeAssistantErrorKind.NOT_CONFIGURED, retrying = false)
                    return
                }
                _status.value = HomeLinkStatus.Connecting
                val error = try {
                    holdConnection(config.baseUrl, token, clock, onDictation) { attempt = 0 }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (failure: Throwable) {
                    failure
                }
                session = null
                val kind = (error as? HomeAssistantException)?.kind ?: HomeAssistantErrorKind.NETWORK
                if (!ReconnectPolicy.shouldRetry(error)) {
                    _status.value = HomeLinkStatus.Failed(kind, retrying = false)
                    return
                }
                _status.value = HomeLinkStatus.Failed(kind, retrying = true)
                delay(ReconnectPolicy.delayMs(attempt++))
            }
        } finally {
            session?.close()
            session = null
            // Un échec définitif reste affiché (il attend un réglage) ; tout le reste redevient « arrêté ».
            val last = _status.value
            if (last !is HomeLinkStatus.Failed || last.retrying) _status.value = HomeLinkStatus.Off
        }
    }

    /** Une connexion, de l'authentification à la coupure ; rend la raison de la coupure. */
    private suspend fun holdConnection(
        baseUrl: String,
        token: String,
        clock: () -> Long,
        onDictation: (PendingDictation) -> Unit,
        onConnected: () -> Unit,
    ): HomeAssistantException = coroutineScope {
        val opened = client.connect(this, baseUrl, token)
        session = opened
        val dictations = opened.dictations()
        _status.value = HomeLinkStatus.Connected
        onConnected()
        val listener = launch {
            dictations.collect { dictation ->
                // L'accusé d'abord : sans lui, l'enceinte dit « Ton téléphone ne répond pas ».
                runCatching { opened.acknowledge(dictation) }
                val pending = PendingDictation(dictation, clock())
                _pending.value = pending
                onDictation(pending)
            }
        }
        val reason = opened.awaitClosed()
        listener.cancel()
        reason
    }

    /**
     * Essai de liaison pour l'écran des réglages : se connecte, s'authentifie et referme.
     * Rend `null` si tout va bien, sinon l'échec classé.
     */
    suspend fun test(baseUrl: String, token: String): HomeAssistantErrorKind? = try {
        coroutineScope {
            client.connect(this, baseUrl, token).close()
        }
        null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: HomeAssistantException) {
        error.kind
    } catch (error: Exception) {
        HomeAssistantErrorKind.NETWORK
    }

    companion object {
        /** Une dictée plus vieille n'est plus servie : la personne a renoncé ou est passée au téléphone. */
        const val PENDING_MAX_AGE_MS = 120_000L
    }
}
