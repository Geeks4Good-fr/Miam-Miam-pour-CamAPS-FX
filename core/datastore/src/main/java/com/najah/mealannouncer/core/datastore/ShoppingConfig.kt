package com.najah.mealannouncer.core.datastore

import android.content.Context
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les grandes enseignes de supermarchés et leurs marques propres (MDD) telles qu'elles
 * figurent dans la colonne `marque` de la base Open Food Facts (`off-base.db`).
 */
enum class SupermarketChain(
    val id: String,
    val label: String,
    val brandKeywords: List<String>,
) {
    CARREFOUR(
        id = "carrefour",
        label = "Carrefour",
        brandKeywords = listOf("carrefour", "simpl", "reflets de france", "terre d'italie"),
    ),
    LECLERC(
        id = "leclerc",
        label = "E.Leclerc",
        brandKeywords = listOf(
            "marque repere", "marque repère", "leclerc", "eco+", "bio village",
            "nos regions ont du talent", "nos régions ont du talent", "tablier blanc",
        ),
    ),
    SYSTEME_U(
        id = "systeme_u",
        label = "Système U / Super U",
        brandKeywords = listOf("u", "u bio", "u saveurs", "bien vu", "systeme u", "système u", "super u", "hyper u"),
    ),
    INTERMARCHE(
        id = "intermarche",
        label = "Intermarché",
        brandKeywords = listOf(
            "intermarche", "intermarché", "chabrior", "paquito", "monique ranou",
            "paturages", "pâturages", "saint eloi", "saint éloi", "top budget", "itineraire des saveurs",
        ),
    ),
    AUCHAN(
        id = "auchan",
        label = "Auchan",
        brandKeywords = listOf("auchan", "pouce", "mmm!"),
    ),
    LIDL(
        id = "lidl",
        label = "Lidl",
        brandKeywords = listOf(
            "lidl", "maribel", "sondey", "milbona", "combino", "envia",
            "maitre jean pierre", "maître jean-pierre", "alesto", "crownfield", "trattoria alfredo",
        ),
    ),
    ALDI(
        id = "aldi",
        label = "Aldi",
        brandKeywords = listOf("aldi", "moser roth", "milsani", "golden bridge", "bononi", "pays gourmand"),
    ),
    CASINO_MONOPRIX(
        id = "casino_monoprix",
        label = "Casino / Monoprix / Franprix",
        brandKeywords = listOf("casino", "monoprix", "franprix", "leader price"),
    ),
    PICARD(
        id = "picard",
        label = "Picard",
        brandKeywords = listOf("picard"),
    ),
    ;

    companion object {
        fun fromId(id: String): SupermarketChain? = entries.firstOrNull { it.id == id }

        /** Reconnaît une enseigne à partir du nom d'un magasin lu sur un ticket ou une facture. */
        fun detectFromStoreName(storeName: String?): SupermarketChain? {
            if (storeName.isNullOrBlank()) return null
            val norm = normalize(storeName)
            return when {
                "carrefour" in norm -> CARREFOUR
                "leclerc" in norm || "repere" in norm -> LECLERC
                "intermarche" in norm || "mousquetaires" in norm -> INTERMARCHE
                "auchan" in norm || "chronodrive" in norm -> AUCHAN
                "lidl" in norm -> LIDL
                "aldi" in norm -> ALDI
                "picard" in norm -> PICARD
                "casino" in norm || "monoprix" in norm || "franprix" in norm || "leader price" in norm -> CASINO_MONOPRIX
                Regex("\\b(super u|hyper u|systeme u|u express|courses u|magasins u)\\b").containsMatchIn(norm) -> SYSTEME_U
                else -> null
            }
        }

        private fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}

/**
 * Réglages des courses de l'utilisateur : ses enseignes habituelles et les produits de ses
 * dernières factures / tickets de caisse (son garde-manger personnel).
 */
data class ShoppingConfig(
    val chains: Set<SupermarketChain> = emptySet(),
    val pantryProducts: List<FoodProduct> = emptyList(),
    val lastReceiptSummary: String? = null,
) {
    /** Tous les mots-clés de marques distributeurs associés aux enseignes cochées. */
    val preferredBrandKeywords: List<String>
        get() = chains.flatMap { it.brandKeywords }.distinct()
}

interface ShoppingSettings {
    val config: StateFlow<ShoppingConfig>

    fun toggleChain(chain: SupermarketChain)

    fun addChain(chain: SupermarketChain)

    /**
     * Ajoute ou met à jour des produits issus d'une facture ou d'un ticket de caisse dans le
     * garde-manger personnel (les plus récents en tête, avec conservation de la portion et du
     * poids de paquet).
     */
    fun recordPurchasedProducts(products: List<FoodProduct>, receiptSummary: String?)

    fun clearPantry()

    /** Recherche dans le garde-manger personnel les produits qui correspondent à [query]. */
    fun searchPantry(query: String, limit: Int = 10): List<FoodProduct>
}

@Singleton
class ShoppingConfigStore @Inject constructor(
    @ApplicationContext context: Context,
) : ShoppingSettings {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(read())
    override val config: StateFlow<ShoppingConfig> = _config.asStateFlow()

    override fun toggleChain(chain: SupermarketChain) {
        val current = _config.value
        val updated = if (chain in current.chains) current.chains - chain else current.chains + chain
        saveChains(updated)
    }

    override fun addChain(chain: SupermarketChain) {
        val current = _config.value
        if (chain in current.chains) return
        saveChains(current.chains + chain)
    }

    private fun saveChains(chains: Set<SupermarketChain>) {
        prefs.edit()
            .putStringSet(KEY_CHAINS, chains.map { it.id }.toSet())
            .apply()
        _config.value = _config.value.copy(chains = chains)
    }

    override fun recordPurchasedProducts(products: List<FoodProduct>, receiptSummary: String?) {
        val current = _config.value
        val merged = (products + current.pantryProducts)
            .distinctBy { it.barcode?.takeIf { c -> c.isNotBlank() } ?: it.id }
            .take(MAX_PANTRY_ITEMS)
        val summary = receiptSummary ?: current.lastReceiptSummary
        prefs.edit()
            .putString(KEY_PANTRY_JSON, encodePantry(merged))
            .putString(KEY_LAST_RECEIPT, summary)
            .apply()
        _config.value = current.copy(pantryProducts = merged, lastReceiptSummary = summary)
    }

    override fun clearPantry() {
        prefs.edit()
            .remove(KEY_PANTRY_JSON)
            .remove(KEY_LAST_RECEIPT)
            .apply()
        _config.value = _config.value.copy(pantryProducts = emptyList(), lastReceiptSummary = null)
    }

    override fun searchPantry(query: String, limit: Int): List<FoodProduct> {
        val pantry = _config.value.pantryProducts
        if (pantry.isEmpty()) return emptyList()
        val words = normalizeTokens(query)
        if (words.isEmpty()) return emptyList()
        return pantry
            .mapNotNull { product ->
                val target = normalize("${product.name} ${product.brand.orEmpty()}")
                val targetWords = target.split(' ').filter { it.isNotEmpty() }
                val matched = words.count { q ->
                    targetWords.any { t -> t == q || t.startsWith(q) || q.startsWith(t) }
                }
                if (matched > 0) product to matched else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(limit)
    }

    private fun read(): ShoppingConfig {
        val chainIds = prefs.getStringSet(KEY_CHAINS, emptySet()).orEmpty()
        val chains = chainIds.mapNotNull { SupermarketChain.fromId(it) }.toSet()
        val pantry = decodePantry(prefs.getString(KEY_PANTRY_JSON, null))
        val summary = prefs.getString(KEY_LAST_RECEIPT, null)
        return ShoppingConfig(
            chains = chains,
            pantryProducts = pantry,
            lastReceiptSummary = summary,
        )
    }

    internal companion object {
        private const val PREFS_NAME = "mealannouncer-shopping"
        private const val KEY_CHAINS = "chains"
        private const val KEY_PANTRY_JSON = "pantry_json"
        private const val KEY_LAST_RECEIPT = "last_receipt"
        private const val MAX_PANTRY_ITEMS = 300

        private val STOP_WORDS = setOf(
            "de", "du", "des", "la", "le", "les", "au", "aux", "et", "avec", "sans", "un", "une",
            "g", "gr", "gramme", "grammes", "kg", "ml", "cl", "litre", "litres",
            "paquet", "sachet", "boite", "pot", "tranche", "tranches", "piece", "pieces",
        )

        internal fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        internal fun normalizeTokens(query: String): List<String> =
            normalize(query)
                .split(' ')
                .filter { it.length >= 2 && it !in STOP_WORDS && !it.all { c -> c.isDigit() } }
                .distinct()

        internal fun encodePantry(products: List<FoodProduct>): String {
            val array = JSONArray()
            for (p in products) {
                val obj = JSONObject()
                obj.put("id", p.id)
                p.barcode?.let { obj.put("barcode", it) }
                obj.put("name", p.name)
                p.brand?.let { obj.put("brand", it) }
                p.carbsPer100g?.let { obj.put("carbsPer100g", it) }
                p.carbsPer100ml?.let { obj.put("carbsPer100ml", it) }
                p.carbsPerUnit?.let { obj.put("carbsPerUnit", it) }
                p.defaultPortionGrams?.let { obj.put("defaultPortionGrams", it) }
                p.defaultPortionMl?.let { obj.put("defaultPortionMl", it) }
                p.servingLabel?.let { obj.put("servingLabel", it) }
                p.packageGrams?.let { obj.put("packageGrams", it) }
                obj.put("updatedAt", p.updatedAt.toEpochMilli())
                array.put(obj)
            }
            return array.toString()
        }

        internal fun decodePantry(json: String?): List<FoodProduct> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                        val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
                        add(
                            FoodProduct(
                                id = id,
                                barcode = obj.optString("barcode").takeIf { it.isNotBlank() },
                                name = name,
                                brand = obj.optString("brand").takeIf { it.isNotBlank() },
                                carbsPer100g = obj.optDouble("carbsPer100g").takeIf { !it.isNaN() },
                                carbsPer100ml = obj.optDouble("carbsPer100ml").takeIf { !it.isNaN() },
                                carbsPerUnit = obj.optDouble("carbsPerUnit").takeIf { !it.isNaN() },
                                defaultPortionGrams = obj.optDouble("defaultPortionGrams").takeIf { !it.isNaN() },
                                defaultPortionMl = obj.optDouble("defaultPortionMl").takeIf { !it.isNaN() },
                                servingLabel = obj.optString("servingLabel").takeIf { it.isNotBlank() },
                                packageGrams = obj.optDouble("packageGrams").takeIf { !it.isNaN() },
                                source = FoodSource.USER_FAVORITE,
                                verifiedByUser = true,
                                updatedAt = Instant.ofEpochMilli(obj.optLong("updatedAt", 0L)),
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
