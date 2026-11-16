package com.najah.mealannouncer.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réglages de la page « À propos » : mode debug et mises à jour.
 *
 * Le **mode debug** est une décision explicite de l'utilisateur, prise en connaissance de
 * cause : coché, il accepte que des journaux — qui peuvent contenir des informations
 * personnelles (repas, produits, messages techniques) — partent chez le développeur. Décoché,
 * rien ne sort.
 */
data class DebugConfig(
    /** Envoi des journaux au développeur après un incident ou une annonce. */
    val debugMode: Boolean = false,

    /**
     * Vérification automatique des mises à jour au démarrage.
     *
     * Activée par défaut : c'est une application de santé, une version fautive doit être
     * corrigée vite. La vérification interroge seulement GitHub, jamais un service tiers.
     */
    val autoUpdate: Boolean = true,

    /**
     * Jeton GitHub permettant d'envoyer les journaux dans un « Gist » privé.
     *
     * Facultatif : sans jeton, les journaux sont proposés par le partage d'Android (courriel,
     * messagerie), ce qui fonctionne toujours mais demande un geste de plus.
     */
    val gistToken: String? = null,
) {
    /** Vrai si les journaux peuvent partir tout seuls, sans geste de l'utilisateur. */
    fun canShipAutomatically(): Boolean = debugMode && !gistToken.isNullOrBlank()
}

/** Réglages observables de la page « À propos ». */
interface DebugSettings {
    val config: StateFlow<DebugConfig>
    fun save(config: DebugConfig)
}

/** Patron habituel du projet : un `StateFlow` adossé à des `SharedPreferences`. */
@Singleton
class DebugConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) : DebugSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    override val config: StateFlow<DebugConfig> = _config.asStateFlow()

    override fun save(config: DebugConfig) {
        prefs.edit()
            .putBoolean(KEY_DEBUG, config.debugMode)
            .putBoolean(KEY_AUTO_UPDATE, config.autoUpdate)
            .putString(KEY_GIST_TOKEN, config.gistToken)
            .apply()
        _config.value = config
    }

    private fun read(): DebugConfig {
        val defaults = DebugConfig()
        return DebugConfig(
            debugMode = prefs.getBoolean(KEY_DEBUG, defaults.debugMode),
            autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, defaults.autoUpdate),
            gistToken = prefs.getString(KEY_GIST_TOKEN, null)?.takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val PREFS_NAME = "mealannouncer-debug"
        const val KEY_DEBUG = "debug"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_GIST_TOKEN = "gist_token"
    }
}
