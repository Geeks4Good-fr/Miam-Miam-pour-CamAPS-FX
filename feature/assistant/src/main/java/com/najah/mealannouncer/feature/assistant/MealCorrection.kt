package com.najah.mealannouncer.feature.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Reconnaît, dans une réponse dite à voix haute, une **contestation** du repas compris :
 * « non, ce n'est pas ce produit », « pas cette marque, c'est du pain Gourmet »…
 *
 * Elle est lue **avant** le type de repas : « non, c'est du pain pour mon repas » contient le mot
 * « repas », et le prendre pour une réponse écrirait dans CamAPS le produit que l'utilisateur
 * vient justement de refuser.
 */
object MealCorrection {

    private val MARKERS = listOf(
        "pas ce produit", "pas le bon", "pas la bonne", "pas cette", "pas celui", "pas celle",
        "ce n est pas", "c est pas", "n est pas", "pas du tout", "erreur", "trompe", "autre produit",
        "autre marque", "mauvais", "mauvaise", "corrige", "correction du produit",
    )

    /** Vrai si [heard] conteste ce qui a été compris, plutôt que de répondre à la question. */
    fun looksLikeCorrection(heard: String): Boolean {
        val text = normalize(heard)
        if (text.isEmpty()) return false
        if (text == "non" || text.startsWith("non ")) return true
        return MARKERS.any { text.contains(it) }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
}
