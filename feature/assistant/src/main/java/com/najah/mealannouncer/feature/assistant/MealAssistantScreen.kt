@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.najah.mealannouncer.feature.assistant

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.feature.assistant.portions.PortionOption
import com.najah.mealannouncer.feature.assistant.portions.PortionPicker
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.feature.assistant.R
import com.najah.mealannouncer.integration.camaps.CamapsWriteResult

/**
 * L'écran du repas dicté.
 *
 * Trois moments, dans l'ordre où l'utilisateur les vit : parler, vérifier les portions,
 * envoyer. Aucun nombre n'est présenté comme acquis : chaque ligne dit d'où vient sa valeur,
 * et une ligne non chiffrée est visible comme telle.
 */
@Composable
fun MealAssistantScreen(
    state: AssistantUiState,
    onMic: () -> Unit,
    onTranscriptChange: (String) -> Unit,
    onAnalyse: () -> Unit,
    onQuantityChange: (Long, String) -> Unit,
    onUnitChange: (Long, MealUnit) -> Unit,
    onAnnounce: () -> Unit,
    onWriteCamaps: () -> Unit,
    onStartDialogue: () -> Unit,
    onStopDialogue: () -> Unit,
    onStopCamapsConfirm: () -> Unit,
    onStopConversation: () -> Unit,
    onRejectProduct: (Long) -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhoto: () -> Unit,
    onConfirmPhoto: () -> Unit,
    onDiscardPhoto: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** « Photographier l'emballage » d'une ligne au poids de pièce estimé. */
    onPreciseByPhoto: (Long) -> Unit = {},
    /** Une portion en images touchée : la ligne passe à ce poids (grammes). */
    onPortionPick: (Long, Double) -> Unit = { _, _ -> },
    /** Un repas favori rappelé d'un geste (point 4 de la demande du 14/11/2026). */
    onLoadFavourite: (String) -> Unit = {},
    /** Le repas affiché enregistré comme favori, sous le nom donné. */
    onSaveFavourite: (String) -> Unit = {},
    /** Mode Gemini : envoyer ou abandonner les photos prises en attente. */
    onSendPhotoBatch: () -> Unit = {},
    onCancelPhotoBatch: () -> Unit = {},
    /** Mode Gemini : une portion en images touchée pour un aliment sans marque. */
    onDirectPortionPick: (String, PortionOption) -> Unit = { _, _ -> },
) {
    var favouriteName by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.assistant_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.assistant_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.localBaseChecked) {
            Spacer(Modifier.height(6.dp))
            val count = state.localBaseCount
            Text(
                text = if (count != null && count > 0) {
                    stringResource(R.string.assistant_base_count, count)
                } else {
                    stringResource(R.string.assistant_base_missing)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (count != null && count > 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(Modifier.height(20.dp))

        // Les repas favoris : le même goûter, annoncé d'un geste (point 4).
        if (state.favourites.isNotEmpty()) {
            Text(
                text = stringResource(R.string.assistant_favourites),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.favourites.forEach { favourite ->
                    AssistChip(
                        onClick = { onLoadFavourite(favourite.id) },
                        label = { Text("${favourite.name} · ${favourite.total.format()} g") },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = onMic,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        ) {
            Text(
                text = if (state.phase == AssistantPhase.RECORDING) {
                    stringResource(R.string.assistant_stop)
                } else {
                    stringResource(R.string.assistant_speak)
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onTakePhoto,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                // Repas en cours : la photo sert à le préciser (emballage), pas à en proposer un autre.
                Text(
                    text = stringResource(
                        if (state.lines.isNotEmpty()) R.string.assistant_photo_package else R.string.assistant_photo_camera,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
            OutlinedButton(
                onClick = onPickPhoto,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.assistant_photo_pick),
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Mode Gemini : un couvert à côté du plat sert d'échelle pour estimer les portions.
        if (state.geminiDirect) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.assistant_utensil_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.photoBatch?.let { batch ->
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.assistant_photo_batch, batch.count),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onTakePhoto) { Text(stringResource(R.string.assistant_photo_batch_more)) }
                        Button(onClick = onSendPhotoBatch) { Text(stringResource(R.string.assistant_photo_batch_send)) }
                        TextButton(onClick = onCancelPhotoBatch) { Text(stringResource(R.string.assistant_photo_batch_cancel)) }
                    }
                }
            }
        }

        if (state.busy) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (state.phase) {
                        AssistantPhase.TRANSCRIBING -> stringResource(R.string.assistant_transcribing)
                        else -> state.progress?.let { step ->
                            stringResource(
                                R.string.assistant_progress,
                                step.name,
                                step.index,
                                step.total,
                            )
                        } ?: stringResource(R.string.assistant_analysing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        state.error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.missingKey) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.assistant_no_key),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.transcript,
            onValueChange = onTranscriptChange,
            label = { Text(stringResource(R.string.assistant_transcript_label)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            minLines = 2,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onAnalyse,
            enabled = !state.busy && state.transcript.isNotBlank(),
        ) {
            Text(stringResource(R.string.assistant_reanalyse))
        }

        state.photoSeen?.let { seen -> SeenCard(seen) }

        state.direct?.let { direct -> GeminiDirectCard(direct, onDirectPortionPick, enabled = !state.busy) }

        state.conversation?.let { talk ->
            Spacer(Modifier.height(16.dp))
            ConversationCard(talk = talk, onStop = onStopConversation)
        }

        state.photo?.let { photo ->
            Spacer(Modifier.height(16.dp))
            PhotoCard(
                photo = photo,
                onConfirm = onConfirmPhoto,
                onDiscard = onDiscardPhoto,
            )
        }

        if (state.lines.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            state.dialogue?.let { dialogue ->
                DialogueCard(dialogue = dialogue, onStop = onStopDialogue)
                Spacer(Modifier.height(12.dp))
            }
            if (state.dialogue == null && state.missingPortions > 0 &&
                state.phase == AssistantPhase.READY
            ) {
                PortionsCard(missing = state.missingPortions, onStart = onStartDialogue)
                Spacer(Modifier.height(12.dp))
            }
            // Contester un produit n'a de sens que quand rien d'autre ne tourne : sinon la ligne
            // changerait sous les yeux pendant qu'une question attend sa réponse.
            val canReject = state.phase == AssistantPhase.READY && !state.busy &&
                state.conversation == null && state.camapsConfirm == null && state.dialogue == null
            state.lines.forEach { line ->
                LineCard(
                    line = line,
                    onQuantityChange = { onQuantityChange(line.id, it) },
                    onUnitChange = { onUnitChange(line.id, it) },
                    onReject = { onRejectProduct(line.id) }.takeIf { canReject },
                    onPrecise = { onPreciseByPhoto(line.id) }.takeIf { canReject },
                    onPortionPick = { grams: Double -> onPortionPick(line.id, grams) }.takeIf { !state.busy },
                )
                Spacer(Modifier.height(10.dp))
            }
        } else if (state.phase == AssistantPhase.READY && state.photo == null) {
            // Une proposition de photo n'est pas un repas vide : on ne dit pas « aucun aliment »
            // sous une carte qui en montre déjà.
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.assistant_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.rejected.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.assistant_rejected, state.rejected.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.warnings.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.assistant_warnings),
                style = MaterialTheme.typography.titleSmall,
            )
            state.warnings.forEach { warning ->
                Text(
                    text = "• $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.lines.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            TotalCard(state)
            state.camapsConfirm?.let { confirm ->
                Spacer(Modifier.height(12.dp))
                CamapsConfirmCard(confirm = confirm, onStop = onStopCamapsConfirm)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onWriteCamaps,
                enabled = state.canWrite,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    stringResource(
                        if (state.liberty) R.string.assistant_write_liberty else R.string.assistant_write_camaps,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAnnounce,
                enabled = state.total.grams > 0.0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.assistant_announce))
            }
            // Enregistrer ce repas comme favori : le même goûter, réannoncé d'un geste.
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = favouriteName,
                    onValueChange = { favouriteName = it },
                    label = { Text(stringResource(R.string.assistant_favourite_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { onSaveFavourite(favouriteName) }) {
                    Text(stringResource(R.string.assistant_favourite_save))
                }
            }
            state.camaps?.let { result ->
                Spacer(Modifier.height(10.dp))
                CamapsNotice(result)
            }
            state.recordNotice?.let { notice ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = onReset) {
                Text(stringResource(R.string.assistant_restart))
            }
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.assistant_back))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.assistant_reminder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Ce que la photo propose.
 *
 * La carte dit toujours qu'il s'agit d'une **proposition** : les poids viennent d'une
 * estimation visuelle, et rien n'entre dans le repas avant le geste de l'utilisateur. Les
 * aliments sans valeur restent visibles avec la mention « valeur manquante » plutôt que
 * d'être comptés zéro.
 */
/** Ce que la photo a montré, tel que le modèle l'a dit (et la voix l'a lu). */
@Composable
private fun SeenCard(seen: String) {
    Spacer(Modifier.height(16.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_photo_seen),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = seen, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * La réponse de Gemini en mode direct, telle qu'il l'a écrite (sans la mise en forme Markdown) :
 * le détail se lit ici, comme sur le site de Gemini ; le total retenu est rappelé en tête.
 */
@Composable
private fun GeminiDirectCard(
    direct: DirectAnswerUi,
    onPortionPick: (String, PortionOption) -> Unit,
    enabled: Boolean,
) {
    Spacer(Modifier.height(16.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_gemini_title, direct.exchanges),
                style = MaterialTheme.typography.titleSmall,
            )
            direct.totalLabel?.let { total ->
                Spacer(Modifier.height(4.dp))
                Text(text = total, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(text = direct.answer, style = MaterialTheme.typography.bodyMedium)
        }
    }
    // Les fiches Open Food Facts que Gemini a comparées à la photo : l'utilisateur voit ce qui a servi.
    if (direct.offCards.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.assistant_gemini_off_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        direct.offCards.forEach { card -> OffCard(card) }
    }
    // Les portions en images des aliments sans marque : toucher une photo précise la portion à Gemini.
    direct.portionChoices.forEach { choice ->
        Spacer(Modifier.height(10.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.assistant_gemini_portion_title, choice.said),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(6.dp))
                PortionPicker(
                    options = choice.options,
                    currentGrams = null,
                    onPick = if (enabled) {
                        { grams: Double -> choice.options.firstOrNull { it.grams == grams }?.let { onPortionPick(choice.said, it) } }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Une fiche Open Food Facts : photo, nom, marque, et les valeurs de l'étiquette qui servent au calcul. */
@Composable
private fun OffCard(card: OffCardUi) {
    val p = card.product
    Spacer(Modifier.height(6.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (card.chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            p.imageUrl?.let { url ->
                ProductThumbnail(image = ProductImage(url, "Open Food Facts"), modifier = Modifier.width(72.dp))
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (if (card.chosen) "✓ " else "") + p.name + (p.brand?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val values = buildList {
                    p.carbsPer100g?.let { add("${formatQuantityText(it)} g de glucides / 100 g") }
                    p.carbsPer100ml?.let { add("${formatQuantityText(it)} g de glucides / 100 ml") }
                    p.carbsPerUnit?.let { add("${formatQuantityText(it)} g par portion") }
                    p.servingLabel?.let { add("portion : $it") }
                    p.packageGrams?.let { add("paquet : ${formatQuantityText(it)} g") }
                    p.barcode?.let { add("code-barres $it") }
                }
                Text(
                    text = values.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (card.chosen) R.string.assistant_gemini_off_chosen else R.string.assistant_gemini_off_candidate,
                        card.askedName,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun PhotoCard(
    photo: PhotoProposal,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_photo_title),
                style = MaterialTheme.typography.titleSmall,
            )
            photo.description?.let { seen ->
                Spacer(Modifier.height(6.dp))
                Text(text = seen, style = MaterialTheme.typography.bodyMedium)
            }
            if (photo.busy) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = photo.step?.let { step ->
                            stringResource(
                                R.string.assistant_progress,
                                step.name,
                                step.index,
                                step.total,
                            )
                        } ?: stringResource(R.string.assistant_photo_busy),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            photo.error?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (photo.lines.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                photo.lines.forEach { line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = line.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${line.quantityText} ${unitLabel(line.unit)}".trim(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        val result = line.carbResult()
                        Text(
                            text = if (result is CarbResult.Computed) {
                                "${result.carbs.format()} g"
                            } else {
                                stringResource(R.string.assistant_photo_missing)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (result is CarbResult.Computed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.assistant_photo_total,
                        photo.total.format(),
                        photo.resolved,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.assistant_photo_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (photo.rejected.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.assistant_rejected,
                        photo.rejected.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            photo.warnings.forEach { warning ->
                Text(
                    text = "• $warning",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onConfirm, enabled = photo.canAdd, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.assistant_photo_confirm))
                }
                OutlinedButton(onClick = onDiscard) {
                    Text(stringResource(R.string.assistant_photo_discard))
                }
            }
        }
    }
}

@Composable
private fun LineCard(
    line: AssistantLine,
    onQuantityChange: (String) -> Unit,
    onUnitChange: (MealUnit) -> Unit,
    /** « Pas ce produit » ; `null` quand on ne peut pas contester en ce moment. */
    onReject: (() -> Unit)?,
    /** « Photographier l'emballage » ; `null` quand on ne peut pas le faire en ce moment. */
    onPrecise: (() -> Unit)? = null,
    onPortionPick: ((Double) -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OriginBadge(line)
            }
            // La photo du produit trouvé : un coup d'œil suffit pour vérifier que c'est bien
            // l'article mangé. Aucune photo quand rien ne désigne sûrement ce produit.
            line.image?.let { image ->
                Spacer(Modifier.height(8.dp))
                ProductThumbnail(image = image)
            }
            if (line.portionOptions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                PortionPicker(
                    options = line.portionOptions,
                    currentGrams = line.quantity.takeIf { line.unit == MealUnit.GRAM && it > 0 },
                    onPick = onPortionPick,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = line.quantityText,
                    onValueChange = onQuantityChange,
                    label = { Text(stringResource(R.string.assistant_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onUnitChange(nextUnitFor(line)) }) {
                    Text(unitLabel(line.unit))
                }
            }
            Spacer(Modifier.height(6.dp))
            line.portionNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
            }
            when (val result = line.carbResult()) {
                is CarbResult.Computed -> Text(
                    text = "${result.carbs.format()} g",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                is CarbResult.Unavailable -> Text(
                    text = result.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            line.estimate?.sourceUrl?.let { source ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.assistant_source, source.take(80)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Poids d'une pièce estimé : la photo de l'emballage (ou des pièces) peut le préciser.
            if (line.photoWouldHelp) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (line.carbsGuessed) R.string.assistant_carbs_guessed else R.string.assistant_weight_guessed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(
                    onClick = { onPrecise?.invoke() },
                    enabled = onPrecise != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.assistant_precise_by_photo), textAlign = TextAlign.Center)
                }
            }
            // Le produit montré n'est pas celui mangé : on l'écarte et l'assistant en cherche un autre.
            if (line.product != null || line.estimate != null) {
                TextButton(onClick = { onReject?.invoke() }, enabled = onReject != null) {
                    Text(stringResource(R.string.assistant_reject_product))
                }
            }
        }
    }
}

/**
 * Le dialogue en cours : la question posée, ce que le micro entend, et où en est
 * l'assistant. Rien de muet : une question qui attend une réponse doit se voir.
 */
@Composable
private fun DialogueCard(dialogue: PortionDialogue, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_dialogue_title, dialogue.lineName),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = dialogue.question,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dialogue.listening || dialogue.thinking) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        dialogue.thinking -> stringResource(R.string.assistant_dialogue_thinking)
                        dialogue.listening -> stringResource(R.string.assistant_dialogue_listening)
                        dialogue.awaitingMic -> stringResource(R.string.assistant_dialogue_manual)
                        else -> stringResource(R.string.assistant_dialogue_speaking)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (dialogue.heard.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.assistant_dialogue_heard, dialogue.heard),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.assistant_portions_stop))
            }
        }
    }
}

/**
 * La confirmation vocale avant d'écrire dans CamAPS, en mode insertion automatique.
 *
 * Elle montre la question posée, ce qui est entendu, et le moment où il faut répondre au micro.
 * Rien ne s'écrit sans un « oui » : la carte rend ce dialogue visible, jamais aveugle.
 */
@Composable
private fun CamapsConfirmCard(confirm: CamapsConfirmDialogue, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_camaps_confirm_title),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = confirm.question,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (confirm.listening) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        confirm.listening -> stringResource(R.string.assistant_dialogue_listening)
                        confirm.awaitingMic -> stringResource(R.string.assistant_dialogue_manual)
                        else -> stringResource(R.string.assistant_dialogue_speaking)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (confirm.heard.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.assistant_dialogue_heard, confirm.heard),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.assistant_camaps_confirm_cancel))
            }
        }
    }
}

/**
 * La conversation avec l'assistant : sa question, ce que le micro entend, et quand il réfléchit.
 * Les lignes du repas, juste en dessous, se mettent à jour après chaque réponse.
 */
@Composable
private fun ConversationCard(talk: ConversationUi, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_conversation_title),
                style = MaterialTheme.typography.labelMedium,
            )
            if (talk.question.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = talk.question,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (talk.listening || talk.thinking) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = when {
                        talk.thinking -> stringResource(R.string.assistant_dialogue_thinking)
                        talk.listening -> stringResource(R.string.assistant_dialogue_listening)
                        talk.awaitingMic -> stringResource(R.string.assistant_dialogue_manual)
                        else -> stringResource(R.string.assistant_dialogue_speaking)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (talk.heard.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.assistant_dialogue_heard, talk.heard),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.assistant_conversation_stop))
            }
        }
    }
}

/** L'invitation à préciser les portions à voix haute, quand il en manque. */
@Composable
private fun PortionsCard(missing: Int, onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.assistant_portions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.assistant_portions_hint, missing),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.assistant_portions_start))
            }
        }
    }
}

