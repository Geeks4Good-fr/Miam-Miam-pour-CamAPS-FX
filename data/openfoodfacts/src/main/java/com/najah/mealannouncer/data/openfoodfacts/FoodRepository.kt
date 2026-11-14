package com.najah.mealannouncer.data.openfoodfacts

import com.najah.mealannouncer.core.model.FoodProduct
import kotlinx.coroutines.flow.Flow

/**
 * Source des produits alimentaires : le cache local d'abord, Open Food Facts ensuite.
 *
 * Le cache n'est pas une optimisation, c'est une exigence : au moment d'annoncer un repas,
 * on n'est pas toujours en ligne, et attendre un serveur pour additionner deux biscuits
 * serait absurde.
 */
interface FoodRepository {

    /** Favoris de l'utilisateur, réactifs : l'écran de saisie les suit. */
    fun observeFavourites(): Flow<List<FoodProduct>>

    suspend fun favourites(): List<FoodProduct>

    /** Recherche dans le cache local uniquement. Ne lève jamais d'exception réseau. */
    suspend fun searchLocal(query: String, limit: Int = 20): List<FoodProduct>

    /**
     * Recherche en ligne puis mise en cache. Peut lever [OpenFoodFactsException] :
     * à l'appelant de décider quoi en dire à l'utilisateur.
     */
    suspend fun searchRemote(query: String, limit: Int = 15): List<FoodProduct>

    /** Cache local, puis réseau si le produit est absent. */
    suspend fun findByBarcode(barcode: String): FoodProduct?

    /**
     * La photo de face du produit de ce code-barres, sur Open Food Facts ; `null` si elle
     * n'existe pas ou si le service est injoignable. Jamais d'erreur : une photo manquante
     * ne doit rien bloquer.
     */
    suspend fun productImage(barcode: String): String? = null

    /**
     * La fiche **en ligne** de ce code-barres (portion comprise), mise en cache ; `null` si elle
     * est introuvable ou si le service est injoignable. Sert quand la base locale connaît le
     * produit mais pas le poids d'une pièce : 0,1 s au lieu d'une recherche web de 12 s.
     */
    suspend fun refreshOnline(barcode: String): FoodProduct? = null

    /**
     * Enregistre un produit et le marque comme vérifié par l'utilisateur : c'est le geste
     * qui distingue une valeur contrôlée d'une valeur trouvée sur Internet.
     */
    suspend fun saveVerified(product: FoodProduct): FoodProduct

    /** Marque un produit existant comme vérifié (favori) sans changer ses valeurs. */
    suspend fun markVerified(id: String): FoodProduct?

    /** Supprime tout le cache Open Food Facts, en conservant les favoris et les saisies. */
    suspend fun clearCache(): Int
}
