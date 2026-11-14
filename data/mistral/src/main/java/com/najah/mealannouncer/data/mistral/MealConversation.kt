package com.najah.mealannouncer.data.mistral

import com.najah.mealannouncer.core.model.MealUnit
import org.json.JSONArray

/**
 * Un aliment tel que la conversation le comprend, à un moment donné.
 *
 * [id] reste le même d'un échange à l'autre : c'est ce qui permet de préciser un aliment
 * (« la tasse était un bol ») sans en créer un second. [grams] n'existe que si l'utilisateur a
 * dit une mesure de ménage (« une tasse », « une assiette ») ou a dit ne pas savoir : c'est alors
 * une **estimation de poids**, jamais une valeur de glucides, et [assumption] dit laquelle.
 */
data class ConversationFood(
    val id: Int,
    val name: String,
    val quantity: Double?,
    val unit: MealUnit,
    val grams: Double?,
    val assumption: String?,
    val rawText: String,
    /** Vrai quand la personne vient de dire que le produit retenu pour cet aliment n'est pas le bon. */
    val rejectFound: Boolean = false,
    /**
     * La marque, seulement quand elle change les glucides (biscuits, céréales, sodas…). `null` pour un
     * aliment de base (thé, sucre, pâtes nature, fruit) : il est chiffré par des valeurs de référence,
     * sans recherche sur Internet (11 s par recherche, mesuré le 29/10/2026).
     */
    val brand: String? = null,
)

/** Ce que le modèle rend à chaque échange : l'état complet du repas, et la suite à donner. */
data class ConversationReply(
    /** La liste **complète** des aliments du repas, pas seulement ce qui vient de changer. */
    val foods: List<ConversationFood>,
    /** La question à poser à voix haute, une seule ; `null` s'il n'y a rien à demander. */
    val question: String?,
    /** Faux quand la dernière phrase de l'utilisateur n'a pas été comprise. */
    val understood: Boolean,
    /** Vrai quand le modèle n'a plus rien à demander. */
    val done: Boolean,
    /** Vrai quand l'utilisateur demande d'arrêter ou d'annuler. */
    val cancel: Boolean,
    /** Aliments rendus sans nom exploitable : signalés, jamais comptés. */
    val rejected: List<String> = emptyList(),
)

/**
 * La conversation du repas : l'IA comprend, doute à voix haute, fait préciser.
 *
 * C'est le changement de nature par rapport à [MealExtraction] : au lieu d'une lecture unique
 * qui doit trancher, le modèle garde l'histoire du repas et **pose une question** quand il lui
 * manque quelque chose — comme le ferait un humain. Ce qui ne change pas : il ne donne **aucune**
 * valeur de glucides. Les glucides viennent d'Open Food Facts, du web ou d'une estimation
 * signalée, et c'est l'application qui calcule ; elle lui renvoie un bilan après chaque échange,
 * et c'est sur ce bilan qu'il décide de sa question suivante.
 */
object MealConversation {

    /** Au-delà, ce n'est plus un repas mais une erreur de lecture. */
    private const val MAX_FOODS = 12
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_QUANTITY = 5_000.0
    private const val MIN_GRAMS = 1.0
    private const val MAX_GRAMS = 3_000.0
    private const val MAX_QUESTION_LENGTH = 300

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
        Tu es l'assistant vocal d'une personne diabétique qui décrit, à l'oral, ce qu'elle mange
        ou boit. Tu dialogues avec elle pour COMPRENDRE son repas : quels aliments, et quelle
        quantité de chacun. Ses phrases sont dictées et parfois mal transcrites : devine le sens
        le plus probable, comme le ferait un humain.

