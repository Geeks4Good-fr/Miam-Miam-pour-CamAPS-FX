package com.najah.mealannouncer.data.voxtral

/** Faux transport : rend des réponses préparées et garde la trace des requêtes reçues. */
internal class FakeTransport(
    response: HttpResponse,
    queued: List<HttpResponse> = emptyList(),
) : HttpTransport {

    private val queue = ArrayDeque(listOf(response) + queued)

    val requests = mutableListOf<HttpRequest>()

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

/** Transport qui lève toujours : pour éprouver la traduction des pannes réseau. */
internal class ThrowingTransport(private val error: Throwable) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        throw error
    }
}

/** Corps JSON d'une réponse de synthèse, l'audio encodé en base64. */
internal fun speechBody(base64Audio: String): String = """{"audio_data":"$base64Audio"}"""
