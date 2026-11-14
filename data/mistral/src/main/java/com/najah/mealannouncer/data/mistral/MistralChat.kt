package com.najah.mealannouncer.data.mistral

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/*
 * Toutes les IA passent par OpenRouter, avec UNE seule clé (décision du 10/11/2026,
 * `docs/DECISIONS.md` § 48) : un nouvel utilisateur n'a qu'un compte à ouvrir, et l'appli choisit
 * pour chaque rôle le meilleur modèle du moment. Choix fondés sur le banc du 10/11/2026
 * (24 photos d'assiettes dont 14 pesées, conversations, relectures, estimations, portions).
 */

/**
 * Conversation du repas, estimations pour 100 g. Gemini 3.1 Flash Lite : 24/24 conversations
 * justes en 1,3 s (Mistral Large : 24/24 en 3,6 s), 34/36 estimations, trois fois moins cher.
 */
const val AI_CHAT_MODEL = "google/gemini-3.1-flash-lite"

/**
 * Relecture des glucides : un modèle d'une AUTRE famille que celui qui a chiffré, pour ne pas
 * reproduire ses erreurs. Mistral Medium 3.1 : 19/20 relectures justes en 1,9 s.
 */
const val AI_REVIEW_MODEL = "mistralai/mistral-medium-3.1"

/**
 * Photos d'assiette et d'emballage. Gemini 3.1 Flash Lite : 11,7 g d'erreur moyenne de glucides
 * sur 14 assiettes pesées (Mistral Medium : 18 à 19 g), 1,1 s par photo, 0,54 $ les 1 000.
 */
const val AI_VISION_MODEL = "google/gemini-3.1-flash-lite"

/**
 * Conversion d'une portion dite (« trois biscuits », « un tiers »). Mistral Medium 3.1 : 16/16 ;
 * Gemini Flash Lite supposait « un tiers du paquet » au lieu de demander « un tiers de quoi ? ».
 */
const val AI_PORTION_MODEL = "mistralai/mistral-medium-3.1"

/** Recherche sur Internet (rare) : le modèle de conversation, avec l'outil web d'OpenRouter. */
const val AI_WEB_MODEL = "google/gemini-3.1-flash-lite"

/** Point d'entrée unique des complétions (texte, image, son). */
internal const val AI_CHAT_URL = "https://openrouter.ai/api/v1/chat/completions"

/** En-têtes d'identification demandés par OpenRouter (classement des applis, aucune donnée). */
internal val OPENROUTER_APP_HEADERS = mapOf(
    "HTTP-Referer" to "https://github.com/Najah69/Miam-Miam-pour-CamAPS-FX",
    "X-Title" to "Miam-Miam pour CamAPS FX",
)

/** Un échange déjà passé d'une conversation : ce que l'utilisateur a dit, ou ce que le modèle a rendu. */
data class ChatTurn(
    val role: ChatRole,
    val content: String,
)

/** Qui parle dans un [ChatTurn] — les deux rôles de l'API, pas plus. */
enum class ChatRole(val code: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

/** Une question adressée au modèle, avec la consigne qui encadre sa réponse. */
data class JsonAsk(
    val system: String,
    val user: String,
    val temperature: Double = 0.0,
    /**
     * Une photo jointe à la question, déjà encodée en data URI JPEG
     * (`data:image/jpeg;base64,…`). `null` pour une question de texte seul.
     */
    val imageDataUri: String? = null,
    /**
     * Les échanges précédents, dans l'ordre, placés entre la consigne et [user] : c'est ce qui
     * donne une mémoire au modèle. Vide pour une question isolée.
     */
    val history: List<ChatTurn> = emptyList(),
    /** Le modèle qui répond (identifiant OpenRouter) : [AI_CHAT_MODEL] par défaut. */
    val model: String = AI_CHAT_MODEL,
)

/**
 * Le cerveau : transforme un texte français en JSON structuré.
 *
 * L'interface ne rend que du texte : c'est volontaire. Le module ne décide pas ce qu'un
 * repas contient ; il rend la réponse du modèle à qui saura la valider.
 */
interface MistralChat {
    /**
     * Envoie [ask] et rend le contenu du premier choix, supposé être un objet JSON.
     * Lève [MistralException] (déjà classée) en cas d'échec.
     */
    fun askJson(apiKey: String, ask: JsonAsk): String
}

/** Client HTTP du point d'entrée `chat/completions`. */
class HttpMistralChat @Inject constructor(
    private val transport: HttpTransport,
) : MistralChat {

    override fun askJson(apiKey: String, ask: JsonAsk): String {
        if (apiKey.isBlank()) {
            throw MistralException(MistralErrorKind.NOT_CONFIGURED, "Aucune clé OpenRouter enregistrée")
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", ask.system))
        ask.history.forEach { turn ->
            messages.put(JSONObject().put("role", turn.role.code).put("content", turn.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent(ask)))
        val body = JSONObject()
            .put("model", ask.model)
            .put("temperature", ask.temperature)
            .put("messages", messages)
            // Mode JSON : le modèle est tenu de rendre un objet. La forme, elle, est vérifiée
            // par l'appelant — un schéma imposé au serveur ne dispenserait pas de la vérifier.
            .put("response_format", JSONObject().put("type", "json_object"))
            .toString()

        val started = System.currentTimeMillis()
        val response = MistralTiming.measure("chat", ask.model) { guardTransport {
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
        MistralTiming.note(
            "  ↳ ${ask.model} ${System.currentTimeMillis() - started} ms${MistralTiming.usage(response.body)}",
        )
        ensureSuccess(response)
        return firstChoiceContent(response.body)
    }

    /**
     * Le contenu du message utilisateur : du texte, ou du texte **et** une photo.
     *
     * L'API attend un tableau de morceaux dans ce second cas, et la photo sous la forme
     * `image_url: {url: …}` (format OpenAI, celui d'OpenRouter) — une data URI ici.
     */
    private fun userContent(ask: JsonAsk): Any {
        val image = ask.imageDataUri ?: return ask.user
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", ask.user))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
    }

    private fun firstChoiceContent(body: String): String {
        val content = try {
            JSONObject(body)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        } catch (error: JSONException) {
            throw MistralException(
                MistralErrorKind.INVALID_RESPONSE,
                "Réponse Mistral illisible",
                error,
            )
        }
        if (content.isBlank()) {
            throw MistralException(
                MistralErrorKind.INVALID_RESPONSE,
                "Mistral n'a rien répondu",
            )
        }
        return content
    }
}

/**
 * Extrait l'objet JSON d'une réponse de modèle.
 *
 * Les modèles encadrent parfois leur JSON de texte ou d'un bloc Markdown : on isole la
 * première accolade ouvrante et la dernière fermante, puis on laisse `org.json` valider.
 */
internal fun extractJsonObject(text: String): JSONObject {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) {
        throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "Aucun objet JSON dans la réponse de Mistral",
        )
    }
    return try {
        JSONObject(text.substring(start, end + 1))
    } catch (error: JSONException) {
        throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "JSON illisible dans la réponse de Mistral",
            error,
        )
    }
}
