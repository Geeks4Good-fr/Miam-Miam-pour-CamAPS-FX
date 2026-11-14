package com.najah.mealannouncer.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.najah.mealannouncer.core.model.MealUnit

/**
 * Une ligne de repas : « Prince ×2 — 22 g ».
 *
 * Supprimer le repas supprime ses lignes (CASCADE) : jamais de ligne orpheline.
 */
@Entity(
    tableName = "meal_items",
    foreignKeys = [
        ForeignKey(
            entity = MealLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["mealLogId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mealLogId"])],
)
data class MealItemEntity(
    @PrimaryKey val id: String,
    val mealLogId: String,
    val productName: String,
    val brand: String?,
    val quantity: Double,
    /** Stocké en TEXT par [com.najah.mealannouncer.core.database.Converters]. */
    val unit: MealUnit,
    val carbs: Double?,
    val confidence: Float,
)
