package com.najah.mealannouncer.core.model

import java.time.Instant

/**
 * Résultat brut du parseur : la phrase dite, et les aliments qu'on y a reconnus.
 *
 * Aucun glucides n'est encore calculé à ce stade.
 */
data class MealIntent(
    val rawTranscript: String,
    val items: List<MealItem>,
    val capturedAt: Instant = Instant.now(),
) {
    /** Vrai si la phrase n'a produit aucun aliment exploitable. */
    fun isEmpty(): Boolean = items.isEmpty()

    companion object {
        /** Intent vide, utilisé quand la dictée n'a rien donné. */
        fun empty(transcript: String, at: Instant = Instant.now()): MealIntent =
            MealIntent(rawTranscript = transcript, items = emptyList(), capturedAt = at)
    }
}
