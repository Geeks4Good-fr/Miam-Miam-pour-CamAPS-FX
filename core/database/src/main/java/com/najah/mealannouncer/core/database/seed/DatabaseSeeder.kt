package com.najah.mealannouncer.core.database.seed

import com.najah.mealannouncer.core.database.MealAnnouncerDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remplit la base au premier lancement avec [FoodSeedData].
 *
 * Le but n'est pas de fournir une base nutritionnelle — elle ne serait ni complète ni
 * tenue à jour — mais de rendre l'application immédiatement utilisable et démontrable,
 * sans réseau, avec deux ou trois produits connus.
 *
 * L'opération est idempotente : si la base contient déjà des produits, elle ne fait rien.
 * L'utilisateur qui supprime un produit de démonstration ne le voit donc pas revenir au
 * lancement suivant.
 */
@Singleton
class DatabaseSeeder @Inject constructor(
    private val database: MealAnnouncerDatabase,
) {

    suspend fun seedIfEmpty(): Int {
        val dao = database.foodProductDao()
        if (dao.count() > 0) return 0
        val products = FoodSeedData.products()
        dao.upsertAll(products)
        return products.size
    }
}
