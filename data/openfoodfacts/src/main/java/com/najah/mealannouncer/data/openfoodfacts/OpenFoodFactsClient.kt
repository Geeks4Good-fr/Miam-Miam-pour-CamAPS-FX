package com.najah.mealannouncer.data.openfoodfacts

import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.ServingText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lecture d'Open Food Facts en HTTP direct.
 *
 * Pas de bibliothèque réseau ici, volontairement : une seule requête GET et une réponse
 * JSON suffisent, et chaque dépendance ajoutée est une dépendance à maintenir et à
 * auditer. `HttpURLConnection` est dans le SDK, ne pèse rien dans l'APK et n'ouvre aucune
 * connexion que l'on ne coupe pas explicitement.
 *
 * Aucun identifiant, aucun jeton, aucune donnée de santé n'est envoyé : uniquement le
 * code-barres ou les mots cherchés.
 */
@Singleton
class OpenFoodFactsClient @Inject constructor() {

    /** Renvoie le produit correspondant au code-barres, ou `null` s'il est inconnu. */
    suspend fun findByBarcode(barcode: String): FoodProduct? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty()) return@withContext null
        // `/api/v2/product` : `/product/…json` range les glucides dans `nutriments` et laissait
        // `carbohydrates_100g` vide — toute fiche y était jugée sans glucides (constaté le 09/11/2026).
        val url = "$BASE/api/v2/product/${URLEncoder.encode(code, "UTF-8")}.json?fields=$FIELDS"
        val body = httpGet(url)
        val root = JSONObject(body)
        // `status` vaut 1 quand la fiche existe, 0 sinon : ce n'est pas une erreur.
        if (root.optInt("status", 0) != 1) return@withContext null
        val product = root.optJSONObject("product") ?: return@withContext null
        parseProduct(product, preferredBarcode = code)
    }

    /**
     * La photo de face d'un produit, par son code-barres : c'est ce qui garantit qu'elle montre
     * **ce** produit. `null` si la fiche n'en a pas.
     */
    suspend fun frontImage(barcode: String): String? = withContext(Dispatchers.IO) {
        val code = barcode.trim()
        if (code.isEmpty() || !code.all { it.isDigit() }) return@withContext null
        val root = JSONObject(httpGet("$BASE/api/v2/product/$code.json?fields=image_front_small_url"))
        if (root.optInt("status", 0) != 1) return@withContext null
        root.optJSONObject("product")?.optString("image_front_small_url")?.takeIf { isOffImage(it) }
    }

    /**
     * Recherche par mots. Renvoie une liste vide si rien ne correspond ; lève
     * [OpenFoodFactsException] si le service est injoignable.
     *
     * 09/11/2026 : l'ancienne recherche (`/api/v2/search?search_terms=`) **ignorait les mots** —
     * « biscuits Prince » rendait « Fromage Blanc Nature », ou une erreur 503 — si bien que chaque
     * produit de marque partait sur la recherche web (9 à 26 s). Le moteur de recherche actuel
     * d'Open Food Facts (search-a-licious) trouve le bon produit en 0,1 à 0,3 s ; les fiches
     * complètes (portion comprise : « Un biscuit 20 g ») sont ensuite lues par code-barres, en
     * parallèle, 0,2 s chacune.
     */
    suspend fun search(query: String, limit: Int = 15): List<FoodProduct> = withContext(Dispatchers.IO) {
        val terms = query.trim()
        if (terms.isEmpty()) return@withContext emptyList()
        val pageSize = limit.coerceIn(1, MAX_SEARCH_PRODUCTS)
        val url = "$SEARCH_BASE/search?q=${URLEncoder.encode(terms, "UTF-8")}" +
            "&page_size=$pageSize&langs=fr&fields=code"
        val hits = JSONObject(httpGet(url)).optJSONArray("hits") ?: JSONArray()
        val codes = (0 until hits.length())
            .mapNotNull { hits.optJSONObject(it)?.optString("code")?.trim()?.takeIf { c -> c.isNotEmpty() && c.all(Char::isDigit) } }
            .distinct()
            .take(pageSize)
        coroutineScope {
            codes.map { code -> async { runCatching { findByBarcode(code) }.getOrNull() } }.awaitAll()
        }.filterNotNull()
    }

    /**
     * Transforme une fiche Open Food Facts en produit du domaine, ou `null` si elle est
     * inexploitable (sans nom, sans aucune donnée de glucides).
     */
    private fun parseProduct(json: JSONObject, preferredBarcode: String?): FoodProduct? {
        val barcode = preferredBarcode ?: json.optString("code").takeIf { it.isNotBlank() }
        val name = OffMapping.cleanName(json.optString("product_name_fr"))
            ?: OffMapping.cleanName(json.optString("product_name"))
            ?: return null

        val brand = OffMapping.cleanBrand(json.optString("brands"))

        // `nutrition_data_per` dit à quoi se rapportent les valeurs : « 100g » ou « 100ml ».
        val perVolume = json.optString("nutrition_data_per").trim().equals("100ml", ignoreCase = true)
        val carbsPer100 = OffMapping.validCarbsPer100(json.optDouble("carbohydrates_100g").takeIf { !it.isNaN() })

        val servingUnit = json.optString("serving_quantity_unit").takeIf { it.isNotBlank() }
        val servingQuantity = OffMapping.servingToBaseQuantity(
            json.optDouble("serving_quantity").takeIf { !it.isNaN() },
            servingUnit,
        )
        val servingIsVolume = servingUnit != null && OffMapping.servingIsVolume(servingUnit) || perVolume

        // Une portion en grammes donne un sens à « 1 unité » : 1 biscuit = 16,5 g = 11 g.
        val carbsPerUnit = if (!servingIsVolume) {
            OffMapping.carbsForServing(carbsPer100, servingQuantity)
        } else {
            null
        }

        val product = FoodProduct(
            id = productId(barcode, name, brand),
            barcode = barcode,
            name = name,
            brand = brand,
            carbsPer100g = if (perVolume) null else carbsPer100,
            carbsPer100ml = if (perVolume) carbsPer100 else null,
            carbsPerUnit = carbsPerUnit,
            defaultPortionGrams = if (!servingIsVolume) servingQuantity else null,
            defaultPortionMl = if (servingIsVolume) servingQuantity else null,
            servingLabel = json.optString("serving_size")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(80)
                .ifBlank { null },
            packageGrams = ServingText.gramsIn(json.optString("quantity")),
            imageUrl = json.optString("image_front_small_url").takeIf { isOffImage(it) },
            source = FoodSource.OPEN_FOOD_FACTS_ONLINE,
            verifiedByUser = false,
            updatedAt = Instant.now(),
        )
        // Une fiche sans aucun glucides ne sert à rien : l'utilisateur saisira à la main.
        return if (product.hasCarbData()) product else null
    }

    /**
     * Identité stable d'un produit : le code-barres quand il existe, sinon une empreinte
     * du nom et de la marque. Deux recherches successives du même produit doivent
     * retomber sur la même ligne en base, sinon le cache se remplit de doublons.
     */
    private fun productId(barcode: String?, name: String, brand: String?): String {
        if (!barcode.isNullOrBlank()) return "off-$barcode"
        val slug = "${name.lowercase()}-${brand?.lowercase().orEmpty()}"
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(80)
        return "off-$slug"
    }

    private fun httpGet(url: String): String {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (error: IOException) {
            throw OpenFoodFactsException("Adresse invalide : $url", error)
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            if (status !in 200..299) {
                throw OpenFoodFactsException("Open Food Facts a répondu $status")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: IOException) {
            throw OpenFoodFactsException("Open Food Facts injoignable", error)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE = "https://world.openfoodfacts.org"
        const val SEARCH_BASE = "https://search.openfoodfacts.org"

        /** Fiches lues par recherche : chacune coûte une requête (Open Food Facts : 100 par minute). */
        const val MAX_SEARCH_PRODUCTS = 6
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000

        /** Open Food Facts demande un agent identifiable ; aucune donnée personnelle ici. */
        const val USER_AGENT = "MealAnnouncer/0.1.0 (Android)"

        const val FIELDS = "code,product_name,product_name_fr,brands,carbohydrates_100g," +
            "serving_quantity,serving_quantity_unit,serving_size,nutrition_data_per,quantity," +
            "image_front_small_url"
    }
}

/**
 * Une photo n'est acceptée que si elle vient des serveurs d'images d'Open Food Facts, en
 * HTTPS : c'est la photo de la fiche de **ce** code-barres, pas une image trouvée ailleurs.
 */
fun isOffImage(url: String?): Boolean {
    val host = try {
        URL(url ?: return false).takeIf { it.protocol == "https" }?.host
    } catch (error: java.net.MalformedURLException) {
        null
    } ?: return false
    return host == "images.openfoodfacts.org" || host == "static.openfoodfacts.org"
}

