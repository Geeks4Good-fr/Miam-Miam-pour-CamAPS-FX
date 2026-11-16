@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.najah.mealannouncer.feature.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.datastore.VoiceEngine
import com.najah.mealannouncer.feature.voice.SpokenBy
import com.najah.mealannouncer.feature.voice.VoiceCatalog

/**
 * Section « Voix ».
 *
 * Le texte dit toujours ce qui va réellement se passer : moteur retenu, moteur réellement
 * employé, et ce qu'il manque quand Voxtral ne peut pas parler. L'utilisateur doit pouvoir décider
 * sans deviner, et sans qu'on promette une voix que l'application ne donnera pas.
 */
@Composable
fun VoiceSettingsSection(
    state: VoiceUiState,
    actions: VoiceActions,
    modifier: Modifier = Modifier,
) {
    VoiceCard(title = stringResource(R.string.settings_voice_title), modifier = modifier) {
        Text(
            text = stringResource(R.string.settings_voice_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.settings_voice_engine),
            style = MaterialTheme.typography.labelLarge,
        )
        VoiceChoice(
            label = stringResource(R.string.settings_voice_engine_android),
            selected = state.engine == VoiceEngine.ANDROID,
            onSelect = { actions.onEngineChange(VoiceEngine.ANDROID) },
        )
        VoiceChoice(
            label = stringResource(R.string.settings_voice_engine_voxtral),
            selected = state.engine == VoiceEngine.VOXTRAL,
            onSelect = { actions.onEngineChange(VoiceEngine.VOXTRAL) },
        )
        VoiceChoice(
            label = stringResource(R.string.settings_voice_engine_piper),
            selected = state.engine == VoiceEngine.PIPER,
            onSelect = { actions.onEngineChange(VoiceEngine.PIPER) },
        )
        Text(
            text = stringResource(
                when (state.effectiveEngine) {
                    VoiceEngine.VOXTRAL -> R.string.settings_voice_effective_voxtral
                    VoiceEngine.PIPER -> R.string.settings_voice_effective_piper
                    VoiceEngine.ANDROID -> R.string.settings_voice_effective_android
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.engine == VoiceEngine.VOXTRAL && !state.usesVoxtral) {
            Text(
                text = stringResource(
                    if (!state.consent) {
                        R.string.settings_voice_needs_consent
                    } else {
                        R.string.settings_voice_needs_key
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_voice_voices),
            style = MaterialTheme.typography.labelLarge,
        )
        // La voix choisie ne sert qu'à Voxtral, mais on la laisse choisir à l'avance : c'est un
        // réglage, pas une activation.
        VoiceCatalog.options.forEach { option ->
            VoiceChoice(
                label = option.label,
                selected = state.voiceId == option.id,
                onSelect = { actions.onVoiceChange(option.id) },
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.consent, onCheckedChange = actions.onConsentChange)
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.settings_voice_consent),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.settings_voice_consent_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.keyInput,
            onValueChange = actions.onKeyInputChange,
            label = { Text(stringResource(R.string.settings_voice_key_label)) },
            placeholder = { Text(stringResource(R.string.settings_voice_key_placeholder)) },
            supportingText = {
                Text(
                    if (state.hasKey) {
                        stringResource(R.string.settings_voice_key_stored)
                    } else {
                        stringResource(R.string.settings_voice_key_help)
                    },
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = actions.onSaveKey) {
                Text(stringResource(R.string.settings_voice_save_key))
            }
            if (state.hasKey) {
                TextButton(onClick = actions.onClearKey) {
                    Text(stringResource(R.string.settings_voice_clear_key))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = actions.onTryVoice,
                enabled = state.trial !is VoiceTrial.Speaking,
            ) {
                Text(
                    if (state.trial is VoiceTrial.Speaking) {
                        stringResource(R.string.settings_voice_trying)
                    } else {
                        stringResource(R.string.settings_voice_try)
                    },
                )
            }
        }
        VoiceStatus(state = state)
    }
}

@Composable
private fun VoiceStatus(state: VoiceUiState) {
    val trial = state.trial
    if (trial is VoiceTrial.Done) {
        Text(
            text = stringResource(
                when (trial.spokenBy) {
                    SpokenBy.VOXTRAL -> R.string.settings_voice_trial_voxtral
                    SpokenBy.PIPER -> R.string.settings_voice_trial_piper
                    SpokenBy.ANDROID -> R.string.settings_voice_trial_android
                    SpokenBy.NONE -> R.string.settings_voice_trial_none
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (trial.spokenBy == SpokenBy.NONE) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
        // La cause, quand Voxtral devait parler et ne l'a pas fait : sans elle, on cherche à
        // l'aveugle entre la clé, le réseau et la voix du téléphone.
        if (!trial.issue.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.settings_voice_trial_issue, trial.issue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    when (state.feedback) {
        VoiceFeedback.NONE -> Unit
        VoiceFeedback.KEY_SAVED -> VoiceMessage(R.string.settings_voice_feedback_key_saved, false)
        VoiceFeedback.KEY_CLEARED -> VoiceMessage(R.string.settings_voice_feedback_key_cleared, false)
        VoiceFeedback.MISSING_KEY -> VoiceMessage(R.string.settings_voice_feedback_missing_key, true)
        VoiceFeedback.CONSENT_REQUIRED ->
            VoiceMessage(R.string.settings_voice_feedback_consent_required, true)
    }
}

@Composable
private fun VoiceMessage(textRes: Int, error: Boolean) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun VoiceChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VoiceCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
