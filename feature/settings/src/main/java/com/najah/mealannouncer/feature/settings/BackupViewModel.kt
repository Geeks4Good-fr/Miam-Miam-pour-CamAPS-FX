package com.najah.mealannouncer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Les allers-retours du fichier de configuration avec l'extérieur de l'application.
 *
 * Le ViewModel ne touche pas aux fichiers : il rend le texte à écrire et reçoit celui qui a été
 * lu. C'est l'écran qui choisit l'emplacement (document d'Android), et donc l'utilisateur qui
 * garde la main sur l'endroit où ses clés sont déposées.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: ConfigBackup,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    /** Prépare le texte à écrire ; l'écran l'enregistre dans le fichier choisi. */
    fun prepareExport(onReady: (String) -> Unit) {
        _state.value = BackupUiState()
        viewModelScope.launch {
            val text = runCatching { backup.exportJson() }
            _state.value = text.fold(
                onSuccess = { BackupUiState(preparedExport = it) },
                onFailure = { BackupUiState(error = it.message ?: "sauvegarde impossible") },
            )
            text.getOrNull()?.let(onReady)
        }
    }

    /** Enregistre le fichier : l'écran vient d'y écrire [preparedExport]. */
    fun confirmExport(written: Boolean) {
        _state.value = if (written) {
            BackupUiState(message = "Configuration sauvegardée.")
        } else {
            BackupUiState(error = "Écriture annulée ou impossible.")
        }
    }

    /** Lit un fichier choisi et applique ce qu'il contient. */
    fun importText(text: String) {
        _state.value = BackupUiState(busy = true)
        viewModelScope.launch {
            _state.value = runCatching { backup.importJson(text) }.fold(
                onSuccess = { report ->
                    if (report.isEmpty) {
                        BackupUiState(error = "Ce fichier ne contient aucun réglage.")
                    } else {
                        val skipped = report.skipped
                        BackupUiState(
                            message = buildString {
                                append("${report.applied} section(s) restaurée(s).")
                                if (skipped.isNotEmpty()) append(" Ignoré : ${skipped.joinToString(", ")}.")
                            },
                        )
                    }
                },
                onFailure = { BackupUiState(error = it.message ?: "fichier illisible") },
            )
        }
    }

    fun dismiss() {
        _state.value = BackupUiState()
    }
}

/** Ce que la section « Sauvegarde » affiche, et le texte prêt à être écrit. */
data class BackupUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Le JSON préparé, à écrire par l'écran dans le document choisi. */
    val preparedExport: String? = null,
)
