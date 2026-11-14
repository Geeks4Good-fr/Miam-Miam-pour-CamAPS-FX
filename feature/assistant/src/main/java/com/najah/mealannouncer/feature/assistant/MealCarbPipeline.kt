package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.DetectedFood
import com.najah.mealannouncer.data.mistral.GeneralCarbEstimate
import com.najah.mealannouncer.data.mistral.JsonAsk
import com.najah.mealannouncer.data.mistral.MealExtraction
import com.najah.mealannouncer.data.mistral.MistralChat
import com.najah.mealannouncer.data.mistral.MistralException
import com.najah.mealannouncer.data.mistral.WebCarbEstimate
import com.najah.mealannouncer.data.mistral.WebCarbResearcher
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import com.najah.mealannouncer.data.openfoodfacts.isOffImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.najah.mealannouncer.feature.assistant.portions.PortionFood
import com.najah.mealannouncer.feature.assistant.portions.PortionLookup
import com.najah.mealannouncer.feature.assistant.portions.PortionOption
import javax.inject.Inject

/** Où en est la recherche, pour que l'écran puisse le dire sans mentir. */
data class PipelineStep(
    val index: Int,
    val total: Int,
    val name: String,
)

/** Le repas lu et chiffré, avec tout ce qu'il faut pour l'expliquer. */
data class PipelineResult(
    val transcript: String,
    val lines: List<AssistantLine>,
    /** Aliments de la phrase qu'on n'a pas pu nommer : ils ne sont pas comptés. */
    val rejected: List<String> = emptyList(),
    /** Ce qui s'est mal passé en chemin, affiché tel quel. */
    val warnings: List<String> = emptyList(),
)

/** Des lignes chiffrées, et les remarques faites en chemin. */
data class ResolvedLines(
    val lines: List<AssistantLine>,
    val warnings: List<String> = emptyList(),
)

/**
 * La chaîne complète : une phrase dictée devient des lignes chiffrées.
 *
 * L'ordre est celui que l'utilisateur a demandé : on lit la phrase, on cherche chaque aliment dans
 * Open Food Facts, puis — et seulement si l'aliment n'y est pas — sur Internet, puis on
 * demande les portions qui manquent (ce que fait l'écran, pas cette classe), puis on
 * calcule.
 *
 * Le modèle ne calcule rien : il nomme les aliments et lit les quantités dites. Les
 * glucides viennent d'une base de données ou d'une page web, et le calcul est celui de
 * [com.najah.mealannouncer.core.model.CarbCalculator], déjà éprouvé.
 */
