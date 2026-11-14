package com.najah.mealannouncer.integration.camaps

/**
 * Ce qui est advenu de l'écriture dans CamAPS FX.
 *
 * Aucun de ces résultats ne dit « repas validé » : la validation dans une application qui
 * dose de l'insuline reste un geste de l'utilisateur.
 */
sealed interface CamapsWriteResult {

    /** La valeur a été saisie dans CamAPS ; il reste à l'utilisateur à confirmer. */
    data class Entered(val carbs: Int) : CamapsWriteResult

    /** CamAPS a été ouvert, mais la saisie reste manuelle. */
    data class Manual(val carbs: Int) : CamapsWriteResult

    /** Rien n'a pu être fait ; [message] est affichable tel quel. */
    data class Failed(val message: String) : CamapsWriteResult
}

/**
 * Ce que l'utilisateur a fait de l'annonce laissée dans CamAPS, vu de l'extérieur.
 *
 * L'automate ne presse jamais « Confirmer » : il **observe** seulement ce qui suit, pour savoir
 * s'il peut écrire les cibles de suivi (Carnet Glooko). Dans le doute, ce n'est pas confirmé.
 */
enum class CamapsMealOutcome {
    /** La boîte s'est fermée et CamAPS a quitté l'écran du repas : l'annonce est enregistrée. */
    CONFIRMED,

    /** La boîte s'est fermée sur le formulaire du repas : l'utilisateur a annulé. */
    CANCELLED,

    /** Rien de décisif n'a été vu dans le délai. */
    TIMEOUT,

    /** L'observation est impossible (service coupé, chemin manuel). */
    UNKNOWN,
}

/**
 * Une annonce de repas à porter dans CamAPS.
 *
 * [confirm] dit si l'automate doit appuyer sur « Continuer » : il ne le fait que sur une
 * confirmation vocale explicite de l'utilisateur, décidée en amont (voir `docs/CAMAPS-REPAS.md` § 2).
 * Rappel : « Ajouter un repas » informe la boucle fermée, il **ne dose pas** d'insuline.
 */
data class CamapsMealRequest(
    val carbs: Int,
    val type: CamapsMealType,
    val confirm: Boolean,
)

/**
 * La porte vers CamAPS FX.
 *
 * Le contrat suit `docs/CAMAPS-REPAS.md` : l'application ouvre le parcours « ajouter un repas »,
 * saisit les glucides et coche le type choisi par l'utilisateur, mais **ne valide jamais un bolus** à sa
 * place, et abandonne proprement si l'interface ne ressemble plus à ce qu'elle était.
 */
interface CamapsMealGateway {

    /**
     * Écrit [carbs] grammes de glucides dans l'écran d'ajout de repas, sans choisir de type ni
     * valider : c'est le geste minimal, conservé pour le chemin manuel.
     *
     * Les échecs ordinaires sont rendus en [CamapsWriteResult.Failed], de sorte que l'écran
     * puisse les dire sans traiter une exception.
     */
    suspend fun writeCarbs(carbs: Int): CamapsWriteResult

    /**
     * Porte l'annonce complète : glucides, type de repas, et — si [CamapsMealRequest.confirm] —
     * appui sur « Continuer ». Jamais au-delà.
     */
    suspend fun announceMeal(request: CamapsMealRequest): CamapsWriteResult

    /**
     * Après un « Continuer », attend de voir ce que l'utilisateur fait de la boîte « Confirmer »
     * (au plus [timeoutMs]). N'agit sur rien : observe seulement.
     */
    suspend fun awaitMealOutcome(timeoutMs: Long): CamapsMealOutcome = CamapsMealOutcome.UNKNOWN
}
