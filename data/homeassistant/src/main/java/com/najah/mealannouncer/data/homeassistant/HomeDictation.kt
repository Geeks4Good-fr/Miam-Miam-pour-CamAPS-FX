package com.najah.mealannouncer.data.homeassistant

/**
 * Les deux champs d'échange posés dans Home Assistant (`docs/VOIX-MAISON.md` § 5 bis).
 *
 * Les événements personnalisés sont réservés aux administrateurs ; le compte « Meal Announcer »
 * ne l'est pas. L'automatisation `assist_repas` écrit donc la phrase dans un champ texte que
 * l'appli observe, et l'appli répond en écrivant le numéro de séance dans un second champ.
 */
object HomeExchange {
    /** Écrit par HA : `séance|enceinte|phrase`. */
    const val DICTATION_ENTITY = "input_text.meal_announcer_dictee"

    /** Écrit par l'appli : le numéro de séance, pour que l'enceinte dise « C'est noté, je regarde. » */
    const val ACK_ENTITY = "input_text.meal_announcer_accuse"

    internal const val SATELLITE_PREFIX = "assist_satellite."
}

/**
 * Une phrase de repas entendue par une enceinte.
 *
 * [satellite] est l'enceinte qui l'a entendue : c'est **la seule** qui mènera la suite du
 * dialogue (§ 5, « une seule enceinte par repas »).
 */
data class HomeDictation(
    val session: String,
    val satellite: String,
    val text: String,
) {
    /**
     * Vrai quand l'utilisateur a seulement dit « miam-miam » à l'enceinte, sans la suite du repas :
     * l'application doit alors demander « Je t'écoute, quel est ton repas ? » (`docs/MIAM-MIAM.md`).
     */
    val isWakeOnly: Boolean
        get() = isWakeOnlyText(text)

    companion object {
        /** Marqueur écrit par l'automatisation `assist_repas` quand `{repas}` est absent. */
        const val WAKE_ONLY = "__MIAM_MIAM__"

        private val WAKE_ONLY_NORMALIZED = setOf("miam", "miam miam", "miam-miam")

        internal fun isWakeOnlyText(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed == WAKE_ONLY) return true
            val normalized = trimmed.lowercase()
                .replace(Regex("[^a-z-]+"), " ")
                .trim()
            return normalized in WAKE_ONLY_NORMALIZED
        }

        /**
         * Lit la valeur du champ de dictée, ou `null` si elle n'en est pas une : champ vide,
         * valeur `unknown` après un redémarrage de HA, morceaux manquants, enceinte qui n'en est
         * pas une. Une valeur douteuse est ignorée plutôt que de lancer un dialogue sur rien.
         */
        fun parse(value: String?): HomeDictation? {
            if (value.isNullOrBlank()) return null
            val parts = value.split('|', limit = 3)
            if (parts.size != 3) return null
            val session = parts[0].trim()
            val satellite = parts[1].trim()
            val text = parts[2].trim()
            if (session.isEmpty() || !session.all { it.isDigit() }) return null
            if (!satellite.startsWith(HomeExchange.SATELLITE_PREFIX) ||
                satellite.length == HomeExchange.SATELLITE_PREFIX.length
            ) return null
            if (text.isEmpty()) return null
            return HomeDictation(session = session, satellite = satellite, text = text)
        }
    }
}

/**
 * Une réponse attendue à une question posée par l'enceinte.
 *
 * Donnée à `ask_question`, la liste des réponses possibles permet à Home Assistant de ranger ce
 * qu'il a entendu sous un [id] connu (« Roupa » ne correspond à rien, « au repas » oui).
 */
data class ExpectedAnswer(
    val id: String,
    val sentences: List<String>,
)

/**
 * Ce que l'enceinte a entendu après une question.
 *
 * [sentence] est `null` sur un silence. [answerId] n'est renseigné que si des réponses
 * attendues avaient été données et que l'une d'elles correspond.
 */
data class HomeAnswer(
    val sentence: String?,
    val answerId: String? = null,
)

/**
 * La bouche et l'oreille d'une enceinte, telles que le dialogue du repas les voit.
 *
 * [HomeAssistantSession] l'implémente ; le dialogue n'en connaît pas plus, ce qui permet de
 * l'éprouver avec une fausse enceinte. Les deux méthodes lèvent [HomeAssistantException].
 */
interface HomeVoiceLink {
    suspend fun say(satellite: String, text: String)

    /**
     * Attend que le haut-parleur de [satellite] se taise vraiment. Essai du 29/10/2026 : la
     * Voice PE rend la main d'une annonce bien avant d'avoir fini de la dire ; sans cette attente,
     * la question suivante ouvre le micro pendant que l'enceinte parle encore.
     */
    suspend fun awaitQuiet(satellite: String) = Unit

    suspend fun ask(
        satellite: String,
        question: String,
        answers: List<ExpectedAnswer> = emptyList(),
    ): HomeAnswer
}
