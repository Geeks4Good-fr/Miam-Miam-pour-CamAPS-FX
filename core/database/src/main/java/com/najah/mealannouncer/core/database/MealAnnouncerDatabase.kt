package com.najah.mealannouncer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.najah.mealannouncer.core.database.dao.FoodProductDao
import com.najah.mealannouncer.core.database.dao.MealItemDao
import com.najah.mealannouncer.core.database.dao.MealLogDao
import com.najah.mealannouncer.core.database.entity.FoodProductEntity
import com.najah.mealannouncer.core.database.entity.MealItemEntity
import com.najah.mealannouncer.core.database.entity.MealLogEntity

/**
 * Base locale unique de l'application : produits, repas et lignes de repas.
 *
 * `exportSchema = true` avec le répertoire `schemas/` du module : chaque évolution du
 * schéma laisse une trace versionnée, sans laquelle aucune migration n'est vérifiable.
 */
@Database(
    entities = [
        FoodProductEntity::class,
        MealLogEntity::class,
        MealItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MealAnnouncerDatabase : RoomDatabase() {

    abstract fun foodProductDao(): FoodProductDao

    abstract fun mealLogDao(): MealLogDao

    abstract fun mealItemDao(): MealItemDao

    companion object {
        const val NAME = "meal-announcer.db"
    }
}
