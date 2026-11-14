package com.najah.mealannouncer.feature.meal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit

/**
 * Écran de composition manuelle d'un repas.
 *
 * Aucun état propre : tout vient de [ManualEntryUiState] et remonte par des rappels.
 * C'est ce qui rend l'écran testable et évite les désynchronisations entre l'affichage et
 * le total.
 */
@Composable
fun ManualEntryScreen(
    state: ManualEntryUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchLocal: () -> Unit,
    onSearchOnline: () -> Unit,
    onAddProduct: (FoodProduct) -> Unit,
    onQuantityChange: (Long, String) -> Unit,
    onUnitChange: (Long, MealUnit) -> Unit,
    onRemoveLine: (Long) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.manual_back)) }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.manual_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.manual_search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSearchLocal, enabled = !state.searching) {
                    Text(stringResource(R.string.manual_search_action))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onSearchOnline, enabled = !state.searching) {
                    Text(stringResource(R.string.manual_search_online))
                }
                if (state.searching) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            Text(
                text = stringResource(R.string.manual_search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.searchFailed) {
                Text(
                    text = stringResource(R.string.manual_search_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Le repas composé vient en premier : une fois deux aliments ajoutés, c'est lui
            // que l'on vient vérifier, pas le catalogue.
            if (state.isEmpty) {
                item { Hint(stringResource(R.string.manual_nothing_yet)) }
            } else {
                item { SectionTitle(stringResource(R.string.manual_meal_section)) }
                items(state.lines, key = { "line-${it.id}" }) { line ->
                    LineRow(
                        line = line,
                        carbResult = state.carbOf(line),
                        onQuantityChange = { onQuantityChange(line.id, it) },
                        onUnitChange = { onUnitChange(line.id, it) },
                        onRemove = { onRemoveLine(line.id) },
                    )
                }
            }

            if (state.results.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.manual_results)) }
                items(state.results, key = { "result-${it.id}" }) { product ->
                    ProductRow(product = product, onAdd = { onAddProduct(product) })
                }
            } else if (state.hasSearched) {
                item { Hint(stringResource(R.string.manual_no_result)) }
            }

            if (state.results.isEmpty() && state.favourites.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.manual_favourites)) }
                items(state.favourites, key = { "fav-${it.id}" }) { product ->
                    ProductRow(product = product, onAdd = { onAddProduct(product) })
                }
            }
        }

        TotalCard(state = state)
        Button(
            onClick = onContinue,
            // Sans aliment chiffré, il n'y a rien à confirmer.
            enabled = state.resolvedCount > 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.manual_continue))
        }
        Text(
            text = stringResource(R.string.manual_next_step),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProductRow(product: FoodProduct, onAdd: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = product.displayName(),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = carbSummary(product),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.manual_add)) }
        }
    }
}

/** « 68 g / 100 g · 11 g l'unité » : la base du calcul, lisible d'un coup d'œil. */
@Composable
private fun carbSummary(product: FoodProduct): String {
    val parts = buildList {
        product.carbsPer100g?.let { add("${formatQuantity(it)} g / 100 g") }
        product.carbsPer100ml?.let { add("${formatQuantity(it)} g / 100 ml") }
        product.carbsPerUnit?.let { add("${formatQuantity(it)} g l'unité") }
    }
    return if (parts.isEmpty()) {
        stringResource(R.string.manual_empty_total)
    } else {
        parts.joinToString(" · ")
    }
}

@Composable
private fun LineRow(
    line: DraftLine,
    carbResult: CarbResult,
    onQuantityChange: (String) -> Unit,
    onUnitChange: (MealUnit) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = line.product.displayName(), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = line.quantityText,
                    onValueChange = onQuantityChange,
                    label = { Text(stringResource(R.string.manual_quantity)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (val result = carbResult) {
                        is CarbResult.Computed -> "${result.carbs.format()} g"
                        is CarbResult.Unavailable -> "—"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text(stringResource(R.string.manual_remove)) }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                unitChoices().forEach { unit ->
                    FilterChip(
                        selected = line.unit == unit,
                        onClick = { onUnitChange(unit) },
                        label = { Text(stringResource(unitLabelRes(unit))) },
                    )
                }
            }
            val unavailable = carbResult as? CarbResult.Unavailable
            if (unavailable != null) {
                Text(
                    text = unavailable.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TotalCard(state: ManualEntryUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.manual_total_label),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.resolvedCount == 0) "—" else "${state.total.format()} g",
                    style = if (state.resolvedCount == 0) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.headlineMedium
                    },
                )
            }
            if (state.unresolvedCount > 0) {
                Text(
                    text = stringResource(R.string.manual_total_unresolved, state.unresolvedCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = if (state.resolvedCount == 0) {
                    stringResource(R.string.manual_empty_total)
                } else {
                    stringResource(R.string.manual_camaps_value, state.camapsGrams)
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Unités proposées à la saisie : les six qui ont un sens pour un repas. */
private fun unitChoices(): List<MealUnit> = listOf(
    MealUnit.UNIT,
    MealUnit.GRAM,
    MealUnit.MILLILITER,
    MealUnit.PORTION,
    MealUnit.CUP,
    MealUnit.SLICE,
)

private fun unitLabelRes(unit: MealUnit): Int = when (unit) {
    MealUnit.UNIT -> R.string.manual_unit_unit
    MealUnit.GRAM -> R.string.manual_unit_gram
    MealUnit.MILLILITER -> R.string.manual_unit_milliliter
    MealUnit.PORTION -> R.string.manual_unit_portion
    MealUnit.CUP -> R.string.manual_unit_cup
    MealUnit.SLICE -> R.string.manual_unit_slice
    MealUnit.UNKNOWN -> R.string.manual_unit_unit
}
