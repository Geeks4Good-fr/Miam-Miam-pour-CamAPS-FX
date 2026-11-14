package com.najah.mealannouncer.feature.meal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.model.CarbCalculator
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.MealIntent
import com.najah.mealannouncer.core.model.MealItem
import com.najah.mealannouncer.core.model.MealTotals
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import com.najah.mealannouncer.data.openfoodfacts.OpenFoodFactsException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Une ligne en cours de composition : un produit et la quantité voulue.
 *
 * La quantité est gardée **telle que tapée** (`quantityText`) et non convertie
 * immédiatement : si l'utilisateur efface le champ pour taper « 2 », il ne doit pas voir
 * « 0 » s'imposer. Le calcul, lui, part d'une quantité nulle et donc non résolue.
 */
data class DraftLine(
    val id: Long,
    val product: FoodProduct,
    val quantityText: String,
    val unit: MealUnit,
) {
    val quantity: Double get() = parseQuantity(quantityText)

    fun carbResult(): CarbResult = CarbCalculator.carbsFor(product, quantity, unit)
}

/**
 * État de l'écran de saisie manuelle.
 *
 * Les totaux sont recalculés à la lecture à partir des lignes : pas de valeur dérivée
 * stockée qui pourrait se désynchroniser des lignes qu'elle prétend résumer.
 */
data class ManualEntryUiState(
    val query: String = "",
    val favourites: List<FoodProduct> = emptyList(),
    val results: List<FoodProduct> = emptyList(),
    val searching: Boolean = false,
    val searchFailed: Boolean = false,
    val hasSearched: Boolean = false,
    val lines: List<DraftLine> = emptyList(),
    private val nextLineId: Long = 1L,
) {

    fun carbOf(line: DraftLine): CarbResult = line.carbResult()

    val resolvedCount: Int get() = lines.count { carbOf(it) is CarbResult.Computed }

    /** Aliments présents mais non chiffrés : ils ne sont jamais comptés comme zéro. */
    val unresolvedCount: Int get() = lines.size - resolvedCount

    val total: Carbs
        get() = Carbs.of(
            lines.sumOf { line ->
                (carbOf(line) as? CarbResult.Computed)?.carbs?.grams ?: 0.0
            },
        )

    val camapsGrams: Int get() = total.toCamapsGrams()

    val isEmpty: Boolean get() = lines.isEmpty()

    /** Identifiant de la prochaine ligne ; `internal` pour rester dans le ViewModel. */
    internal fun allocateLineId(): Pair<Long, ManualEntryUiState> =
        nextLineId to copy(nextLineId = nextLineId + 1)
}

/**
 * Ce qui part vers l'écran de confirmation : le repas chiffré, et les aliments qui n'ont
 * pas pu l'être. Les seconds ne sont jamais comptés comme zéro.
 */
data class ConfirmableMeal(
    val meal: MealConfirmation,
    val skipped: List<String> = emptyList(),
)

/**
 * Compose un repas à la main : chercher un produit, choisir une quantité, voir le total.
 *
 * C'est le chemin de secours permanent de l'application : même quand la dictée vocale
 * échouera — bruit, accent, produit inconnu — il doit rester possible d'annoncer un repas
 * en quelques gestes.
 */
