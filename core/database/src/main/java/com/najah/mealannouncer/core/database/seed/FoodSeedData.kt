package com.najah.mealannouncer.core.database.seed

import com.najah.mealannouncer.core.database.entity.FoodProductEntity
import com.najah.mealannouncer.core.model.FoodSource
import java.time.Instant

/**
 * Jeu de données minimal, utilisé pour les essais et la démonstration :
 * un produit compté à l'unité, une boisson au volume, un produit sans glucides et un
 * produit vendu au poids.
 *
 * Ces valeurs sont des ordres de grandeur réalistes, pas des données officielles : elles
 * sont **vérifiées par l'utilisateur** avant d'être annoncées.
 */
object FoodSeedData {

    private val seededAt: Long = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli()

    fun products(): List<FoodProductEntity> = listOf(
        FoodProductEntity(
            id = "seed-prince",
            barcode = "3229820129488",
            name = "Biscuit Prince chocolat",
            brand = "LU",
            carbsPer100g = 68.0,
            carbsPer100ml = null,
            carbsPerUnit = 11.0,
            defaultPortionGrams = 16.5,
            defaultPortionMl = null,
            source = FoodSource.MANUAL,
            verifiedByUser = true,
            updatedAt = seededAt,
        ),
        FoodProductEntity(
            id = "seed-candy-up",
            barcode = "3245390094686",
            name = "Candy Up chocolat",
            brand = "Candy Up",
            carbsPer100g = null,
            carbsPer100ml = 9.0,
            carbsPerUnit = null,
            defaultPortionGrams = null,
            defaultPortionMl = 200.0,
            source = FoodSource.MANUAL,
            verifiedByUser = true,
            updatedAt = seededAt,
        ),
        FoodProductEntity(
            id = "seed-eau",
            barcode = null,
            name = "Eau",
            brand = null,
            carbsPer100g = 0.0,
            carbsPer100ml = 0.0,
            carbsPerUnit = 0.0,
            defaultPortionGrams = null,
            defaultPortionMl = 250.0,
            source = FoodSource.MANUAL,
            verifiedByUser = true,
            updatedAt = seededAt,
        ),
        FoodProductEntity(
            id = "seed-pain-complet",
            barcode = null,
            name = "Pain complet",
            brand = null,
            carbsPer100g = 43.0,
            carbsPer100ml = null,
            carbsPerUnit = null,
            defaultPortionGrams = 35.0,
            defaultPortionMl = null,
            source = FoodSource.MANUAL,
            verifiedByUser = true,
            updatedAt = seededAt,
        ),
    )
}
