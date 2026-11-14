package com.najah.mealannouncer.feature.voice

import com.najah.mealannouncer.core.datastore.VoiceConfig
import com.najah.mealannouncer.core.datastore.VoiceEngine
import com.najah.mealannouncer.core.datastore.VoiceSettings
import com.najah.mealannouncer.data.voxtral.VoxtralVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'aiguillage de la parole : quel moteur parle, et ce qui se passe quand il ne peut pas.
 *
 * Les tests portent sur le contrat qui compte pour l'utilisateur : **une annonce aboutit toujours si la
 * voix Android est là**, et Voxtral ne parle jamais sans consentement et sans clé.
 */
class VoiceAnnouncerTest {

    /** Moteur factice : rend toujours [outcome] et retient ce qu'on lui a demandé. */
    private class FakeEngine(private val outcome: Boolean) : VoiceEngineTts {
        var calls = 0
        var lastText: String? = null

        override suspend fun speak(text: String): Boolean {
            calls++
            lastText = text
            return outcome
        }
    }

    /** Moteur qui lève, pour vérifier que l'exception n'empêche pas le repli. */
    private class ThrowingEngine : VoiceEngineTts {
        var calls = 0

        override suspend fun speak(text: String): Boolean {
            calls++
            throw IllegalStateException("moteur en panne")
        }
    }

    private class FakeSettings(config: VoiceConfig) : VoiceSettings {
        override val config: StateFlow<VoiceConfig> = MutableStateFlow(config)
    }

    private fun voxtralReady() = VoiceConfig(
        engine = VoiceEngine.VOXTRAL,
        consentMistral = true,
        hasKey = true,
    )

    private fun announcer(
        settings: FakeSettings,
        android: VoiceEngineTts,
        voxtral: VoiceEngineTts,
        piper: VoiceEngineTts = FakeEngine(false),
    ) = VoiceAnnouncer(settings = settings, android = android, voxtral = voxtral, piper = piper)

    @Test
    fun `piper parle en premier quand il est choisi`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(true)
        val piper = FakeEngine(true)
        val announcer = announcer(FakeSettings(VoiceConfig(engine = VoiceEngine.PIPER)), android, voxtral, piper)

        assertEquals(SpokenBy.PIPER, announcer.announce("Repas annoncé : 45 grammes de glucides."))
        assertEquals(1, piper.calls)
        assertEquals(0, voxtral.calls)
        assertEquals(0, android.calls)
    }

    @Test
    fun `sans modele piper la voix android prend le relais`() = runTest {
        val android = FakeEngine(true)
        val piper = FakeEngine(false)
        val announcer = announcer(FakeSettings(VoiceConfig(engine = VoiceEngine.PIPER)), android, FakeEngine(true), piper)

        assertEquals(SpokenBy.ANDROID, announcer.announce("Repas annoncé : 45 grammes de glucides."))
        assertEquals(1, piper.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `voxtral parle quand il est choisi, consenti et configure`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(true)
        val announcer = announcer(FakeSettings(voxtralReady()), android, voxtral)

        assertEquals(SpokenBy.VOXTRAL, announcer.announce("Repas annoncé : 62 grammes de glucides."))
        assertEquals(1, voxtral.calls)
        assertEquals(0, android.calls)
    }

    @Test
    fun `la voix android est le defaut`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(true)
        val announcer = announcer(FakeSettings(VoiceConfig()), android, voxtral)

        assertEquals(SpokenBy.ANDROID, announcer.announce("Repas annoncé : 40 grammes de glucides."))
        assertEquals(0, voxtral.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `sans consentement voxtral reste muet meme choisi`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(true)
        val config = VoiceConfig(engine = VoiceEngine.VOXTRAL, consentMistral = false, hasKey = true)
        val announcer = announcer(FakeSettings(config), android, voxtral)

        assertEquals(SpokenBy.ANDROID, announcer.announce("Repas annoncé : 55 grammes de glucides."))
        assertEquals(0, voxtral.calls)
    }

    @Test
    fun `sans cle voxtral reste muet meme consenti`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(true)
        val config = VoiceConfig(engine = VoiceEngine.VOXTRAL, consentMistral = true, hasKey = false)
        val announcer = announcer(FakeSettings(config), android, voxtral)

        assertEquals(SpokenBy.ANDROID, announcer.announce("Repas annoncé : 30 grammes de glucides."))
        assertEquals(0, voxtral.calls)
    }

    @Test
    fun `une synthese voxtral ratee bascule sur la voix android`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(false)
        val announcer = announcer(FakeSettings(voxtralReady()), android, voxtral)

        assertEquals(SpokenBy.ANDROID, announcer.announce("Repas annoncé : 70 grammes de glucides."))
        assertEquals(1, voxtral.calls)
        assertEquals(1, android.calls)
    }

    @Test
    fun `un moteur voxtral qui leve ne prive pas de la voix android`() = runTest {
        val android = FakeEngine(true)
        val voxtral = ThrowingEngine()
        val announcer = announcer(FakeSettings(voxtralReady()), android, voxtral)

        assertEquals(SpokenBy.ANDROID, announcer.announce("Repas annoncé : 20 grammes de glucides."))
        assertEquals(1, voxtral.calls)
    }

    @Test
    fun `une exception avalee laisse une trace pour l'ecran d'essai`() = runTest {
        // Sans cette trace, l'écran d'essai afficherait « Lu par la voix Android » sans dire
        // pourquoi Voxtral s'est tu : c'est exactement ce qui a coûté une heure le 26/10.
        val announcer = announcer(
            FakeSettings(voxtralReady()),
            FakeEngine(true),
            ThrowingEngine(),
        )

        announcer.announce("Repas annoncé : 45 grammes de glucides.")

        assertEquals("IllegalStateException : moteur en panne", announcer.lastSwallowed)
    }

    @Test
    fun `sans aucune voix disponible rien n'est dit sans erreur`() = runTest {
        val announcer = announcer(FakeSettings(VoiceConfig()), FakeEngine(false), FakeEngine(false))

        assertEquals(SpokenBy.NONE, announcer.announce("Repas annoncé : 10 grammes de glucides."))
    }

    @Test
    fun `un texte vide ne reveille aucune voix`() = runTest {
        val android = FakeEngine(true)
        val voxtral = FakeEngine(true)
        val announcer = announcer(FakeSettings(voxtralReady()), android, voxtral)

        assertEquals(SpokenBy.NONE, announcer.announce("   "))
        assertEquals(0, android.calls)
        assertEquals(0, voxtral.calls)
    }

    @Test
    fun `l'annonce d'un repas emploie la phrase figee par les glucides`() = runTest {
        val android = FakeEngine(true)
        val announcer = announcer(FakeSettings(VoiceConfig()), android, FakeEngine(true))

        assertEquals(SpokenBy.ANDROID, announcer.announceMeal(62.0))
        assertEquals(MealAnnouncementText.forCarbs(62.0), android.lastText)
        assertEquals("Repas annoncé : 62 grammes de glucides.", android.lastText)
    }

    @Test
    fun `la voix par defaut de datastore correspond a Marie`() {
        // core:datastore ne peut pas importer data:voxtral (cycle) : la valeur est répétée, ce
        // test garantit que les deux ne divergent pas en silence.
        assertEquals(VoxtralVoice.MARIE.id, com.najah.mealannouncer.core.datastore.DEFAULT_VOICE_ID)
    }
}
