package com.najah.mealannouncer.feature.meal.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.najah.mealannouncer.core.database.repository.FavoriteMealRepository
import com.najah.mealannouncer.core.database.repository.JournalRepository
import com.najah.mealannouncer.core.datastore.MealRecordSettings
import com.najah.mealannouncer.core.model.AutomationOutcome
import com.najah.mealannouncer.core.model.AutomationSource
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FavoriteMeal
import com.najah.mealannouncer.core.model.FavoriteMealLine
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import com.najah.mealannouncer.data.openfoodfacts.OpenFoodFactsException
import com.najah.mealannouncer.integration.camaps.CamapsMealOutcome
import com.najah.mealannouncer.integration.camaps.CamapsMealRequest
import com.najah.mealannouncer.integration.camaps.CamapsMealGateway
import com.najah.mealannouncer.integration.camaps.CamapsMealType
import com.najah.mealannouncer.integration.camaps.CamapsWriteResult
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnet
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnetMoment
import com.najah.mealannouncer.integration.glookoexport.GlookoCarnetResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

/**
 * Une ligne en cours de chiffrage dans la recherche manuelle.
 *
 * [quantityText] est gardée telle que tapée : effacer le champ pour taper « 2 » ne doit pas
 * faire apparaître « 0 » entre-temps. Le calculateur, lui, part d'une quantité nulle et non
 * résolue.
 */
data class SearchLine(
    val id: Long,
    val product: FoodProduct,
    val mode: CalcMode,
    val quantityText: String,
) {
    val quantity: Double get() = parseSearchQuantity(quantityText)

    fun carbResult(): CarbResult = SearchCalculator.compute(product, mode, quantity)
}

/** Ce que l'écran de recherche manuelle a à montrer. */
data class SearchUiState(
    val query: String = "",
    val results: List<FoodProduct> = emptyList(),
    val favourites: List<FavouriteMealUi> = emptyList(),
    val productFavourites: List<FoodProduct> = emptyList(),
    val searching: Boolean = false,
    val searchFailed: Boolean = false,
    val hasSearched: Boolean = false,
    val lines: List<SearchLine> = emptyList(),
    val camapsType: CamapsMealType = CamapsMealType.MEAL,
    val sending: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
    val favouriteName: String = "",
    val savingFavourite: Boolean = false,
    /** Mode Liberty (24/11/2026) : le panier est noté, jamais annoncé dans CamAPS. */
    val liberty: Boolean = false,
    private val nextLineId: Long = 1L,
) {
    val resolvedCount: Int get() = lines.count { it.carbResult() is CarbResult.Computed }

    val unresolvedCount: Int get() = lines.size - resolvedCount

    val total: Carbs
        get() = SearchCalculator.totalOf(lines.map { (it.carbResult() as? CarbResult.Computed)?.carbs })

    val camapsGrams: Int get() = total.toCamapsGrams()

    /** Envoyer une annonce vide à CamAPS n'aurait pas de sens. */
    val canSend: Boolean get() = resolvedCount > 0 && !sending

    internal fun allocateLineId(): Pair<Long, SearchUiState> =
        nextLineId to copy(nextLineId = nextLineId + 1)
}

/** Un repas favori tel que la liste le montre, avec son total déjà calculé. */
data class FavouriteMealUi(val favorite: FavoriteMeal, val total: Carbs) {
    val id: String get() = favorite.id
    val name: String get() = favorite.name
    val resumable: Boolean get() = favorite.lines.isNotEmpty()
}

/**
 * Recherche manuelle dans la base Open Food Facts (point 2 de la demande du 14/11/2026).
 *
 * Deux principes :
 * 1. la recherche est **locale d'abord** (garde-manger, cache, base embarquée) : elle fonctionne
 *    sans réseau, ce qui est le cas au moment d'un repas ;
 * 2. chaque ligne a **son** calculateur, choisi selon le type de produit. Un paquet de biscuits
 *    se chiffre à la pièce, une boisson au volume, un plat au poids.
 */
