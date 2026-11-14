package com.najah.mealannouncer.data.mistral

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject

/**
 * Requête HTTP minimale du module Mistral.
 *
 * Le corps est en octets, et non en texte : la transcription envoie un fichier audio en
 * `multipart/form-data`. C'est la seule raison pour laquelle ce module a son propre
 * transport plutôt que celui de `data:voxtral`, qui ne transporte que du texte.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
)

/** Réponse HTTP réduite au code et au corps texte. */
data class HttpResponse(
    val code: Int,
    val body: String,
)

/** Unique porte de sortie réseau du module : un test en fournit une fausse. */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/** Transport réel, sur `HttpURLConnection` — aucune dépendance tierce. */
class UrlConnectionTransport @Inject constructor() : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            request.contentType?.let { setRequestProperty("Content-Type", it) }
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (request.body != null) doOutput = true
        }
        try {
            request.body?.let { body ->
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(code = code, body = text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000

        // Une extraction de repas ou une transcription prend plus longtemps qu'une syllabe :
        // la marge est plus large que celle de Voxtral, sans devenir une attente sans fin.
        const val READ_TIMEOUT_MS = 45_000
    }
}

/** Traduit une panne de transport en échec classé, pour que l'appelant sache quoi faire. */
internal fun <T> guardTransport(block: () -> T): T = try {
    block()
} catch (timeout: SocketTimeoutException) {
    throw MistralException(MistralErrorKind.TIMEOUT, "Délai dépassé en joignant Mistral", timeout)
} catch (io: IOException) {
    throw MistralException(MistralErrorKind.NETWORK, "Mistral injoignable", io)
}

/**
 * Vérifie le code de la réponse et lève un échec déjà classé.
 *
 * Le corps est repris dans le message quand il explique le refus : sans lui, « clé
 * refusée » ne dit pas si la clé est fausse, expirée ou mal envoyée.
 */
internal fun ensureSuccess(response: HttpResponse) {
    when {
        response.code in 200..299 -> Unit

        response.code == 401 || response.code == 403 -> throw MistralException(
            MistralErrorKind.UNAUTHORIZED,
            "Mistral a refusé la clé (${response.code}) : ${response.body.trim().take(160)}",
        )

        response.code == 429 -> throw MistralException(
            MistralErrorKind.QUOTA,
            "Quota Mistral atteint (429) : réessayez dans un instant.",
        )

        response.code in 500..599 -> throw MistralException(
            MistralErrorKind.SERVER,
            "Mistral a renvoyé une erreur serveur (${response.code}).",
        )

        else -> throw MistralException(
            MistralErrorKind.UNEXPECTED,
            "Réponse inattendue de Mistral (${response.code}) : ${response.body.trim().take(160)}",
        )
    }
}
