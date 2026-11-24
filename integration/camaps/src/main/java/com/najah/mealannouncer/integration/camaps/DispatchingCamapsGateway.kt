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

    /**
     * Mode Liberty (équipe médicale, 24/11/2026) : **rien** ne part vers « Ajouter un repas », ni
     * saisie ni ouverture de l'écran. Les appelants testent déjà le réglage ; ce verrou-ci est le
     * filet si un chemin l'oubliait.
     */
    private fun libertyLock(): CamapsWriteResult? =
        if (settings.config.value.liberty) CamapsWriteResult.Failed(LIBERTY_MESSAGE) else null

    override suspend fun writeCarbs(carbs: Int): CamapsWriteResult =
        libertyLock() ?: chosen().writeCarbs(carbs)

    override suspend fun announceMeal(request: CamapsMealRequest): CamapsWriteResult =
        libertyLock() ?: chosen().announceMeal(request)

    override suspend fun awaitMealOutcome(timeoutMs: Long): CamapsMealOutcome =
        if (settings.config.value.liberty) CamapsMealOutcome.UNKNOWN else chosen().awaitMealOutcome(timeoutMs)

    companion object {
        const val LIBERTY_MESSAGE =
            "Mode Liberty : Miam-Miam n'annonce aucun repas dans CamAPS FX (voir Réglages → Repas & CamAPS)."
    }
}
