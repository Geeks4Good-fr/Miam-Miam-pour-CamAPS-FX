package com.najah.mealannouncer.data.mistral

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.inject.Inject

/**
 * Transcription de secours (quand la dictée sur le téléphone, Kroko, manque), par OpenRouter.
 * Voxtral Small : 0,9 s pour 8 s de dictée, essai du 10/11/2026 (Gemini Flash Lite : 2 s).
 */
const val AI_TRANSCRIBE_MODEL = "mistralai/voxtral-small-24b-2507"

/** Ce qu'on demande au modèle : le texte entendu, rien d'autre. */
internal const val TRANSCRIBE_INSTRUCTIONS =
    "Transcris mot pour mot la dictée, en français. Rends seulement le texte entendu, sans " +
        "guillemets, sans rien ajouter ni corriger. Si tu n'entends rien, rends une chaîne vide."

/**
 * Un enregistrement à transcrire.
 *
 * L'audio est gardé en mémoire le temps de l'envoi : une dictée de repas dure quelques
 * secondes, et un fichier temporaire sur le disque d'un téléphone n'apporte rien.
 */
class TranscriptionRequest(
    val audio: ByteArray,
    val fileName: String = "repas.wav",
    val mimeType: String = "audio/wav",
    /** Langue de la dictée : la préciser améliore nettement la reconnaissance. */
    val language: String? = "fr",
    val model: String = AI_TRANSCRIBE_MODEL,
)

/** Ce que sait faire Mistral pour nous : transformer une dictée en texte. */
interface MistralTranscriber {
    /**
     * Transcrit [request]. [apiKey] sort du Keystore à l'appel et n'est jamais journalisé.
     * Lève [MistralException] (déjà classée) en cas d'échec.
     */
    fun transcribe(apiKey: String, request: TranscriptionRequest): String
}

/** Un fichier d'un envoi `multipart/form-data`. */
class MultipartFile(
    val field: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Compose un corps `multipart/form-data`.
 *
 * Écrit à la main parce que le dépôt n'embarque aucune bibliothèque HTTP : le format est
 * simple, et le construire ici le rend vérifiable par un test JVM, octet par octet.
 */
internal fun multipartBody(
    boundary: String,
    fields: List<Pair<String, String>>,
    files: List<MultipartFile>,
): ByteArray {
    val out = ByteArrayOutputStream()
    fun writeText(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

    fields.forEach { (name, value) ->
        writeText("--$boundary\r\n")
        writeText("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        writeText(value)
        writeText("\r\n")
    }
    files.forEach { file ->
        writeText("--$boundary\r\n")
        writeText(
            "Content-Disposition: form-data; name=\"${file.field}\"; " +
                "filename=\"${file.fileName}\"\r\n",
        )
        writeText("Content-Type: ${file.mimeType}\r\n\r\n")
        out.write(file.bytes)
        writeText("\r\n")
    }
    writeText("--$boundary--\r\n")
    return out.toByteArray()
}

/** Client HTTP : la dictée est envoyée comme son (`input_audio`) à une complétion OpenRouter. */
class HttpMistralTranscriber @Inject constructor(
    private val transport: HttpTransport,
) : MistralTranscriber {

    override fun transcribe(apiKey: String, request: TranscriptionRequest): String {
        if (apiKey.isBlank()) {
            throw MistralException(MistralErrorKind.NOT_CONFIGURED, "Aucune clé OpenRouter enregistrée")
        }
        if (request.audio.isEmpty()) {
            throw MistralException(MistralErrorKind.INVALID_RESPONSE, "Enregistrement vide")
        }
        val format = request.fileName.substringAfterLast('.', "wav").lowercase()
        val audio = JSONObject()
            .put("type", "input_audio")
            .put(
                "input_audio",
                JSONObject().put("data", Base64.getEncoder().encodeToString(request.audio)).put("format", format),
            )
        val body = JSONObject()
            .put("model", request.model)
            .put("temperature", 0.0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", TRANSCRIBE_INSTRUCTIONS))
                    .put(JSONObject().put("role", "user").put("content", JSONArray().put(audio))),
            )
            .toString()
        val response = MistralTiming.measure("dictée", request.model) { guardTransport {
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
        ensureSuccess(response)
        return transcriptionText(response.body)
    }

    private fun transcriptionText(body: String): String {
        val text = try {
            JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty()
        } catch (error: JSONException) {
            throw MistralException(
                MistralErrorKind.INVALID_RESPONSE,
                "Réponse de transcription illisible",
                error,
            )
        }.trim().removeSurrounding("\"").removeSurrounding("«", "»").trim()
        // Une transcription vide n'est pas une panne : c'est un silence. Le dire ainsi
        // évite de faire croire à une erreur technique alors qu'il suffit de reparler.
        if (text.isBlank()) {
            throw MistralException(
                MistralErrorKind.INVALID_RESPONSE,
                "Rien n'a été entendu : parlez plus près du micro.",
            )
        }
        return text
    }
}
