package com.najah.mealannouncer.feature.settings

import com.najah.mealannouncer.core.datastore.MealRecordConfig

/**
 * Réglages de l'enregistrement d'un repas vus par l'écran.
 *
 * [camapsAccessibilityRunning] n'est pas un réglage mais un **constat** : le service
 * d'accessibilité CamAPS ne s'allume que dans Android, et l'insertion automatique ne fonctionne
 * pas sans lui. L'écran le dit pour que l'utilisateur ne croie pas une case suffisante.
 */
data class MealRecordUiState(
    val camapsAutoInsert: Boolean = false,
    val suiviDiabetologue: Boolean = false,
    val glookoCarnet: Boolean = true,
    val nightscoutNote: Boolean = true,
    val attachPhoto: Boolean = true,
    val geminiDirect: Boolean = true,
    val liberty: Boolean = false,
    val libertyAnswered: Boolean = false,
    val camapsAccessibilityRunning: Boolean = false,
) {
    fun toConfig(): MealRecordConfig = MealRecordConfig(
        camapsAutoInsert = camapsAutoInsert,
        suiviDiabetologue = suiviDiabetologue,
        glookoCarnet = glookoCarnet,
        nightscoutNote = nightscoutNote,
        attachPhoto = attachPhoto,
        geminiDirect = geminiDirect,
        liberty = liberty,
        libertyAnswered = libertyAnswered,
    )
}

/** Les gestes de la section « Enregistrement du repas », regroupés. */
data class MealRecordActions(
    val onCamapsAutoChange: (Boolean) -> Unit,
    val onSuiviChange: (Boolean) -> Unit,
    val onGlookoCarnetChange: (Boolean) -> Unit,
    val onNightscoutNoteChange: (Boolean) -> Unit,
    val onAttachPhotoChange: (Boolean) -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onGeminiDirectChange: (Boolean) -> Unit = {},
    val onLibertyChange: (Boolean) -> Unit = {},
)
