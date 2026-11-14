package com.najah.mealannouncer.data.mistral

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

/**
 * Recherche sur Internet, par OpenRouter (outil `web` sur `chat/completions`).
 *
 * Rarement utilisée depuis le 10/11/2026 : la conversation ne la lance plus que pour le poids
 * d'une pièce quand le modèle n'en a donné aucune estimation. Le nom de l'interface reste
 * historique (Mistral faisait cette recherche jusqu'au 10/11/2026).
 */
interface MistralWebSearch {
    /**
     * Pose [question] à un modèle outillé de recherche web, avec [instructions] pour
     * cadrer la réponse. Rend le texte de la réponse du modèle.
     */
    fun ask(apiKey: String, question: String, instructions: String): String
}

/** Client HTTP : complétion OpenRouter avec l'outil de recherche web. */
class HttpMistralWebSearch @Inject constructor(
    private val transport: HttpTransport,
) : MistralWebSearch {

    override fun ask(apiKey: String, question: String, instructions: String): String {
        if (apiKey.isBlank()) {
            throw MistralException(MistralErrorKind.NOT_CONFIGURED, "Aucune clé OpenRouter enregistrée")
        }
        val body = JSONObject()
            .put("model", AI_WEB_MODEL)
            .put("temperature", 0.0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instructions))
                    .put(JSONObject().put("role", "user").put("content", question)),
            )
            // Cinq résultats suffisent pour une fiche de produit ; chacun est facturé.
            .put("plugins", JSONArray().put(JSONObject().put("id", "web").put("max_results", WEB_RESULTS)))
            .toString()

        val response = MistralTiming.measure("web", AI_WEB_MODEL) { guardTransport {
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
        MistralTiming.note("  ↳ web${MistralTiming.usage(response.body)}")
        ensureSuccess(response)
        return answer(response.body)
    }

    private fun answer(body: String): String {
        val content = try {
            JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        } catch (error: JSONException) {
            throw MistralException(MistralErrorKind.INVALID_RESPONSE, "Réponse de recherche illisible", error)
        }
        val text = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val chunk = content.optJSONObject(index) ?: continue
                    if (chunk.optString("type") == "text") append(chunk.optString("text"))
                }
            }
            else -> null
        }
        return text?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw MistralException(MistralErrorKind.INVALID_RESPONSE, "La recherche n'a rendu aucune réponse")
    }

    private companion object {
        const val WEB_RESULTS = 5
    }
}
