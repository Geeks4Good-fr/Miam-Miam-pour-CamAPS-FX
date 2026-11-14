package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.integration.glooko.GlookoDestination
import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ce que l'écran des réglages montre de l'import Glooko. */
data class GlookoUiState(
    val enabled: Boolean = false,
    val includeSgv: Boolean = false,
    val windowHours: Long = GlookoPreferences.WINDOWS[1],
    val exportOnMeal: Boolean = true,
    val cadenceMinutes: Long = GlookoPreferences.CADENCES.last(),
    val dryRun: Boolean = false,
    val destination: GlookoDestination = GlookoDestination.CLASSIC_NIGHTSCOUT,
    val lastRunMs: Long = 0L,
    val lastSuccessMs: Long = 0L,
    val summary: String? = null,
    val accessibilityRunning: Boolean = false,
    val cartographyPath: String? = null,
)

/**
 * Les gestes de la section Glooko, regroupés.
 *
 * Un objet plutôt que neuf paramètres : la signature de l'écran reste lisible, et un écran
 * de test peut n'en brancher que quelques-uns.
 */
class GlookoActions(
    val onEnabled: (Boolean) -> Unit = {},
    val onIncludeSgv: (Boolean) -> Unit = {},
    val onWindow: (Long) -> Unit = {},
    val onExportOnMeal: (Boolean) -> Unit = {},
    val onCadence: (Long) -> Unit = {},
    val onDryRun: (Boolean) -> Unit = {},
    val onDestination: (GlookoDestination) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onOpenAccessibility: () -> Unit = {},
    val onCartography: () -> Unit = {},
)

/**
 * Section « Import Glooko ».
 *
 * Elle dit l'état réel : dernier passage, dernier succès, service d'accessibilité actif ou
 * non. Rien n'est présenté comme réussi tant que l'import ne l'a pas confirmé.
 */
@Composable
fun GlookoSettingsSection(
    state: GlookoUiState,
    actions: GlookoActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_glooko_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_glooko_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Où partent les données : s'applique aussi à un export partagé à la main.
            Text(
                text = stringResource(R.string.settings_glooko_destination),
                style = MaterialTheme.typography.labelLarge,
            )
            ChoiceRow(
                values = GlookoDestination.entries.toList(),
                selected = state.destination,
                label = {
                    stringResource(
                        when (it) {
                            GlookoDestination.CLASSIC_NIGHTSCOUT -> R.string.settings_glooko_destination_classic
                            GlookoDestination.CAMAPS_NIGHTSCOUT -> R.string.settings_glooko_destination_camaps
                        },
                    )
                },
                onSelect = actions.onDestination,
            )
            Text(
                text = stringResource(
                    when (state.destination) {
                        GlookoDestination.CLASSIC_NIGHTSCOUT -> R.string.settings_glooko_destination_classic_help
                        GlookoDestination.CAMAPS_NIGHTSCOUT -> R.string.settings_glooko_destination_camaps_help
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            GlookoSwitchRow(
                label = stringResource(R.string.settings_glooko_enable),
                checked = state.enabled,
                onCheckedChange = actions.onEnabled,
            )

            if (state.enabled) {
                Spacer(Modifier.height(12.dp))
                GlookoSwitchRow(
                    label = stringResource(R.string.settings_glooko_on_meal),
                    checked = state.exportOnMeal,
                    onCheckedChange = actions.onExportOnMeal,
                )
                Text(
                    text = stringResource(R.string.settings_glooko_on_meal_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_glooko_cadence),
                    style = MaterialTheme.typography.labelLarge,
                )
                ChoiceRow(
                    values = GlookoPreferences.CADENCES,
                    selected = state.cadenceMinutes,
                    label = { stringResource(R.string.settings_glooko_cadence_hours, it / 60) },
                    onSelect = actions.onCadence,
                )
                Text(
                    text = stringResource(R.string.settings_glooko_cadence_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_glooko_window),
                    style = MaterialTheme.typography.labelLarge,
                )
                ChoiceRow(
                    values = GlookoPreferences.WINDOWS,
                    selected = state.windowHours,
                    label = { stringResource(R.string.settings_glooko_window_hours, it) },
                    onSelect = actions.onWindow,
                )

                Spacer(Modifier.height(12.dp))
                GlookoSwitchRow(
                    label = stringResource(R.string.settings_glooko_sgv),
                    checked = state.includeSgv,
                    onCheckedChange = actions.onIncludeSgv,
                )
                Text(
                    text = stringResource(R.string.settings_glooko_sgv_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                GlookoSwitchRow(
                    label = stringResource(R.string.settings_glooko_dry_run),
                    checked = state.dryRun,
                    onCheckedChange = actions.onDryRun,
                )
                Text(
                    text = stringResource(R.string.settings_glooko_dry_run_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = if (state.lastSuccessMs > 0L) {
                    stringResource(R.string.settings_glooko_status_last, formatInstant(state.lastSuccessMs))
                } else {
                    stringResource(R.string.settings_glooko_status_never)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            state.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_glooko_status_summary, summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (state.accessibilityRunning) {
                        R.string.settings_glooko_accessibility_on
                    } else {
                        R.string.settings_glooko_accessibility_off
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.accessibilityRunning) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            Spacer(Modifier.height(12.dp))
            // Deux boutons l'un sous l'autre : côte à côte, « Réglages d'accessibilité » était
            // écrasé dans une case étroite et se coupait une lettre par ligne.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = actions.onSyncNow,
                    enabled = state.enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_glooko_sync))
                }
                OutlinedButton(
                    onClick = actions.onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_glooko_accessibility))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_glooko_cartography_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButtonRow(
                label = stringResource(R.string.settings_glooko_cartography),
                onClick = actions.onCartography,
            )
            state.cartographyPath?.let { path ->
                Text(
                    text = stringResource(R.string.settings_glooko_cartography_done, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in values) {
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) },
            )
        }
    }
}

@Composable
private fun TextButtonRow(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
        Text(label)
    }
}

@Composable
private fun GlookoSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

private val STATUS_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm")

private fun formatInstant(timestampMs: Long): String =
    STATUS_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestampMs))
