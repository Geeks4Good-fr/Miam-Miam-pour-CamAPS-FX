package com.najah.mealannouncer.feature.history

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.NightscoutStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Les repas déjà confirmés, du plus récent au plus ancien.
 *
 * L'historique sert à répondre à une seule question : « qu'est-ce que j'ai annoncé, et
 * quand ? » Il affiche donc le total, le détail tel qu'il a été saisi et la valeur envoyée.
 *
 * Depuis le 14/11/2026, il s'affiche aussi comme onglet du journal ([JournalScreen], d'où
 * [showHeader] = false) et propose d'ajouter un repas aux favoris pour le réannoncer.
 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    onBack: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    onFavourite: ((HistoryEntry) -> Unit)? = null,
) {
    var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.history_back)) }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        if (entries.isEmpty()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.history_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = pluralStringResource(
                            R.plurals.history_count,
                            entries.size,
                            entries.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(entries, key = { it.meal.id }) { entry ->
                    MealCard(
                        entry = entry,
                        onDelete = { pendingDelete = entry },
                        onFavourite = onFavourite?.let { callback -> { callback(entry) } },
                    )
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.history_delete_message,
                        target.meal.noteOrSummary(),
                        target.meal.totalCarbs.format(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.meal.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.history_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.history_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun MealCard(entry: HistoryEntry, onDelete: () -> Unit, onFavourite: (() -> Unit)?) {
    val meal = entry.meal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatDate(meal),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${meal.totalCarbs.format()} g",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_camaps_value,
                            meal.totalCarbs.toCamapsGrams(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.history_delete))
                }
            }

            if (onFavourite != null) {
                TextButton(onClick = onFavourite) {
                    Text(stringResource(R.string.history_favourite))
                }
            }

            if (meal.manualTotalEdited) {
                Text(
                    text = stringResource(R.string.history_manual_total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(text = meal.noteOrSummary(), style = MaterialTheme.typography.bodyMedium)

            if (meal.rawTranscript.isNotBlank()) {
                Text(
                    text = "« ${meal.rawTranscript} »",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            NightscoutStatusLine(entry.nightscoutStatus)
        }
    }
}

/** Ce que dit le journal de l'envoi Nightscout, et rien de plus. */
@Composable
private fun NightscoutStatusLine(status: NightscoutStatus) {
    val label = when (status) {
        NightscoutStatus.PENDING -> R.string.history_nightscout_pending
        NightscoutStatus.SENT -> R.string.history_nightscout_sent
        NightscoutStatus.FAILED -> R.string.history_nightscout_failed
        NightscoutStatus.DUPLICATE_SUSPECT -> R.string.history_nightscout_duplicate
        NightscoutStatus.NOT_REQUESTED -> null
    } ?: return
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = if (status == NightscoutStatus.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(top = 6.dp),
    )
}

private val dateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)

private fun formatDate(meal: MealConfirmation): String =
    dateFormat.format(meal.createdAt.atZone(ZoneId.systemDefault()))
