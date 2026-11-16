package com.najah.mealannouncer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.najah.mealannouncer.core.database.dao.AutomationLogDao
import com.najah.mealannouncer.core.database.dao.FavoriteMealDao
import com.najah.mealannouncer.core.database.dao.FoodProductDao
import com.najah.mealannouncer.core.database.dao.MealItemDao
import com.najah.mealannouncer.core.database.dao.MealLogDao
import com.najah.mealannouncer.core.database.entity.AutomationLogEntity
import com.najah.mealannouncer.core.database.entity.FavoriteMealEntity
import com.najah.mealannouncer.core.database.entity.FoodProductEntity
import com.najah.mealannouncer.core.database.entity.MealItemEntity
import com.najah.mealannouncer.core.database.entity.MealLogEntity

/**
 * Base locale unique de l'application : produits, repas, lignes de repas, journal
 * d'automatisation et repas favoris.
 *
 * `exportSchema = true` avec le répertoire `schemas/` du module : chaque évolution du
 * schéma laisse une trace versionnée, sans laquelle aucune migration n'est vérifiable.
 */
@Database(
    entities = [
        FoodProductEntity::class,
        MealLogEntity::class,
        MealItemEntity::class,
        AutomationLogEntity::class,
        FavoriteMealEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MealAnnouncerDatabase : RoomDatabase() {

    abstract fun foodProductDao(): FoodProductDao

    abstract fun mealLogDao(): MealLogDao

    abstract fun mealItemDao(): MealItemDao

    abstract fun automationLogDao(): AutomationLogDao

    abstract fun favoriteMealDao(): FavoriteMealDao

    companion object {
        const val NAME = "meal-announcer.db"

        /**
         * Version 2 (14/11/2026) : journal d'automatisation et repas favoris.
         *
         * Deux tables neuves, aucune modification des tables existantes : la migration est donc
         * purement additive. Le journal des repas de l'utilisateur est intact — une migration
         * destructive effacerait des mois d'annonces.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `automation_log` (" +
                        "`id` TEXT NOT NULL, " +
                        "`at` INTEGER NOT NULL, " +
                        "`carbs` INTEGER NOT NULL, " +
                        "`description` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_log_at` " +
                        "ON `automation_log` (`at`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_automation_log_outcome` " +
                        "ON `automation_log` (`outcome`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `favorite_meals` (" +
                        "`id` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`linesJson` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER, " +
                        "`uses` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_favorite_meals_name` " +
                        "ON `favorite_meals` (`name`)",
                )
            }
        }
    }
}
