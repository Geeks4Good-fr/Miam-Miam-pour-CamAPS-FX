package com.najah.mealannouncer.core.datastore

/**
 * Moteur qui lit l'annonce à voix haute.
 *
 * [ANDROID] est le moteur système, **toujours disponible et hors ligne** : c'est le repli qui
 * garantit qu'une annonce aboutit même sans réseau. [VOXTRAL] est le moteur Mistral, de
 * meilleure qualité mais qui envoie le texte de l'annonce sur Internet : il ne peut donc servir
 * qu'avec un consentement explicite et une clé enregistrée. [PIPER] est une voix neuronale
 * calculée **sur le téléphone** (sherpa-onnx, voix Piper fr_FR « upmc », docs/VOIX-LOCALE.md) :
 * rien ne sort de l'appareil, aucune clé n'est nécessaire, et si son modèle manque la voix
 * Android prend le relais.
 */
enum class VoiceEngine {
    ANDROID,
    VOXTRAL,
    PIPER,
}

/**
 * Identifiant de la voix Voxtral par défaut (Marie, `voice_592`).
 *
 * La valeur est répétée ici plutôt qu'importée : `core:datastore` ne dépend pas de
 * `data:voxtral` (ce serait un cycle). `VoxtralVoice.MARIE.id` porte la même valeur, et
 * `feature:voice` vérifie la correspondance.
 */
const val DEFAULT_VOICE_ID = "voice_592"

/**
 * Réglages de la voix, **sans la clé** : celle-ci ne vit que dans [VoxtralSecretStore].
 *
 * [consentMistral] vaut `false` par défaut : tant que l'utilisateur n'a pas accepté que la
 * quantité de glucides parte chez Mistral, le moteur Voxtral ne peut pas être utilisé, même
 * si le moteur est sélectionné et la clé présente.
 */
data class VoiceConfig(
    val engine: VoiceEngine = VoiceEngine.ANDROID,
    val voiceId: String = DEFAULT_VOICE_ID,
    val consentMistral: Boolean = false,
    val hasKey: Boolean = false,
) {
    /**
     * Le moteur réellement employé.
     *
     * Une sélection « Voxtral » sans consentement ou sans clé retombe sur la voix Android :
     * l'écran peut ainsi afficher honnêtement ce qui va se passer.
     */
    fun effectiveEngine(): VoiceEngine = when {
        engine == VoiceEngine.PIPER -> VoiceEngine.PIPER
        engine == VoiceEngine.VOXTRAL && consentMistral && hasKey -> VoiceEngine.VOXTRAL
        else -> VoiceEngine.ANDROID
    }

    /** Vrai si Voxtral peut réellement parler dans cet état. */
    fun canUseVoxtral(): Boolean = effectiveEngine() == VoiceEngine.VOXTRAL

    /** Vrai si la voix locale Piper doit être essayée en premier. */
    fun usesPiper(): Boolean = effectiveEngine() == VoiceEngine.PIPER
}
