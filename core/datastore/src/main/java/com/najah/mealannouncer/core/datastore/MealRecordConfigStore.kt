package com.najah.mealannouncer.core.datastore

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réglages de l'enregistrement d'un repas, gardés dans les préférences.
 *
 * Même patron que [VoiceConfigStore] : un `StateFlow` que l'interface observe, écrit à plat dans
 * des `SharedPreferences`. Aucun secret ici, seulement des interrupteurs.
 */
@Singleton
class MealRecordConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) : MealRecordSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())

    override val config: StateFlow<MealRecordConfig> = _config.asStateFlow()

    /** Enregistre les réglages et les publie. */
    fun save(config: MealRecordConfig) {
        prefs.edit()
            .putBoolean(KEY_CAMAPS_AUTO, config.camapsAutoInsert)
            .putBoolean(KEY_SUIVI, config.suiviDiabetologue)
            .putBoolean(KEY_GLOOKO, config.glookoCarnet)
            .putBoolean(KEY_NIGHTSCOUT, config.nightscoutNote)
            .putBoolean(KEY_PHOTO, config.attachPhoto)
            .putBoolean(KEY_GEMINI_DIRECT, config.geminiDirect)
            .putBoolean(KEY_LIBERTY, config.liberty)
            .putBoolean(KEY_LIBERTY_ANSWERED, config.libertyAnswered)
            .apply()
        _config.value = config
    }

    private fun read(): MealRecordConfig {
        val defaults = MealRecordConfig()
        return MealRecordConfig(
            camapsAutoInsert = prefs.getBoolean(KEY_CAMAPS_AUTO, defaults.camapsAutoInsert),
            suiviDiabetologue = prefs.getBoolean(KEY_SUIVI, defaults.suiviDiabetologue),
            glookoCarnet = prefs.getBoolean(KEY_GLOOKO, defaults.glookoCarnet),
            nightscoutNote = prefs.getBoolean(KEY_NIGHTSCOUT, defaults.nightscoutNote),
            attachPhoto = prefs.getBoolean(KEY_PHOTO, defaults.attachPhoto),
            geminiDirect = prefs.getBoolean(KEY_GEMINI_DIRECT, defaults.geminiDirect),
            liberty = prefs.getBoolean(KEY_LIBERTY, defaults.liberty),
            libertyAnswered = prefs.getBoolean(KEY_LIBERTY_ANSWERED, defaults.libertyAnswered),
        )
    }

    private companion object {
        const val PREFS_NAME = "mealannouncer-meal-record"
        const val KEY_CAMAPS_AUTO = "camapsAutoInsert"
        const val KEY_SUIVI = "suiviDiabetologue"
        const val KEY_GLOOKO = "glookoCarnet"
        const val KEY_NIGHTSCOUT = "nightscoutNote"
        const val KEY_PHOTO = "attachPhoto"
        const val KEY_GEMINI_DIRECT = "geminiDirect"
        const val KEY_LIBERTY = "liberty"
        const val KEY_LIBERTY_ANSWERED = "libertyAnswered"
    }
}
