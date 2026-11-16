@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.najah.mealannouncer.feature.meal.search

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
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
import com.najah.mealannouncer.feature.meal.R
import com.najah.mealannouncer.integration.camaps.CamapsMealType

/**
 * Recherche manuelle dans la base Open Food Facts (point 2 de la demande du 14/11/2026).
 *
 * L'écran est un panier : on cherche, on ajoute plusieurs produits, chacun avec **son**
 * calculateur (à la pièce, au poids, à la portion, au volume), et on lance l'automatisation
 * d'un seul geste. Les favoris sont montrés en tête : c'est le plus souvent le même goûter.
 */
@Composable
fun ManualSearchScreen(
    state: SearchUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSearchOnline: () -> Unit,
    onAddProduct: (FoodProduct) -> Unit,
    onModeChange: (Long, CalcMode) -> Unit,
    onQuantityChange: (Long, String) -> Unit,
    onRemoveLine: (Long) -> Unit,
    onTypeChange: (CamapsMealType) -> Unit,
    onSend: () -> Unit,
    onFavouriteNameChange: (String) -> Unit,
    onSaveFavourite: () -> Unit,
    onLoadFavourite: (String) -> Unit,
    onDeleteFavourite: (String) -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.search_back)) }
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.search_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.search_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(onClick = onSearch, enabled = !state.searching) {
                    Text(stringResource(R.string.search_action))
                }
                OutlinedButton(onClick = onSearchOnline, enabled = !state.searching) {
                    Text(stringResource(R.string.search_online))
                }
                if (state.searching) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.searchFailed) {
                Text(
                    text = stringResource(R.string.search_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val notice = state.notice
            if (notice != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.noticeIsError) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissNotice) { Text("OK") }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.lines.isEmpty()) {
                item { Hint(stringResource(R.string.search_nothing_yet)) }
            } else {
                item { SectionTitle(stringResource(R.string.search_basket)) }
                items(state.lines, key = { "line-${it.id}" }) { line ->
                    CalculatorRow(
                        line = line,
                        carbResult = line.carbResult(),
                        onModeChange = { onModeChange(line.id, it) },
                        onQuantityChange = { onQuantityChange(line.id, it) },
                        onRemove = { onRemoveLine(line.id) },
                    )
                }
            }

            if (state.favourites.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.search_favourites)) }
                items(state.favourites, key = { "fav-${it.id}" }) { favourite ->
                    FavouriteRow(
                        favourite = favourite,
                        onLoad = { onLoadFavourite(favourite.id) },
                        onDelete = { onDeleteFavourite(favourite.id) },
                    )
                }
            }

            if (state.results.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.search_results)) }
                items(state.results, key = { "result-${it.id}" }) { product ->
                    ProductRow(product = product, onAdd = { onAddProduct(product) })
                }
            } else if (state.hasSearched) {
                item { Hint(stringResource(R.string.search_no_result)) }
            }

            if (state.results.isEmpty() && state.productFavourites.isNotEmpty() && !state.hasSearched) {
                item { SectionTitle(stringResource(R.string.search_products_favourites)) }
                items(state.productFavourites, key = { "pfav-${it.id}" }) { product ->
                    ProductRow(product = product, onAdd = { onAddProduct(product) })
                }
            }
        }

        if (state.lines.isNotEmpty()) {
            TotalCard(state = state, onTypeChange = onTypeChange, onSend = onSend)
            FavouriteSaveRow(state = state, onNameChange = onFavouriteNameChange, onSave = onSaveFavourite)
        }
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
                Text(text = product.displayName(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = baseSummary(product),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAdd) { Text(stringResource(R.string.search_add)) }
        }
    }
}

/** « 55 g / 100 g · 12 g l'unité » : ce sur quoi le calculateur s'appuiera. */
@Composable
private fun baseSummary(product: FoodProduct): String {
    val parts = buildList {
        product.carbsPer100g?.let { add("${formatSearchQuantity(it)} g / 100 g") }
        product.carbsPer100ml?.let { add("${formatSearchQuantity(it)} g / 100 ml") }
        product.carbsPerUnit?.let { add("${formatSearchQuantity(it)} g l'unité") }
        product.defaultPortionGrams?.let { add("portion ${formatSearchQuantity(it)} g") }
        product.packageGrams?.let { add("paquet ${formatSearchQuantity(it)} g") }
    }
    return if (parts.isEmpty()) {
        stringResource(R.string.search_no_carb_data)
    } else {
        parts.joinToString(" · ")
    }
}

