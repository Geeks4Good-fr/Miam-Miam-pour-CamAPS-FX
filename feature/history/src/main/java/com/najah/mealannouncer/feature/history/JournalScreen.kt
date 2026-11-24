@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.najah.mealannouncer.feature.history

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.model.AutomationEntry
import com.najah.mealannouncer.core.model.AutomationOutcome
import com.najah.mealannouncer.core.model.AutomationSummary
import com.najah.mealannouncer.core.model.GeminiMealLog
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Journal (point 3 de la demande du 14/11/2026).
 *
 * Deux onglets, volontairement séparés :
 * - **Automatisations** : ce que l'automatisation a réussi. La distinction demandée y est nette —
 *   « menée jusqu'au bout » (CamAPS **et** Carnet Glooko) contre « seulement jusqu'à CamAPS » ;
 * - **Repas** : l'historique des repas déjà enregistrés (l'ancien écran), inchangé.
 *
 * Un bandeau de synthèse compte les issues : c'est la première chose qu'on vient lire.
 */
@Composable
fun JournalScreen(
    entries: List<AutomationEntry>,
    summary: AutomationSummary,
    meals: List<HistoryEntry>,
    notice: String?,
    onBack: () -> Unit,
    onDeleteEntry: (String) -> Unit,
    onClearEntries: () -> Unit,
    onFavourite: (AutomationEntry) -> Unit,
    onDeleteMeal: (String) -> Unit,
    onFavouriteMeal: (HistoryEntry) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    geminiMeals: List<GeminiMealLog> = emptyList(),
    onDeleteGeminiMeal: (String) -> Unit = {},
    onNoteGlooko: (GeminiMealLog) -> Unit = {},
    onSendNightscout: (GeminiMealLog) -> Unit = {},
    geminiSending: Boolean = false,
) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_AUTOMATIONS) }
    var pendingClear by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.journal_back)) }
            Spacer(Modifier.width(4.dp))
            Text(text = stringResource(R.string.journal_title), style = MaterialTheme.typography.titleLarge)
        }

        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == TAB_AUTOMATIONS,
                onClick = { tab = TAB_AUTOMATIONS },
                text = { Text(stringResource(R.string.journal_tab_automations)) },
            )
            Tab(
                selected = tab == TAB_MEALS,
                onClick = { tab = TAB_MEALS },
                text = { Text(stringResource(R.string.journal_tab_meals)) },
            )
            Tab(
                selected = tab == TAB_GEMINI,
                onClick = { tab = TAB_GEMINI },
                text = { Text(stringResource(R.string.journal_tab_gemini)) },
            )
        }

        if (notice != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = notice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismissNotice) { Text("OK") }
                }
            }
        }

        when (tab) {
            TAB_GEMINI -> GeminiJournalTab(
                meals = geminiMeals,
                onDelete = onDeleteGeminiMeal,
                onNoteGlooko = onNoteGlooko,
                onSendNightscout = onSendNightscout,
                sending = geminiSending,
            )

            TAB_MEALS -> HistoryScreen(
                entries = meals,
                onBack = onBack,
                onDelete = onDeleteMeal,
                onFavourite = onFavouriteMeal,
                showHeader = false,
                modifier = Modifier.fillMaxSize(),
            )

            else -> AutomationsTab(
                entries = entries,
                summary = summary,
                onDelete = onDeleteEntry,
                onClear = { pendingClear = true },
                onFavourite = onFavourite,
            )
        }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text(stringResource(R.string.journal_clear_title)) },
            text = { Text(stringResource(R.string.journal_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearEntries()
                        pendingClear = false
                    },
                ) { Text(stringResource(R.string.journal_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) {
                    Text(stringResource(R.string.journal_clear_cancel))
                }
            },
        )
    }
}

@Composable
private fun AutomationsTab(
    entries: List<AutomationEntry>,
    summary: AutomationSummary,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onFavourite: (AutomationEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SummaryCard(summary = summary, onClear = onClear)
        if (entries.isEmpty()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = stringResource(R.string.journal_empty), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.journal_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                AutomationCard(entry = entry, onDelete = { onDelete(entry.id) }, onFavourite = { onFavourite(entry) })
            }
        }
    }
}

/** Le décompte par issue : « jusqu'au bout », « seulement CamAPS », puis le reste. */
@Composable
private fun SummaryCard(summary: AutomationSummary, onClear: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.journal_summary_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.journal_summary_complete, summary.complete),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.journal_summary_camaps_only, summary.camapsOnly),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            if (summary.cancelled + summary.failed > 0) {
                Text(
                    text = stringResource(
                        R.string.journal_summary_other,
                        summary.cancelled,
                        summary.failed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (summary.total > 0) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = onClear) { Text(stringResource(R.string.journal_clear)) }
            }
        }
    }
}

@Composable
private fun AutomationCard(entry: AutomationEntry, onDelete: () -> Unit, onFavourite: () -> Unit) {
    var confirmDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.journal_entry_delete_title),
            message = stringResource(R.string.journal_entry_delete_message),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatEntryDate(entry),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${entry.carbs} g · ${entry.description}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = outcomeLabel(entry.outcome),
                    style = MaterialTheme.typography.labelLarge,
                    color = outcomeColor(entry.outcome),
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(
                    text = entry.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (entry.carbs > 0) {
                    TextButton(onClick = onFavourite) {
                        Text(stringResource(R.string.journal_favourite))
                    }
                }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.journal_delete)) }
            }
        }
    }
}

@Composable
private fun outcomeLabel(outcome: AutomationOutcome): String = stringResource(
    when (outcome) {
        AutomationOutcome.COMPLETE -> R.string.journal_outcome_complete
        AutomationOutcome.CAMAPS_ONLY -> R.string.journal_outcome_camaps_only
        AutomationOutcome.CANCELLED -> R.string.journal_outcome_cancelled
        AutomationOutcome.FAILED -> R.string.journal_outcome_failed
        AutomationOutcome.LIBERTY -> R.string.journal_outcome_liberty
    },
)

@Composable
private fun outcomeColor(outcome: AutomationOutcome) = when (outcome) {
    AutomationOutcome.COMPLETE -> MaterialTheme.colorScheme.primary
    AutomationOutcome.CAMAPS_ONLY -> MaterialTheme.colorScheme.tertiary
    AutomationOutcome.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    AutomationOutcome.FAILED -> MaterialTheme.colorScheme.error
    AutomationOutcome.LIBERTY -> MaterialTheme.colorScheme.secondary
}

private const val TAB_AUTOMATIONS = 0
private const val TAB_MEALS = 1
private const val TAB_GEMINI = 2

private val entryDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)

private fun formatEntryDate(entry: AutomationEntry): String =
    entryDateFormat.format(entry.at.atZone(ZoneId.systemDefault()))
