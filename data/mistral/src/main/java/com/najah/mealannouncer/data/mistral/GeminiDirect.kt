package com.najah.mealannouncer.data.mistral

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/*
 * Mode « Gemini direct » (demande du 23/11/2026, `docs/DECISIONS.md` § 50).
 *
 * Le banc du 23/11 a montré que Gemini, interrogé comme sur gemini.google.com (les photos et les
 * phrases telles quelles, sans consigne), calcule un plateau d'hôpital à 57–73 g quand le site en
 * donne 54–67 g ; c'est la chaîne de l'appli (découpage en lignes, Open Food Facts, relecture par
 * un autre modèle) qui dégradait le résultat. Ici, rien ne s'interpose : la conversation part telle
 * quelle, et un second appel, court, se contente de LIRE la réponse pour en tirer le total et la
 * phrase à dire.
 */

/** Le modèle qui mène la conversation en mode direct : le plus proche de Gemini web au banc du 23/11. */
const val AI_DIRECT_MODEL = "google/gemini-4-hydrogen"

/**
 * Le contexte permanent de la conversation directe, comme les « infos enregistrées » de Gemini :
 * qui parle, et ce qu'on attend de chaque message (un calcul de glucides, des questions si besoin).
 */
const val DIRECT_CONTEXT =
    "Je suis diabétique de type 1. Quand je te décris, te nomme ou te montre en photo ce que je mange " +
        "ou bois, calcule mes glucides : détaille aliment par aliment (portion estimée et glucides) puis " +
        "donne le total. S'il y a un couvert ou un objet de taille connue sur la photo, sers-t'en pour " +
        "estimer les portions. S'il te manque une information pour être précis (quantité, portion, part " +
        "du paquet, marque, cuisson), pose-moi la question."

/** Le modèle qui lit la réponse pour en extraire le total et la phrase à dire : rapide et peu cher. */
const val AI_DIRECT_READER_MODEL = "google/gemini-3.1-flash-lite"

/** Une photo JPEG en data URI, telle que l'API l'attend. */
fun jpegDataUri(jpeg: ByteArray): String =
    "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(jpeg)

/** Un message de la conversation directe : du texte, et éventuellement des photos (data URI JPEG). */
data class DirectMessage(
    val role: ChatRole,
    val text: String,
    /** Les photos jointes, dans l'ordre (plusieurs photos d'un même repas partent ensemble). */
    val images: List<String> = emptyList(),
)

/** Un produit industriel (de marque, emballé) que Gemini a vu sur une photo ou entendu nommer. */
data class DirectProduct(
    val name: String,
    val brand: String?,
    /** Code-barres lu sur la photo, s'il est lisible. */
    val barcode: String?,
)

/**
 * Ce que l'appli retient d'une réponse de Gemini : le total, la fourchette, ce qu'il faut dire, et
 * la question posée s'il y en a une. Rien n'est recalculé : ce sont les chiffres de la réponse.
 */
data class DirectSummary(
    /** Le total de glucides retenu par Gemini pour ce qui est mangé, `null` s'il n'en donne pas. */
    val totalGrams: Double?,
    val minGrams: Double?,
    val maxGrams: Double?,
    /** Une à trois phrases à lire à voix haute, sans tableau ni mise en forme. */
    val spoken: String,
    /** La question que Gemini pose à l'utilisateur, en une phrase, `null` s'il n'en pose pas. */
    val question: String?,
    /** Le repas en quelques mots (« carottes râpées, bœuf en daube, pommes de terre, pain ») ; `null` si inconnu. */
    val meal: String? = null,
    /**
     * Le détail aliment par aliment, court (« carottes râpées 7 g, pommes de terre 30 g, pain 25 g ») :
     * c'est ce qui part au Carnet Glooko et dans la note Nightscout. `null` si la réponse n'en donne pas.
     */
    val detail: String? = null,
    /**
     * Vrai quand le total couvre tout le repas : rien n'est annoncé comme manquant (« le plat chaud
     * n'est pas encore sur la photo »). Seul un total complet peut partir seul vers CamAPS.
     */
    val complete: Boolean = false,
    /** Les produits industriels du repas : l'appli cherche leur fiche Open Food Facts. */
    val products: List<DirectProduct> = emptyList(),
    /**
     * Les aliments sans marque dont la quantité reste estimée (riz, purée, salade…) : l'appli
     * propose leurs portions en images.
     */
    val plainFoods: List<String> = emptyList(),
    /** Codes-barres des fiches Open Food Facts que Gemini dit avoir retenues après comparaison. */
    val chosenBarcodes: List<String> = emptyList(),
)

/** La conversation directe avec Gemini, et la lecture de ses réponses. */
interface GeminiDirectChat {

