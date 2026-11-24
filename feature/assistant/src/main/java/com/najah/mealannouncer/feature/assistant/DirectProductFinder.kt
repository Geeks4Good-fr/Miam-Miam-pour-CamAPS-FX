package com.najah.mealannouncer.feature.assistant

import android.util.Log
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.data.mistral.DirectProduct
import com.najah.mealannouncer.data.mistral.jpegDataUri
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

/** Une fiche Open Food Facts proposée pour un produit vu par Gemini, avec sa photo si elle a pu être lue. */
data class OffCandidate(
    /** Le produit tel que Gemini l'a nommé. */
    val asked: DirectProduct,
    val product: FoodProduct,
    /** Photo de la fiche (data URI JPEG), jointe au message pour la comparaison visuelle. */
    val imageDataUri: String?,
)

/**
 * Mode Gemini direct : retrouve dans Open Food Facts les produits industriels que Gemini a vus ou
 * entendus nommer (demande du porteur du projet du 23/11/2026), pour que Gemini compare visuellement la fiche à
 * la photo de l'utilisateur et calcule avec les vraies valeurs de l'étiquette.
 *
 * Ordre : code-barres lu (base du téléphone, puis en ligne), sinon nom + marque dans la base du
 * téléphone, puis en ligne. Aucune erreur ne remonte : un produit introuvable laisse Gemini à son
 * estimation.
 */
