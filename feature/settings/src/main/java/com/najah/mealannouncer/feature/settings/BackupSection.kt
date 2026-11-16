package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Sauvegarde de la configuration (15/11/2026).
 *
 * Née d'un constat concret : la version de test et la version publiée sont deux applications
 * distinctes, et passer de l'une à l'autre obligeait à ressaisir toutes les clés. Un fichier
 * suffit désormais — et il sert aussi au changement de téléphone.
 */
@Composable
fun BackupSection(
    state: BackupUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settings_backup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_backup_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onExport, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_backup_export))
                }
                OutlinedButton(onClick = onImport, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_backup_import))
                }
                if (state.busy) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
            }
            val message = state.message
            val error = state.error
            if (message != null || error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message ?: error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}
