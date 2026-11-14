package com.najah.mealannouncer.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle qui protège la vie privée : Voxtral ne peut parler qu'avec **consentement et clé**.
 *
 * Ce test est petit mais il compte plus que les autres : c'est lui qui garantit qu'un réglage
 * à moitié fait ne suffit pas à envoyer une donnée de santé à Mistral.
 */
class VoiceConfigTest {

    @Test
    fun `par defaut la voix android parle`() {
        val config = VoiceConfig()

        assertEquals(VoiceEngine.ANDROID, config.effectiveEngine())
        assertFalse(config.canUseVoxtral())
        assertFalse("le consentement ne doit jamais être implicite", config.consentMistral)
    }

    @Test
    fun `voxtral demande le moteur, le consentement et la cle`() {
        val complet = VoiceConfig(
            engine = VoiceEngine.VOXTRAL,
            consentMistral = true,
            hasKey = true,
        )

        assertEquals(VoiceEngine.VOXTRAL, complet.effectiveEngine())
        assertTrue(complet.canUseVoxtral())
    }

    @Test
    fun `il manque toujours une des trois conditions`() {
        val sansMoteur = VoiceConfig(engine = VoiceEngine.ANDROID, consentMistral = true, hasKey = true)
        val sansConsentement = VoiceConfig(engine = VoiceEngine.VOXTRAL, consentMistral = false, hasKey = true)
        val sansCle = VoiceConfig(engine = VoiceEngine.VOXTRAL, consentMistral = true, hasKey = false)

        listOf(sansMoteur, sansConsentement, sansCle).forEach { config ->
            assertEquals(VoiceEngine.ANDROID, config.effectiveEngine())
            assertFalse(config.canUseVoxtral())
        }
    }

    @Test
    fun `la voix par defaut est Marie`() {
        assertEquals("voice_592", DEFAULT_VOICE_ID)
        assertEquals(DEFAULT_VOICE_ID, VoiceConfig().voiceId)
    }
}
