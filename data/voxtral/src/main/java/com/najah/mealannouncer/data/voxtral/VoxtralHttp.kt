package com.najah.mealannouncer.data.voxtral

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject

/**
 * Requête HTTP minimale, indépendante de la bibliothèque utilisée.
 *
 * Même abstraction que `data:nightscout` : elle permet d'éprouver le client Voxtral avec un
 * faux transport, sans réseau et sans Android.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
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
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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
        // La première syllabe doit arriver vite ; au-delà, « feature:voice » bascule sur la voix Android.
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

/** Traduit une panne de transport en échec classé, pour que l'appelant sache quoi faire. */
internal fun <T> guardTransport(block: () -> T): T = try {
    block()
} catch (timeout: SocketTimeoutException) {
    throw VoxtralException(
        kind = VoxtralErrorKind.TIMEOUT,
        message = "Délai dépassé en joignant Voxtral",
        cause = timeout,
    )
} catch (io: IOException) {
    throw VoxtralException(
        kind = VoxtralErrorKind.NETWORK,
        message = "Voxtral injoignable",
        cause = io,
    )
}
