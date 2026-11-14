package com.najah.mealannouncer.data.voxtral

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Test

class VoxtralClientTest {

    // base64("abc") -> octets 97,98,99. Contrat vérifié sur l'API réelle le 26/10/2026.
    private val audioBody = speechBody("YWJj")

    private fun api(transport: HttpTransport) = HttpVoxtralApi(transport)

    private fun failure(block: () -> Unit): VoxtralException =
        assertThrows(VoxtralException::class.java) { block() }

    @Test
    fun `construit la requete POST attendue`() {
        val transport = FakeTransport(HttpResponse(200, audioBody))
        api(transport).synthesize(
            apiKey = "clef-123",
            request = SpeechRequest(input = "Repas annoncé : 62 grammes de glucides."),
        )
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.mistral.ai/v1/audio/speech", request.url)
        assertEquals("Bearer clef-123", request.headers["Authorization"])
        assertEquals("application/json", request.contentType)
        val body = JSONObject(request.body!!)
        assertEquals("voxtral-mini-tts-3-0", body.getString("model"))
        assertEquals("Repas annoncé : 62 grammes de glucides.", body.getString("input"))
        assertEquals("voice_592", body.getString("voice_id"))
        assertEquals("mp3", body.getString("response_format"))
    }

    @Test
    fun `decode l'audio base64`() {
        val transport = FakeTransport(HttpResponse(200, audioBody))
        val audio = api(transport).synthesize("clef", SpeechRequest(input = "Bonjour."))
        assertArrayEquals(byteArrayOf(97, 98, 99), audio)
    }

    @Test
    fun `une voix choisie part dans voice_id`() {
        val transport = FakeTransport(HttpResponse(200, audioBody))
        api(transport).synthesize(
            "clef",
            SpeechRequest(input = "Bonjour.", voice = VoxtralVoice.CAMILLE),
        )
        assertEquals("voice_3141", JSONObject(transport.requests.single().body!!).getString("voice_id"))
    }

    @Test
    fun `clef vide — NOT_CONFIGURED et aucun appel reseau`() {
        val transport = FakeTransport(HttpResponse(200, audioBody))
        val error = failure { api(transport).synthesize("", SpeechRequest(input = "x")) }
        assertEquals(VoxtralErrorKind.NOT_CONFIGURED, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `401 — clef refusee`() {
        val error = failure {
            api(FakeTransport(HttpResponse(401, "Unauthorized"))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.UNAUTHORIZED, error.kind)
    }

    @Test
    fun `429 — quota`() {
        val error = failure {
            api(FakeTransport(HttpResponse(429, "Too Many Requests"))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.QUOTA, error.kind)
        assertTrue(error.kind.isRetryable)
    }

    @Test
    fun `503 — erreur serveur`() {
        val error = failure {
            api(FakeTransport(HttpResponse(503, "oops"))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.SERVER, error.kind)
    }

    @Test
    fun `code inattendu — UNEXPECTED`() {
        val error = failure {
            api(FakeTransport(HttpResponse(418, "teapot"))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.UNEXPECTED, error.kind)
    }

    @Test
    fun `audio absent — INVALID_RESPONSE`() {
        val error = failure {
            api(FakeTransport(HttpResponse(200, """{"autre":"champ"}"""))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `delai depasse — TIMEOUT`() {
        val error = failure {
            api(ThrowingTransport(SocketTimeoutException("lent"))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.TIMEOUT, error.kind)
    }

    @Test
    fun `reseau coupe — NETWORK`() {
        val error = failure {
            api(ThrowingTransport(IOException("coupé"))).synthesize("k", SpeechRequest(input = "x"))
        }
        assertEquals(VoxtralErrorKind.NETWORK, error.kind)
    }
}
