package com.najah.mealannouncer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.najah.mealannouncer.core.model.FoodSource

/**
 * Produit alimentaire connu localement : favori de l'utilisateur ou copie d'Open Food Facts.
 *
 * Le code-barres est indexé (recherche la plus fiable) et le nom aussi, pour que la
 * recherche « biscuit prince » reste rapide sur un cache de plusieurs milliers de lignes.
 */
@Entity(
    tableName = "food_products",
    indices = [
        Index(value = ["barcode"]),
        Index(value = ["name"]),
        Index(value = ["brand"]),
    ],
)
data class FoodProductEntity(
    @PrimaryKey val id: String,
    val barcode: String?,
    val name: String,
    val brand: String?,
    val carbsPer100g: Double?,
    val carbsPer100ml: Double?,
    val carbsPerUnit: Double?,
    val defaultPortionGrams: Double?,
    val defaultPortionMl: Double?,
    /** Stocké en TEXT par [com.najah.mealannouncer.core.database.Converters]. */
    val source: FoodSource,
    val verifiedByUser: Boolean,
    val updatedAt: Long,
)
