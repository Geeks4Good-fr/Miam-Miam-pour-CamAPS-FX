package com.najah.mealannouncer.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réglages de la « voix de la maison » (`docs/VOIX-MAISON.md`, V2–V4).
 *
 * Désactivée par défaut : tant que l'utilisateur ne l'allume pas, l'appli n'ouvre aucune connexion vers
 * Home Assistant. L'adresse est celle que l'on tape dans un navigateur ; le jeton (compte HA
 * « Meal Announcer », non administrateur) vit à part, dans son coffre Keystore.
 */
data class HomeVoiceConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_HOME_URL,
)

/**
 * Aucune adresse par défaut : chacun saisit celle de son Home Assistant. Préférer une adresse
 * HTTPS : une adresse `http://` (Tailscale, réseau local) est bloquée par Android, qui refuse
 * le trafic non chiffré.
 */
const val DEFAULT_HOME_URL = ""

/** Réglages de la voix de la maison, observables, avec la lecture du jeton. */
interface HomeVoiceSettings {

    val config: StateFlow<HomeVoiceConfig>

    /** Jeton longue durée en clair, ou `null` s'il n'est pas enregistré. Jamais journalisé. */
    fun readToken(): String?
}

/** Même patron que [MealRecordConfigStore] ; le jeton est délégué à [HomeAssistantSecretStore]. */
@Singleton
class HomeVoiceConfigStore @Inject constructor(
    @ApplicationContext context: Context,
    private val tokens: HomeAssistantSecretStore,
) : HomeVoiceSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    override val config: StateFlow<HomeVoiceConfig> = _config.asStateFlow()

    override fun readToken(): String? = tokens.read()

    fun hasToken(): Boolean = tokens.hasSecret()

    fun saveToken(token: String) = tokens.write(token.trim())

    fun forgetToken() = tokens.clear()

    fun save(config: HomeVoiceConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_URL, config.baseUrl.trim())
            .apply()
        _config.value = config.copy(baseUrl = config.baseUrl.trim())
    }

    private fun read(): HomeVoiceConfig {
        val defaults = HomeVoiceConfig()
        return HomeVoiceConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
            baseUrl = prefs.getString(KEY_URL, null) ?: defaults.baseUrl,
        )
    }

    private companion object {
        const val PREFS_NAME = "mealannouncer-home-voice"
        const val KEY_ENABLED = "enabled"
        const val KEY_URL = "baseUrl"
    }
}
