package com.najah.mealannouncer.integration.camaps.di

import com.najah.mealannouncer.integration.camaps.CamapsMealGateway
import com.najah.mealannouncer.integration.camaps.DispatchingCamapsGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Câblage de la porte CamAPS.
 *
 * C'est [DispatchingCamapsGateway] qui est branchée : elle choisit à chaque appel entre le manuel
 * (défaut) et l'automatique selon le réglage « Insertion automatique dans CamAPS ». Le parcours
 * réel a été relevé sur l'appareil le 26/10/2026 (voir `docs/CAMAPS-REPAS.md`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CamapsModule {

    @Binds
    @Singleton
    abstract fun bindGateway(impl: DispatchingCamapsGateway): CamapsMealGateway
}
