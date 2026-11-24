package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Section « Enregistrement du repas ».
 *
 * Deux idées, dans cet ordre : d'abord l'insertion automatique dans CamAPS (avec l'état réel du
 * service d'accessibilité, parce qu'une case ne suffit pas à l'allumer) ; ensuite le mode suivi
 * diabétologue, désactivé par défaut, qui seul fait apparaître les détails pour le médecin.
 */
@Composable
fun MealRecordSettingsSection(
    state: MealRecordUiState,
    actions: MealRecordActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_meal_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_meal_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Liberty en tête : c'est lui qui décide si CamAPS fait partie de la séquence.
            Spacer(Modifier.height(12.dp))
            MealSwitchRow(
                label = stringResource(R.string.settings_meal_liberty),
                checked = state.liberty,
                onCheckedChange = actions.onLibertyChange,
            )
            Text(
                text = stringResource(
                    if (state.liberty) R.string.settings_meal_liberty_on else R.string.settings_meal_liberty_off,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.liberty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_meal_liberty_doctor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            MealSwitchRow(
                label = stringResource(R.string.settings_meal_gemini_direct),
                checked = state.geminiDirect,
                onCheckedChange = actions.onGeminiDirectChange,
            )
            Text(
                text = stringResource(R.string.settings_meal_gemini_direct_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Sous Liberty, rien ne va dans CamAPS : l'insertion automatique n'a plus d'objet et
            // n'est pas proposée (le réglage est gardé tel quel pour le jour où Liberty est coupé).
            if (!state.liberty) {
                Spacer(Modifier.height(12.dp))
                MealSwitchRow(
                    label = stringResource(R.string.settings_meal_camaps_auto),
                    checked = state.camapsAutoInsert,
                    onCheckedChange = actions.onCamapsAutoChange,
                )
                Text(
                    text = stringResource(R.string.settings_meal_camaps_auto_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Le constat, pas une promesse : sans le service allumé, la case ne fait rien.
            if (state.camapsAutoInsert && !state.liberty) {
                Text(
                    text = stringResource(
                        if (state.camapsAccessibilityRunning) {
                            R.string.settings_meal_camaps_accessibility_on
                        } else {
                            R.string.settings_meal_camaps_accessibility_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.camapsAccessibilityRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                TextButton(onClick = actions.onOpenAccessibility) {
                    Text(stringResource(R.string.settings_meal_accessibility_button))
                }
            }

            Spacer(Modifier.height(12.dp))
            MealSwitchRow(
                label = stringResource(R.string.settings_meal_suivi),
                checked = state.suiviDiabetologue,
                onCheckedChange = actions.onSuiviChange,
            )
            Text(
                text = stringResource(R.string.settings_meal_suivi_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Les détails pour le médecin ne se règlent que si le suivi est actif : sinon ils ne
            // changent rien, et les montrer tromperait sur ce qui part réellement.
            if (state.suiviDiabetologue) {
                Spacer(Modifier.height(8.dp))
                MealSwitchRow(
                    label = stringResource(R.string.settings_meal_glooko),
                    checked = state.glookoCarnet,
                    onCheckedChange = actions.onGlookoCarnetChange,
                )
                MealSwitchRow(
                    label = stringResource(R.string.settings_meal_nightscout),
                    checked = state.nightscoutNote,
                    onCheckedChange = actions.onNightscoutNoteChange,
                )
                MealSwitchRow(
                    label = stringResource(R.string.settings_meal_photo),
                    checked = state.attachPhoto,
                    onCheckedChange = actions.onAttachPhotoChange,
                )
            }
        }
    }
}

@Composable
private fun MealSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
