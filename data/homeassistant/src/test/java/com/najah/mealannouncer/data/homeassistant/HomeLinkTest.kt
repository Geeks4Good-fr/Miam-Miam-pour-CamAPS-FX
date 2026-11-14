package com.najah.mealannouncer.data.homeassistant

import com.najah.mealannouncer.core.datastore.HomeVoiceConfig
import com.najah.mealannouncer.core.datastore.HomeVoiceSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLinkTest {

    private val enceinte = "assist_satellite.home_assistant_voice_09e904_satellite_assist"

    private class FakeSettings(
        enabled: Boolean = true,
        url: String = "http://100.64.0.10",
        var token: String? = "jeton-123",
    ) : HomeVoiceSettings {
        val flow = MutableStateFlow(HomeVoiceConfig(enabled = enabled, baseUrl = url))
        override val config: StateFlow<HomeVoiceConfig> = flow
        override fun readToken(): String? = token
    }

    private fun link(ha: FakeHomeAssistant, settings: FakeSettings = FakeSettings()) =
        HomeLink(HomeAssistantClient(ha), settings)

    @Test
    fun `voix de la maison desactivee — aucune connexion, etat arrete`() = runTest {
        val ha = FakeHomeAssistant()
        val link = link(ha, FakeSettings(enabled = false))
        link.run()
        assertEquals(0, ha.opens)
        assertEquals(HomeLinkStatus.Off, link.status.value)
    }

    @Test
    fun `sans jeton — echec definitif NOT_CONFIGURED, aucune connexion`() = runTest {
        val ha = FakeHomeAssistant()
        val link = link(ha, FakeSettings(token = null))
        link.run()
        assertEquals(0, ha.opens)
        assertEquals(HomeLinkStatus.Failed(HomeAssistantErrorKind.NOT_CONFIGURED, retrying = false), link.status.value)
    }

    @Test
    fun `jeton refuse — une seule tentative, echec definitif (pas de bannissement)`() = runTest {
        val ha = FakeHomeAssistant(onRequest = {
            if (it.optString("type") == "auth") say(JSONObject().put("type", "auth_invalid"))
        })
        val link = link(ha)
        link.run()
        assertEquals("Un jeton refusé ne doit jamais être réessayé", 1, ha.opens)
        assertEquals(HomeLinkStatus.Failed(HomeAssistantErrorKind.UNAUTHORIZED, retrying = false), link.status.value)
    }

    @Test
    fun `une dictee — accuse de reception aussitot, puis mise en attente pour l'ecran`() = runTest {
        val ha = FakeHomeAssistant()
        val link = link(ha)
        val received = mutableListOf<PendingDictation>()
        val job = backgroundScope.launch { link.run(clock = { 42L }) { received += it } }
        runCurrent()
        assertEquals(HomeLinkStatus.Connected, link.status.value)
        val subscribeId = ha.sentOfType("subscribe_entities").single().getInt("id")

        ha.event(subscribeId, FakeHomeAssistant.initial("1|$enceinte|ancien"))
        ha.event(subscribeId, FakeHomeAssistant.change("1793222734|$enceinte|un biscuit prince"))
        runCurrent()

        val ack = ha.sentOfType("call_service").single().getJSONObject("service_data")
        assertEquals(HomeExchange.ACK_ENTITY, ack.getString("entity_id"))
        assertEquals("1793222734", ack.getString("value"))
        val pending = link.pending.value!!
        assertEquals("un biscuit prince", pending.dictation.text)
        assertEquals(42L, pending.receivedAtMs)
        assertEquals(listOf(pending), received)

        assertTrue("La première prise réussit", link.take(pending))
        assertFalse("Une dictée ne se prend qu'une fois", link.take(pending))
        assertNull(link.pending.value)
        job.cancel()
    }

    @Test
    fun `coupure — reconnexion apres le premier delai, sans repartir de zero`() = runTest {
        val ha = FakeHomeAssistant()
        val link = link(ha)
        backgroundScope.launch { link.run() }
        runCurrent()
        assertEquals(1, ha.opens)

        ha.drop(java.io.IOException("Wi-Fi perdu"))
        runCurrent()
        assertEquals(HomeLinkStatus.Failed(HomeAssistantErrorKind.NETWORK, retrying = true), link.status.value)
        advanceTimeBy(ReconnectPolicy.delayMs(0) - 100)
        runCurrent()
        assertEquals("Pas de reconnexion avant le délai", 1, ha.opens)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, ha.opens)
        assertEquals(HomeLinkStatus.Connected, link.status.value)
    }

    @Test
    fun `say et ask passent par la connexion du moment`() = runTest {
        val ha = FakeHomeAssistant()
        val link = link(ha)
        backgroundScope.launch { link.run() }
        runCurrent()
        link.say(enceinte, "C'est noté.")
        assertEquals("announce", ha.sentOfType("call_service").last().getString("service"))
    }

    @Test
    fun `sans connexion — say echoue en NETWORK pour que le dialogue bascule`() = runTest {
        val link = link(FakeHomeAssistant())
        try {
            link.say(enceinte, "Bonjour.")
            fail("say doit échouer sans connexion")
        } catch (error: HomeAssistantException) {
            assertEquals(HomeAssistantErrorKind.NETWORK, error.kind)
        }
    }

    @Test
    fun `arret du service — connexion fermee, etat arrete`() = runTest {
        val ha = FakeHomeAssistant()
        val link = link(ha)
        val job = backgroundScope.launch { link.run() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(ha.closedByApp)
        assertEquals(HomeLinkStatus.Off, link.status.value)
    }

    @Test
    fun `essai de liaison des reglages — null si tout va bien, sinon la raison`() = runTest {
        assertNull(link(FakeHomeAssistant()).test("http://100.64.0.10", "jeton"))
        val refuse = FakeHomeAssistant(onRequest = {
            if (it.optString("type") == "auth") say(JSONObject().put("type", "auth_invalid"))
        })
        assertEquals(HomeAssistantErrorKind.UNAUTHORIZED, link(refuse).test("http://100.64.0.10", "faux"))
        assertEquals(HomeAssistantErrorKind.NOT_CONFIGURED, link(FakeHomeAssistant()).test("", "jeton"))
    }
}
