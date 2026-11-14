package com.najah.mealannouncer.feature.settings

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.feature.assistant.portions.PortionBank
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PortionsUiState(
    val baseCount: Int = 0,
    val importedCount: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
)

/** Les gestes de la section ; l'activité ouvre les sélecteurs de fichiers. */
data class PortionsActions(
    val onImport: () -> Unit,
    val onExport: () -> Unit,
    val onClearImported: () -> Unit,
)

/** Réglages → Portions en images (`docs/PORTIONS.md`). */
@HiltViewModel
class PortionsSettingsViewModel @Inject constructor(
    private val bank: PortionBank,
) : ViewModel() {

    private val _state = MutableStateFlow(PortionsUiState(busy = true))
    val state: StateFlow<PortionsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    private fun refresh(message: String? = _state.value.message) {
        viewModelScope.launch {
            val (base, user) = withContext(Dispatchers.IO) { bank.counts() }
            _state.update { PortionsUiState(base, user, busy = false, message = message) }
        }
    }

    fun import(uri: Uri) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { bank.import(uri) }
            refresh(report.summary())
        }
    }

    fun export(uri: Uri) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { runCatching { bank.export(uri, includeBase = _state.value.importedCount == 0) } }
            refresh(count.fold({ "$it aliment(s) exporté(s)." }, { "Export impossible : ${it.message}" }))
        }
    }

    fun clearImported() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { bank.clearImported() }
            refresh("Aliments importés effacés ; la base libre reste.")
        }
    }
}

@Composable
fun PortionsSettingsSection(state: PortionsUiState, actions: PortionsActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_portions_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_portions_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.settings_portions_counts, state.baseCount, state.importedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = actions.onImport, enabled = !state.busy) { Text(stringResource(R.string.settings_portions_import)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = actions.onExport, enabled = !state.busy) { Text(stringResource(R.string.settings_portions_export)) }
            }
            state.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (state.importedCount > 0) {
                TextButton(onClick = actions.onClearImported, enabled = !state.busy) {
                    Text(stringResource(R.string.settings_portions_clear))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_portions_credits),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
