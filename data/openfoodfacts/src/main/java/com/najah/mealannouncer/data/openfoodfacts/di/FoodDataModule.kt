package com.najah.mealannouncer.data.openfoodfacts.di

import com.najah.mealannouncer.data.openfoodfacts.DefaultFoodRepository
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Lie l'interface au dépôt concret.
 *
 * Les écrans dépendent de [FoodRepository], jamais de l'implémentation : le jour où
 * Open Food Facts sera remplacé ou complété, un seul fichier changera.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class FoodDataModule {

    @Binds
    @Singleton
    abstract fun bindFoodRepository(impl: DefaultFoodRepository): FoodRepository
}
