package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTextTest {

    @Test
    fun `la reecoute confirme miam-miam mais pas miami ni myriam`() {
        assertTrue(WakeWordText.verifiedByTranscript("Miam Miam, je mange deux pains au chocolat."))
        assertTrue(WakeWordText.verifiedByTranscript("miam"))
        // Syllabes collées par Kroko (essai réel du 10/11/2026).
        assertTrue(WakeWordText.verifiedByTranscript("Yamiam"))
        assertTrue(WakeWordText.verifiedByTranscript("miammiam je mange"))
        assertTrue(WakeWordText.verifiedByTranscript("Diam Diam, j’ai mangé"))
        assertFalse(WakeWordText.verifiedByTranscript("À Miami, les secours ont évacué"))
        assertFalse(WakeWordText.verifiedByTranscript("Myriam a remporté la finale"))
        assertFalse(WakeWordText.verifiedByTranscript("Mia et Liam sont partis à Niamey"))
        assertFalse(WakeWordText.verifiedByTranscript(""))
        assertFalse(WakeWordText.verifiedByTranscript(null))
    }

    @Test
    fun `la grammaire vosk garde le mot-cle, les leurres et le jeton inconnu`() {
        val grammar = org.json.JSONArray(WakeWordText.VOSK_GRAMMAR)
        val words = (0 until grammar.length()).map { grammar.getString(it) }
        assertTrue("miam-miam" in words)
        assertTrue("[unk]" in words)
        assertTrue("miami" in words)
        assertEquals(words.size, words.toSet().size)
    }

    @Test
    fun `detecte miam-miam dans les reponses partielles et finales de Vosk`() {
        assertTrue(WakeWordText.containsWakeWord("""{"partial" : "miam-miam"}"""))
        assertTrue(WakeWordText.containsWakeWord("""{"partial" : "miam miam"}"""))
        assertTrue(WakeWordText.containsWakeWord("""{"text" : "[unk] miam-miam"}"""))
        assertTrue(WakeWordText.containsWakeWord("Miam-Miam !"))
    }

    @Test
    fun `ignore un seul miam, le silence ou le jeton inconnu`() {
        assertFalse(WakeWordText.containsWakeWord("""{"partial" : ""}"""))
        assertFalse(WakeWordText.containsWakeWord("""{"partial" : "[unk]"}"""))
        assertFalse(WakeWordText.containsWakeWord("""{"partial" : "miam"}"""))
        assertFalse(WakeWordText.containsWakeWord(null))
    }

    @Test
    fun `retire miam-miam en tete d'une phrase dite d'une traite`() {
        assertEquals(
            "je mange deux pains au chocolat",
            WakeWordText.stripWakePrefix("Miam-miam, je mange deux pains au chocolat"),
        )
        assertEquals(
            "deux biscuits Prince",
            WakeWordText.stripWakePrefix("miam miam : deux biscuits Prince"),
        )
        assertEquals(
            "annonce 25 grammes",
            WakeWordText.stripWakePrefix("Miam, miam-miam - annonce 25 grammes"),
        )
        assertEquals(
            "",
            WakeWordText.stripWakePrefix("Miam-miam !"),
        )
    }

    @Test
    fun `laisse intacte une phrase qui ne commence pas par miam-miam`() {
        assertEquals(
            "je mange un yaourt",
            WakeWordText.stripWakePrefix("je mange un yaourt"),
        )
    }
}
