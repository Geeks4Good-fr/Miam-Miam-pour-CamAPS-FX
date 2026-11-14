package com.najah.mealannouncer.integration.glooko

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ce que l'import retient d'un passage à l'autre.
 *
 * Une interface, et non la classe concrète, pour que l'import se teste sans Android.
 */
interface GlookoState {
    /** Dernière import réussi, en millisecondes ; 0 si aucun. */
    var lastSuccessMs: Long

    /** Dernier passage, réussi ou non. */
    var lastRunMs: Long

    /** Empreinte du dernier profil écrit : un profil identique n'est pas réécrit. */
    var profileHash: String?
}

/**
 * Préférences et état de l'import Glooko.
 *
 * Aucune donnée de santé ici : ni glycémie, ni dose, ni repas. Seulement des réglages et des
 * dates — de quoi savoir quand l'import a réussi et s'il faut réécrire le profil.
 */
@Singleton
class GlookoPreferences @Inject constructor(
    @ApplicationContext context: Context,
) : GlookoState {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Écriture groupée d'un réglage, sans dépendre de `core-ktx`. */
    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        editor.block()
        editor.apply()
    }

    // ---- Réglages utilisateur ----------------------------------------------------

    /** Import automatique armé ou non. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = edit { putBoolean(KEY_ENABLED, value) }

    /** Écrire aussi les glycémies du CSV (désactivé si xDrip+ fait déjà le travail). */
    var includeSgv: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_SGV, false)
        set(value) = edit { putBoolean(KEY_INCLUDE_SGV, value) }

    /** Fenêtre remontée à chaque passage, en heures. */
    var windowHours: Long
        get() = prefs.getLong(KEY_WINDOW_HOURS, GlookoPlanOptions.DEFAULT_WINDOW_HOURS)
        set(value) = edit { putLong(KEY_WINDOW_HOURS, value.coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS)) }

    /**
     * Où partent les données de Glooko : Nightscout classique (conversion dans l'appli, défaut)
     * ou Nightscout for CamAPS FX (CSV intact, interprété par le serveur).
     */
    var destination: GlookoDestination
        get() = prefs.getString(KEY_DESTINATION, null)
            ?.let { runCatching { GlookoDestination.valueOf(it) }.getOrNull() }
            ?: GlookoDestination.CLASSIC_NIGHTSCOUT
        set(value) = edit { putString(KEY_DESTINATION, value.name) }

    /** Écrire dans le vide, pour voir ce qui serait importé sans rien envoyer. */
    var dryRun: Boolean
        get() = prefs.getBoolean(KEY_DRY_RUN, false)
        set(value) = edit { putBoolean(KEY_DRY_RUN, value) }

    /** Signature des documents écrits, pour les distinguer d'une saisie manuelle. */
    var enteredBy: String
        get() = prefs.getString(KEY_ENTERED_BY, null) ?: GlookoPlanOptions.DEFAULT_ENTERED_BY
        set(value) = edit { putString(KEY_ENTERED_BY, value.ifBlank { GlookoPlanOptions.DEFAULT_ENTERED_BY }) }

    var device: String
        get() = prefs.getString(KEY_DEVICE, null) ?: GlookoPlanOptions.DEFAULT_DEVICE
        set(value) = edit { putString(KEY_DEVICE, value.ifBlank { GlookoPlanOptions.DEFAULT_DEVICE }) }

    /** Dossier de partage choisi pour le repli sans automatisation. */
    var shareFolderUri: String?
        get() = prefs.getString(KEY_SHARE_FOLDER, null)
        set(value) = edit { putString(KEY_SHARE_FOLDER, value) }

    /**
     * Export déclenché à chaque repas annoncé : le déclencheur normal.
     *
     * Un repas est le moment où l'on a besoin du contexte d'insuline et de glucides, et
     * Glooko ne rafraîchit ses données que toutes les quelques heures : c'est donc une
     * occasion bien placée, et un export inutile ne coûte qu'un fichier identique.
     */
    var exportOnMeal: Boolean
        get() = prefs.getBoolean(KEY_EXPORT_ON_MEAL, true)
        set(value) = edit { putBoolean(KEY_EXPORT_ON_MEAL, value) }

    /**
     * Filet de sécurité : le passage périodique, en minutes.
     *
     * Il ne remplace pas le déclenchement par repas — il rattrape les journées où aucun
     * repas n'est annoncé, et où Glooko ne serait donc jamais interrogé. Une journée par
     * défaut : Glooko ne publie pas plus souvent, et chaque passage prend l'écran.
     */
    var cadenceMinutes: Long
        get() = prefs.getLong(KEY_CADENCE, DEFAULT_CADENCE_MINUTES)
            .coerceIn(MIN_CADENCE_MINUTES, MAX_CADENCE_MINUTES)
        set(value) = edit { putLong(KEY_CADENCE, value.coerceIn(MIN_CADENCE_MINUTES, MAX_CADENCE_MINUTES)) }

    // ---- État -------------------------------------------------------------------

    /** Dernière import réussi, en millisecondes ; 0 si aucun. */
    override var lastSuccessMs: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS, 0L)
        set(value) = edit { putLong(KEY_LAST_SUCCESS, value) }

    /** Dernier passage, réussi ou non : sert à afficher « il y a 12 minutes ». */
    override var lastRunMs: Long
        get() = prefs.getLong(KEY_LAST_RUN, 0L)
        set(value) = edit { putLong(KEY_LAST_RUN, value) }

    /**
     * Dernier passage tenté, en millisecondes, quel qu'en soit l'auteur.
     *
     * Deux repas rapprochés ne doivent pas produire deux exports : Glooko ne rafraîchit pas
     * si vite, et chaque export prend l'écran du téléphone. Un export manuel posé juste avant
     * un repas compte donc aussi.
     */
    var lastExportAttemptMs: Long
        get() = prefs.getLong(KEY_LAST_EXPORT_ATTEMPT, 0L)
        set(value) = edit { putLong(KEY_LAST_EXPORT_ATTEMPT, value) }

    /** Empreinte du dernier profil écrit : un profil identique n'est pas réécrit. */
    override var profileHash: String?
        get() = prefs.getString(KEY_PROFILE_HASH, null)
        set(value) = edit { putString(KEY_PROFILE_HASH, value) }

    /** Dernier compte rendu, en une phrase, pour l'écran des réglages. */
    var lastSummary: String?
        get() = prefs.getString(KEY_LAST_SUMMARY, null)
        set(value) = edit { putString(KEY_LAST_SUMMARY, value) }

    fun planOptions(nowMs: Long): GlookoImportOptions = GlookoImportOptions(
        nowMs = nowMs,
        windowHours = windowHours,
        includeSgv = includeSgv,
        enteredBy = enteredBy,
        device = device,
        dryRun = dryRun,
    )

    companion object {
        private const val NAME = "mealannouncer-glooko"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INCLUDE_SGV = "include_sgv"
        private const val KEY_WINDOW_HOURS = "window_hours"
        private const val KEY_DRY_RUN = "dry_run"
        private const val KEY_DESTINATION = "destination"
        private const val KEY_ENTERED_BY = "entered_by"
        private const val KEY_DEVICE = "device"
        private const val KEY_SHARE_FOLDER = "share_folder"
        private const val KEY_EXPORT_ON_MEAL = "export_on_meal"
        private const val KEY_CADENCE = "cadence_minutes"
        private const val KEY_LAST_SUCCESS = "last_success_ms"
        private const val KEY_LAST_RUN = "last_run_ms"
        private const val KEY_LAST_EXPORT_ATTEMPT = "last_export_attempt_ms"
        private const val KEY_PROFILE_HASH = "profile_hash"
        private const val KEY_LAST_SUMMARY = "last_summary"

        const val MIN_WINDOW_HOURS = 6L
        const val MAX_WINDOW_HOURS = 168L

        /** Fenêtres proposées dans l'interface. */
        val WINDOWS = listOf(24L, 48L, 72L, 168L)

        const val DEFAULT_CADENCE_MINUTES = 1_440L
        const val MIN_CADENCE_MINUTES = 360L
        const val MAX_CADENCE_MINUTES = 1_440L

        /** Filets de sécurité proposés : six heures, douze heures, une journée. */
        val CADENCES = listOf(360L, 720L, 1_440L)

        /**
         * Délai après l'annonce d'un repas avant de prendre l'écran, en minutes.
         *
         * Une annonce de repas est le moment où l'on saisit le bolus dans CamAPS FX : lui
         * voler l'écran à cet instant serait hostile. Trois minutes plus tard, la saisie est
         * faite, et Glooko a de toute façon besoin de quelques minutes pour publier le repas.
         */
        const val MEAL_DELAY_MINUTES = 3L

        /** En deçà, deux repas rapprochés ne déclenchent qu'un seul export. */
        const val DEBOUNCE_MINUTES = 30L
    }
}
