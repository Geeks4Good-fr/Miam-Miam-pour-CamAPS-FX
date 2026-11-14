package com.najah.mealannouncer.feature.assistant

import java.text.Normalizer
import java.util.Locale

/**
 * Commandes vocales et textuelles de gestion du repas en cours :
 * - [CancelAll] : « annuler tout le repas », « annuler tous le repas », « annule tout le repas »…
 * - [RemoveLastFood] : « annuler le dernier aliment », « annule le dernier aliment »…
 * - [AddFood] : « ajoute … », « rajoute … » (ou « ajoute » / « rajoute » seul pour ouvrir l'écoute).
 */
sealed interface MealCommand {

    /** Annule et réinitialise l'intégralité du repas en cours. */
    data object CancelAll : MealCommand

    /** Retire uniquement le dernier aliment ajouté au repas. */
    data object RemoveLastFood : MealCommand

    /**
     * Ajoute un ou plusieurs aliments au repas existant sans écraser les lignes déjà présentes.
     * Si [foodText] est vide (« ajoute » ou « rajoute » dit seul), l'assistant demande quoi ajouter.
     */
    data class AddFood(val foodText: String) : MealCommand

    companion object {
        private val REMOVE_LAST_REGEX = Regex(
            pattern = "^(?:non\\s+|ok\\s+|d\\s*accord\\s+)?" +
                "(?:annuler?|retirer?|supprimer?|enlever?)" +
                "(?:\\s+(?:le|la|l))?" +
                "\\s+dernier(?:\\s+(?:aliment|produit|element))?" +
                "(?:\\s+(?:du\\s+repas|s\\s*il\\s+te\\s+plait|stp|merci))*$",
        )

        private val CANCEL_ALL_REGEX = Regex(
            pattern = "^(?:non\\s+|ok\\s+|d\\s*accord\\s+)?" +
                "(?:" +
                "(?:annuler?|tout\\s+annuler?)(?:\\s+(?:tout|tous|toute|toutes))?(?:\\s+(?:le|les|ce|mon)\\s+repas)?" +
                "|" +
                "(?:tout|tous)\\s+annuler?(?:\\s+le\\s+repas)?" +
                ")" +
                "(?:\\s+(?:s\\s*il\\s+te\\s+plait|stp|merci))*$",
        )

        private val ADD_PREFIX_REGEX = Regex(
            pattern = "^[\\s,.:;!?¡¿\\-–—'\"«»]*(?:(?:j\\s*['’]?\\s*|je\\s+)?r?ajouter?s?)\\b" +
                "(?:\\s+(?:aussi|egalement|également|encore))?" +
                "(?:\\s+(?:au|à\\s+mon|a\\s+mon|dans\\s+le|dans\\s+mon)\\s+repas)?" +
                "[\\s,.:;!?¡¿\\-–—'\"«»]*(.*)$",
            option = RegexOption.IGNORE_CASE,
        )

        private val TRAILING_POLITE_REGEX = Regex(
            pattern = "\\s*(?:,?\\s*(?:s\\s*['’]?\\s*il\\s+te\\s+pla[iî]t|stp|merci))\\s*[.!?…]*$",
            option = RegexOption.IGNORE_CASE,
        )

        /**
         * Analyse une phrase dictée ou saisie au clavier.
         * Retourne `null` s'il s'agit d'une description normale de repas.
         */
        fun parse(rawText: String): MealCommand? {
            val stripped = WakeWordText.stripWakePrefix(rawText).ifBlank { rawText.trim() }
            val normalized = normalize(stripped)
            if (normalized.isEmpty()) return null

            if (REMOVE_LAST_REGEX.matches(normalized)) {
                return RemoveLastFood
            }
            if (CANCEL_ALL_REGEX.matches(normalized)) {
                return CancelAll
            }

            val addMatch = ADD_PREFIX_REGEX.find(stripped)
            if (addMatch != null) {
                val remainder = addMatch.groupValues[1]
                    .replace(TRAILING_POLITE_REGEX, "")
                    .trim()
                    .trim('.', '!', '?', ',', ';', ':', '-', '«', '»', '"')
                    .trim()
                return AddFood(remainder)
            }

            return null
        }

        private fun normalize(text: String): String =
            Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
    }
}