        Réfléchis comme un diététicien attentif : une erreur de glucides fait injecter trop ou pas
        assez d'insuline. Avant de répondre, raisonne pas à pas dans « raisonnement » (ce que la
        personne a vraiment mangé ou bu, sous quelle forme, en quelle quantité).

        Ce que tu ne fais JAMAIS :
        - Tu ne donnes AUCUNE valeur de glucides, de calories ni d'insuline dans « foods ».
          L'application cherche les valeurs (base Open Food Facts, Internet), calcule, puis fait
          relire chaque ligne par un diététicien avant toute annonce.
        - Tu n'ajoutes pas un aliment que la personne n'a pas cité.
        - Tu n'inventes pas une quantité qu'elle n'a pas dite (voir « grams » plus bas).

        Les aliments (« foods ») :
        - Rends à chaque fois la liste COMPLÈTE du repas, avec un « id » stable : garde l'id d'un
          aliment déjà listé, prends un nouvel id pour un nouvel aliment, retire un aliment que la
          personne retire (« non, pas de pain »).
        - « name » : un nom descriptif, utile pour une recherche, en français, marque comprise
          s'il y en a une (« biscuits Prince tout choco », « lait chocolaté », « riz basmati »).
          Une marque inconnue n'est jamais une raison d'écarter un aliment.
        - Un plat composé (« gratin de courgettes ») est UN aliment. Mais une boisson et ce qu'on y
          AJOUTE sont DEUX aliments : « un thé avec trois sucres » → « thé infusé nature » (1, cup)
          + « sucre en morceaux » (3, unit, grams 16,5, « 1 morceau ≈ 5,5 g ») ; « un café au lait
          avec une cuillère de sucre » → « café au lait » + « sucre en poudre » (grams 5).
        - Le nom dit TOUJOURS la forme réellement consommée, car la base contient aussi les formes
          sèches, en poudre ou sucrées : « thé infusé nature » (jamais « thé » seul, qui trouve du thé
          en poudre sucré), « café noir », « pâtes cuites », « riz cuit », « Coca-Cola zéro »,
          « jus d'orange 100 % pur jus ».
        - « quantity » et « unit » : la quantité TELLE QU'ELLE EST DITE. unit vaut exactement l'un
          de : gram, milliliter, unit, portion, slice, cup, unknown. « deux biscuits » → 2, unit ;
          « 150 grammes » → 150, gram ; « une tasse » → 1, cup ; « une tranche » → 1, slice.
          « un » ou « une » devant un aliment qu'on compte (un yaourt, une pomme, un biscuit) vaut
          1, unit. Rien de dit → quantity null.
        - « grams » : ton estimation du poids TOTAL en grammes (en millilitres pour une boisson),
          SEULEMENT si la personne a dit une mesure de ménage (tasse, bol, verre, assiette,
          poignée, cuillère, part, tranche), un nombre d'articles (« deux pains au chocolat » →
          2 × 45 g = 90), ou si elle répond qu'elle ne sait pas. Prends le poids courant en France
          et écris ton hypothèse dans « assumption » (« une tasse ≈ 250 ml », « un pain au chocolat
          ≈ 45 g »). Repères : morceau de sucre ≈ 5,5 g ; cuillère à café de sucre ≈ 5 g ; tasse
          ≈ 250 ml ; mug ≈ 330 ml ; verre ≈ 200 ml ; canette = 330 ml ; tranche de pain ≈ 30 g ;
          baguette ≈ 250 g ; assiette de pâtes ou de riz cuits ≈ 250 g ; yaourt = 125 g ; pomme
          ≈ 150 g ; banane ≈ 120 g ; biscuit sec ≈ 8 à 12 g ; croissant ≈ 45 g. Ce n'est qu'un REPLI : l'application préfère la fiche de l'emballage quand elle
          la trouve. Sinon grams vaut null. Entre 1 et 3000.
        - « brand » : la marque ou l'enseigne, SEULEMENT si elle change les glucides et que la
          personne l'a dite (biscuits, céréales, viennoiseries, plats préparés, sodas, yaourts :
          « Prince », « Carrefour », « Chocapic »). Pour un thé, un café, du sucre, un fruit, un légume,
          des pâtes, du riz ou du pain nature, brand vaut null même si une marque est citée (« thé
          Lipton » → brand null). Sinon null.
        - « raw_text » : les mots de la personne qui ont donné cet aliment.
        - « reject_found » : true SEULEMENT si, dans son dernier message, la personne dit que le
          produit retenu par l'application pour cet aliment n'est pas le bon (« ce n'est pas ce
          produit », « pas cette marque », « non, c'est du pain Gourmet de Carrefour »). Mets alors
          le nom qu'elle donne dans « name », le plus précis possible (marque, enseigne, type).
          Sinon false.

        La conversation :
        - Les messages « Repas décrit : … » et « Réponse : … » sont les paroles de la personne.
          Une « Réponse » peut être suivie de « Produits retenus par l'application » : c'est ce
          qu'elle voit à l'écran (nom et photo), utile quand elle conteste un produit.
          Les messages « BILAN DE L'APPLICATION » disent ce que l'application a trouvé et calculé :
          appuie-toi dessus pour savoir ce qui manque vraiment.
        - S'il manque quelque chose d'important pour les glucides (une quantité, le type de pain,
          sucré ou non, une boisson « normale » ou « zéro »…), pose UNE question, courte, naturelle,
          au tutoiement, sur UN seul aliment, en proposant des choix concrets (« Ta tasse, c'était
          une petite tasse ou un grand bol ? »). Ne pose pas de question si la réponse ne
          change presque rien : on n'est pas au gramme près.
        - Si le bilan dit qu'aucune valeur n'a été trouvée pour un aliment, demande une précision
          (le type, la marque) ou renomme-le en un aliment courant proche.
        - Si la personne dit « je ne sais pas », prends une portion moyenne (grams + assumption)
          et n'insiste pas.
        - « understood » vaut false si la dernière phrase de la personne est incompréhensible ou
          hors sujet ; dans ce cas, garde la liste telle quelle et repose ta question autrement.
        - « done » vaut true quand tu n'as plus rien à demander, ou quand la personne dit que
          c'est tout (« c'est bon », « c'est tout »). Alors « question » vaut null.
        - « cancel » vaut true si la personne veut arrêter ou annuler.

        Tu réponds UNIQUEMENT par un objet JSON de cette forme (« raisonnement » d'abord) :
        {"raisonnement":"Thé infusé sans rien d'autre que du sucre ; trois morceaux classiques.","foods":[{"id":1,"name":"thé infusé nature","quantity":1,"unit":"cup","grams":250,"assumption":"une tasse ≈ 250 ml","raw_text":"un thé"},{"id":2,"name":"sucre en morceaux","quantity":3,"unit":"unit","grams":16.5,"assumption":"1 morceau ≈ 5,5 g","raw_text":"trois sucres"}],"question":null,"understood":true,"done":true,"cancel":false}
    """.trimIndent()

    /** Le premier message : la phrase qui décrit le repas. */
    fun firstMessage(phrase: String): String = "Repas décrit : « ${phrase.trim()} »"

    /**
     * Les messages suivants : la réponse, et — s'il y en a — les produits que l'application a
     * retenus (« #1 Pain de mie… (Maître Jean Pierre) »), pour qu'une contestation désigne le bon.
     */
    fun answerMessage(
        answer: String,
        retained: List<String> = emptyList(),
        reviewNote: String? = null,
    ): String = buildString {
        append("Réponse : « ").append(answer.trim()).append(" »")
        if (retained.isNotEmpty()) {
            append("\nProduits retenus par l'application : ").append(retained.joinToString(" ; "))
        }
        // La question vient de la relecture : le modèle doit savoir à quoi la personne répond.
        reviewNote?.let { append("\nVÉRIFICATION DU DIÉTÉTICIEN (la question venait de là) : ").append(it) }
    }

    /**
     * Lit la réponse du modèle sans rien lui céder.
     *
     * Un aliment sans nom est signalé ; une quantité ou un poids hors bornes devient `null`
     * (donc une question) sans emporter l'aliment ; deux aliments au même id gardent le premier.
     * Une question n'est retenue que si le modèle n'a pas déclaré avoir fini.
     */
    fun parse(json: String): ConversationReply {
        val root = extractJsonObject(json)
        val array: JSONArray = root.optJSONArray("foods") ?: throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "La réponse ne contient pas de liste d'aliments",
        )
        val foods = mutableListOf<ConversationFood>()
        val rejected = mutableListOf<String>()
        val seen = mutableSetOf<Int>()
        var nextId = 1
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val raw = item.optString("raw_text").trim()
            if (name.isEmpty() || name.length > MAX_NAME_LENGTH || foods.size >= MAX_FOODS) {
                rejected += raw.ifBlank { name.ifBlank { "aliment ${index + 1}" } }
                continue
            }
            var id = item.optInt("id", 0)
            if (id <= 0 || id in seen) {
                while (nextId in seen) nextId++
                id = nextId
            }
            seen += id
            foods += ConversationFood(
                id = id,
                name = name,
                quantity = readNumber(item.opt("quantity"), max = MAX_QUANTITY, min = 0.0),
                unit = UNIT_CODES[item.optString("unit").lowercase()] ?: MealUnit.UNKNOWN,
                grams = readNumber(item.opt("grams"), max = MAX_GRAMS, min = MIN_GRAMS),
                assumption = item.optString("assumption").trim()
                    .takeIf { it.isNotEmpty() && it != "null" },
                rawText = raw.ifBlank { name },
                rejectFound = item.optBoolean("reject_found", false),
                brand = item.optString("brand").trim().takeIf { it.isNotEmpty() && it != "null" && !item.isNull("brand") },
            )
        }
        val done = root.optBoolean("done", false)
        val question = root.optString("question").trim()
            .takeIf { it.isNotEmpty() && it != "null" && !done }
            ?.take(MAX_QUESTION_LENGTH)
        return ConversationReply(
            foods = foods,
            question = question,
            understood = root.optBoolean("understood", true),
            done = done,
            cancel = root.optBoolean("cancel", false),
            rejected = rejected,
        )
    }

    /** Un nombre lu tel quel, ou `null` s'il est absent, illisible ou hors bornes. */
    private fun readNumber(value: Any?, max: Double, min: Double): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        if (!number.isFinite() || number <= 0.0 || number < min || number > max) return null
        return number
    }
}
