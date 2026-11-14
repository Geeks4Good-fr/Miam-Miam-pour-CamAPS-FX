package com.najah.mealannouncer.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réglages Nightscout : adresse, type d'identifiant, activation, simulation.
 *
 * Le secret n'est pas ici : il est confié à [SecretStore]. Cette classe ne lit donc jamais
 * de donnée sensible et peut être journalisée sans risque (elle ne contient qu'une URL).
 */
@Singleton
class NightscoutConfigStore @Inject constructor(
    @ApplicationContext context: Context,
    private val secrets: SecretStore,
) : NightscoutSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    /** Réglages courants, observables par l'interface. */
    override val config: StateFlow<NightscoutConfig> = _config.asStateFlow()

    override fun readSecret(): String? = secrets.read()

    /**
     * Enregistre les réglages. [secret] n'est écrit que s'il est fourni (donc seulement
     * quand l'utilisateur en saisit un nouveau).
     */
    fun save(config: NightscoutConfig, secret: String? = null) {
        if (!secret.isNullOrEmpty()) {
            secrets.write(secret)
        }
        val stored = config.copy(hasSecret = secrets.hasSecret())
        prefs.edit()
            .putString(KEY_BASE_URL, stored.baseUrl)
            .putString(KEY_CREDENTIAL, stored.credentialType.name)
            .putBoolean(KEY_ENABLED, stored.enabled)
            .putBoolean(KEY_SIMULATION, stored.simulation)
            .apply()
        _config.value = stored
    }

    /** Oublie le secret et désactive l'intégration. */
    fun clearSecret() {
        secrets.clear()
        save(config.value.copy(enabled = false, hasSecret = false))
    }

    private fun read(): NightscoutConfig = NightscoutConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        credentialType = runCatching {
            CredentialType.valueOf(prefs.getString(KEY_CREDENTIAL, null) ?: "")
        }.getOrDefault(CredentialType.API_SECRET),
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        simulation = prefs.getBoolean(KEY_SIMULATION, false),
        hasSecret = secrets.hasSecret(),
    )

    private companion object {
        const val PREFS_NAME = "mealannouncer-nightscout"
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_CREDENTIAL = "credentialType"
        const val KEY_ENABLED = "enabled"
        const val KEY_SIMULATION = "simulation"
    }
}
