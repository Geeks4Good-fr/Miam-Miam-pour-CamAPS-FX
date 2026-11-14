package com.najah.mealannouncer.integration.glooko.di

import com.najah.mealannouncer.integration.glooko.GlookoPreferences
import com.najah.mealannouncer.integration.glooko.GlookoState
import com.najah.mealannouncer.integration.glooko.GlookoToNightscoutMapper
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Câblage Hilt de l'import Glooko. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GlookoModule {

    @Binds
    @Singleton
    abstract fun bindGlookoState(implementation: GlookoPreferences): GlookoState

    companion object {
        /**
         * Le convertisseur est sans état et sans dépendance : une seule instance suffit, et
         * la fournir explicitement évite d'annoter la classe de conversion.
         */
        @Provides
        @Singleton
        fun provideMapper(): GlookoToNightscoutMapper = GlookoToNightscoutMapper()
    }
}
