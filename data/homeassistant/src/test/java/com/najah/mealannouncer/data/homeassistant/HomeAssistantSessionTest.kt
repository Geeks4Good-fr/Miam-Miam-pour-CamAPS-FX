package com.najah.mealannouncer.data.homeassistant

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeAssistantSessionTest {

    private val enceinte = "assist_satellite.home_assistant_voice_09e904_satellite_assist"

    private suspend fun TestScope.connect(
        ha: FakeHomeAssistant,
        url: String = "http://100.64.0.10",
        token: String = "jeton-123",
    ): HomeAssistantSession = HomeAssistantClient(ha).connect(backgroundScope, url, token)

    private suspend fun failure(block: suspend () -> Unit): HomeAssistantException {
        try {
            block()
        } catch (error: HomeAssistantException) {
            return error
        }
        fail("Une HomeAssistantException était attendue")
        throw AssertionError()
    }

    // ── Connexion ────────────────────────────────────────────────────────────────────

    @Test
    fun `s'authentifie avec le jeton sur l'adresse websocket`() = runTest {
        val ha = FakeHomeAssistant()
        val session = connect(ha)
        assertEquals("ws://100.64.0.10/api/websocket", ha.openedUrl)
        val auth = ha.sentOfType("auth").single()
        assertEquals("jeton-123", auth.getString("access_token"))
        assertTrue("La session doit être ouverte après auth_ok", session.isOpen)
    }

    @Test
    fun `jeton refuse — UNAUTHORIZED, connexion refermee, pas de nouvel essai`() = runTest {
        val ha = FakeHomeAssistant(onRequest = {
            if (it.optString("type") == "auth") say(JSONObject().put("type", "auth_invalid"))
        })
        val error = failure { connect(ha) }
        assertEquals(HomeAssistantErrorKind.UNAUTHORIZED, error.kind)
        assertTrue("La connexion refusée doit être fermée", ha.closedByApp)
        assertFalse("Un jeton refusé ne doit jamais être réessayé (bannissement HA)", ReconnectPolicy.shouldRetry(error))
    }

    @Test
    fun `HA muet a la connexion — TIMEOUT`() = runTest {
        val ha = FakeHomeAssistant(greet = false)
        val error = failure { connect(ha) }
        assertEquals(HomeAssistantErrorKind.TIMEOUT, error.kind)
    }

    @Test
    fun `adresse vide ou jeton vide — NOT_CONFIGURED sans ouvrir de connexion`() = runTest {
        val ha = FakeHomeAssistant()
        assertEquals(HomeAssistantErrorKind.NOT_CONFIGURED, failure { connect(ha, url = " ") }.kind)
        assertEquals(HomeAssistantErrorKind.NOT_CONFIGURED, failure { connect(ha, token = "") }.kind)
        assertNull("Aucune connexion ne doit être ouverte", ha.openedUrl)
    }

    // ── Questions et annonces ────────────────────────────────────────────────────────

    @Test
    fun `ask pose la question sans carillon et rend la phrase entendue`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { message ->
            when (message.optString("type")) {
                "auth" -> say(JSONObject().put("type", "auth_ok"))
                "call_service" -> ok(
                    message.getInt("id"),
                    JSONObject().put("response", JSONObject().put("sentence", "Un seul.").put("id", JSONObject.NULL)),
                )
            }
        }
        val session = connect(ha)
        val answer = session.ask(enceinte, "Combien en manges-tu ?")
        assertEquals("Un seul.", answer.sentence)
        assertNull(answer.answerId)

        val call = ha.sentOfType("call_service").single()
        assertEquals("assist_satellite", call.getString("domain"))
        assertEquals("ask_question", call.getString("service"))
        assertTrue("ask_question doit demander la réponse", call.getBoolean("return_response"))
        val data = call.getJSONObject("service_data")
        assertEquals(enceinte, data.getString("entity_id"))
        assertEquals("Combien en manges-tu ?", data.getString("question"))
        assertFalse("Pas de carillon avant une question (§ 4 bis)", data.getBoolean("preannounce"))
        assertFalse("Sans réponses attendues, rien n'est envoyé", data.has("answers"))
    }

    @Test
    fun `ask transmet les reponses attendues et rend celle reconnue`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { message ->
            when (message.optString("type")) {
                "auth" -> say(JSONObject().put("type", "auth_ok"))
                "call_service" -> ok(
                    message.getInt("id"),
                    JSONObject().put(
                        "response",
                        JSONObject().put("sentence", "Au repas.").put("id", "repas"),
                    ),
                )
            }
        }
        val session = connect(ha)
        val answer = session.ask(
            enceinte,
            "Repas ou collation ?",
            listOf(
                ExpectedAnswer("repas", listOf("repas", "au repas")),
                ExpectedAnswer("collation", listOf("collation")),
            ),
        )
        assertEquals("repas", answer.answerId)
        assertEquals("Au repas.", answer.sentence)

        val answers = ha.sentOfType("call_service").single()
            .getJSONObject("service_data").getJSONArray("answers")
        assertEquals(2, answers.length())
        assertEquals("repas", answers.getJSONObject(0).getString("id"))
        assertEquals("au repas", answers.getJSONObject(0).getJSONArray("sentences").getString(1))
    }

    @Test
    fun `silence — la phrase est nulle`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { message ->
            when (message.optString("type")) {
                "auth" -> say(JSONObject().put("type", "auth_ok"))
                "call_service" -> ok(message.getInt("id"), JSONObject().put("response", JSONObject().put("sentence", "")))
            }
        }
        assertNull(connect(ha).ask(enceinte, "Combien ?").sentence)
    }

    @Test
    fun `rien entendu — HA refuse « No answer », c'est un silence et la liaison reste ouverte`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { message ->
            when (message.optString("type")) {
                "auth" -> say(JSONObject().put("type", "auth_ok"))
                "call_service" -> refuse(message.getInt("id"), "home_assistant_error", "No answer from question")
            }
        }
        val session = connect(ha)
        val answer = session.ask(enceinte, "Repas ou collation ?")
        assertNull(answer.sentence)
        assertNull(answer.answerId)
        assertTrue(session.isOpen)
    }

    @Test
    fun `say annonce sans carillon sur l'enceinte qui a entendu`() = runTest {
        val ha = FakeHomeAssistant()
        connect(ha).say(enceinte, "Déverrouille ton téléphone pour confirmer dans CamAPS.")
        val data = ha.sentOfType("call_service").single().also {
            assertEquals("announce", it.getString("service"))
            assertFalse("Une annonce n'attend pas de réponse", it.has("return_response"))
        }.getJSONObject("service_data")
        assertEquals(enceinte, data.getString("entity_id"))
        assertEquals("Déverrouille ton téléphone pour confirmer dans CamAPS.", data.getString("message"))
        assertFalse(data.getBoolean("preannounce"))
    }

    @Test
    fun `acknowledge ecrit le numero de seance dans le champ d'accuse`() = runTest {
        val ha = FakeHomeAssistant()
        connect(ha).acknowledge(HomeDictation("1793222734", enceinte, "un biscuit prince"))
        val call = ha.sentOfType("call_service").single()
        assertEquals("input_text", call.getString("domain"))
        assertEquals("set_value", call.getString("service"))
        val data = call.getJSONObject("service_data")
        assertEquals(HomeExchange.ACK_ENTITY, data.getString("entity_id"))
        assertEquals("1793222734", data.getString("value"))
    }

    @Test
    fun `les numeros de message croissent a chaque appel`() = runTest {
        val ha = FakeHomeAssistant()
        val session = connect(ha)
        session.say(enceinte, "Un.")
        session.say(enceinte, "Deux.")
        val ids = ha.sentOfType("call_service").map { it.getInt("id") }
        assertTrue("Les identifiants doivent croître : $ids", ids[1] > ids[0])
    }

    private val lecteur = "media_player.home_assistant_voice_09e904_media_player"

    private fun lecteurEtat(etat: String, initial: Boolean): JSONObject =
        if (initial) {
            JSONObject().put("a", JSONObject().put(lecteur, JSONObject().put("s", etat)))
        } else {
            JSONObject().put("c", JSONObject().put(lecteur, JSONObject().put("+", JSONObject().put("s", etat))))
        }

    /** HA dont le registre relie l'enceinte à son lecteur ; le lecteur part dans l'état [depart]. */
    private fun haAvecLecteur(depart: String, onSubscribe: (Int) -> Unit = {}) = FakeHomeAssistant().apply {
        onRequest = { message ->
            val id = message.optInt("id")
            when (message.optString("type")) {
                "auth" -> say(JSONObject().put("type", "auth_ok"))
                "config/entity_registry/list_for_display" -> ok(
                    id,
                    JSONObject().put(
                        "entities",
                        JSONArray()
                            .put(JSONObject().put("ei", enceinte).put("di", "voice"))
                            .put(JSONObject().put("ei", "media_player.salon").put("di", "tv"))
                            .put(JSONObject().put("ei", lecteur).put("di", "voice")),
                    ),
                )
                "subscribe_entities" -> {
                    ok(id)
                    event(id, lecteurEtat(depart, initial = true))
                    onSubscribe(id)
                }
                else -> ok(id)
            }
        }
    }

    @Test
    fun `awaitQuiet attend que le haut-parleur de l'enceinte se taise, puis se desabonne`() = runTest {
        var abonnement = -1
        val ha = haAvecLecteur("playing") { abonnement = it }
        val session = connect(ha)
        val attente = async { session.awaitQuiet(enceinte) }
        runCurrent()
        assertFalse("L'enceinte parle encore : on attend", attente.isCompleted)
        assertEquals(lecteur, ha.sentOfType("subscribe_entities").single().getJSONArray("entity_ids").getString(0))

        ha.event(abonnement, lecteurEtat("idle", initial = false))
        runCurrent()
        assertTrue("Le haut-parleur s'est tu : on rend la main", attente.isCompleted)
        assertEquals(abonnement, ha.sentOfType("unsubscribe_events").single().getInt("subscription"))
    }

    @Test
    fun `awaitQuiet — rien ne joue, on laisse au son le temps de demarrer puis on rend la main`() = runTest {
        val session = connect(haAvecLecteur("idle"))
        val attente = async { session.awaitQuiet(enceinte) }
        advanceTimeBy(HomeAssistantSession.QUIET_START_MS - 100)
        runCurrent()
        assertFalse(attente.isCompleted)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(attente.isCompleted)
    }

    @Test
    fun `awaitQuiet ne reste jamais bloque — le haut-parleur qui ne se tait pas libere apres le delai`() = runTest {
        val session = connect(haAvecLecteur("playing"))
        val attente = async { session.awaitQuiet(enceinte) }
        advanceTimeBy(HomeAssistantSession.QUIET_TIMEOUT_MS + 1_000)
        runCurrent()
        assertTrue(attente.isCompleted)
    }

    @Test
    fun `sans registre lisible, le lecteur se deduit du nom de l'enceinte`() {
        assertEquals(lecteur, HomeAssistantSession.guessMediaPlayer(enceinte))
    }

    // ── Échecs ───────────────────────────────────────────────────────────────────────

    @Test
    fun `HA refuse l'appel — SERVICE avec la raison`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { message ->
            when (message.optString("type")) {
                "auth" -> say(JSONObject().put("type", "auth_ok"))
                "call_service" -> refuse(message.getInt("id"), "unauthorized", "Unauthorized")
            }
        }
        val error = failure { connect(ha).say(enceinte, "Bonjour.") }
        assertEquals(HomeAssistantErrorKind.SERVICE, error.kind)
        assertTrue("La raison de HA doit être gardée : ${error.message}", error.message.contains("unauthorized"))
    }

    @Test
    fun `pas de reponse a une question — TIMEOUT apres le delai de question`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { if (it.optString("type") == "auth") say(JSONObject().put("type", "auth_ok")) }
        val session = connect(ha)
        val pending = async { failure { session.ask(enceinte, "Combien ?") } }
        advanceTimeBy(HomeAssistantSession.ASK_TIMEOUT_MS - 1_000)
        runCurrent()
        assertFalse("Une question ne doit pas expirer avant son délai", pending.isCompleted)
        advanceTimeBy(2_000)
        assertEquals(HomeAssistantErrorKind.TIMEOUT, pending.await().kind)
        assertTrue("Un délai dépassé ne ferme pas la connexion", session.isOpen)
    }

    @Test
    fun `la connexion tombe pendant une question — NETWORK, et les appels suivants echouent aussi`() = runTest {
        val ha = FakeHomeAssistant()
        ha.onRequest = { if (it.optString("type") == "auth") say(JSONObject().put("type", "auth_ok")) }
        val session = connect(ha)
        val pending = async { failure { session.ask(enceinte, "Combien ?") } }
        runCurrent()
        ha.drop(java.io.IOException("Wi-Fi perdu"))
        runCurrent()
        assertEquals(HomeAssistantErrorKind.NETWORK, pending.await().kind)
        assertFalse(session.isOpen)
        assertEquals(HomeAssistantErrorKind.NETWORK, session.closedReason?.kind)
        assertEquals(HomeAssistantErrorKind.NETWORK, failure { session.say(enceinte, "Encore ?") }.kind)
        assertTrue("Une coupure réseau doit être réessayée", ReconnectPolicy.shouldRetry(session.awaitClosed()))
    }

    // ── Dictées ──────────────────────────────────────────────────────────────────────

    @Test
    fun `dictations ignore l'ancienne valeur, les doublons et les valeurs douteuses`() = runTest {
        val ha = FakeHomeAssistant()
        val session = connect(ha)
        val flow = session.dictations()
        val subscribe = ha.sentOfType("subscribe_entities").single()
        assertEquals(HomeExchange.DICTATION_ENTITY, subscribe.getJSONArray("entity_ids").getString(0))
        val id = subscribe.getInt("id")

        val received = async { flow.take(2).toList() }
        runCurrent()
        ha.event(id, FakeHomeAssistant.initial("1700000000|$enceinte|vieux repas"))
        ha.event(id, FakeHomeAssistant.change("1700000000|$enceinte|vieux repas"))
        ha.event(id, FakeHomeAssistant.change("unknown"))
        ha.event(id, FakeHomeAssistant.change("1793222734|$enceinte|un biscuit prince"))
        ha.event(id, FakeHomeAssistant.change("1793222734|$enceinte|un biscuit prince"))
        ha.event(id, FakeHomeAssistant.change("1793224307|$enceinte|deux pains au chocolat"))
        runCurrent()

        assertEquals(
            listOf(
                HomeDictation("1793222734", enceinte, "un biscuit prince"),
                HomeDictation("1793224307", enceinte, "deux pains au chocolat"),
            ),
            received.await(),
        )
    }

    @Test
    fun `le flux de dictees se termine quand la connexion tombe`() = runTest {
        val ha = FakeHomeAssistant()
        val session = connect(ha)
        val flow = session.dictations()
        val received = async { flow.toList() }
        runCurrent()
        ha.drop()
        runCurrent()
        assertTrue("Aucune dictée ne doit être inventée", received.await().isEmpty())
    }

    @Test
    fun `close ferme la connexion et termine la session`() = runTest {
        val ha = FakeHomeAssistant()
        val session = connect(ha)
        session.close()
        runCurrent()
        assertTrue(ha.closedByApp)
        assertFalse(session.isOpen)
    }
}
