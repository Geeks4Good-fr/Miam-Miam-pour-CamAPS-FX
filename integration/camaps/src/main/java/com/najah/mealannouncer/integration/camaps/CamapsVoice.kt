package com.najah.mealannouncer.integration.camaps

import java.text.Normalizer

/**
 * Le dialogue vocal d'une annonce de repas, côté compréhension.
 *
 * Tout est **pur** (texte → texte / type) et testable : l'assistant parle avec la voix (ailleurs),
 * mais c'est ici qu'on décide ce qu'une réponse de l'utilisateur veut dire. Deux règles de sûreté guident
 * ces fonctions :
 *
 * - un type de repas n'est retenu que si la réponse le désigne clairement ; sinon `null`, et
 *   l'assistant redemande ;
 * - une confirmation n'est retenue que sur un **oui** franc, jamais sur un « non » ni sur un doute :
 *   c'est ce oui qui autorise l'automate à appuyer sur « Continuer ».
 */
object CamapsVoice {

    /**
     * Quel type de repas la réponse [transcript] désigne, ou `null` si ce n'est pas clair.
     *
     * L'ordre compte : « repas à absorption lente » contient « repas », donc on cherche d'abord
     * l'hypoglycémie, puis l'absorption lente, et seulement ensuite le repas ordinaire.
     */
    fun matchMealType(transcript: String): CamapsMealType? {
        val t = normalize(transcript)
        if (t.isBlank()) return null
        return when {
            t.contains("hypo") || t.contains("resucr") || t.contains("correction") -> CamapsMealType.HYPO
            t.contains("lent") || t.contains("absorption") -> CamapsMealType.SLOW
            t.contains("repas") || t.contains("collation") || t.contains("snack") ||
                t.contains("gouter") || t.contains("manger") || t.contains("casse-croute") ->
                CamapsMealType.MEAL
            else -> null
        }
    }

    /**
     * La réponse [transcript] est-elle un « oui » franc ?
     *
     * Une négation présente (« non », « pas ») l'emporte toujours : « non c'est bon » n'est pas une
     * confirmation. Sinon, un mot d'accord suffit.
     */
    fun isConfirmation(transcript: String): Boolean {
        val t = normalize(transcript)
        if (t.isBlank()) return false
        val words = t.split(' ', '\'', ',', '.', '!', '?').filter { it.isNotBlank() }
        if (words.any { it == "non" || it == "pas" || it == "annule" || it == "attends" }) return false
        return words.any { it in AFFIRMATIVES } || t.contains("je confirme") || t.contains("c'est bon")
    }

    /**
     * L'énoncé unique : ce qui a été compris, le total, et la demande du type — en une seule fois.
     *
     * [summary] reprend les aliments du repas (« lait chocolaté, pitch fraise »). Vide, on annonce
     * seulement le total. Répondre le type vaut confirmation : pas de seconde question.
     */
    fun announceAndAsk(summary: String, carbs: Int): String = buildString {
        if (summary.isNotBlank()) append("J'ai compris : ").append(summary).append(". ")
        append(carbs).append(" grammes de glucides. ")
        append("Quel type : repas ou collation, hypoglycémie, ou repas à glucides lents ?")
    }

    /** Relance concise quand le type n'a pas été compris : on ne répète pas tout le repas. */
    fun typeNotUnderstood(): String =
        "Je n'ai pas compris. Repas ou collation, hypoglycémie, ou repas à glucides lents ?"

    /** Après plusieurs essais sans comprendre : on propose la photo plutôt que de bloquer. */
    fun photoSuggestion(): String =
        "Je n'ai pas réussi à comprendre le type. Tu peux photographier ton assiette, ou recommencer."

    /** Question posée à l'ouverture : les trois choix, dans l'ordre de l'écran. */
    fun typeQuestion(carbs: Int): String =
        "$carbs grammes de glucides. Quel type : repas ou collation, " +
            "traitement de l'hypoglycémie, ou repas à absorption lente ?"

    /** Demande de confirmation, après le choix du type. */
    fun confirmPrompt(carbs: Int, type: CamapsMealType): String {
        val label = CamapsScreen.MEAL_TYPE_LABELS.getValue(type)
        return "$carbs grammes, $label. Tu confirmes ?"
    }

    /** Mots d'accord acceptés seuls. */
    private val AFFIRMATIVES =
        setOf("oui", "ouais", "ok", "okay", "confirme", "valide", "vas-y", "accord", "daccord")

    /** Minuscule, sans accents, espaces normalisés : pour comparer sans trébucher sur la casse. */
    private fun normalize(text: String): String {
        val lower = text.trim().lowercase()
        val stripped = Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return stripped.replace(Regex("\\s+"), " ")
    }
}
