package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSilenceTrackerTest {

    @Test
    fun `s'arrete apres la parole suivie d'une seconde et demie de silence`() {
        val tracker = SpeechSilenceTracker()
        // Silence initial : ne coupe pas quand stopOnInitialSilence est faux.
        repeat(10) {
            assertFalse(tracker.onChunk(rms = 80.0, durationMs = 250L))
        }
        assertFalse(tracker.hasSpoken)

        // Parole pendant 500 ms.
        assertFalse(tracker.onChunk(rms = 1_200.0, durationMs = 250L))
        assertTrue(tracker.hasSpoken)
        assertFalse(tracker.onChunk(rms = 1_500.0, durationMs = 250L))

        // Silence pendant 1 250 ms : pas encore 1 500 ms.
        repeat(5) {
            assertFalse(tracker.onChunk(rms = 100.0, durationMs = 250L))
        }
        // 1 500 ms de silence atteints -> fin de phrase.
        assertTrue(tracker.onChunk(rms = 100.0, durationMs = 250L))
    }

    @Test
    fun `en mode reveil vocal, coupe aussi si personne ne parle`() {
        val tracker = SpeechSilenceTracker(
            stopOnInitialSilence = true,
            initialSilenceMaxMs = 2_000L,
        )
        repeat(7) {
            assertFalse(tracker.onChunk(rms = 60.0, durationMs = 250L))
        }
        assertTrue(tracker.onChunk(rms = 60.0, durationMs = 250L))
        assertFalse(tracker.hasSpoken)
    }

    @Test
    fun `calcule l'amplitude RMS d'un tampon PCM 16 bits`() {
        val silent = ByteArray(320)
        assertEquals(0.0, SpeechSilenceTracker.pcmRms(silent), 0.01)

        // Échantillon constant = 1000 (0x03E8 -> low=0xE8, high=0x03).
        val constant = ByteArray(200) { i -> if (i % 2 == 0) 0xE8.toByte() else 0x03.toByte() }
        assertEquals(1_000.0, SpeechSilenceTracker.pcmRms(constant), 0.5)
    }
}
