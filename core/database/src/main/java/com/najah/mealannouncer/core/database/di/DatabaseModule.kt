package com.najah.mealannouncer.core.database.di

import android.content.Context
import androidx.room.Room
import com.najah.mealannouncer.core.database.MealAnnouncerDatabase
import com.najah.mealannouncer.core.database.dao.AutomationLogDao
import com.najah.mealannouncer.core.database.dao.FavoriteMealDao
import com.najah.mealannouncer.core.database.dao.FoodProductDao
import com.najah.mealannouncer.core.database.dao.MealItemDao
import com.najah.mealannouncer.core.database.dao.MealLogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Fournit la base Room et ses DAO à toute l'application.
 *
 * Pas de `fallbackToDestructiveMigration` : une migration destructive effacerait le journal des
 * repas sans que personne ne l'ait demandé. Chaque évolution du schéma fournit donc sa vraie
 * migration — la version 2 (journal d'automatisation et favoris) est purement additive.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MealAnnouncerDatabase =
        Room.databaseBuilder(
            context,
            MealAnnouncerDatabase::class.java,
            MealAnnouncerDatabase.NAME,
        )
            .addMigrations(MealAnnouncerDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideFoodProductDao(database: MealAnnouncerDatabase): FoodProductDao =
        database.foodProductDao()

    @Provides
    fun provideMealLogDao(database: MealAnnouncerDatabase): MealLogDao =
        database.mealLogDao()

    @Provides
    fun provideMealItemDao(database: MealAnnouncerDatabase): MealItemDao =
        database.mealItemDao()

    @Provides
    fun provideAutomationLogDao(database: MealAnnouncerDatabase): AutomationLogDao =
        database.automationLogDao()

    @Provides
    fun provideFavoriteMealDao(database: MealAnnouncerDatabase): FavoriteMealDao =
        database.favoriteMealDao()
}
