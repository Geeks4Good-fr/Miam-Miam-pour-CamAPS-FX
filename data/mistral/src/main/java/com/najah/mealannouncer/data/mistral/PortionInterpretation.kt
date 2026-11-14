package com.najah.mealannouncer.data.mistral

import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject

/**
 * Ce que le modèle a compris d'une réponse de portion.
 *
 * [grams] et [question] ne sont jamais renseignés tous les deux : soit on a un poids, soit on
 * a une question à poser. Les deux à la fois voudrait dire que le modèle a calculé **et**
 * douté, et l'application ne saurait pas quoi faire du doute.
 */
data class PortionEstimate(
    val grams: Double?,
    val understood: String,
    val detail: String,
    val question: String?,
) {
    /** Vrai si l'on peut chiffrer la ligne avec cette réponse. */
    val hasGrams: Boolean get() = grams != null
}

/**
 * Comprend une réponse de portion que l'analyseur déterministe n'a pas su lire.
 *
 * Le modèle ne calcule pas les glucides — c'est Open Food Facts qui les donne, et le
 * calculateur de l'application qui les applique. Il ne fait qu'une chose ici : traduire une
 * phrase (« une bonne poignée », « le quart d'une assiette », « deux doigts de ») en un poids,
 * ou dire qu'il ne peut pas. Une réponse hors bornes est rejetée par [parse], comme partout
 * ailleurs dans l'application : un modèle qui rend 15 000 grammes ne doit pas produire
 * 15 000 grammes.
 */
object PortionInterpretation {

    /** En deçà et au-delà, ce n'est plus une portion mais une erreur de lecture. */
    private const val MIN_GRAMS = 1.0
    private const val MAX_GRAMS = 3_000.0

    val SYSTEM_PROMPT: String = """
        Tu aides quelqu'un à dire la quantité d'un aliment qu'il vient de manger. On te donne
        un aliment, ce que la fiche nutritionnelle dit de sa portion, et sa réponse.

        Règles absolues :
        - Tu ne donnes AUCUNE valeur de glucides, aucune calorie, aucun conseil d'insuline.
          Tu rends seulement un poids d'aliment en grammes.
        - Tu n'inventes jamais un poids. Si la réponse ne permet pas de conclure, tu poses une
          question précise à la place, et tu mets grammes à null.
        - Une fraction sans référent (« un tiers », « la moitié ») n'a pas de sens toute seule :
          demande « de quoi » (du paquet, de la portion, d'une assiette), sauf si la réponse
          nomme elle-même le référent.
        - Tu utilises les données de la fiche quand elles existent : poids de la portion,
          libellé de portion (« 2 biscuits (25 g) »), poids du paquet. Un compte d'articles ne
          se convertit que si le libellé dit combien d'articles fait une portion.
        - Un pourcentage se rapporte à la portion de la fiche, jamais au paquet.
        - Tu écris dans « detail » le calcul que tu as fait, en français, avec les nombres ;
          c'est ce que la personne verra à l'écran pour vérifier.
        - Si la réponse ne parle pas de cet aliment (autre aliment, autre sujet), grammes vaut
          null et question vaut null.
        - Un poids hors de 1 à 3000 grammes est une erreur : ne le rends pas.

        Tu réponds UNIQUEMENT par un objet JSON de cette forme :
        {"grammes":25,"compris":"deux biscuits","detail":"2 biscuits x 12,5 g = 25 g","question":null}

        ou, quand il te manque une base :
        {"grammes":null,"compris":"un tiers","detail":null,"question":"Un tiers de quoi ? Du paquet ou de la portion ?"}
    """.trimIndent()

    /** La question posée au modèle : la fiche de l'aliment, puis les mots de la personne. */
    fun userPrompt(
        food: String,
        servingGrams: Double?,
        servingLabel: String?,
        packageGrams: Double?,
        answer: String,
    ): String = buildString {
        appendLine("Aliment : $food")
        appendLine(
            "Portion indiquée : " +
                (servingGrams?.let(::grammes) ?: "inconnue"),
        )
        if (!servingLabel.isNullOrBlank()) {
            appendLine("Libellé de portion : « $servingLabel »")
        }
        appendLine("Poids du paquet : " + (packageGrams?.let(::grammes) ?: "inconnu"))
        appendLine()
        appendLine("Sa réponse : « ${answer.trim()} »")
        appendLine()
        append("Rends le JSON.")
    }

    /**
     * Lit la réponse du modèle, en refusant tout ce qui n'est pas exploitable.
     *
     * Un texte de question vide vaut `null` : c'est ainsi que le modèle dit « je n'ai rien à
     * demander », et le dialogue saura alors qu'il n'a pas compris.
     */
    fun parse(json: String): PortionEstimate {
        val racine = extractJsonObject(json)
        return PortionEstimate(
            grams = lireGrammes(racine.opt("grammes")),
            understood = racine.optString("compris").trim().take(MAX_TEXTE),
            detail = racine.optString("detail").trim().take(MAX_TEXTE),
            question = racine.optString("question")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_QUESTION)
                .ifBlank { null },
        )
    }

    /** Un poids tel qu'on l'écrit dans l'invite : « 25 g », « 12,5 g ». */
    private fun grammes(valeur: Double): String {
        val nombre = if (valeur == valeur.toLong().toDouble()) {
            valeur.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", valeur).replace('.', ',')
        }
        return "$nombre g"
    }

    private fun lireGrammes(valeur: Any?): Double? {
        val nombre = when (valeur) {
            is Number -> valeur.toDouble()
            is String -> valeur.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!nombre.isFinite()) return null
        if (nombre < MIN_GRAMS || nombre > MAX_GRAMS) return null
        return nombre
    }

    private const val MAX_TEXTE = 120

    private const val MAX_QUESTION = 160
}

/**
 * Traduit une réponse de portion en poids, en s'appuyant sur le modèle.
 *
 * L'implémentation ne décide de rien : elle envoie la question et rend ce que
 * [PortionInterpretation.parse] a bien voulu lire.
 */
interface PortionInterpreter {
    /**
     * Lève [MistralException] quand l'appel échoue ; l'appelant décide alors de demander un
     * poids en grammes plutôt que de rester bloqué.
     */
    fun interpret(
        apiKey: String,
        food: String,
        servingGrams: Double?,
        servingLabel: String?,
        packageGrams: Double?,
        answer: String,
    ): PortionEstimate
}

/** Appel réel au modèle de texte, en mode JSON. */
class HttpPortionInterpreter @Inject constructor(
    private val chat: MistralChat,
) : PortionInterpreter {

    override fun interpret(
        apiKey: String,
        food: String,
        servingGrams: Double?,
        servingLabel: String?,
        packageGrams: Double?,
        answer: String,
    ): PortionEstimate = PortionInterpretation.parse(
        chat.askJson(
            apiKey = apiKey,
            ask = JsonAsk(
                system = PortionInterpretation.SYSTEM_PROMPT,
                model = AI_PORTION_MODEL,
                user = PortionInterpretation.userPrompt(
                    food = food,
                    servingGrams = servingGrams,
                    servingLabel = servingLabel,
                    packageGrams = packageGrams,
                    answer = answer,
                ),
            ),
        ),
    )
}
