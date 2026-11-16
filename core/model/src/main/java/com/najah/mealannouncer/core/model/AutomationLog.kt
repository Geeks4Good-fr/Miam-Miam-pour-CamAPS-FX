package com.najah.mealannouncer.core.model

import java.time.Instant

/**
 * Jusqu'où l'automatisation d'un repas a été menée.
 *
 * La distinction demandée par l'utilisateur est celle qui compte au quotidien : un repas saisi dans
 * CamAPS mais **non** noté dans le Carnet Glooko n'est pas un repas complet. Le journal doit
 * le dire sans qu'on ait à fouiller trois applicatifs pour le comprendre.
 */
enum class AutomationOutcome {
    /** Tout a été fait : CamAPS d'abord, puis les cibles de suivi (Carnet Glooko / note). */
    COMPLETE,

    /**
     * L'automatisation s'est arrêtée à CamAPS : les glucides y sont, mais le Carnet Glooko (ou
     * la note Nightscout) n'a pas été écrit — suivi coupé, carnet en échec, ou confirmation
     * dans CamAPS jamais observée.
     */
    CAMAPS_ONLY,

    /** Annulé dans CamAPS : rien n'est noté nulle part, et c'est voulu. */
    CANCELLED,

    /** CamAPS n'a pas pu être atteint (alerte, écran inattendu, service d'accessibilité coupé). */
    FAILED,
}

/** D'où part l'annonce : ce qui a déclenché l'automatisation. */
enum class AutomationSource {
    /** L'assistant (dictée, photo, dialogue de portion). */
    ASSISTANT,

    /** La recherche manuelle dans Open Food Facts. */
    SEARCH,

    /** Un repas favori annoncé en un geste. */
    FAVORITE,

    /** La quantité dictée à une enceinte de la maison. */
    DIRECT,

    /** La composition manuelle historique (écran de confirmation). */
    MANUAL,
}

/**
 * Une ligne du journal d'automatisation.
 *
 * Le journal des repas (`meal_logs`) garde ce que l'utilisateur a mangé ; celui-ci garde ce que
 * l'automatisation a **réussi à faire** de chaque annonce. Les deux sont volontairement séparés :
 * on doit pouvoir lire « 12 réussites complètes, 3 arrêtées à CamAPS » sans recalculer quoi que
 * ce soit, et sans mélanger un fait médical (les glucides) avec un fait technique (l'appui sur
 * « Confirmer » a-t-il été vu ?).
 */
data class AutomationEntry(
    val id: String,
    val at: Instant,
    /** Les glucides annoncés, tels qu'envoyés à CamAPS. */
    val carbs: Int,
    /** La description du repas (« pain au chocolat : 41,4 g »), pour reconnaître l'annonce. */
    val description: String,
    val source: AutomationSource,
    val outcome: AutomationOutcome,
    /** Ce qui s'est réellement passé, en une phrase : c'est la pièce à conviction du journal. */
    val detail: String = "",
) {
    /** Vrai si tout a abouti (CamAPS **et** la suite). */
    val isComplete: Boolean get() = outcome == AutomationOutcome.COMPLETE
}

/**
 * Ce que le journal retient d'une période : de quoi afficher un bandeau de synthèse en tête.
 *
 * Compté une fois, à l'enregistrement, plutôt que recalculé à chaque affichage : la liste peut
 * contenir des milliers de lignes après quelques mois.
 */
data class AutomationSummary(
    val complete: Int = 0,
    val camapsOnly: Int = 0,
    val cancelled: Int = 0,
    val failed: Int = 0,
) {
    val total: Int get() = complete + camapsOnly + cancelled + failed
}
