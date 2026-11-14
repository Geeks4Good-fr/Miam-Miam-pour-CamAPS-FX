package com.najah.mealannouncer.integration.camaps

/** Ce que montre CamAPS à un instant donné, pour suivre le sort d'une annonce. */
enum class CamapsObservedState {
    /** La boîte « Annuler / Confirmer » est ouverte. */
    DIALOG,

    /** Le formulaire « Ajouter un repas » (titre + champ des glucides) est à l'écran. */
    FORM,

    /** CamAPS est au premier plan, sur un autre écran (accueil). */
    ELSEWHERE,

    /** Une autre application est au premier plan (volet, appel…) : on ne conclut rien. */
    OTHER_APP,
}

/**
 * Déduit, d'une suite d'observations, ce que l'utilisateur a fait de l'annonce — sans jamais rien
 * toucher.
 *
 * - CamAPS quitte le formulaire (avec ou sans boîte, selon les glucides) → **confirmé** ;
 * - la boîte se ferme et le formulaire revient → **annulé**.
 *
 * Une conclusion n'est rendue qu'après [stableCount] observations identiques d'affilée : juste après
 * « Confirmer », le formulaire peut rester visible un instant avant le retour à l'accueil, et le
 * lire comme une annulation priverait le Carnet d'un repas bien confirmé.
 */
class CamapsOutcomeTracker(private val stableCount: Int = 4) {

    private var sawDialog = false
    private var last: CamapsObservedState? = null
    private var streak = 0

    /** Ajoute une observation ; rend la conclusion quand elle est sûre, sinon `null`. */
    fun observe(state: CamapsObservedState): CamapsMealOutcome? {
        if (state == CamapsObservedState.DIALOG) sawDialog = true
        streak = if (state == last) streak + 1 else 1
        last = state
        if (streak < stableCount) return null
        return when (state) {
            CamapsObservedState.ELSEWHERE -> CamapsMealOutcome.CONFIRMED
            CamapsObservedState.FORM -> if (sawDialog) CamapsMealOutcome.CANCELLED else null
            CamapsObservedState.DIALOG, CamapsObservedState.OTHER_APP -> null
        }
    }
}