class DirectProductFinder @Inject constructor(
    private val foods: FoodRepository,
) {

    suspend fun find(products: List<DirectProduct>): List<OffCandidate> = withContext(Dispatchers.IO) {
        products.flatMap { asked ->
            val found = runCatching { candidates(asked) }.getOrElse { error ->
                Log.w(TAG, "Recherche Open Food Facts impossible pour ${asked.name}", error)
                emptyList()
            }
            found.take(MAX_PER_PRODUCT).map { product ->
                OffCandidate(asked, product, runCatching { image(product) }.getOrNull())
            }
        }
    }

    /**
     * Essai du 24/11/2026 : pour « biscuits Prince classiques » (LU), la base locale (garde-manger
     * compris) rendait des frites, des carottes râpées et un taboulé Carrefour — un seul mot commun,
     * « classiques ». Chaque fiche doit maintenant partager assez de mots **parlants** avec le produit
     * nommé ([rank]) ; sinon on cherche en ligne, puis sans la marque. Mieux vaut aucune fiche
     * qu'une fausse : Gemini garde alors son estimation.
     */
    private suspend fun candidates(asked: DirectProduct): List<FoodProduct> {
        asked.barcode?.let { code -> foods.findByBarcode(code)?.let { return listOf(it) } }
        val withBrand = listOfNotNull(asked.brand, asked.name).joinToString(" ")
        val local = rank(asked, foods.searchLocal(withBrand, SEARCH_LIMIT))
        if (local.isNotEmpty()) return local
        val remote = rank(asked, runCatching { foods.searchRemote(withBrand, SEARCH_LIMIT) }.getOrDefault(emptyList()))
        if (remote.isNotEmpty() || asked.brand == null) return remote
        return rank(asked, foods.searchLocal(asked.name, SEARCH_LIMIT))
    }

    private suspend fun image(product: FoodProduct): String? {
        val url = product.imageUrl ?: product.barcode?.let { foods.productImage(it) } ?: return null
        if (!url.startsWith("https://")) return null
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = IMAGE_TIMEOUT_MS
            readTimeout = IMAGE_TIMEOUT_MS
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_IMAGE_BYTES) return null
                }
                out.toByteArray()
            }
            jpegDataUri(bytes)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "MealAnnouncerDirect"
        const val MAX_PER_PRODUCT = 3
        private const val SEARCH_LIMIT = 8
        private const val IMAGE_TIMEOUT_MS = 8_000
        private const val MAX_IMAGE_BYTES = 1_500_000

        private fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

        /** Mots qui ne désignent pas un produit : à eux seuls, ils ne rapprochent rien. */
        private val WEAK_WORDS = setOf(
            "de", "du", "des", "la", "le", "les", "l", "d", "au", "aux", "a", "et", "en", "un", "une",
            "avec", "sans", "pour", "sur", "par", "classique", "classiques", "original", "originale",
            "originaux", "nature", "bio", "gout", "saveur", "recette", "format", "paquet", "maxi", "mini",
        )

        private fun words(text: String?): Set<String> =
            normalize(text.orEmpty()).split(Regex("[^a-z0-9]+")).filter { it.length >= 2 && it !in WEAK_WORDS }.toSet()

        /** « biscuit » et « biscuits », « chocolat » et « chocolate » : même racine. */
        private fun sameWord(a: String, b: String): Boolean =
            a == b || (minOf(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a)))

        /**
         * Garde les fiches qui partagent assez de mots parlants (nom ou marque) avec le produit
         * nommé par Gemini — deux au moins, ou tous s'il n'y en a qu'un — et met devant celles de la
         * marque dite, puis les mieux assorties. Les fiches sans glucides sont écartées.
         */
        internal fun rank(asked: DirectProduct, pool: List<FoodProduct>): List<FoodProduct> {
            val wanted = words(asked.name) + words(asked.brand)
            if (wanted.isEmpty()) return emptyList()
            val needed = minOf(2, wanted.size)
            val brandWords = words(asked.brand)
            return pool
                .filter { it.hasCarbs() }
                .map { product ->
                    val have = words(product.name) + words(product.brand)
                    val score = wanted.count { w -> have.any { sameWord(it, w) } }
                    val brandHit = brandWords.isNotEmpty() && brandWords.any { w -> have.any { sameWord(it, w) } }
                    Triple(product, score, brandHit)
                }
                .filter { (_, score, _) -> score >= needed }
                .sortedWith(compareByDescending<Triple<FoodProduct, Int, Boolean>> { it.third }.thenByDescending { it.second })
                .map { it.first }
        }

        private fun FoodProduct.hasCarbs() = carbsPer100g != null || carbsPer100ml != null || carbsPerUnit != null

        private fun grams(value: Double): String =
            if (value == Math.floor(value)) value.toLong().toString() else String.format(Locale.FRANCE, "%.1f", value)

        /**
         * Le message que l'appli ajoute à la conversation : les fiches trouvées, dans l'ordre des
         * photos jointes, et ce que Gemini doit en faire (comparer, choisir, calculer, demander ce
         * qui manque). Il est marqué comme venant de l'appli, pas de l'utilisateur.
         */
        fun message(candidates: List<OffCandidate>): String = buildString {
            appendLine("[Message de l'application Miam-Miam, pas de l'utilisateur]")
            appendLine("Voici les fiches Open Food Facts trouvées pour les produits industriels de ce repas.")
            val withImage = candidates.filter { it.imageDataUri != null }
            if (withImage.isNotEmpty()) {
                appendLine("Les images jointes sont, dans l'ordre, les photos des fiches marquées « (photo n) ».")
            }
            var photo = 0
            candidates.groupBy { it.asked }.forEach { (asked, list) ->
                appendLine()
                appendLine("Produit vu ou nommé : « ${asked.name} »" + (asked.brand?.let { " ($it)" } ?: ""))
                list.forEachIndexed { index, candidate ->
                    val p = candidate.product
                    val tag = if (candidate.imageDataUri != null) " (photo ${++photo})" else " (pas de photo)"
                    append("- Fiche ${index + 1}$tag : ${p.name}")
                    p.brand?.let { append(" — marque $it") }
                    p.barcode?.let { append(" — code-barres $it") }
                    appendLine()
                    val values = buildList {
                        p.carbsPer100g?.let { add("glucides ${grams(it)} g pour 100 g") }
                        p.carbsPer100ml?.let { add("glucides ${grams(it)} g pour 100 ml") }
                        p.carbsPerUnit?.let { add("glucides ${grams(it)} g par portion indiquée") }
                        p.servingLabel?.let { add("portion indiquée : $it") }
                        p.defaultPortionGrams?.let { add("portion ${grams(it)} g") }
                        p.defaultPortionMl?.let { add("portion ${grams(it)} ml") }
                        p.packageGrams?.let { add("contenu du paquet ${grams(it)} g") }
                    }
                    appendLine("  " + values.joinToString(" ; "))
                }
            }
            appendLine()
            appendLine(
                "Compare visuellement chaque photo de fiche avec la photo de l'utilisateur (s'il y en a une) " +
                    "et dis quelle fiche correspond au produit, en donnant son code-barres, ou qu'aucune ne correspond. " +
                    "Avec la fiche retenue, refais le calcul des glucides de ce produit à partir de ses valeurs. " +
                    "Si la quantité réellement consommée n'est pas connue, pose la question à l'utilisateur " +
                    "(une portion ? quel pourcentage du paquet ? combien de grammes ? combien de centilitres ?). " +
                    "Puis redonne le total du repas.",
            )
        }.trim()
    }
}