@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    private val repository: FoodRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ManualEntryUiState())
    val state: StateFlow<ManualEntryUiState> = _state.asStateFlow()

    init {
        // Les favoris sont la première chose montrée : le même biscuit revient souvent.
        viewModelScope.launch {
            repository.observeFavourites().collect { favourites ->
                _state.update { it.copy(favourites = favourites) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** Recherche dans le cache local : instantanée, fonctionne hors ligne. */
    fun searchLocal() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(results = it.favourites, hasSearched = false, searchFailed = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchFailed = false) }
            val local = repository.searchLocal(query)
            _state.update { it.copy(results = local, searching = false, hasSearched = true) }
        }
    }

    /** Recherche en ligne, puis mise en cache : c'est ce qui enrichit le cache local. */
    fun searchOnline() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchFailed = false) }
            val local = repository.searchLocal(query)
            val remote = try {
                repository.searchRemote(query)
            } catch (error: OpenFoodFactsException) {
                _state.update {
                    it.copy(results = local, searching = false, searchFailed = true, hasSearched = true)
                }
                return@launch
            }
            // Les résultats en ligne complètent les résultats locaux sans les dupliquer.
            val merged = (local + remote).distinctBy { it.id }
            _state.update {
                it.copy(results = merged, searching = false, searchFailed = false, hasSearched = true)
            }
        }
    }

    /** Ajoute un produit avec une quantité et une unité de départ plausibles. */
    fun addProduct(product: FoodProduct) {
        _state.update { current ->
            val (id, withId) = current.allocateLineId()
            val (quantityText, unit) = defaultDraft(product)
            withId.copy(lines = withId.lines + DraftLine(id, product, quantityText, unit))
        }
    }

    fun onQuantityChange(lineId: Long, text: String) {
        _state.update { current ->
            current.copy(
                lines = current.lines.map { line ->
                    if (line.id == lineId) line.copy(quantityText = text) else line
                },
            )
        }
    }

    fun onUnitChange(lineId: Long, unit: MealUnit) {
        _state.update { current ->
            current.copy(
                lines = current.lines.map { line ->
                    if (line.id == lineId) line.copy(unit = unit) else line
                },
            )
        }
    }

    fun removeLine(lineId: Long) {
        _state.update { current -> current.copy(lines = current.lines.filterNot { it.id == lineId }) }
    }

    /** Marque un produit comme vérifié : il deviendra un favori mis en avant. */
    fun markFavourite(product: FoodProduct) {
        viewModelScope.launch { repository.saveVerified(product) }
    }

    /**
     * Transforme les lignes en cours en repas confirmable.
     *
     * Renvoie `null` quand aucun aliment n'est chiffré : il n'y aurait rien à confirmer, et
     * un total de 0 g affiché comme un vrai repas serait un mensonge.
     */
    fun confirmableMeal(): ConfirmableMeal? {
        val current = _state.value
        if (current.isEmpty) return null

        val intent = MealIntent(
            // La saisie manuelle n'a pas de phrase dictée : le transcript reste vide.
            rawTranscript = "",
            items = current.lines.map { line ->
                MealItem(
                    rawText = line.product.displayName(),
                    normalizedProductName = line.product.name,
                    brand = line.product.brand,
                    quantity = line.quantity,
                    unit = line.unit,
                    matchedProduct = line.product,
                    confidence = 1f,
                )
            },
            capturedAt = Instant.now(),
        )
        val total = MealTotals.compute(intent)
        if (total.isEmpty) return null

        return ConfirmableMeal(
            meal = MealConfirmation(
                id = UUID.randomUUID().toString(),
                totalCarbs = total.total,
                items = total.resolved,
                notes = "",
                rawTranscript = "",
            ),
            skipped = total.incomplete.map { it.item.rawText },
        )
    }

    /** Repart d'un repas vide, une fois le précédent enregistré. */
    fun clearMeal() {
        _state.update {
            it.copy(
                lines = emptyList(),
                query = "",
                results = emptyList(),
                hasSearched = false,
                searchFailed = false,
            )
        }
    }

    private fun defaultDraft(product: FoodProduct): Pair<String, MealUnit> {
        // Copier dans des variables locales : le compilateur ne peut pas garantir la
        // stabilité d'une propriété d'un autre module entre deux lectures.
        val perUnit = product.carbsPerUnit
        val per100g = product.carbsPer100g
        val per100ml = product.carbsPer100ml
        val portionGrams = product.defaultPortionGrams
        val portionMl = product.defaultPortionMl

        return when {
            // Un biscuit compté à l'unité : « 1 » est la quantité naturelle.
            perUnit != null -> "1" to MealUnit.UNIT
            per100ml != null && portionMl != null ->
                formatQuantity(portionMl) to MealUnit.MILLILITER
            per100g != null && portionGrams != null ->
                formatQuantity(portionGrams) to MealUnit.GRAM
            per100g != null -> "100" to MealUnit.GRAM
            portionMl != null -> formatQuantity(portionMl) to MealUnit.MILLILITER
            else -> "1" to MealUnit.UNIT
        }
    }
}

/**
 * Lit une quantité tapée au clavier numérique.
 *
 * La virgule française est acceptée comme le point, et les espaces de séparation des
 * milliers sont ignorés : la saisie ne doit pas être une source d'erreur.
 */
internal fun parseQuantity(text: String): Double {
    val cleaned = text.trim().replace(" ", "").replace('\u00A0', ' ').replace(',', '.')
    return cleaned.toDoubleOrNull() ?: 0.0
}

/** Affiche une quantité sans décimale inutile : « 200 », « 16,5 ». */
internal fun formatQuantity(value: Double): String {
    val rounded = Carbs.of(value).rounded(2)
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}
