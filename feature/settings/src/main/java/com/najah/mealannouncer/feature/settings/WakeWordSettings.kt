package com.najah.mealannouncer.feature.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.WakeWordConfig
import com.najah.mealannouncer.core.datastore.WakeWordConfigStore
import com.najah.mealannouncer.feature.assistant.VoskModelStorage
import com.najah.mealannouncer.feature.assistant.WakeWordLink
import com.najah.mealannouncer.feature.assistant.WakeWordStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * État de la section « Mot-clé "miam-miam" » dans l'écran des réglages (`docs/MIAM-MIAM.md`).
 */
data class WakeWordUiState(
    val enabled: Boolean = false,
    val status: WakeWordStatus = WakeWordStatus.Off,
    val modelPresent: Boolean = false,
    val modelPath: String = "",
    val hasMicPermission: Boolean = true,
    val canNotify: Boolean = true,
    val canFullScreen: Boolean = true,
    val notice: String? = null,
)

data class WakeWordActions(
    val onEnabledChange: (Boolean) -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenNotifications: () -> Unit,
    val onOpenFullScreen: () -> Unit,
    val onRefresh: () -> Unit,
)

@HiltViewModel
class WakeWordSettingsViewModel @Inject constructor(
    private val store: WakeWordConfigStore,
    private val link: WakeWordLink,
    private val modelStorage: VoskModelStorage,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(read())
    val state: StateFlow<WakeWordUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.config.collect { config ->
                _state.update { it.copy(enabled = config.enabled) }
            }
        }
        viewModelScope.launch {
            link.status.collect { status ->
                _state.update { it.copy(status = status) }
            }
        }
    }

    private fun read(): WakeWordUiState {
        val config = store.config.value
        return WakeWordUiState(
            enabled = config.enabled,
            status = link.status.value,
            modelPresent = modelStorage.available,
            modelPath = modelStorage.directory().absolutePath,
            hasMicPermission = hasMicPermission(),
            canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            canFullScreen = canUseFullScreen(),
        )
    }

    fun refresh() {
        _state.update {
            it.copy(
                enabled = store.config.value.enabled,
                modelPresent = modelStorage.available,
                modelPath = modelStorage.directory().absolutePath,
                hasMicPermission = hasMicPermission(),
                canNotify = NotificationManagerCompat.from(context).areNotificationsEnabled(),
                canFullScreen = canUseFullScreen(),
            )
        }
    }

    fun onEnabledChange(enabled: Boolean) {
        refresh()
        val current = _state.value
        if (enabled && !current.modelPresent) {
            _state.update { it.copy(notice = context.getString(R.string.settings_wake_model_missing, current.modelPath)) }
            return
        }
        if (enabled && !current.hasMicPermission) {
            _state.update { it.copy(notice = context.getString(R.string.settings_wake_no_mic)) }
            return
        }
        store.save(WakeWordConfig(enabled = enabled))
        _state.update { it.copy(enabled = enabled, notice = null) }
    }

    fun openAppSettings() {
        startSettings(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
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

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun canUseFullScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
}

@Composable
fun WakeWordSettingsSection(
    state: WakeWordUiState,
    actions: WakeWordActions,
    modifier: Modifier = Modifier,
) {
    LifecycleResumeEffect(Unit) {
        actions.onRefresh()
        onPauseOrDispose { }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_wake_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_wake_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_wake_enabled), modifier = Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = actions.onEnabledChange)
            }
            Text(
                text = wakeStatusText(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.modelPresent) {
                    stringResource(R.string.settings_wake_model_ok)
                } else {
                    stringResource(R.string.settings_wake_model_missing, state.modelPath)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.modelPresent) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            state.notice?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!state.hasMicPermission) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_wake_no_mic), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onOpenAppSettings) {
                    Text(stringResource(R.string.settings_home_open_settings))
                }
            }
            if (!state.canNotify) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_home_no_notifications), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onOpenNotifications) {
                    Text(stringResource(R.string.settings_home_open_settings))
                }
            }
            if (!state.canFullScreen) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_home_no_full_screen), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = actions.onOpenFullScreen) {
                    Text(stringResource(R.string.settings_home_open_settings))
                }
            }
        }
    }
}

@Composable
private fun wakeStatusText(state: WakeWordUiState): String = when (state.status) {
    WakeWordStatus.Off -> stringResource(
        if (state.enabled) R.string.settings_wake_status_starting else R.string.settings_wake_status_off,
    )
    WakeWordStatus.Listening -> stringResource(R.string.settings_wake_status_listening)
    WakeWordStatus.Paused -> stringResource(R.string.settings_wake_status_paused)
    WakeWordStatus.ModelMissing -> stringResource(R.string.settings_wake_model_missing, state.modelPath)
    WakeWordStatus.MicUnavailable -> stringResource(R.string.settings_wake_no_mic)
}
