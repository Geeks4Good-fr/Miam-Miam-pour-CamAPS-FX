package com.najah.mealannouncer.core.database.di

import android.content.Context
import androidx.room.Room
import com.najah.mealannouncer.core.database.MealAnnouncerDatabase
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
 * Pas de `fallbackToDestructiveMigration` : à la version 1 du schéma, une migration
 * destructive effacerait le journal des repas sans que personne ne l'ait demandé.
 * Toute évolution future devra fournir une vraie migration.
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
        ).build()

    @Provides
    fun provideFoodProductDao(database: MealAnnouncerDatabase): FoodProductDao =
        database.foodProductDao()

    @Provides
    fun provideMealLogDao(database: MealAnnouncerDatabase): MealLogDao =
        database.mealLogDao()

    @Provides
    fun provideMealItemDao(database: MealAnnouncerDatabase): MealItemDao =
        database.mealItemDao()
}
