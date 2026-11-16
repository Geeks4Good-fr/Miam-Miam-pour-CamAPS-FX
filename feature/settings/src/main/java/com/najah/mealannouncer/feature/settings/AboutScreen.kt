package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Page « À propos » (point 6 de la demande du 14/11/2026).
 *
 * Elle dit la version, le copyright et les licences des composants, puis laisse l'utilisateur
 * décider du **mode debug** — avec l'explication de ce qui sort du téléphone — et de la mise à
 * jour automatique. Rien n'est coché sans être expliqué.
 */
@Composable
fun AboutScreen(
    state: AboutUiState,
    onBack: () -> Unit,
    onDebugChange: (Boolean) -> Unit,
    onAutoUpdateChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onShipLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onInstallDownloaded: () -> Unit = {},
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.about_back)) }
            Spacer(Modifier.width(4.dp))
            Text(text = stringResource(R.string.about_title), style = MaterialTheme.typography.titleLarge)
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.about_app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.about_version, state.versionName, state.versionCode),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = stringResource(R.string.about_what), style = MaterialTheme.typography.bodyMedium)

            SectionCard(title = stringResource(R.string.about_copyright_title)) {
                Text(
                    text = stringResource(R.string.about_copyright),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = stringResource(R.string.about_licences_title)) {
                Text(
                    text = stringResource(R.string.about_licences),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- Mode debug ----
            SectionCard(title = stringResource(R.string.about_debug_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.config.debugMode, onCheckedChange = onDebugChange)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.about_debug_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = stringResource(R.string.about_debug_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onShipLogs, enabled = !state.shipping) {
                        Text(
                            if (state.shipping) {
                                stringResource(R.string.about_debug_sending)
                            } else {
                                stringResource(R.string.about_debug_send_now)
                            },
                        )
                    }
                    OutlinedButton(onClick = onShareLogs, enabled = !state.shipping) {
                        Text(stringResource(R.string.about_debug_share))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (state.shipping) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp))
                }
                Text(
                    text = stringResource(R.string.about_debug_where),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val message = state.shipMessage
                if (message != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (state.shipError) {
                            stringResource(R.string.about_debug_failed, message)
                        } else {
                            stringResource(R.string.about_debug_sent, message)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.shipError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                    TextButton(onClick = onDismissMessage) { Text("OK") }
                }
            }

            // ---- Mises à jour ----
            SectionCard(title = stringResource(R.string.about_updates_title)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.about_updates_auto),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Switch(
                        checked = state.config.autoUpdate,
                        onCheckedChange = onAutoUpdateChange,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCheckUpdate,
                    enabled = state.update !is UpdateState.Checking &&
                        state.update !is UpdateState.Downloading,
                ) {
                    Text(stringResource(R.string.about_updates_check))
                }
                Spacer(Modifier.height(8.dp))
                UpdateBlock(
                    state = state,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallDownloaded,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun UpdateBlock(state: AboutUiState, onDownload: () -> Unit, onInstall: () -> Unit) {
    when (val update = state.update) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> Text(
            text = stringResource(R.string.about_updates_checking),
            style = MaterialTheme.typography.bodySmall,
        )
        UpdateState.UpToDate -> Text(
            text = stringResource(R.string.about_updates_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is UpdateState.Available -> Column {
            Text(
                text = stringResource(
                    R.string.about_updates_available,
                    update.info.versionName,
                    state.versionName,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (update.info.notes.isNotBlank()) {
                Text(
                    text = stringResource(R.string.about_release_notes),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = update.info.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDownload) { Text(stringResource(R.string.about_updates_download)) }
        }
        is UpdateState.Downloading -> Column {
            Text(
                text = stringResource(R.string.about_updates_downloading, update.percent),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { update.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is UpdateState.Ready -> Column {
            Text(
                text = stringResource(R.string.about_updates_installing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // L'écran d'installation d'Android peut être refermé sans installer : on doit
            // pouvoir le rouvrir sans retélécharger 50 Mo.
            TextButton(onClick = onInstall) {
                Text(stringResource(R.string.about_updates_download))
            }
        }
        is UpdateState.Failed -> Column {
            Text(
                text = stringResource(R.string.about_updates_failed, update.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.about_updates_need_install_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}
