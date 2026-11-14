package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.Carbs

/**
 * Les phrases que l'assistant dit pendant qu'il demande les portions.
 *
 * Elles sont écrites ici, en un seul endroit, pour deux raisons : elles sont **dites** à voix
 * haute, donc elles doivent se comprendre sans l'écran (« un tiers de quoi ? » ne veut rien
 * dire si personne n'a dit de fraction) ; et elles doivent rester courtes, parce qu'une
 * question de dix secondes fait perdre le fil à celui qui répond.
 *
 * Aucune de ces phrases ne donne de valeur de glucides inventée : le poids est celui que
 * l'utilisateur vient de dire, les glucides viennent du calculateur.
 */
internal object PortionQuestions {

    /** La première question, celle qui ouvre le dialogue pour un aliment. */
    fun forLine(line: AssistantLine): String {
        if (line.portionOptions.size > 1) return forVisualOptions(line)
        val context = line.portionContext()
        val portion = context.servingGrams
        val articles = context.itemsPerServing
        val mot = context.itemWord
        val paquet = line.product?.packageGrams

        val reperes = buildList {
            if (portion != null && articles != null && mot != null && articles > 0.0) {
                val parArticle = portion / articles
                val compte = if (articles == 1.0) {
                    "un $mot"
                } else {
                    "${formatQuantityText(articles)} ${pluriel(mot, articles)}"
                }
                add("une portion, c'est $compte de ${formatQuantityText(parArticle)} g")
            } else if (portion != null) {
                add("une portion fait ${formatQuantityText(portion)} g")
            }
            if (paquet != null) {
                add("le paquet fait ${formatQuantityText(paquet)} g")
            }
        }

        return buildString {
            append("Quelle quantité de ${line.name} avez-vous mangée ?")
            if (reperes.isNotEmpty()) {
                append(' ')
                append(reperes.joinToString(", ").replaceFirstChar { it.titlecase() })
                append('.')
            }
            append(" Dites un poids en grammes")
            if (mot != null && articles != null) {
                // L'invitation propose n'importe quel nombre : elle se dit au pluriel, même
                // quand la portion de la fiche ne compte qu'un article.
                append(", ou un nombre de ${mot}s")
            }
            append(", ou une fraction.")
        }
    }

    /** Question posée quand la banque propose plusieurs portions pesées en photo pour [line]. */
    fun forVisualOptions(line: AssistantLine): String {
        val options = line.portionOptions
        val weights = options.map { formatQuantityText(it.grams) }
        val enumeration = when (weights.size) {
            0 -> ""
            1 -> "${weights.single()} grammes"
            else -> weights.dropLast(1).joinToString(", ") + " ou " + weights.last() + " grammes"
        }
        return "Pour ${line.name}, vous avez ${options.size} portions en photo : $enumeration. Laquelle choisissez-vous ?"
    }

    /** Un poids dit en grammes ou en kilos (« 200 g », « deux cents grammes », « 1,2 kg »). */
    private val SPOKEN_WEIGHT = Regex("""(\d\s*(g|gr|kg)\b)|\b(grammes?|kilos?|kilogrammes?)\b""")

    /** L'état de cuisson est déjà dit : pas de question. */
    private val COOKING_STATE = Regex("""\b(cuits?|cuites?|crus?|crues?|cuire|secs?|sèches?|seches?)\b""")

    /**
     * Vrai si [rawText] donne un poids sans dire s'il est cuit ou cru. Le riz cru pèse environ
     * 2,5 fois moins que cuit pour les mêmes glucides : un poids sans état est ambigu.
     * Sans poids (« une assiette de riz »), le plat est évidemment cuit : pas de question.
     */
    fun weightWithoutCookingState(rawText: String): Boolean {
        val text = rawText.lowercase()
        return SPOKEN_WEIGHT.containsMatchIn(text) && !COOKING_STATE.containsMatchIn(text)
    }

    /** Question posée par l'application pour un poids d'aliment qui change à la cuisson. */
    fun forCookingState(line: AssistantLine): String =
        "Pour ${line.rawText.trim()} : c'est le poids déjà cuit, ou le poids cru, avant cuisson ?"

    /**
     * Le mot accordé au nombre, pour une phrase dite à voix haute.
     *
     * « 2 biscuit(s) » se lirait « deux biscuit s » : un pluriel écrit entre parenthèses ne
     * s'entend pas, il faut choisir.
     */
    private fun pluriel(mot: String, nombre: Double): String =
        if (nombre == 1.0) mot else "${mot}s"

    /** Quand le modèle a posé sa propre question, c'est elle qu'on dit ; sinon celle-ci. */
    fun gramsPlease(): String =
        "Dites-moi plutôt un poids en grammes, ou une fraction comme « la moitié »."

    /** Quand rien n'a été entendu, ou rien de compréhensible. */
    fun notUnderstood(): String =
        "Je n'ai pas compris. Dites par exemple « 150 grammes », « deux biscuits » " +
            "ou « un tiers du paquet »."

    /** Confirmation d'une portion retenue : le poids **et** les glucides calculés. */
    fun noted(line: AssistantLine, carbs: Carbs?): String = buildString {
        append("C'est noté : ${formatQuantityText(line.quantity)} g")
        if (carbs != null && carbs.grams > 0.0) {
            append(", soit ${carbs.format()} g de glucides")
        }
        append('.')
    }

    /** La fin du dialogue : le total, et ce qui reste sans réponse. */
    fun finished(total: Carbs, unresolved: Int): String = when {
        unresolved > 0 && total.grams > 0.0 ->
            "C'est tout. Total : ${total.format()} g de glucides. " +
                "$unresolved aliment(s) restent sans portion."

        unresolved > 0 ->
            "C'est tout. $unresolved aliment(s) restent sans portion : " +
                "je ne peux pas calculer le total."

        else -> "C'est tout. Total : ${total.format()} g de glucides."
    }
}
