package com.najah.mealannouncer.data.mistral

import com.najah.mealannouncer.core.model.MealUnit
import org.json.JSONException

/**
 * Un aliment reconnu dans une phrase dictée, avant toute recherche de valeurs.
 *
 * [quantity] est **nullable** et c'est le cœur du contrat : quand la phrase ne dit pas la
 * quantité, l'IA n'en invente pas une. C'est l'application qui demandera la portion.
 */
data class DetectedFood(
    val name: String,
    val quantity: Double?,
    val unit: MealUnit,
    val rawText: String,
    val barcode: String? = null,
) {
    /** Vrai si la quantité manque : la portion devra être demandée. */
    fun needsPortion(): Boolean = quantity == null || quantity <= 0.0
}

/** Ce que la lecture d'une phrase a donné : les aliments, et ceux qu'on a dû écarter. */
data class ExtractionResult(
    val foods: List<DetectedFood>,
    val rejected: List<String> = emptyList(),
)

/**
 * Lecture d'une phrase de repas.
 *
 * Le modèle ne fait qu'une chose : découper la phrase en aliments et lire les quantités
 * dites. Il ne donne aucun glucides — c'est le rôle d'Open Food Facts, puis du web, puis
 * du calculateur. Un modèle qui « sait » qu'un riz fait 28 g aux 100 g est un modèle qui
 * peut se tromper sans qu'on le voie.
 */
object MealExtraction {

    /** Unités acceptées dans la réponse du modèle, telles qu'on les lui demande. */
    private val UNIT_CODES = mapOf(
        "gram" to MealUnit.GRAM,
        "milliliter" to MealUnit.MILLILITER,
        "unit" to MealUnit.UNIT,
        "portion" to MealUnit.PORTION,
        "slice" to MealUnit.SLICE,
        "cup" to MealUnit.CUP,
        "unknown" to MealUnit.UNKNOWN,
    )

    val SYSTEM_PROMPT: String = """
        Tu transformes une phrase française — dite à l'oral, parfois mal transcrite — qui décrit
        ce que quelqu'un s'apprête à manger ou à boire, en une liste d'aliments.

        Ton rôle est de COMPRENDRE et d'EXTRAIRE, jamais de chiffrer.

        Règles absolues :
        - Tu extrais CHAQUE aliment ou boisson que la personne nomme, y compris les MARQUES et les
          produits que tu ne connais pas (« Pitch », « Franchetto Cacao Balcony », « Granola »…).
          Tu gardes le nom TEL QU'IL EST DIT, au mieux pour une recherche. Une marque inconnue n'est
          JAMAIS une raison d'écarter un aliment : il sera cherché ailleurs (base de données, web,
          ou estimation). Mieux vaut extraire un aliment qui sera ensuite précisé que de le perdre.
        - Tu n'ajoutes JAMAIS un aliment absent de la phrase, même pour « compléter ».
        - Tu ne donnes AUCUNE valeur de glucides : ce n'est pas ton rôle.
        - Tu ignores les mots du langage parlé qui ne sont pas des aliments (« j'ai pris », « pour
          mon petit-déjeuner », « euh », « je crois ») : ils t'aident à comprendre, mais ne sont
          pas des aliments.
        - Quand la quantité n'est PAS dite, tu mets quantity à null (on la demandera). Tu ne
          l'estimes pas, tu ne prends pas une portion « habituelle ». Quand elle est dite en
          nombre ou en poids (« deux biscuits », « 150 grammes »), tu la mets avec son unité.
        - Tu gardes un nom utile et DESCRIPTIF : « riz basmati » plutôt que « riz » ; « lait
          chocolaté » plutôt que « lait » ; garde ce qui caractérise l'aliment (parfum, type).
        - Tu ne traduis pas : les noms restent en français.
        - Un plat composé (« tajine de poulet aux olives », « gratin de courgettes ») est UN seul
          aliment : tu ne le découpes pas en ingrédients (sinon les glucides seraient comptés deux
          fois).
        - Une boisson avec un ajout sucré (« un thé avec trois sucres », « un café avec deux
          sucres ») : garde l'ajout DANS le nom (« thé avec trois sucres ») pour que les glucides
          du sucre soient pris en compte ensuite.
        - Tu ne rends une liste vide QUE si la phrase ne décrit vraiment aucun aliment ni boisson.
          Dans le doute, tu extrais plutôt que d'écarter.

        Tu réponds UNIQUEMENT par un objet JSON de cette forme :
        {"foods":[{"name":"lait chocolaté","quantity":1,"unit":"cup","raw_text":"un lait chocolaté dans une tasse"}]}

        unit vaut exactement l'un de : gram, milliliter, unit, portion, slice, cup, unknown.
        raw_text reprend les mots de la phrase qui ont donné cet aliment.
    """.trimIndent()

    /** La question posée au modèle, avec la phrase dictée. */
    fun userPrompt(transcript: String): String = """
        Phrase dictée : « ${transcript.trim()} »

        Rends le JSON des aliments.
    """.trimIndent()

    /**
     * Lit la réponse du modèle.
     *
     * Un aliment sans nom exploitable est écarté et **signalé** : le compter comme zéro
     * fausserait le total, et le faire disparaître en silence ferait croire que la phrase
     * a été comprise en entier.
     */
    fun parse(json: String): ExtractionResult {
        val root = extractJsonObject(json)
        val array = try {
            root.optJSONArray("foods")
        } catch (error: JSONException) {
            null
        } ?: throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "La réponse ne contient pas de liste d'aliments",
        )

        val foods = mutableListOf<DetectedFood>()
        val rejected = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH) {
                val raw = item.optString("raw_text").trim()
                rejected += raw.ifBlank { name.ifBlank { "aliment $index" } }
                continue
            }
            val rawText = item.optString("raw_text").trim().ifBlank { name }
            foods += DetectedFood(
                name = name,
                quantity = readQuantity(item.opt("quantity")),
                unit = UNIT_CODES[item.optString("unit").lowercase()] ?: MealUnit.UNKNOWN,
                rawText = rawText,
            )
        }
        return ExtractionResult(foods = foods, rejected = rejected)
    }

    /**
     * Lit une quantité sans jamais l'inventer.
     *
     * Hors bornes ou illisible : `null`, c'est-à-dire « la portion sera demandée ». Un
     * modèle qui rend 15 000 grammes de riz ne doit pas produire 15 000 grammes de riz.
     */
    private fun readQuantity(value: Any?): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!number.isFinite() || number <= 0.0 || number > MAX_QUANTITY) return null
        return number
    }

    /** Au-delà, ce n'est plus une portion mais une erreur de lecture. */
    private const val MAX_QUANTITY = 5_000.0

    private const val MAX_NAME_LENGTH = 80
}
