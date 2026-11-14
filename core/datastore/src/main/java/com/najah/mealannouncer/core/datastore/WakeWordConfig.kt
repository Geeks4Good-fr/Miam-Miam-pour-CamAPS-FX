package com.najah.mealannouncer.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réglage de l'écoute permanente du mot-clé « miam-miam » sur le téléphone (`docs/MIAM-MIAM.md`).
 *
 * Désactivée par défaut : tant que l'interrupteur (dans les réglages ou dans la tuile de
 * raccourcis rapides Android) n'est pas allumé, aucun service n'ouvre le micro en veille.
 */
data class WakeWordConfig(
    val enabled: Boolean = false,
)

/** Réglage observable du mot-clé « miam-miam ». */
interface WakeWordSettings {
    val config: StateFlow<WakeWordConfig>
    fun save(config: WakeWordConfig)
}

/** Même patron que [MealRecordConfigStore] : un `StateFlow` adossé à des `SharedPreferences`. */
@Singleton
class WakeWordConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) : WakeWordSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    override val config: StateFlow<WakeWordConfig> = _config.asStateFlow()

    override fun save(config: WakeWordConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
        _config.value = config
    }

    private fun read(): WakeWordConfig {
        val defaults = WakeWordConfig()
        return WakeWordConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, defaults.enabled),
        )
    }

    private companion object {
        const val PREFS_NAME = "mealannouncer-wake-word"
        const val KEY_ENABLED = "enabled"
    }
}
