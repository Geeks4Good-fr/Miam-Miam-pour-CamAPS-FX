package com.najah.mealannouncer.data.openfoodfacts

import com.najah.mealannouncer.core.database.dao.FoodProductDao
import com.najah.mealannouncer.core.database.mapper.toDomain
import com.najah.mealannouncer.core.database.mapper.toEntity
import com.najah.mealannouncer.core.datastore.ShoppingSettings
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implémentation par défaut : Room comme source de vérité, la base complète du téléphone
 * ensuite, le réseau en dernier.
 *
 * Deux règles portent tout le reste :
 * 1. un produit vérifié par l'utilisateur n'est **jamais** écrasé par une fiche en ligne ;
 * 2. ce qui vient du réseau entre en base, sinon la recherche suivante coûterait une
 *    requête de plus et ne fonctionnerait pas hors ligne.
 */
@Singleton
class DefaultFoodRepository @Inject constructor(
    private val dao: FoodProductDao,
    private val client: OpenFoodFactsClient,
    private val offDatabase: OffDatabase,
    private val shopping: ShoppingSettings? = null,
) : FoodRepository {

    override fun observeFavourites(): Flow<List<FoodProduct>> =
        dao.observeFavorites().map { entities -> entities.map { it.toDomain() } }

    override suspend fun favourites(): List<FoodProduct> = dao.favorites().map { it.toDomain() }

    /**
     * Recherche locale : d'abord les produits achetés sur la dernière facture de courses
     * ([ShoppingSettings.searchPantry]), puis les favoris/produits déjà vus dans Room, puis la
     * base Open Food Facts du téléphone (pré-triée selon les enseignes habituelles de
     * l'utilisateur).
     */
    override suspend fun searchLocal(query: String, limit: Int): List<FoodProduct> {
        val terms = query.trim()
        // Une recherche vide n'a pas de sens : on montre les favoris, qui sont le point
        // de départ naturel d'une saisie manuelle.
        if (terms.isEmpty()) return favourites()
        val fromPantry = shopping?.searchPantry(terms, limit).orEmpty()
        val cached = dao.search(terms, limit).map { it.toDomain() }
        val preferredBrands = shopping?.config?.value?.preferredBrandKeywords.orEmpty()
        val fromBase = offDatabase.search(terms, limit, preferredBrands = preferredBrands)
        return (fromPantry + cached + fromBase).distinctBy { it.id }.take(limit)
    }

    override suspend fun searchRemote(query: String, limit: Int): List<FoodProduct> =
        client.search(query, limit).map { store(it) }

    override suspend fun findByBarcode(barcode: String): FoodProduct? {
        val code = barcode.trim()
        if (code.isEmpty()) return null
        dao.findByBarcode(code)?.let { return it.toDomain() }
        offDatabase.findByBarcode(code)?.let { return it }
        val fromNetwork = client.findByBarcode(code) ?: return null
        return store(fromNetwork)
    }

    override suspend fun refreshOnline(barcode: String): FoodProduct? = try {
        client.findByBarcode(barcode.trim())?.let { store(it) }
    } catch (error: OpenFoodFactsException) {
        null
    } catch (error: org.json.JSONException) {
        null
    }

    override suspend fun productImage(barcode: String): String? = try {
        client.frontImage(barcode)
    } catch (error: OpenFoodFactsException) {
        null
    } catch (error: org.json.JSONException) {
        null
    }

    override suspend fun saveVerified(product: FoodProduct): FoodProduct {
        val verified = product.copy(verifiedByUser = true, updatedAt = Instant.now())
        dao.upsert(verified.toEntity())
        return verified
    }

    override suspend fun markVerified(id: String): FoodProduct? {
        val existing = dao.findById(id)?.toDomain() ?: return null
        val verified = existing.copy(verifiedByUser = true, updatedAt = Instant.now())
        dao.upsert(verified.toEntity())
        return verified
    }

    override suspend fun clearCache(): Int = dao.clearCache()

    /**
     * Met en cache un produit venu du réseau, sans écraser une valeur vérifiée.
     *
     * La source passe à [FoodSource.OPEN_FOOD_FACTS_CACHE] : à la relecture, on saura que
     * ces chiffres viennent d'Internet et non d'une étiquette lue par l'utilisateur.
     */
    private suspend fun store(product: FoodProduct): FoodProduct {
        val existing = dao.findById(product.id)?.toDomain()
        if (existing != null && existing.verifiedByUser) return existing
        val cached = product.copy(
            source = FoodSource.OPEN_FOOD_FACTS_CACHE,
            verifiedByUser = false,
            updatedAt = Instant.now(),
        )
        dao.upsert(cached.toEntity())
        return cached
    }
}
