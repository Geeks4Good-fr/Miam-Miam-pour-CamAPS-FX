package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.integration.camaps.CamapsMealType
import com.najah.mealannouncer.integration.camaps.CamapsVoice

/**
 * Un total de glucides dicté tel quel : « 25 grammes », « annonce 25 g de glucides, repas, et
 * note Glooko » (demande de l'utilisateur, 30/10/2026).
 *
 * Il saute la recherche et le calcul des aliments : on connaît son total, ou l'automate a buté
 * sur un obstacle et l'on reprend la main à la voix. La suite est la même qu'après un calcul —
 * type de repas, puis « Ajouter un repas » dans CamAPS, que **l'utilisateur seul** confirme.
 *
 * N'est reconnue qu'une phrase faite **uniquement** d'un nombre de grammes, du type et de la
 * demande de Carnet Glooko : « 25 grammes de pâtes » reste un aliment à chercher.
 */
data class DirectCarbs(
    val grams: Int,
    val type: CamapsMealType?,
    val glooko: Boolean,
) {
    companion object {
        /** Au-delà, ce n'est plus un total plausible : la phrase part au calcul normal. */
        const val MAX_GRAMS = 300

        private val PATTERN = Regex(
            "^(?:(?:annonce|annoncer|annonces|note|noter|notes|mets|met|enregistre|saisis|saisir)\\s+)?" +
                "(\\d{1,3})(?:[.,]\\d+)?\\s*(?:g|gr|grammes?)\\b(?:\\s+de\\s+glucides?)?(.*)$",
        )

        /** Les seuls mots admis après le nombre : liaison, type de repas, Carnet Glooko. */
        private val ALLOWED = setOf(
            "et", "puis", "le", "la", "les", "un", "une", "au", "dans", "de", "du", "en", "pour", "a",
            "note", "noter", "notes", "annote", "annoter", "renseigne", "renseigner", "mets", "ecris",
            "glooko", "carnet", "repas", "collation", "snack", "gouter", "hypo", "hypoglycemie",
            "resucrage", "lent", "lents", "lente", "lentes", "absorption", "glucides", "glucide",
            "type", "c'est", "est",
        )

        fun parse(text: String): DirectCarbs? {
            val t = CarbSanity.normalize(text).trim().trimEnd('.', '!', '?', ' ')
            val match = PATTERN.find(t) ?: return null
            val grams = match.groupValues[1].toIntOrNull() ?: return null
            if (grams !in 1..MAX_GRAMS) return null
            val rest = match.groupValues[2]
            val words = rest.split(Regex("[^a-z']+")).filter { it.isNotBlank() }
            if (words.any { it !in ALLOWED }) return null
            return DirectCarbs(
                grams = grams,
                type = CamapsVoice.matchMealType(rest),
                glooko = "glooko" in words || "carnet" in words,
            )
        }
    }
}
