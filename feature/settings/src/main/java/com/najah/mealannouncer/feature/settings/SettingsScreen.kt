package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.datastore.CredentialType
import com.najah.mealannouncer.data.nightscout.userMessage

/**
 * Réglages de l'envoi vers Nightscout.
 *
 * L'écran ne cache rien : il dit où va le secret, ce que fait la simulation, et il refuse
 * d'activer l'envoi tant que l'adresse ou le secret manque.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onUrlChange: (String) -> Unit,
    onSecretChange: (String) -> Unit,
    onCredentialTypeChange: (CredentialType) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSimulationChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onForgetSecret: () -> Unit,
    glookoActions: GlookoActions,
    onRefreshGlooko: () -> Unit,
    voiceActions: VoiceActions,
    mealRecordActions: MealRecordActions,
    homeVoice: HomeVoiceUiState,
    homeVoiceActions: HomeVoiceActions,
    wakeWord: WakeWordUiState,
    wakeWordActions: WakeWordActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** La clé OpenRouter des IA (décision 48) ; `null` dans les aperçus. */
    aiKey: AiKeyUiState? = null,
    aiKeyActions: AiKeyActions? = null,
    /** La banque de portions en images (`docs/PORTIONS.md`) ; `null` dans les aperçus. */
    portions: PortionsUiState? = null,
    portionsActions: PortionsActions? = null,
    /** Enseignes habituelles et import de factures/tickets de courses ; `null` dans les aperçus. */
    shopping: ShoppingUiState? = null,
    shoppingActions: ShoppingActions? = null,
    /** État de l'assistant d'installation simplifiée ; `null` dans les aperçus. */
    setupWizard: SetupWizardUiState? = null,
    onOpenSetupWizard: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        // L'état Glooko (dernier import, accessibilité) se relit à chaque venue sur l'écran.
        LaunchedEffect(Unit) { onRefreshGlooko() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (setupWizard != null) {
                SetupWizardBannerCard(
                    state = setupWizard,
                    onOpenWizard = onOpenSetupWizard,
                )
                Spacer(Modifier.height(16.dp))
            }
            if (aiKey != null && aiKeyActions != null) {
                // En tête : sans cette clé, ni dialogue, ni photo, ni relecture.
                AiKeySettingsSection(state = aiKey, actions = aiKeyActions)
                Spacer(Modifier.height(16.dp))
            }
            if (portions != null && portionsActions != null) {
                PortionsSettingsSection(state = portions, actions = portionsActions)
                Spacer(Modifier.height(16.dp))
            }
            if (shopping != null && shoppingActions != null) {
                ShoppingSettingsSection(state = shopping, actions = shoppingActions)
                Spacer(Modifier.height(16.dp))
            }
            Section(title = stringResource(R.string.settings_nightscout_title)) {
                Text(
                    text = stringResource(R.string.settings_nightscout_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.baseUrl,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.settings_url_label)) },
                    placeholder = { Text(stringResource(R.string.settings_url_placeholder)) },
                    supportingText = { Text(stringResource(R.string.settings_url_help)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.secretInput,
                    onValueChange = onSecretChange,
                    label = { Text(stringResource(R.string.settings_secret_label)) },
                    placeholder = { Text(stringResource(R.string.settings_secret_placeholder)) },
                    supportingText = {
                        Text(
                            if (state.secretStored) {
                                stringResource(R.string.settings_secret_stored)
                            } else {
                                stringResource(R.string.settings_secret_help)
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.settings_credential_type),
                    style = MaterialTheme.typography.labelLarge,
                )
                CredentialOption(
                    label = stringResource(R.string.settings_credential_api_secret),
                    selected = state.credentialType == CredentialType.API_SECRET,
                    onSelect = { onCredentialTypeChange(CredentialType.API_SECRET) },
                )
                CredentialOption(
                    label = stringResource(R.string.settings_credential_token),
                    selected = state.credentialType == CredentialType.ACCESS_TOKEN,
                    onSelect = { onCredentialTypeChange(CredentialType.ACCESS_TOKEN) },
                )
                Text(
                    text = stringResource(R.string.settings_credential_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            Section(title = stringResource(R.string.settings_enable)) {
                SwitchRow(
                    label = stringResource(R.string.settings_enable),
                    checked = state.enabled,
                    onCheckedChange = onEnabledChange,
                )
                SwitchRow(
                    label = stringResource(R.string.settings_simulation),
                    checked = state.simulation,
                    onCheckedChange = onSimulationChange,
                )
                Text(
                    text = stringResource(R.string.settings_simulation_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) { Text(stringResource(R.string.settings_save)) }
                OutlinedButton(onClick = onTest, enabled = state.test !is ConnectionTestState.Testing) {
                    Text(
                        if (state.test is ConnectionTestState.Testing) {
                            stringResource(R.string.settings_testing)
                        } else {
                            stringResource(R.string.settings_test)
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Feedback(state = state)

            if (state.secretStored) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onForgetSecret) { Text(stringResource(R.string.settings_forget)) }
            }
            Spacer(Modifier.height(16.dp))
            GlookoSettingsSection(state = state.glooko, actions = glookoActions)
            Spacer(Modifier.height(16.dp))
            MealRecordSettingsSection(state = state.mealRecord, actions = mealRecordActions)
            Spacer(Modifier.height(16.dp))
            VoiceSettingsSection(state = state.voice, actions = voiceActions)
            Spacer(Modifier.height(16.dp))
            WakeWordSettingsSection(state = wakeWord, actions = wakeWordActions)
            Spacer(Modifier.height(16.dp))
            HomeVoiceSettingsSection(state = homeVoice, actions = homeVoiceActions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

@Composable
private fun CredentialOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Feedback(state: SettingsUiState) {
    when (val test = state.test) {
        ConnectionTestState.Idle -> Unit

        ConnectionTestState.Testing -> Text(
            text = stringResource(R.string.settings_testing),
            style = MaterialTheme.typography.bodyMedium,
        )

        is ConnectionTestState.Success -> Column {
            Text(
                text = stringResource(
                    R.string.settings_test_success,
                    test.server.name.ifBlank { "Nightscout" },
                    test.server.version,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (test.server.units.isNotBlank()) {
                Text(
                    text = stringResource(R.string.settings_test_units, test.server.units),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!test.server.apiEnabled) {
                Text(
                    text = stringResource(R.string.settings_test_api_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is ConnectionTestState.Failure -> Text(
            text = test.kind.userMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    if (state.test == ConnectionTestState.Idle) {
        when (state.feedback) {
            SaveFeedback.NONE -> Unit
            SaveFeedback.SAVED -> Text(
                text = stringResource(R.string.settings_feedback_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            SaveFeedback.INVALID_URL -> Text(
                text = stringResource(R.string.settings_feedback_invalid_url),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            SaveFeedback.MISSING_SECRET -> Text(
                text = stringResource(R.string.settings_feedback_missing_secret),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
