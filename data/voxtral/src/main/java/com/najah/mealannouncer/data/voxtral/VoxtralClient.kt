package com.najah.mealannouncer.data.voxtral

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64
import javax.inject.Inject

/** Modèle de synthèse retenu (`docs/VOXTRAL.md` § 2.1). */
const val VOXTRAL_TTS_MODEL = "voxtral-mini-tts-3-0"

/** Point d'entrée de l'API audio de Mistral. */
internal const val VOXTRAL_SPEECH_URL = "https://api.mistral.ai/v1/audio/speech"

/**
 * Une phrase à faire dire, et comment.
 *
 * [input] est le texte lu tel quel. Il ne doit contenir **aucune donnée de glycémie** : seuls
 * les glucides du repas, déjà une donnée de santé, dont l'envoi est un choix explicite
 * (`docs/VOXTRAL.md` § 6).
 */
data class SpeechRequest(
    val input: String,
    val voice: VoxtralVoice = VoxtralVoice.DEFAULT,
    val format: VoxtralAudioFormat = VoxtralAudioFormat.DEFAULT,
    val model: String = VOXTRAL_TTS_MODEL,
)

/** Ce que sait faire Voxtral pour nous : transformer une phrase en audio. */
interface VoxtralApi {
    /**
     * Fabrique l'audio de [request]. [apiKey] sort du Keystore à l'appel et n'est jamais
     * journalisé. Lève [VoxtralException] (déjà classée) en cas d'échec ; l'appelant bascule
     * alors sur la voix Android.
     */
    fun synthesize(apiKey: String, request: SpeechRequest): ByteArray
}

/**
 * Client HTTP de Voxtral.
 *
 * L'authentification tient en un en-tête `Authorization: Bearer <clé>`. La réponse du mode
 * simple est un JSON qui porte l'audio encodé en base64 dans `audio_data` ; on le décode en
 * octets, prêts à être écrits dans un fichier et joués.
 */
class HttpVoxtralApi @Inject constructor(
    private val transport: HttpTransport,
) : VoxtralApi {

    override fun synthesize(apiKey: String, request: SpeechRequest): ByteArray {
        if (apiKey.isBlank()) {
            throw VoxtralException(VoxtralErrorKind.NOT_CONFIGURED, "Aucune clé Voxtral enregistrée")
        }
        val body = JSONObject()
            .put("model", request.model)
            .put("input", request.input)
            .put("voice_id", request.voice.id)
            .put("response_format", request.format.wire)
            .toString()

        val response = guardTransport {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = VOXTRAL_SPEECH_URL,
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    body = body,
                    contentType = "application/json",
                ),
            )
        }
        ensureSuccess(response)
        return decodeAudio(response.body)
    }

    private fun decodeAudio(body: String): ByteArray {
        val base64 = try {
            JSONObject(body).optString("audio_data", "")
        } catch (error: JSONException) {
            throw VoxtralException(
                VoxtralErrorKind.INVALID_RESPONSE,
                "Réponse Voxtral illisible",
                error,
            )
        }
        if (base64.isBlank()) {
            throw VoxtralException(VoxtralErrorKind.INVALID_RESPONSE, "Audio absent de la réponse Voxtral")
        }
        return try {
            Base64.getDecoder().decode(base64)
        } catch (error: IllegalArgumentException) {
            throw VoxtralException(
                VoxtralErrorKind.INVALID_RESPONSE,
                "Audio Voxtral non décodable",
                error,
            )
        }
    }

    private fun ensureSuccess(response: HttpResponse) {
        when {
            response.code in 200..299 -> Unit
            response.code == 401 || response.code == 403 -> throw VoxtralException(
                VoxtralErrorKind.UNAUTHORIZED,
                // Le corps dit *pourquoi* (clé inconnue, en-tête absent…), sans jamais contenir
                // la clé : il vaut mieux le montrer que le perdre.
                "Voxtral a refusé la clé (${response.code}) : ${response.body.trim().take(160)}",
            )
            response.code == 429 -> throw VoxtralException(
                VoxtralErrorKind.QUOTA,
                "Quota Voxtral atteint (429)",
            )
            response.code in 500..599 -> throw VoxtralException(
                VoxtralErrorKind.SERVER,
                "Voxtral a renvoyé une erreur serveur (${response.code})",
            )
            else -> throw VoxtralException(
                VoxtralErrorKind.UNEXPECTED,
                "Réponse inattendue de Voxtral (${response.code})",
            )
        }
    }
}