@Composable
private fun FavouriteRow(favourite: FavouriteMealUi, onLoad: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = favourite.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "${favourite.total.format()} g · ${favourite.favorite.lines.size} aliment(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onLoad) { Text(stringResource(R.string.search_use)) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.search_remove)) }
        }
    }
}

/**
 * Une ligne du panier : le produit, **son** calculateur, et la formule du calcul.
 */
@Composable
private fun CalculatorRow(
    line: SearchLine,
    carbResult: CarbResult,
    onModeChange: (CalcMode) -> Unit,
    onQuantityChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = line.product.displayName(), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SearchCalculator.modesFor(line.product).forEach { mode ->
                    FilterChip(
                        selected = line.mode == mode,
                        onClick = { onModeChange(mode) },
                        label = { Text(calcModeLabel(mode)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = line.quantityText,
                    onValueChange = onQuantityChange,
                    label = { Text(calcUnitLabel(line.mode)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(130.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = when (carbResult) {
                        is CarbResult.Computed -> "${carbResult.carbs.format()} g"
                        is CarbResult.Unavailable -> "—"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRemove) { Text(stringResource(R.string.search_remove)) }
            }
            Text(
                text = if (SearchCalculator.isValidQuantity(line.quantity)) {
                    SearchCalculator.formula(line.product, line.mode, line.quantity, carbResult)
                } else {
                    stringResource(R.string.search_quantity_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            val unavailable = carbResult as? CarbResult.Unavailable
            if (unavailable != null) {
                Text(
                    text = unavailable.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TotalCard(
    state: SearchUiState,
    onTypeChange: (CamapsMealType) -> Unit,
    onSend: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.search_total),
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
                    text = stringResource(R.string.search_total_unresolved, state.unresolvedCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.resolvedCount > 0) {
                Text(
                    text = stringResource(R.string.search_camaps_value, state.camapsGrams),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                camapsTypes().forEach { type ->
                    FilterChip(
                        selected = state.camapsType == type,
                        onClick = { onTypeChange(type) },
                        label = { Text(camapsTypeLabel(type)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSend,
                enabled = state.canSend,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.sending) {
                        stringResource(R.string.search_sending)
                    } else {
                        stringResource(R.string.search_send)
                    },
                )
            }
        }
    }
}

@Composable
private fun FavouriteSaveRow(
    state: SearchUiState,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.favouriteName,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.search_favourite_name)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        AssistChip(
            onClick = onSave,
            enabled = !state.savingFavourite,
            label = { Text(stringResource(R.string.search_favourite_save)) },
        )
    }
}

@Composable
private fun calcModeLabel(mode: CalcMode): String = stringResource(
    when (mode) {
        CalcMode.PER_100G -> R.string.search_mode_weight
        CalcMode.PER_100ML -> R.string.search_mode_volume
        CalcMode.PER_UNIT -> R.string.search_mode_unit
        CalcMode.PER_PORTION -> R.string.search_mode_portion
        CalcMode.PACKAGE -> R.string.search_mode_package
    },
)

@Composable
private fun calcUnitLabel(mode: CalcMode): String = stringResource(
    when (mode) {
        CalcMode.PER_100G, CalcMode.PACKAGE -> R.string.search_unit_gram
        CalcMode.PER_100ML -> R.string.search_unit_ml
        CalcMode.PER_UNIT -> R.string.search_unit_unit
        CalcMode.PER_PORTION -> R.string.search_unit_portion
    },
)

private fun camapsTypes(): List<CamapsMealType> =
    listOf(CamapsMealType.MEAL, CamapsMealType.SLOW, CamapsMealType.HYPO)

@Composable
private fun camapsTypeLabel(type: CamapsMealType): String = stringResource(
    when (type) {
        CamapsMealType.MEAL -> R.string.search_type_meal
        CamapsMealType.SLOW -> R.string.search_type_slow
        CamapsMealType.HYPO -> R.string.search_type_hypo
    },
)