@HiltViewModel
class ManualSearchViewModel @Inject constructor(
    private val repository: FoodRepository,
    private val favorites: FavoriteMealRepository,
    private val journal: JournalRepository,
    private val camaps: CamapsMealGateway,
    private val glookoCarnet: GlookoCarnet,
    private val mealRecord: MealRecordSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            mealRecord.config.collect { config -> _state.update { it.copy(liberty = config.liberty) } }
        }
        viewModelScope.launch {
            favorites.observeFavorites().collect { list ->
                _state.update { current ->
                    current.copy(
                        favourites = list.map { FavouriteMealUi(it, it.total) }.take(MAX_FAVOURITES),
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeFavourites().collect { products ->
                _state.update { it.copy(productFavourites = products.take(MAX_FAVOURITES)) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun onTypeChange(type: CamapsMealType) {
        _state.update { it.copy(camapsType = type) }
    }

    /** Recherche locale : instantanée, hors ligne, et déjà triée par l'application. */
    fun search() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) {
            _state.update { it.copy(results = it.productFavourites, hasSearched = false, searchFailed = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchFailed = false, notice = null) }
            val local = repository.searchLocal(query, SEARCH_LIMIT)
            _state.update { it.copy(results = local, searching = false, hasSearched = true) }
        }
    }

    /** Recherche en ligne, en complément : c'est elle qui enrichit la base locale. */
    fun searchOnline() {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(searching = true, searchFailed = false, notice = null) }
            val local = repository.searchLocal(query, SEARCH_LIMIT)
            val remote = try {
                repository.searchRemote(query, SEARCH_LIMIT)
            } catch (error: OpenFoodFactsException) {
                _state.update {
                    it.copy(results = local, searching = false, searchFailed = true, hasSearched = true)
                }
                return@launch
            }
            val merged = (local + remote).distinctBy { it.id }.take(SEARCH_LIMIT)
            _state.update {
                it.copy(results = merged, searching = false, hasSearched = true)
            }
        }
    }

    /** Ajoute un produit avec le calculateur que son type de produit appelle. */
    fun addProduct(product: FoodProduct) {
        val mode = SearchCalculator.defaultMode(product) ?: CalcMode.PER_100G
        _state.update { current ->
            val (id, withId) = current.allocateLineId()
            val quantity = SearchCalculator.defaultQuantity(product, mode)
            withId.copy(
                lines = withId.lines + SearchLine(id, product, mode, formatSearchQuantity(quantity)),
                notice = null,
            )
        }
    }

    /** Change de calculateur : la quantité repart sur la valeur naturelle de ce mode. */
    fun onModeChange(lineId: Long, mode: CalcMode) {
        _state.update { current ->
            current.copy(
                lines = current.lines.map { line ->
                    if (line.id != lineId) {
                        line
                    } else {
                        line.copy(
                            mode = mode,
                            quantityText = formatSearchQuantity(
                                SearchCalculator.defaultQuantity(line.product, mode),
                            ),
                        )
                    }
                },
            )
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

    fun removeLine(lineId: Long) {
        _state.update { current -> current.copy(lines = current.lines.filterNot { it.id == lineId }) }
    }

    fun clearLines() {
        _state.update { it.copy(lines = emptyList(), notice = null) }
    }

    /** Retire un message affiché (bandeau d'envoi ou d'enregistrement). */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * Lance l'automatisation : les glucides du panier partent dans CamAPS.
     *
     * Le parcours est copié du contrat `docs/CAMAPS-REPAS.md` — CamAPS d'abord, puis le Carnet
     * Glooko **seulement** après une confirmation observée — et chaque issue est écrite au
     * journal, y compris l'arrêt à CamAPS : c'est ce que le journal doit distinguer.
     */
    fun sendToCamaps() {
        val current = _state.value
        if (!current.canSend) return
        val carbs = current.camapsGrams
        val description = describe(current.lines)
        if (mealRecord.config.value.liberty) {
            recordLiberty(carbs, description)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(sending = true, notice = null) }
            val result = camaps.announceMeal(
                CamapsMealRequest(carbs = carbs, type = current.camapsType, confirm = true),
            )
            if (result !is CamapsWriteResult.Entered) {
                val message = (result as? CamapsWriteResult.Failed)?.message
                    ?: "CamAPS n'a pas pu être atteint."
                journal.record(
                    carbs = carbs,
                    description = description,
                    source = AutomationSource.SEARCH,
                    outcome = AutomationOutcome.FAILED,
                    detail = message,
                )
                _state.update { it.copy(sending = false, notice = message, noticeIsError = true) }
                return@launch
            }
            _state.update {
                it.copy(
                    sending = false,
                    notice = "$carbs g saisis dans CamAPS. Appuyez sur Confirmer dans CamAPS.",
                )
            }
            when (camaps.awaitMealOutcome(CONFIRM_WAIT_MS)) {
                CamapsMealOutcome.CONFIRMED -> {
                    val tracked = writeSuiviTargets(description, carbs)
                    journal.record(
                        carbs = carbs,
                        description = description,
                        source = AutomationSource.SEARCH,
                        outcome = if (tracked) AutomationOutcome.COMPLETE else AutomationOutcome.CAMAPS_ONLY,
                        detail = if (tracked) {
                            "Confirmé dans CamAPS, repas noté dans le Carnet Glooko."
                        } else {
                            "Confirmé dans CamAPS, rien de noté ensuite (suivi coupé ou carnet en échec)."
                        },
                    )
                    _state.update {
                        it.copy(
                            notice = if (tracked) {
                                "$carbs g dans CamAPS, repas noté dans le Carnet Glooko."
                            } else {
                                "$carbs g dans CamAPS. Rien n'a été noté ensuite (suivi diabétologue coupé)."
                            },
                            lines = if (tracked) emptyList() else it.lines,
                        )
                    }
                }

                CamapsMealOutcome.CANCELLED -> {
                    journal.record(
                        carbs = carbs,
                        description = description,
                        source = AutomationSource.SEARCH,
                        outcome = AutomationOutcome.CANCELLED,
                        detail = "Annulé dans CamAPS.",
                    )
                    _state.update { it.copy(notice = "Annulé dans CamAPS : rien n'est noté.") }
                }

                else -> {
                    journal.record(
                        carbs = carbs,
                        description = description,
                        source = AutomationSource.SEARCH,
                        outcome = AutomationOutcome.CAMAPS_ONLY,
                        detail = "Confirmation dans CamAPS non observée.",
                    )
                    _state.update {
                        it.copy(notice = "Confirmation CamAPS non vue : rien n'est noté ensuite.")
                    }
                }
            }
        }
    }

    /**
     * Mode Liberty (équipe médicale, 24/11/2026) : rien vers CamAPS, le panier est seulement noté
     * (journal, puis Carnet Glooko si le suivi diabétologue l'autorise).
     */
    private fun recordLiberty(carbs: Int, description: String) {
        viewModelScope.launch {
            _state.update { it.copy(sending = true, notice = null) }
            val tracked = writeSuiviTargets(description, carbs)
            journal.record(
                carbs = carbs,
                description = description,
                source = AutomationSource.SEARCH,
                outcome = AutomationOutcome.LIBERTY,
                detail = if (tracked) {
                    "Mode Liberty : rien dans CamAPS, repas noté dans le Carnet Glooko."
                } else {
                    "Mode Liberty : rien dans CamAPS ; Carnet Glooko non écrit (suivi coupé ou échec)."
                },
            )
            _state.update {
                it.copy(
                    sending = false,
                    notice = if (tracked) {
                        "Mode Liberty : $carbs g notés dans le journal et le Carnet Glooko. Rien dans CamAPS."
                    } else {
                        "Mode Liberty : $carbs g notés dans le journal. Rien dans CamAPS."
                    },
                    lines = if (tracked) emptyList() else it.lines,
                )
            }
        }
    }

    /** Écrit le repas dans le Carnet Glooko si le suivi l'autorise ; dit si c'est fait. */
    private suspend fun writeSuiviTargets(description: String, carbs: Int): Boolean {
        val config = mealRecord.config.value
        if (!config.writesGlookoCarnet() || description.isBlank()) return false
        val moment = GlookoCarnetMoment.forHour(LocalTime.now().hour)
        return when (val result = glookoCarnet.addEntry(description, carbs, moment)) {
            is GlookoCarnetResult.Saved -> true
            is GlookoCarnetResult.Failed -> {
                _state.update { it.copy(notice = "Carnet Glooko : ${result.message}", noticeIsError = true) }
                false
            }
        }
    }

    fun onFavouriteNameChange(name: String) {
        _state.update { it.copy(favouriteName = name) }
    }

    /** Enregistre le panier courant comme repas favori, pour l'annoncer en un geste. */
    fun saveAsFavourite() {
        val current = _state.value
        if (current.lines.isEmpty()) return
        val name = current.favouriteName.trim().ifBlank { defaultFavouriteName(current.lines) }
        val lines = current.lines.map { line ->
            FavoriteMealLine(
                name = line.product.name,
                brand = line.product.brand,
                barcode = line.product.barcode,
                quantity = line.quantity,
                unit = SearchCalculator.unitOf(line.mode),
                carbsPer100g = line.product.carbsPer100g,
                carbsPer100ml = line.product.carbsPer100ml,
                carbsPerUnit = line.product.carbsPerUnit,
                portionGrams = line.product.defaultPortionGrams,
                portionMl = line.product.defaultPortionMl,
                servingLabel = line.product.servingLabel,
                packageGrams = line.product.packageGrams,
            )
        }
        viewModelScope.launch {
            _state.update { it.copy(savingFavourite = true) }
            favorites.create(name, lines)
            _state.update {
                it.copy(savingFavourite = false, favouriteName = "", notice = "« $name » enregistré dans les favoris.")
            }
        }
    }

    /** Recharge un favori dans le panier. */
    fun loadFavourite(id: String) {
        val favorite = _state.value.favourites.firstOrNull { it.id == id }?.favorite ?: return
        _state.update { current ->
            var next = current.copy(lines = emptyList(), notice = null)
            favorite.lines.forEach { line ->
                val (lineId, withId) = next.allocateLineId()
                val product = line.toProduct()
                val mode = SearchCalculator.defaultMode(product) ?: CalcMode.PER_100G
                next = withId.copy(
                    lines = withId.lines + SearchLine(
                        lineId,
                        product,
                        mode,
                        formatSearchQuantity(line.quantity),
                    ),
                )
            }
            next
        }
        viewModelScope.launch { favorites.markUsed(id) }
    }

    fun deleteFavourite(id: String) {
        viewModelScope.launch { favorites.delete(id) }
    }

    /** Adresses déjà trouvées (ou absentes) par code-barres : une seule requête par produit. */
    private val imageUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * L'adresse de la photo de face d'un produit, pour le reconnaître d'un coup d'œil (demande du
     * 24/11/2026). Les fiches en ligne la portent déjà ; celles de la base du téléphone ne l'ont
     * pas : on la demande à Open Food Facts par code-barres, une fois. `null` sans photo ou hors ligne.
     */
    suspend fun imageUrlFor(product: FoodProduct): String? {
        product.imageUrl?.let { return it }
        val barcode = product.barcode?.takeIf { it.isNotBlank() } ?: return null
        imageUrls[barcode]?.let { return it.ifEmpty { null } }
        val url = repository.productImage(barcode)
        // Un échec réseau n'est pas mémorisé comme « sans photo » : seul un vrai résultat l'est.
        if (url != null) imageUrls[barcode] = url
        return url
    }

    companion object {
        private const val SEARCH_LIMIT = 25
        private const val MAX_FAVOURITES = 12
        private const val CONFIRM_WAIT_MS = 90_000L

        /** La description envoyée au Carnet : « pain au chocolat : 41,4 g». */
        internal fun describe(lines: List<SearchLine>): String = lines
            .mapNotNull { line ->
                val carbs = (line.carbResult() as? CarbResult.Computed)?.carbs ?: return@mapNotNull null
                val name = line.product.displayName()
                "$name : ${carbs.format()} g"
            }
            .joinToString(" + ")

        internal fun defaultFavouriteName(lines: List<SearchLine>): String =
            lines.take(2).joinToString(" + ") { it.product.name }.take(80)
    }
}

/** Lit une quantité tapée : virgule française acceptée, espaces de milliers ignorés. */
internal fun parseSearchQuantity(text: String): Double {
    val cleaned = text.trim().replace(" ", "").replace('\u00A0', ' ').replace(',', '.')
    return cleaned.toDoubleOrNull() ?: 0.0
}

/** Affiche une quantité sans décimale inutile : « 200 », « 16,5 ». */
internal fun formatSearchQuantity(value: Double): String {
    val rounded = Carbs.of(value).rounded(2)
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}

/** L'unité d'affichage du calculateur, pour l'étiquette des puces. */
internal fun CalcMode.unitLabel(): String = when (this) {
    CalcMode.PER_100G -> "g"
    CalcMode.PER_100ML -> "ml"
    CalcMode.PER_UNIT -> "pièce"
    CalcMode.PER_PORTION -> "portion"
    CalcMode.PACKAGE -> "paquet"
}

/** L'unité du modèle, utile pour les favoris. */
internal fun CalcMode.mealUnit(): MealUnit = SearchCalculator.unitOf(this)
