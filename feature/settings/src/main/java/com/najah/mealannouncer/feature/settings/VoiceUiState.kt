package com.najah.mealannouncer.feature.settings

import com.najah.mealannouncer.core.datastore.DEFAULT_VOICE_ID
import com.najah.mealannouncer.core.datastore.VoiceConfig
import com.najah.mealannouncer.core.datastore.VoiceEngine
import com.najah.mealannouncer.feature.voice.SpokenBy

/**
 * Ce que dit la section « Voix » après un geste.
 *
 * Un message par situation réelle : clé enregistrée, clé effacée, et les deux refus possibles —
 * demander Voxtral sans consentement, ou sans clé. Rien d'autre : pas de « erreur » vague.
 */
enum class VoiceFeedback {
    NONE,
    KEY_SAVED,
    KEY_CLEARED,
    MISSING_KEY,
    CONSENT_REQUIRED,
}

/** Résultat de « Essai de voix ». */
sealed interface VoiceTrial {

    data object Idle : VoiceTrial

    data object Speaking : VoiceTrial

    /**
     * [spokenBy] dit ce qui a réellement parlé, pas ce qui était prévu ; [issue] dit pourquoi
     * Voxtral s'est tu, quand il devait parler.
     */
    data class Done(val spokenBy: SpokenBy, val issue: String? = null) : VoiceTrial
}

/**
 * Réglages de la voix vus par l'écran.
 *
 * [effectiveEngine] est calculé comme le fera l'annonce réelle ([VoiceConfig.effectiveEngine]) :
 * l'écran ne peut donc pas afficher une promesse que l'application ne tiendra pas.
 */
data class VoiceUiState(
    val engine: VoiceEngine = VoiceEngine.ANDROID,
    val voiceId: String = DEFAULT_VOICE_ID,
    val consent: Boolean = false,
    val hasKey: Boolean = false,
    val keyInput: String = "",
    val feedback: VoiceFeedback = VoiceFeedback.NONE,
    val trial: VoiceTrial = VoiceTrial.Idle,
) {

    /** Le moteur qui parlera vraiment, une fois consentement et clé pris en compte. */
    val effectiveEngine: VoiceEngine
        get() = toConfig().effectiveEngine()

    val usesVoxtral: Boolean
        get() = effectiveEngine == VoiceEngine.VOXTRAL

    fun toConfig(): VoiceConfig = VoiceConfig(
        engine = engine,
        voiceId = voiceId,
        consentMistral = consent,
        hasKey = hasKey,
    )
}

/** Les gestes de la section « Voix », regroupés pour ne pas allonger la signature de l'écran. */
data class VoiceActions(
    val onEngineChange: (VoiceEngine) -> Unit,
    val onVoiceChange: (String) -> Unit,
    val onConsentChange: (Boolean) -> Unit,
    val onKeyInputChange: (String) -> Unit,
    val onSaveKey: () -> Unit,
    val onClearKey: () -> Unit,
    val onTryVoice: () -> Unit,
)