    /** Envoie la conversation [messages] (le dernier est celui de l'utilisateur) et rend la réponse brute. */
    fun reply(apiKey: String, messages: List<DirectMessage>, model: String = AI_DIRECT_MODEL): String

    /**
     * Lit [answer] (avec le message [userText] qui l'a appelée, pour les marques dites) et en tire le
     * total, la phrase à dire, la question éventuelle et les produits.
     */
    fun summarize(apiKey: String, answer: String, userText: String = ""): DirectSummary
}

class HttpGeminiDirectChat @Inject constructor(
    private val transport: HttpTransport,
    private val chat: MistralChat,
) : GeminiDirectChat {

    override fun reply(apiKey: String, messages: List<DirectMessage>, model: String): String {
        if (apiKey.isBlank()) {
            throw MistralException(MistralErrorKind.NOT_CONFIGURED, "Aucune clé OpenRouter enregistrée")
        }
        // Une seule consigne, courte, l'équivalent des « infos enregistrées » du site de Gemini : sans
        // elle, une phrase seule (« j'ai bu une tasse de lait chocolaté ») ouvrait une simple
        // discussion au lieu d'un calcul (essai du 23/11/2026). Le reste part tel quel.
        val array = JSONArray().put(JSONObject().put("role", "system").put("content", DIRECT_CONTEXT))
        messages.forEach { message -> array.put(toJson(message)) }
        val body = JSONObject()
            .put("model", model)
            .put("messages", array)
            .toString()
        val started = System.currentTimeMillis()
        val response = MistralTiming.measure("direct", model) { guardTransport {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = AI_CHAT_URL,
                    headers = mapOf("Authorization" to "Bearer $apiKey") + OPENROUTER_APP_HEADERS,
                    body = body.toByteArray(Charsets.UTF_8),
                    contentType = "application/json",
                ),
            )
        } }
        MistralTiming.note("  ↳ $model ${System.currentTimeMillis() - started} ms${MistralTiming.usage(response.body)}")
        ensureSuccess(response)
        return answerText(response.body)
    }

    override fun summarize(apiKey: String, answer: String, userText: String): DirectSummary {
        val input = if (userText.isBlank()) {
            answer
        } else {
            "MESSAGE DE LA PERSONNE :\n${userText.take(2_000)}\n\nRÉPONSE DE L'ASSISTANT :\n$answer"
        }
        val raw = chat.askJson(
            apiKey,
            JsonAsk(system = READER_INSTRUCTIONS, user = input, model = AI_DIRECT_READER_MODEL),
        )
        return parseSummary(raw, answer)
    }

    private fun toJson(message: DirectMessage): JSONObject {
        val content: Any = if (message.images.isEmpty()) {
            message.text
        } else {
            // Les photos d'abord, puis le texte : l'ordre d'un envoi sur le site de Gemini.
            JSONArray().apply {
                message.images.forEach { image ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
                }
                put(JSONObject().put("type", "text").put("text", message.text))
            }
        }
        return JSONObject().put("role", message.role.code).put("content", content)
    }

    private fun answerText(body: String): String {
        val content = try {
            JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty()
        } catch (error: JSONException) {
            throw MistralException(MistralErrorKind.INVALID_RESPONSE, "Réponse de Gemini illisible", error)
        }
        if (content.isBlank()) {
            throw MistralException(MistralErrorKind.INVALID_RESPONSE, "Gemini n'a rien répondu")
        }
        return content.trim()
    }

    companion object {
        internal val READER_INSTRUCTIONS = """
            Tu lis la réponse d'un assistant qui calcule les glucides d'un repas pour une personne
            diabétique de type 1 (précédée, s'il est fourni, du message de la personne). Tu ne
            recalcules RIEN et tu n'inventes AUCUN chiffre : tu recopies ceux de la réponse. Garde le
            tutoiement ou le vouvoiement de la réponse.
            Rends un objet JSON :
            - "total_g" : le total de glucides que la réponse retient pour ce que la personne mange
              (la valeur moyenne ou centrale si elle donne une fourchette ; si elle propose plusieurs
              cas, celui qu'elle présente comme le repas complet). null si la réponse ne donne aucun total.
            - "min_g", "max_g" : les bornes de la fourchette du total, null si aucune.
            - "a_dire" : une à trois phrases courtes et naturelles, en français, à lire à voix haute :
              le total, puis les principaux aliments avec leurs glucides. Pas de tableau, pas de
              symbole, pas de markdown, les nombres en chiffres suivis de « grammes ». Sans la question.
            - "question" : la ou les questions que la réponse pose à la personne pour affiner le calcul
              (quantité mangée, portion, part du paquet, grammes, centilitres, cuisson…), regroupées
              en une ou deux phrases courtes à dire à voix haute ; null si elle n'en pose aucune.
              Une simple formule de politesse finale (« n'hésite pas si… ») n'est pas une question.
            - "produits" : liste des produits INDUSTRIELS emballés ou de marque (biscuits, yaourt,
              soda, plat préparé, fromage en portion…) vus, ou nommés par la personne ou la réponse, chacun
              {"nom": "...", "marque": "..." ou null, "code_barres": "chiffres lus" ou null}. [] si aucun.
              Pas les aliments faits maison ni les fruits et légumes en vrac.
            - "aliments_sans_marque" : noms courts des aliments SANS marque dont la quantité est
              estimée (riz, purée, pâtes, pommes de terre, salade, pain…), [] si aucun.
            - "fiches_retenues" : codes-barres des fiches Open Food Facts que la réponse dit avoir
              retenues après comparaison des photos, [] si aucune.
            - "repas" : les aliments du repas en quelques mots, séparés par des virgules (60 caractères
              au plus), null si aucun.
            - "detail" : chaque aliment avec sa portion et ses glucides tels que la réponse les donne,
              séparés par des virgules (250 caractères au plus), par exemple « carottes râpées 90 g :
              7 g, pommes de terre 4 pièces 175 g : 30 g, pain 45 g : 25 g ». null si aucun.
            - "complet" : true seulement si le total repose sur des aliments vus ou décrits par la
              personne. false si une partie du total est SUPPOSÉE (un plat « non visible », « absent
              de la photo », « indiqué sur le ticket », compté avec une portion standard), si la
              réponse sépare plusieurs cas (« ce qui est visible » / « si vous mangez tout »), si un
              élément reste à peser ou à préciser, ou si elle pose une question.
              Dans ce cas, "a_dire" le signale en une phrase (par exemple : « le plat chaud n'est pas
              sur la photo, je l'ai compté avec une portion standard »).
        """.trimIndent()

        /** Lit le JSON du lecteur ; s'il est illisible, la réponse elle-même sert de phrase, sans total. */
        internal fun parseSummary(raw: String, answer: String): DirectSummary {
            val json = runCatching { extractJsonObject(raw) }.getOrNull()
                ?: return DirectSummary(null, null, null, spokenFallback(answer), null)
            fun grams(name: String): Double? =
                if (json.isNull(name)) null else json.optDouble(name).takeIf { it.isFinite() && it >= 0.0 && it <= 1000.0 }
            val spoken = json.optString("a_dire").trim().takeIf { it.isNotEmpty() && it != "null" }
                ?: spokenFallback(answer)
            fun text(name: String): String? =
                if (json.isNull(name)) null else json.optString(name).trim().takeIf { it.isNotEmpty() && it != "null" }
            val question = text("question")
            return DirectSummary(
                totalGrams = grams("total_g"),
                minGrams = grams("min_g"),
                maxGrams = grams("max_g"),
                spoken = spoken,
                question = question,
                meal = text("repas")?.take(80),
                detail = text("detail")?.take(300),
                complete = json.optBoolean("complet", false) && question == null,
                products = json.optJSONArray("produits")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        fun field(key: String) = if (item.isNull(key)) null else item.optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
                        val name = field("nom") ?: return@mapNotNull null
                        DirectProduct(
                            name = name.take(80),
                            brand = field("marque")?.take(60),
                            barcode = field("code_barres")?.filter { it.isDigit() }?.takeIf { it.length in 8..14 },
                        )
                    }.take(MAX_PRODUCTS)
                }.orEmpty(),
                plainFoods = strings(json, "aliments_sans_marque").take(MAX_PLAIN_FOODS),
                chosenBarcodes = strings(json, "fiches_retenues").map { it.filter(Char::isDigit) }.filter { it.length in 8..14 },
            )
        }

        /** Au plus 3 produits par réponse : au-delà, la recherche Open Food Facts deviendrait trop longue. */
        const val MAX_PRODUCTS = 3
        const val MAX_PLAIN_FOODS = 6

        private fun strings(json: JSONObject, key: String): List<String> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { s -> s.isNotEmpty() && s != "null" } }
        }

        /** Les premières phrases de la réponse, débarrassées de la mise en forme. */
        internal fun spokenFallback(answer: String): String =
            plainText(answer).split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ").take(300)

        /** Le texte de la réponse sans markdown : titres, gras, puces et tableaux deviennent du texte. */
        fun plainText(answer: String): String = answer.lines()
            .filterNot { it.trim().matches(Regex("^\\|?\\s*:?-{2,}.*")) || it.trim() == "---" }
            .joinToString("\n") { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^[*+-]\\s+"), "• ")
                    .replace(Regex("^\\|(.*)\\|$")) { m -> m.groupValues[1].split('|').joinToString(" · ") { it.trim() } }
                    .replace("**", "")
                    .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+?)\\*(?![\\w*])"), "$1")
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}
