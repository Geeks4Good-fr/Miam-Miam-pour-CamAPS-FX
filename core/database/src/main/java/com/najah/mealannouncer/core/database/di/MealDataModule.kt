package com.najah.mealannouncer.core.database.di

import com.najah.mealannouncer.core.database.repository.DefaultMealRepository
import com.najah.mealannouncer.core.database.repository.MealRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Lie le journal des repas à son implémentation Room.
 *
 * Les écrans dépendent de [MealRepository] : le jour où le journal devra être exporté vers
 * un autre stockage, un seul fichier changera.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MealDataModule {

    @Binds
    @Singleton
    abstract fun bindMealRepository(impl: DefaultMealRepository): MealRepository
}
