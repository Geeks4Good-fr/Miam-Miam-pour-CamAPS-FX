package com.najah.mealannouncer.data.mistral

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

/** Le corps d'une requête, relu comme du texte : les tests JSON en ont besoin. */
internal fun HttpRequest.bodyText(): String =
    String(checkNotNull(body) { "Requête sans corps" }, Charsets.UTF_8)

/** Le corps d'une requête relu octet par octet, pour inspecter un envoi multipart. */
internal fun HttpRequest.bodyBytes(): ByteArray = checkNotNull(body) { "Requête sans corps" }

/** Cherche une suite d'octets dans une autre, comme le ferait un éditeur hexadécimal. */
internal fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    if (sequence.isEmpty() || sequence.size > size) return false
    for (start in 0..(size - sequence.size)) {
        var matches = true
        for (offset in sequence.indices) {
            if (this[start + offset] != sequence[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}
