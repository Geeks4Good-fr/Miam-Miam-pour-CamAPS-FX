@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.najah.mealannouncer.feature.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.HomeVoiceConfig
import com.najah.mealannouncer.core.datastore.HomeVoiceConfigStore
import com.najah.mealannouncer.data.homeassistant.HomeAssistantErrorKind
import com.najah.mealannouncer.data.homeassistant.HomeAssistantUrl
import com.najah.mealannouncer.data.homeassistant.HomeLink
import com.najah.mealannouncer.data.homeassistant.HomeLinkStatus
import com.najah.mealannouncer.data.homeassistant.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Résultat du bouton « Tester la liaison ». */
sealed interface HomeVoiceTrial {
    data object Idle : HomeVoiceTrial
    data object Testing : HomeVoiceTrial
    data object Ok : HomeVoiceTrial
    data class Failed(val message: String) : HomeVoiceTrial
}

/**
 * La section « Voix de la maison » vue par l'écran (`docs/VOIX-MAISON.md` § 6.4).
 *
 * [canNotify] et [canFullScreen] disent si Android laissera l'appli se signaler, puis s'ouvrir
 * au-dessus de l'écran verrouillé, quand un repas est dicté à une enceinte.
 */
data class HomeVoiceUiState(
    val enabled: Boolean = false,
    val url: String = "",
    val hasToken: Boolean = false,
    val tokenInput: String = "",
    val status: HomeLinkStatus = HomeLinkStatus.Off,
    val trial: HomeVoiceTrial = HomeVoiceTrial.Idle,
    val notice: String? = null,
    val canNotify: Boolean = true,
    val canFullScreen: Boolean = true,
) {
    val urlValid: Boolean
        get() = HomeAssistantUrl.websocketUrl(url) != null
}

data class HomeVoiceActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onUrlChange: (String) -> Unit,
    val onTokenInputChange: (String) -> Unit,
    val onSaveToken: () -> Unit,
    val onForgetToken: () -> Unit,
    val onTest: () -> Unit,
    val onOpenNotifications: () -> Unit,
    val onOpenFullScreen: () -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * Réglages de la voix de la maison. Le service de liaison suit [HomeVoiceConfigStore.config] :
 * l'activité le démarre ou l'arrête selon l'interrupteur.
 */
