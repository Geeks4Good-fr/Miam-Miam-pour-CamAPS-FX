package com.najah.mealannouncer.data.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantUrlTest {

    @Test
    fun `adresse http — ws avec le chemin de l'api`() {
        assertEquals("ws://100.64.0.10/api/websocket", HomeAssistantUrl.websocketUrl("http://100.64.0.10"))
        assertEquals("ws://100.64.0.10/api/websocket", HomeAssistantUrl.websocketUrl("http://100.64.0.10/"))
    }

    @Test
    fun `adresse https — wss`() {
        assertEquals(
            "wss://ha.exemple.fr/api/websocket",
            HomeAssistantUrl.websocketUrl(" https://ha.exemple.fr/ "),
        )
    }

    @Test
    fun `sans schema — http par defaut, le port est garde`() {
        assertEquals("ws://192.168.1.104:8123/api/websocket", HomeAssistantUrl.websocketUrl("192.168.1.104:8123"))
    }

    @Test
    fun `une adresse websocket deja complete reste la meme`() {
        assertEquals(
            "ws://100.64.0.10/api/websocket",
            HomeAssistantUrl.websocketUrl("ws://100.64.0.10/api/websocket"),
        )
    }

    @Test
    fun `adresse vide, schema etranger ou espace — refusee`() {
        assertNull(HomeAssistantUrl.websocketUrl(""))
        assertNull(HomeAssistantUrl.websocketUrl("ftp://100.64.0.10"))
        assertNull(HomeAssistantUrl.websocketUrl("http://ha maison"))
    }
}

class HomeDictationTest {

    private val enceinte = "assist_satellite.home_assistant_voice_09e904_satellite_assist"

    @Test
    fun `lit seance, enceinte et phrase`() {
        assertEquals(
            HomeDictation("1793222734", enceinte, "un biscuit prince"),
            HomeDictation.parse("1793222734|$enceinte| un biscuit prince "),
        )
    }

    @Test
    fun `la phrase peut contenir une barre verticale`() {
        assertEquals("pain | beurre", HomeDictation.parse("1|$enceinte|pain | beurre")?.text)
    }

    @Test
    fun `valeurs douteuses — ignorees`() {
        listOf(
            null, "", "unknown", "unavailable",
            "1793222734|$enceinte|",
            "abc|$enceinte|un biscuit",
            "1793222734|media_player.salon|un biscuit",
            "1793222734|assist_satellite.|un biscuit",
            "1793222734|un biscuit",
        ).forEach { assertNull("« $it » ne doit pas lancer un repas", HomeDictation.parse(it)) }
    }

    @Test
    fun `mot-cle miam-miam seul — reconnu comme reveil sans phrase`() {
        assertTrue(HomeDictation("1", enceinte, HomeDictation.WAKE_ONLY).isWakeOnly)
        assertTrue(HomeDictation("1", enceinte, "miam-miam").isWakeOnly)
        assertTrue(HomeDictation("1", enceinte, "Miam miam !").isWakeOnly)
        assertFalse(HomeDictation("1", enceinte, "miam-miam deux biscuits").isWakeOnly)
        assertFalse(HomeDictation("1", enceinte, "deux biscuits").isWakeOnly)
    }
}

class ReconnectPolicyTest {

    @Test
    fun `vite d'abord puis de plus en plus espace, plafonne`() {
        val delays = (0..7).map { ReconnectPolicy.delayMs(it) }
        assertEquals(1_000L, delays[0])
        assertTrue("Les délais ne doivent jamais diminuer : $delays", delays.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, delays.last())
    }

    @Test
    fun `seules les pannes passageres sont reessayees`() {
        fun retry(kind: HomeAssistantErrorKind) = ReconnectPolicy.shouldRetry(HomeAssistantException(kind, "x"))
        assertTrue(retry(HomeAssistantErrorKind.NETWORK))
        assertTrue(retry(HomeAssistantErrorKind.TIMEOUT))
        assertFalse(retry(HomeAssistantErrorKind.UNAUTHORIZED))
        assertFalse(retry(HomeAssistantErrorKind.NOT_CONFIGURED))
        assertTrue("Une panne inconnue (IOException brute) est passagère", ReconnectPolicy.shouldRetry(java.io.IOException()))
    }
}
