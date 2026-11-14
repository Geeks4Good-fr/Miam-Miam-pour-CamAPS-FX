package com.najah.mealannouncer.feature.confirmation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.model.CarbBasis
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.ResolvedMealItem
import com.najah.mealannouncer.integration.glookoexport.GlookoMealTrigger
import java.util.Locale

/**
 * Relecture avant enregistrement : le total, son détail, et ce qui n'a pas été compté.
 *
 * L'écran ne promet rien qu'il ne fait : il annonce l'enregistrement dans l'historique et
 * rappelle qu'aucun envoi vers CamAPS FX, Nightscout ou Glooko n'a encore lieu.
 */
@Composable
fun ConfirmationScreen(
    payload: ConfirmationPayload?,
    state: ConfirmationUiState,
    nightscoutNotice: NightscoutNotice,
    glookoTrigger: GlookoMealTrigger?,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewMeal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.confirmation_back)) }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.confirmation_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (payload == null) {
            // Après une fermeture de l'application, le repas n'existe plus : le dire plutôt
            // que d'afficher un total à zéro qui aurait l'air d'un vrai repas.
            Text(
                text = stringResource(R.string.confirmation_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            PayloadBody(
                payload = payload,
                state = state,
                nightscoutNotice = nightscoutNotice,
                glookoTrigger = glookoTrigger,
                onSave = onSave,
                onOpenHistory = onOpenHistory,
                onNewMeal = onNewMeal,
            )
        }
    }
}

@Composable
private fun ColumnScope.PayloadBody(
    payload: ConfirmationPayload,
    state: ConfirmationUiState,
    nightscoutNotice: NightscoutNotice,
    glookoTrigger: GlookoMealTrigger?,
    onSave: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewMeal: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { TotalCard(payload.meal.totalCarbs) }

        if (payload.skipped.isNotEmpty()) {
            item { SkippedCard(payload.skipped) }
        }

        item {
            Text(
                text = stringResource(R.string.confirmation_items_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(payload.meal.items) { item ->
            ItemRow(item)
        }
    }

    if (state is ConfirmationUiState.Saved) {
        SavedBlock(
            state = state,
            nightscoutNotice = nightscoutNotice,
            glookoTrigger = glookoTrigger,
            onOpenHistory = onOpenHistory,
            onNewMeal = onNewMeal,
        )
    } else {
        SaveBlock(state = state, onSave = onSave)
    }
}

@Composable
private fun TotalCard(total: Carbs) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.confirmation_total_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "${total.format()} g",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = stringResource(
                    R.string.confirmation_camaps_value,
                    total.toCamapsGrams(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SkippedCard(skipped: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.confirmation_incomplete_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.confirmation_incomplete_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            for (name in skipped) {
                Text(
                    text = "• $name",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ItemRow(item: ResolvedMealItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.displayName(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(
                    R.string.confirmation_quantity,
                    formatQuantity(item.quantity),
                    unitText(item),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(basisLabelRes(item.basis)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${item.totalCarbs.format()} g",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SaveBlock(state: ConfirmationUiState, onSave: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        if (state is ConfirmationUiState.Failed) {
            Text(
                text = stringResource(R.string.confirmation_failed, state.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Button(
            onClick = onSave,
            enabled = state !is ConfirmationUiState.Saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (state is ConfirmationUiState.Saving) {
                        R.string.confirmation_saving
                    } else {
                        R.string.confirmation_save
                    },
                ),
            )
        }
        Text(
            text = stringResource(R.string.confirmation_no_send),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun SavedBlock(
    state: ConfirmationUiState.Saved,
    nightscoutNotice: NightscoutNotice,
    glookoTrigger: GlookoMealTrigger?,
    onOpenHistory: () -> Unit,
    onNewMeal: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.confirmation_saved),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${state.total.format()} g",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.confirmation_saved_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        NightscoutLine(nightscoutNotice)
        GlookoLine(glookoTrigger)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.confirmation_open_history))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onNewMeal, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.confirmation_new_meal))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Ce qu'il est advenu de l'envoi vers Nightscout, dit sans détour.
 *
 * « En attente » n'est pas « envoyé » : l'écran ne doit pas laisser croire que le traitement
 * est déjà dans Nightscout alors que le fond de tâche ne l'a pas encore écrit.
 */
@Composable
private fun NightscoutLine(notice: NightscoutNotice) {
    val label = when (notice) {
        NightscoutNotice.NONE -> null
        NightscoutNotice.QUEUED -> R.string.confirmation_nightscout_queued
        NightscoutNotice.SIMULATED -> R.string.confirmation_nightscout_simulated
        NightscoutNotice.DISABLED -> R.string.confirmation_nightscout_disabled
    } ?: return
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Ce qu'il adviendra de l'export Glooko, dit seulement quand c'est vrai.
 *
 * Le passage prend l'écran du téléphone quelques minutes plus tard : le taire serait une
 * surprise désagréable au milieu d'une saisie. Quand rien n'est programmé — import coupé,
 * réglage retiré, export déjà fait à l'instant — il n'y a rien à dire, et l'écran se tait.
 */
@Composable
private fun GlookoLine(trigger: GlookoMealTrigger?) {
    if (trigger != GlookoMealTrigger.SCHEDULED) return
    Text(
        text = stringResource(R.string.confirmation_glooko_scheduled),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun basisLabelRes(basis: CarbBasis): Int = when (basis) {
    CarbBasis.PER_UNIT -> R.string.confirmation_basis_unit
    CarbBasis.PER_100_G -> R.string.confirmation_basis_100g
    CarbBasis.PER_100_ML -> R.string.confirmation_basis_100ml
}

/**
 * Unité affichée à côté de la quantité, accordée en nombre : « 1 unité », « 2 unités ».
 *
 * Les grammes et les millilitres ne s'accordent pas ; ce sont les décomptes qui ont besoin
 * d'un pluriel, et « 2 unité(s) » n'est pas du français.
 */
@Composable
private fun unitText(item: ResolvedMealItem): String = when (item.unit) {
    MealUnit.GRAM -> stringResource(R.string.confirmation_unit_gram)
    MealUnit.MILLILITER -> stringResource(R.string.confirmation_unit_milliliter)
    MealUnit.UNIT -> pluralUnit(R.plurals.confirmation_units_unit, item.quantity)
    MealUnit.PORTION -> pluralUnit(R.plurals.confirmation_units_portion, item.quantity)
    MealUnit.SLICE -> pluralUnit(R.plurals.confirmation_units_slice, item.quantity)
    MealUnit.CUP -> pluralUnit(R.plurals.confirmation_units_cup, item.quantity)
    MealUnit.UNKNOWN -> pluralUnit(R.plurals.confirmation_units_unit, item.quantity)
}

@Composable
private fun pluralUnit(id: Int, quantity: Double): String {
    // Android n'accorde qu'avec un entier : 1,5 portion se dit au pluriel, comme 2.
    val count = if (quantity == 1.0) 1 else 2
    return pluralStringResource(id, count, count)
}

/** « 2 » et non « 2,0 », mais « 16,5 » quand la précision existe. */
private fun formatQuantity(value: Double): String {
    val rounded = Carbs.of(value).rounded(2)
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}
