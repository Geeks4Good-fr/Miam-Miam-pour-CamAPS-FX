@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.najah.mealannouncer.feature.history

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.model.GeminiMealLog
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Onglet « Gemini » du journal : chaque repas mené en mode Gemini direct, avec **toute** la
 * conversation — les phrases dites, les réponses complètes de Gemini et leurs tableaux — et ce
 * qui est parti vers CamAPS, le Carnet Glooko et Nightscout. Rien de ceci ne quitte le téléphone,
 * sauf si l'utilisateur le partage lui-même.
 */
@Composable
internal fun GeminiJournalTab(
    meals: List<GeminiMealLog>,
    onDelete: (String) -> Unit,
    onNoteGlooko: (GeminiMealLog) -> Unit = {},
    onSendNightscout: (GeminiMealLog) -> Unit = {},
    sending: Boolean = false,
) {
    if (meals.isEmpty()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = stringResource(R.string.journal_gemini_empty), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(meals, key = { it.id }) { meal ->
            GeminiMealCard(
                meal = meal,
                onDelete = { onDelete(meal.id) },
                onNoteGlooko = { onNoteGlooko(meal) },
                onSendNightscout = { onSendNightscout(meal) },
                sending = sending,
            )
        }
    }
}

@Composable
private fun GeminiMealCard(
    meal: GeminiMealLog,
    onDelete: () -> Unit,
    onNoteGlooko: () -> Unit,
    onSendNightscout: () -> Unit,
    sending: Boolean,
) {
    var open by rememberSaveable(meal.id) { mutableStateOf(false) }
    // Effacer une conversation est définitif : on le demande (essai du 24/11/2026, la carte partait
    // au premier toucher).
    var confirmDelete by rememberSaveable(meal.id) { mutableStateOf(false) }
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = geminiDateFormat.format(meal.startedAt.atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = totalLabel(meal) + (meal.meal.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.journal_gemini_counts, meal.exchanges, meal.photos, meal.model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (meal.detail.isNotBlank()) {
                Text(
                    text = meal.detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (meal.destinations.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(text = stringResource(R.string.journal_gemini_destinations), style = MaterialTheme.typography.titleSmall)
                meal.destinations.forEach { line ->
                    Text(text = "• $line", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                meal.turns.forEachIndexed { index, turn ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = if (turn.fromUser) {
                            stringResource(if (turn.withPhoto) R.string.journal_gemini_you_photo else R.string.journal_gemini_you)
                        } else {
                            stringResource(R.string.journal_gemini_answer)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    // Le texte tel que Gemini l'a écrit, tableaux compris : police à chasse fixe et
                    // défilement horizontal, pour que les colonnes des tableaux restent alignées.
                    Text(
                        text = turn.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = if (turn.fromUser) FontFamily.Default else FontFamily.Monospace,
                        ),
                        softWrap = turn.fromUser || !turn.text.contains('|'),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(
                                if (!turn.fromUser && turn.text.contains('|')) {
                                    Modifier.horizontalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
            // Après coup : le Carnet Glooko et Nightscout, quand l'enchaînement automatique s'est arrêté.
            if (meal.totalCarbs != null) {
                // FlowRow : sur un écran étroit, un bouton passe à la ligne au lieu d'être coupé en
                // syllabes (« Sup / pri / mer », essai du 24/11/2026).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onNoteGlooko, enabled = !sending) { Text(stringResource(R.string.journal_gemini_note_glooko)) }
                    TextButton(onClick = onSendNightscout, enabled = !sending) { Text(stringResource(R.string.journal_gemini_send_nightscout)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { open = !open }) {
                    Text(stringResource(if (open) R.string.journal_gemini_hide else R.string.journal_gemini_show))
                }
                TextButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Repas Gemini — ${totalLabel(meal)}")
                            putExtra(Intent.EXTRA_TEXT, exportText(meal))
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                ) { Text(stringResource(R.string.journal_gemini_share)) }
                TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.journal_delete)) }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.journal_gemini_delete_title),
            message = stringResource(R.string.journal_gemini_delete_message),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Demande avant d'effacer : « Supprimer » ou « Annuler ». */
@Composable
internal fun ConfirmDeleteDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.journal_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.journal_clear_cancel)) } },
    )
}

private fun grams(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.FRANCE, "%.1f", value)

private fun totalLabel(meal: GeminiMealLog): String {
    val total = meal.totalCarbs ?: return "Sans total"
    val low = meal.minCarbs
    val high = meal.maxCarbs
    val range = if (low != null && high != null && high > low) " (${grams(low)} à ${grams(high)} g)" else ""
    return "${grams(total)} g$range"
}

/** Le repas entier en texte (Markdown de Gemini compris), pour le partager ou l'archiver. */
internal fun exportText(meal: GeminiMealLog): String = buildString {
    appendLine("# Repas du ${geminiDateFormat.format(meal.startedAt.atZone(ZoneId.systemDefault()))}")
    appendLine("Total retenu : ${totalLabel(meal)}")
    if (meal.meal.isNotBlank()) appendLine("Repas : ${meal.meal}")
    if (meal.detail.isNotBlank()) appendLine("Détail : ${meal.detail}")
    appendLine("Modèle : ${meal.model}")
    if (meal.destinations.isNotEmpty()) {
        appendLine()
        appendLine("## Envois")
        meal.destinations.forEach { appendLine("- $it") }
    }
    meal.turns.forEach { turn ->
        appendLine()
        appendLine(if (turn.fromUser) "## Vous" + (if (turn.withPhoto) " (avec une photo)" else "") else "## Gemini")
        appendLine(turn.text)
    }
}.trim()

private val geminiDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm", Locale.FRANCE)