class MealCarbPipeline @Inject constructor(
    private val chat: MistralChat,
    private val webResearcher: WebCarbResearcher,
    private val foodRepository: FoodRepository,
    /** Banque de portions en images : passe avant Open Food Facts (aliments sans marque). */
    private val portions: PortionLookup = PortionLookup.NONE,
) {

    /** Les portions en images d'un produit de la banque (vide pour tout autre produit). */
    fun portionOptions(productId: String): List<PortionOption> = portions.options(productId)

    /** L'aliment de la banque derrière un produit, ou `null`. */
    fun bankFood(productId: String): PortionFood? = portions.food(productId)

    /** L'identifiant d'aliment de la banque reconnu dans [name], ou `null`. */
    fun bankFoodId(name: String): String? = portions.match(name)?.food?.id


    suspend fun analyse(
        apiKey: String,
        transcript: String,
        onStep: (PipelineStep) -> Unit = {},
    ): PipelineResult = withContext(Dispatchers.IO) {
        val phrase = transcript.trim()
        if (phrase.isEmpty()) {
            return@withContext PipelineResult(transcript = "", lines = emptyList())
        }

        val answer = chat.askJson(
            apiKey = apiKey,
            ask = JsonAsk(
                system = MealExtraction.SYSTEM_PROMPT,
                user = MealExtraction.userPrompt(phrase),
            ),
        )
        val extraction = MealExtraction.parse(answer)
        val warnings = mutableListOf<String>()
        if (extraction.foods.isEmpty()) {
            warnings += "Aucun aliment reconnu dans cette phrase."
        }
        val resolved = resolveLines(apiKey, extraction.foods, onStep)
        PipelineResult(
            transcript = phrase,
            lines = resolved.lines,
            rejected = extraction.rejected,
            warnings = warnings + resolved.warnings,
        )
    }

    /**
     * Chiffre une liste d'aliments **déjà nommés** : la photo d'assiette suit exactement le
     * même chemin que la phrase dictée.
     *
     * Partager cette étape est ce qui garantit qu'un aliment venu d'une photo est cherché,
     * nommé et chiffré comme les autres — et qu'il ne peut pas exister deux façons d'obtenir
     * un nombre dans l'application.
     */
    suspend fun resolveLines(
        apiKey: String,
        foods: List<DetectedFood>,
        onStep: (PipelineStep) -> Unit = {},
    ): ResolvedLines = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val lines = foods.mapIndexed { index, food ->
            onStep(PipelineStep(index = index + 1, total = foods.size, name = food.name))
            val byBarcode = food.barcode?.let { lookupByBarcode(it) }
            val (product, estimate) = if (byBarcode != null) {
                byBarcode to null
            } else {
                lookup(apiKey, food.name, warnings)
            }
            val options = product?.let { portionOptions(it.id) }.orEmpty()
            AssistantLine(
                id = index.toLong() + 1L,
                name = product?.displayName() ?: estimate?.name ?: food.name,
                rawText = food.rawText,
                quantityText = food.quantity?.let { formatQuantityText(it) }.orEmpty(),
                unit = if (food.unit == MealUnit.UNKNOWN) MealUnit.GRAM else food.unit,
                product = product,
                estimate = estimate,
                image = imageFor(product, estimate),
                portionOptions = options,
            )
        }
        ResolvedLines(lines = lines, warnings = warnings)
    }

    /**
     * Retrouve un produit par son code-barres (cache Room, puis base locale Open Food Facts, puis
     * réseau) et le rend s'il porte une valeur de glucides exploitable.
     */
    suspend fun lookupByBarcode(barcode: String): FoodProduct? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty()) return@withContext null
        try {
            foodRepository.findByBarcode(code)?.takeIf { it.hasCarbData() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Cherche un produit photographié dans Open Food Facts (base locale d'abord) : par son
     * code-barres s'il a été lu, sinon par le nom du produit imprimé sur l'emballage.
     */
    suspend fun lookupPackageInOff(
        barcode: String?,
        productName: String?,
        visibleFood: String?,
        exclude: Set<String> = emptySet(),
    ): FoodProduct? = withContext(Dispatchers.IO) {
        barcode?.let { code ->
            lookupByBarcode(code)?.takeIf { productIdentity(it, null) !in exclude }?.let { return@withContext it }
        }
        val warnings = mutableListOf<String>()
        for (candidate in listOfNotNull(productName, visibleFood).map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            findProduct(candidate, warnings, exclude = exclude, remote = true)?.let { return@withContext it }
        }
        null
    }

    /**
     * La valeur d'un aliment nommé : un produit d'Open Food Facts, sinon une valeur du web ou une
     * estimation signalée. C'est l'unique source de glucides — la conversation passe par ici,
     * comme la phrase dictée et la photo.
     */
    suspend fun lookup(
        apiKey: String,
        name: String,
        warnings: MutableList<String>,
        /** Produits refusés par l'utilisateur pour ce repas ([productIdentity]) : jamais repris. */
        exclude: Set<String> = emptySet(),
        /**
         * Faux pour un aliment de base (thé, sucre, pâtes cuites, fruit) : seule la base locale est
         * consultée, puis directement la valeur de référence du modèle (1 s), jamais Internet (6 à
         * 11 s par recherche). La relecture contrôle cette valeur comme les autres.
         */
        branded: Boolean = true,
        /**
         * Faux dans la conversation (10/11/2026) : un produit de marque absent d'Open Food Facts
         * prend l'estimation du modèle (≈ 2 s) au lieu d'Internet (12 à 38 s mesurés), et la ligne
         * propose de photographier le tableau nutritionnel.
         */
        webForBranded: Boolean = true,
    ): Pair<FoodProduct?, WebCarbEstimate?> = withContext(Dispatchers.IO) {
        // La banque de portions d'abord (embarquée, hors ligne, pesée) — sauf pour un produit de
        // marque, que seule sa fiche décrit bien.
        if (!branded) {
            portions.match(name)?.toProduct()
                ?.takeIf { productIdentity(it, null) !in exclude }
                ?.let { return@withContext it to null }
        }
        val product = findProduct(name, warnings, exclude, remote = branded)
        val estimate = if (product == null) {
            findEstimate(apiKey, name, warnings, exclude, web = branded && webForBranded)
        } else {
            null
        }
        product to estimate
    }

    /**
     * La photo qui désigne **sûrement** le produit chiffré, ou `null`.
     *
     * Dans l'ordre : la photo de la fiche Open Food Facts ; à défaut, celle de son code-barres ;
     * pour une valeur du web, l'image de la page même ; sinon celle du code-barres lu sur la page.
     * Une estimation moyenne n'a jamais de photo : elle ne désigne aucun produit précis.
     */
    suspend fun imageFor(product: FoodProduct?, estimate: WebCarbEstimate?): ProductImage? =
        withContext(Dispatchers.IO) {
            if (product != null) {
                product.imageUrl?.let { return@withContext ProductImage(it, ORIGIN_OFF) }
                val code = product.barcode ?: return@withContext null
                return@withContext foodRepository.productImage(code)?.let { ProductImage(it, ORIGIN_OFF) }
            }
            if (estimate == null || estimate.general) return@withContext null
            estimate.imageUrl?.let { url ->
                // Une page Open Food Facts trouvée par le web reste une photo Open Food Facts :
                // c'est ce que l'utilisateur doit lire sous la vignette.
                val fromOff = isOffImage(url) ||
                    estimate.sourceUrl?.contains("openfoodfacts.org") == true
                return@withContext ProductImage(url, if (fromOff) ORIGIN_OFF else ORIGIN_WEB)
            }
            val code = estimate.barcode ?: return@withContext null
            foodRepository.productImage(code)?.let { ProductImage(it, ORIGIN_OFF_BARCODE) }
        }

    /** La fiche en ligne du produit (par son code-barres), ou `null` : voir [FoodRepository.refreshOnline]. */
    suspend fun onlineSheet(product: FoodProduct): FoodProduct? = withContext(Dispatchers.IO) {
        val code = product.barcode ?: return@withContext null
        foodRepository.refreshOnline(code)?.takeIf { it.hasCarbData() }
    }

    /**
     * La fiche « par pièce » d'un produit, cherchée sur Internet : glucides d'une pièce, ou poids
     * d'une pièce (déduit du paquet au besoin). Sert quand Open Food Facts connaît le produit mais
     * pas ce que pèse une pièce. `null` si rien de fiable n'est trouvé.
     */
    suspend fun unitInfo(
        apiKey: String,
        name: String,
        warnings: MutableList<String>,
    ): WebCarbEstimate? = withContext(Dispatchers.IO) {
        val found = try {
            webResearcher.lookup(apiKey, name)
        } catch (error: MistralException) {
            null
        }
        if (found?.knowsUnit != true) {
            warnings += "Poids d'une pièce introuvable pour « $name »."
            null
        } else {
            found
        }
    }

    /**
     * Cherche un produit dans Open Food Facts : le cache d'abord, le réseau ensuite.
     *
     * On ne retient qu'un produit qui porte une donnée de glucides **et** dont le nom
     * correspond à l'aliment demandé : un produit trouvé sans valeur ne vaut pas mieux
     * qu'un produit absent, et un produit hors sujet chiffrerait l'aliment avec les
     * glucides d'un autre. Dans les deux cas, on laisse sa chance à Internet.
     */
    private suspend fun findProduct(
        name: String,
        warnings: MutableList<String>,
        exclude: Set<String> = emptySet(),
        remote: Boolean = true,
    ): FoodProduct? {
        foodRepository.searchLocal(name, limit = LOCAL_LIMIT)
            .firstOrNull {
                it.hasCarbData() && FoodNameMatcher.isPlausible(name, it.name) &&
                    productIdentity(it, null) !in exclude
            }
            ?.let { return it }
        if (!remote) return null

        val remoteFound = try {
            foodRepository.searchRemote(name, limit = REMOTE_LIMIT)
        } catch (error: Exception) {
            warnings += "Open Food Facts injoignable pour « $name » : ${error.message ?: "cause inconnue"}"
            emptyList()
        }
        val plausible = remoteFound.filter {
            it.hasCarbData() && FoodNameMatcher.isPlausible(name, it.name) &&
                productIdentity(it, null) !in exclude
        }
        if (plausible.isEmpty() && remoteFound.isNotEmpty()) {
            warnings += "Open Food Facts n'a rien proposé qui corresponde à « $name » : " +
                "valeur cherchée sur Internet."
        }
        return plausible.firstOrNull()
    }

    /**
     * Cherche une valeur pour un aliment absent de la base : d'abord sur Internet (une source),
     * puis — si rien n'est trouvé — une **estimation généraliste** de connaissance commune.
     *
     * L'estimation est le filet final : il y a toujours un nombre à annoncer, au pire une moyenne
     * clairement signalée. On n'est pas au gramme près — c'est une annonce, pas un calcul de dose —
     * et rien n'est écrit sans la confirmation de l'utilisateur.
     */
    private fun findEstimate(
        apiKey: String,
        name: String,
        warnings: MutableList<String>,
        exclude: Set<String> = emptySet(),
        web: Boolean = true,
    ): WebCarbEstimate? {
        val found = if (!web) {
            null
        } else {
            try {
                webResearcher.lookup(apiKey, name)
            } catch (error: MistralException) {
                null
            }?.takeIf { productIdentity(null, it) !in exclude }
        }
        val web = found
        if (web != null) {
            if (web.sourceUrl == null) {
                warnings += "Valeur trouvée sur Internet pour « $name », sans source vérifiable."
            }
            return web
        }
        return try {
            val answer = chat.askJson(
                apiKey = apiKey,
                ask = JsonAsk(system = GeneralCarbEstimate.SYSTEM, user = GeneralCarbEstimate.userPrompt(name)),
            )
            val estimate = GeneralCarbEstimate.parse(name, answer)
            if (productIdentity(null, estimate) in exclude) {
                warnings += "Aucune autre valeur trouvée pour « $name »."
                return null
            }
            warnings += "« $name » introuvable : estimation généraliste (valeur moyenne, à confirmer)."
            estimate
        } catch (error: MistralException) {
            warnings += "Aucune valeur ni estimation pour « $name »."
            null
        }
    }

    private companion object {
        const val LOCAL_LIMIT = 5
        const val REMOTE_LIMIT = 8

        const val ORIGIN_OFF = "Photo Open Food Facts"
        const val ORIGIN_WEB = "Photo de la page source"
        const val ORIGIN_OFF_BARCODE = "Photo Open Food Facts (code-barres de la fiche web)"
    }
}
