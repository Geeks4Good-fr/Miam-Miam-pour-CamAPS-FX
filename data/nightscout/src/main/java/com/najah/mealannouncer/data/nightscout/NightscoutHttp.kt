package com.najah.mealannouncer.data.nightscout

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.inject.Inject

/** Requête HTTP minimale, indépendante de la bibliothèque utilisée. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val contentType: String? = null,
    /**
     * Corps en octets bruts, envoyé tel quel : le CSV de Glooko pour un Nightscout for CamAPS FX
     * (BOM et fins de ligne conservés, sinon l'empreinte de l'aperçu changerait). Prioritaire sur [body].
     */
    val bodyBytes: ByteArray? = null,
)

/** Réponse HTTP réduite au code et au corps texte. */
data class HttpResponse(
    val code: Int,
    val body: String,
)

/**
 * Unique porte de sortie réseau du module.
 *
 * L'abstraction sert aux tests : tout le reste (adresse, en-tête d'authentification, codes
 * d'erreur, lecture du JSON) se vérifie sans réseau et sans Android.
 */
fun interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * Transport réel, à base de [HttpURLConnection].
 *
 * Pas de bibliothèque HTTP : deux requêtes JSON par repas ne justifient ni OkHttp ni
 * Retrofit, et chaque dépendance en moins est une mise à jour de moins.
 */
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
            if (request.body != null || request.bodyBytes != null) doOutput = true
        }
        try {
            val bytes = request.bodyBytes ?: request.body?.toByteArray(Charsets.UTF_8)
            bytes?.let { payload -> connection.outputStream.use { it.write(payload) } }
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
        const val READ_TIMEOUT_MS = 15_000
    }
}

/** Traduit une panne de transport en échec classé, pour que l'appelant sache quoi faire. */
internal fun <T> guardTransport(block: () -> T): T = try {
    block()
} catch (timeout: SocketTimeoutException) {
    throw NightscoutException(
        kind = NightscoutErrorKind.TIMEOUT,
        message = "Délai dépassé en joignant Nightscout",
        cause = timeout,
    )
} catch (io: IOException) {
    throw NightscoutException(
        kind = NightscoutErrorKind.NETWORK,
        message = "Nightscout injoignable",
        cause = io,
    )
}
