package com.najah.mealannouncer.integration.camaps

import com.najah.mealannouncer.core.datastore.MealRecordSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * La porte qui choisit, à chaque appel, entre l'automatique et le manuel selon le réglage.
 *
 * C'est elle qui est branchée dans l'application. Tant que « Insertion automatique dans CamAPS »
 * est **désactivé** (le défaut), tout passe par [ManualCamapsGateway] : CamAPS est seulement
 * ouvert, l'utilisateur saisit. Une fois activé — et le service d'accessibilité allumé —, les annonces
 * passent par [AccessibilityCamapsGateway].
 *
 * Le choix est relu à chaque appel : couper le réglage reprend aussitôt la main, sans redémarrage.
 */
@Singleton
class DispatchingCamapsGateway @Inject constructor(
    private val manual: ManualCamapsGateway,
    private val auto: AccessibilityCamapsGateway,
    private val settings: MealRecordSettings,
) : CamapsMealGateway {

    private fun chosen(): CamapsMealGateway =
        if (settings.config.value.camapsAutoInsert) auto else manual

    override suspend fun writeCarbs(carbs: Int): CamapsWriteResult = chosen().writeCarbs(carbs)

    override suspend fun announceMeal(request: CamapsMealRequest): CamapsWriteResult =
        chosen().announceMeal(request)

    override suspend fun awaitMealOutcome(timeoutMs: Long): CamapsMealOutcome =
        chosen().awaitMealOutcome(timeoutMs)
}
