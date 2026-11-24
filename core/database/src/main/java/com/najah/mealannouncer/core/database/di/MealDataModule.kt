package com.najah.mealannouncer.core.database.di

import com.najah.mealannouncer.core.database.repository.DefaultFavoriteMealRepository
import com.najah.mealannouncer.core.database.repository.DefaultGeminiMealLogRepository
import com.najah.mealannouncer.core.database.repository.GeminiMealLogRepository
import com.najah.mealannouncer.core.database.repository.DefaultJournalRepository
import com.najah.mealannouncer.core.database.repository.DefaultMealRepository
import com.najah.mealannouncer.core.database.repository.FavoriteMealRepository
import com.najah.mealannouncer.core.database.repository.JournalRepository
import com.najah.mealannouncer.core.database.repository.MealRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Lie les dépôts du journal à leur implémentation Room.
 *
 * Les écrans dépendent des interfaces : le jour où le journal devra être exporté vers un autre
 * stockage, un seul fichier changera.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MealDataModule {

    @Binds
    @Singleton
    abstract fun bindMealRepository(impl: DefaultMealRepository): MealRepository

    @Binds
    @Singleton
    abstract fun bindJournalRepository(impl: DefaultJournalRepository): JournalRepository

    @Binds
    @Singleton
    abstract fun bindFavoriteMealRepository(
        impl: DefaultFavoriteMealRepository,
    ): FavoriteMealRepository

    @Binds
    @Singleton
    abstract fun bindGeminiMealLogRepository(
        impl: DefaultGeminiMealLogRepository,
    ): GeminiMealLogRepository
}