@HiltViewModel
class HomeVoiceSettingsViewModel @Inject constructor(
    private val store: HomeVoiceConfigStore,
    private val link: HomeLink,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<HomeVoiceUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { link.status.collect { status -> _state.update { it.copy(status = status) } } }
    }

    private fun read(): HomeVoiceUiState {
        val config = store.config.value
        return HomeVoiceUiState(
            enabled = config.enabled,
            url = config.baseUrl,
            hasToken = store.hasToken(),
            status = link.status.value,
            canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            canFullScreen = canUseFullScreen(),
        )
    }

    /** Relit les autorisations Android (elles changent hors de l'appli). */
    fun refresh() {
        _state.update {
            it.copy(
                hasToken = store.hasToken(),
                canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                canFullScreen = canUseFullScreen(),
            )
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        val current = _state.value
        if (enabled && (!current.hasToken || !current.urlValid)) {
            _state.update { it.copy(notice = context.getString(R.string.settings_home_needs_setup)) }
            return
        }
        store.save(HomeVoiceConfig(enabled = enabled, baseUrl = current.url))
        _state.update { it.copy(enabled = enabled, notice = null) }
    }

    fun onUrlChange(url: String) {
        _state.update { it.copy(url = url, trial = HomeVoiceTrial.Idle, notice = null) }
        if (HomeAssistantUrl.websocketUrl(url) != null) {
            store.save(HomeVoiceConfig(enabled = _state.value.enabled, baseUrl = url))
        }
    }

    fun onTokenInputChange(value: String) {
        _state.update { it.copy(tokenInput = value, notice = null) }
    }

    fun saveToken() {
        val token = _state.value.tokenInput.trim()
        if (token.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.saveToken(token) }
            _state.update {
                it.copy(
                    tokenInput = "",
                    hasToken = true,
                    trial = HomeVoiceTrial.Idle,
                    notice = context.getString(R.string.settings_home_token_saved),
                )
            }
        }
    }

    fun forgetToken() {
        store.forgetToken()
        // Sans jeton, la liaison ne peut plus tenir : on éteint l'interrupteur plutôt que de mentir.
        store.save(HomeVoiceConfig(enabled = false, baseUrl = _state.value.url))
        _state.update {
            it.copy(
                enabled = false,
                hasToken = false,
                trial = HomeVoiceTrial.Idle,
                notice = context.getString(R.string.settings_home_token_forgotten),
            )
        }
    }

    /** Se connecte et s'authentifie une fois, avec le jeton saisi ou, à défaut, celui enregistré. */
    fun test() {
        if (_state.value.trial is HomeVoiceTrial.Testing) return
        _state.update { it.copy(trial = HomeVoiceTrial.Testing, notice = null) }
        viewModelScope.launch {
            val typed = _state.value.tokenInput.trim()
            val token = typed.ifEmpty { withContext(Dispatchers.IO) { store.readToken() }.orEmpty() }
            val kind = if (token.isEmpty()) {
                HomeAssistantErrorKind.NOT_CONFIGURED
            } else {
                withContext(Dispatchers.IO) { link.test(_state.value.url, token) }
            }
            _state.update {
                it.copy(trial = if (kind == null) HomeVoiceTrial.Ok else HomeVoiceTrial.Failed(kind.userMessage))
            }
        }
    }

    fun openNotificationSettings() {
        startSettings(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        startSettings(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")),
        )
    }

    private fun startSettings(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun canUseFullScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
}

/**
 * Section « Voix de la maison ».
 *
 * Elle dit l'état réel de la liaison et ce qui manque, sans jargon : L'utilisateur doit savoir d'un coup
 * d'œil si l'enceinte peut joindre son téléphone.
 */
@Composable
fun HomeVoiceSettingsSection(
    state: HomeVoiceUiState,
    actions: HomeVoiceActions,
    modifier: Modifier = Modifier,
) {
    // Les autorisations changent hors de l'appli (réglages Android) : on les relit à chaque retour.
    LifecycleResumeEffect(Unit) {
        actions.onRefresh()
        onPauseOrDispose { }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_home_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_home_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_home_enabled), modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = actions.onEnabledChange)
            }
            Text(
                text = statusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.url,
                onValueChange = actions.onUrlChange,
                label = { Text(stringResource(R.string.settings_home_url)) },
                isError = !state.urlValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.tokenInput,
                onValueChange = actions.onTokenInputChange,
                label = {
                    Text(
                        stringResource(
                            if (state.hasToken) R.string.settings_home_token_replace else R.string.settings_home_token,
                        ),
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = actions.onSaveToken, enabled = state.tokenInput.isNotBlank()) {
                    Text(stringResource(R.string.settings_home_save_token))
                }
                OutlinedButton(onClick = actions.onTest, enabled = state.trial !is HomeVoiceTrial.Testing) {
                    Text(stringResource(R.string.settings_home_test))
                }
            }
            when (val trial = state.trial) {
                HomeVoiceTrial.Idle -> Unit
                HomeVoiceTrial.Testing -> Text(stringResource(R.string.settings_home_testing))
                HomeVoiceTrial.Ok -> Text(
                    stringResource(R.string.settings_home_test_ok),
                    color = MaterialTheme.colorScheme.primary,
                )
                is HomeVoiceTrial.Failed -> Text(trial.message, color = MaterialTheme.colorScheme.error)
            }
            state.notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (state.hasToken) {
                TextButton(onClick = actions.onForgetToken) { Text(stringResource(R.string.settings_home_forget_token)) }
            }
            if (!state.canNotify) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_home_no_notifications), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onOpenNotifications) { Text(stringResource(R.string.settings_home_open_settings)) }
            }
            if (!state.canFullScreen) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_home_no_full_screen), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onOpenFullScreen) { Text(stringResource(R.string.settings_home_open_settings)) }
            }
        }
    }
}

@Composable
private fun statusText(state: HomeVoiceUiState): String = when (val status = state.status) {
    HomeLinkStatus.Off -> stringResource(
        if (state.enabled) R.string.settings_home_status_starting else R.string.settings_home_status_off,
    )
    HomeLinkStatus.Connecting -> stringResource(R.string.settings_home_status_connecting)
    HomeLinkStatus.Connected -> stringResource(R.string.settings_home_status_connected)
    is HomeLinkStatus.Failed -> if (status.retrying) {
        stringResource(R.string.settings_home_status_retrying, status.kind.userMessage)
    } else {
        stringResource(R.string.settings_home_status_stopped, status.kind.userMessage)
    }
}
