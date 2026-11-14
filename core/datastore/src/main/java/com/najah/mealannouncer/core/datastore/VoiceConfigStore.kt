package com.najah.mealannouncer.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Ce que la voix doit savoir d'elle-même, pour l'interface et pour l'annonce. */
interface VoiceSettings {

    /** Réglages courants, observables par l'interface. */
    val config: StateFlow<VoiceConfig>
}

/**
 * Réglages de la voix : moteur, voix, consentement.
 *
 * La clé Mistral n'est pas ici : elle est confiée à [VoxtralSecretStore], et cette classe ne
 * retient que le fait qu'elle existe (`hasKey`) — jamais sa valeur, jamais dans un journal.
 */
@Singleton
class VoiceConfigStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: VoxtralSecretStore,
) : VoiceSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    override val config: StateFlow<VoiceConfig> = _config.asStateFlow()

    /** Vrai si une clé Mistral est enregistrée (sans la lire). */
    fun hasKey(): Boolean = secrets.hasSecret()

    /**
     * Enregistre les réglages. [key] n'est écrit que s'il est fourni : laisser le champ vide
     * dans l'interface conserve la clé existante.
     */
    fun save(config: VoiceConfig, key: String? = null) {
        if (!key.isNullOrEmpty()) {
            secrets.write(key)
        }
        val stored = config.copy(hasKey = secrets.hasSecret())
        prefs.edit()
            .putString(KEY_ENGINE, stored.engine.name)
            .putString(KEY_VOICE_ID, stored.voiceId)
            .putBoolean(KEY_CONSENT, stored.consentMistral)
            .apply()
        _config.value = stored
    }

    /** Oublie la clé Mistral et revient à la voix Android, sans consentement. */
    fun clearKey() {
        secrets.clear()
        save(config.value.copy(engine = VoiceEngine.ANDROID, consentMistral = false, hasKey = false))
    }

    private fun read(): VoiceConfig = VoiceConfig(
        engine = runCatching {
            VoiceEngine.valueOf(prefs.getString(KEY_ENGINE, null) ?: "")
        }.getOrDefault(VoiceEngine.ANDROID),
        voiceId = prefs.getString(KEY_VOICE_ID, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE_ID,
        consentMistral = prefs.getBoolean(KEY_CONSENT, false),
        hasKey = secrets.hasSecret(),
    )

    private companion object {
        const val PREFS_NAME = "mealannouncer-voice"
        const val KEY_ENGINE = "engine"
        const val KEY_VOICE_ID = "voiceId"
        const val KEY_CONSENT = "consentMistral"
    }
}
