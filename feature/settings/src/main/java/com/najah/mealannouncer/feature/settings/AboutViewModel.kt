package com.najah.mealannouncer.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.datastore.DebugConfig
import com.najah.mealannouncer.core.datastore.DebugSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Où en est la recherche de mise à jour. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    data class Available(val info: UpdateInfo) : UpdateState

    data class Downloading(val percent: Int) : UpdateState

    data class Ready(val file: File) : UpdateState

    data class Failed(val message: String) : UpdateState
}

/** Ce que la page « À propos » a à montrer. */
data class AboutUiState(
    val versionName: String = "",
    val versionCode: Long = 0L,
    val config: DebugConfig = DebugConfig(),
    val update: UpdateState = UpdateState.Idle,
    val shipping: Boolean = false,
    val shipMessage: String? = null,
    val shipError: Boolean = false,
)

/**
 * La page « À propos » (point 6 de la demande du 14/11/2026).
 *
 * Elle porte trois choses : ce qu'est l'application et sous quelles licences, le mode debug
 * (avec son consentement explicite d'envoi de journaux), et la mise à jour depuis les
 * publications du projet.
 */
@HiltViewModel
class AboutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debug: DebugSettings,
    private val updates: UpdateChecker,
    private val logs: LogShipper,
) : ViewModel() {

    private val _state = MutableStateFlow(AboutUiState())
    val state: StateFlow<AboutUiState> = _state.asStateFlow()

    init {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        _state.update {
            it.copy(
                versionName = info?.versionName ?: "?",
                versionCode = info?.longVersionCode ?: 0L,
                config = debug.config.value,
            )
        }
        viewModelScope.launch {
            debug.config.collect { config -> _state.update { it.copy(config = config) } }
        }
        // La vérification automatique ne se déclenche qu'une fois par ouverture d'écran, et
        // seulement si l'utilisateur ne l'a pas coupée.
        viewModelScope.launch {
            if (debug.config.value.autoUpdate) checkForUpdate(silent = true)
        }
    }

    fun setDebugMode(enabled: Boolean) {
        debug.save(debug.config.value.copy(debugMode = enabled))
        _state.update { it.copy(shipMessage = null) }
    }

    fun setAutoUpdate(enabled: Boolean) {
        debug.save(debug.config.value.copy(autoUpdate = enabled))
    }

    fun setToken(token: String) {
        debug.save(debug.config.value.copy(gistToken = token.ifBlank { null }))
    }

    fun dismissMessage() {
        _state.update { it.copy(shipMessage = null) }
    }

    /** Cherche une version plus récente ; [silent] évite de crier « à jour » à l'ouverture. */
    fun checkForUpdate(silent: Boolean = false) {
        if (_state.value.update is UpdateState.Checking) return
        viewModelScope.launch {
            _state.update { it.copy(update = UpdateState.Checking) }
            val current = _state.value.versionName
            val found = runCatching { updates.latest(current) }.getOrNull()
            _state.update {
                when {
                    found != null -> it.copy(update = UpdateState.Available(found))
                    silent -> it.copy(update = UpdateState.Idle)
                    else -> it.copy(update = UpdateState.UpToDate)
                }
            }
        }
    }

    /**
     * Télécharge l'APK proposé puis ouvre l'écran d'installation d'Android.
     *
     * Sans l'autorisation « installer des applications inconnues », le téléchargement se ferait
     * pour rien : on le dit avant.
     */
    fun downloadAndInstall() {
        val info = (_state.value.update as? UpdateState.Available)?.info ?: return
        if (!updates.canInstall()) {
            // On conduit vers le réglage au lieu d'échouer : c'est un écran d'Android, pas une
            // erreur de l'application.
            updates.openInstallPermissionSettings()
            _state.update {
                it.copy(update = UpdateState.Failed("autorisez « Installer des applications inconnues » puis appuyez de nouveau"))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(update = UpdateState.Downloading(0)) }
            try {
                val file = updates.download(info) { percent ->
                    _state.update { it.copy(update = UpdateState.Downloading(percent)) }
                }
                _state.update { it.copy(update = UpdateState.Ready(file)) }
                updates.install(file)
            } catch (error: Throwable) {
                _state.update {
                    it.copy(update = UpdateState.Failed(error.message ?: "téléchargement impossible"))
                }
            }
        }
    }

    /** Relance l'écran d'installation sur l'APK déjà téléchargé. */
    fun installDownloaded() {
        val file = (_state.value.update as? UpdateState.Ready)?.file ?: return
        runCatching { updates.install(file) }
            .onFailure { error ->
                _state.update { it.copy(update = UpdateState.Failed(error.message ?: "installation impossible")) }
            }
    }

    /** Envoie les journaux au développeur — **sans rien demander** à l'utilisateur (demande du
     * 15/11/2026) : ni compte, ni jeton. L'adresse du dépôt est copiée dans le presse-papier et
     * affichée. Un jeton GitHub, s'il a été saisi, reste possible et passe par un Gist privé.
     */
    fun shipLogs() {
        viewModelScope.launch {
            _state.update { it.copy(shipping = true, shipMessage = null) }
            val token = debug.config.value.gistToken
            val result = if (token.isNullOrBlank()) logs.shipToPasteBin() else logs.shipToGist(token)
            result.gistUrl?.let(::copyToClipboard)
            _state.update {
                it.copy(
                    shipping = false,
                    shipMessage = result.gistUrl ?: result.error ?: "envoi impossible",
                    shipError = !result.isSuccess,
                )
            }
        }
    }

    /** Met l'adresse dans le presse-papier : elle peut ainsi être collée n'importe où. */
    private fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Miam-Miam", text))
        }
    }
}