@Composable
private fun OriginBadge(line: AssistantLine) {    val (label, container) = when (line.origin) {
        // Distinguer la base du téléphone d'une valeur prise à l'instant sur le réseau : c'est
        // ce qui permet de voir d'un coup d'œil que la recherche est bien restée locale.
        CarbOrigin.OFF -> offlineLabel(line.product?.source) to
            MaterialTheme.colorScheme.secondaryContainer

        CarbOrigin.WEB -> stringResource(R.string.assistant_origin_web) to
            MaterialTheme.colorScheme.tertiaryContainer

        // Une estimation n'est pas une mesure : on le dit clairement, c'est une valeur moyenne.
        CarbOrigin.GENERAL -> stringResource(R.string.assistant_origin_general) to
            MaterialTheme.colorScheme.tertiaryContainer

        null -> stringResource(R.string.assistant_origin_none) to
            MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** « hors ligne » pour la base embarquée, « en ligne » pour une requête réseau. */
@Composable
private fun offlineLabel(source: FoodSource?): String = when (source) {
    FoodSource.OPEN_FOOD_FACTS_ONLINE -> stringResource(R.string.assistant_origin_off_online)
    else -> stringResource(R.string.assistant_origin_off_offline)
}

@Composable
private fun TotalCard(state: AssistantUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.assistant_total, state.total.format()),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (state.liberty) R.string.assistant_liberty_value else R.string.assistant_camaps_value,
                    state.camapsGrams,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state.unresolvedCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.assistant_unresolved, state.unresolvedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CamapsNotice(result: CamapsWriteResult) {
    val (text, container) = when (result) {
        is CamapsWriteResult.Entered -> "Valeur saisie dans CamAPS : ${result.carbs} g. " +
            "Vérifiez, puis confirmez vous-même." to MaterialTheme.colorScheme.secondaryContainer

        is CamapsWriteResult.Manual -> stringResource(R.string.assistant_manual_hint) to
            MaterialTheme.colorScheme.secondaryContainer

        is CamapsWriteResult.Failed -> result.message to MaterialTheme.colorScheme.errorContainer
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}
