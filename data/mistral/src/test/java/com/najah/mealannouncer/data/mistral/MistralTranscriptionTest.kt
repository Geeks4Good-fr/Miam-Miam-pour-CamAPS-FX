package com.najah.mealannouncer.data.mistral

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.Base64

class MistralTranscriptionTest {

    private val audio = byteArrayOf(82, 73, 70, 70, 0, 1, 2, 3, 87, 65, 86, 69)

    private fun reply(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(text)}}}]}"""

    private fun transcriber(transport: HttpTransport) = HttpMistralTranscriber(transport)

    private fun failure(block: () -> Unit): MistralException =
        assertThrows(MistralException::class.java) { block() }

    @Test
    fun `envoie le son a OpenRouter avec la consigne de transcription`() {
        val transport = FakeTransport(HttpResponse(200, reply("deux biscuits")))
        transcriber(transport).transcribe("clef-123", TranscriptionRequest(audio))

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals("Bearer clef-123", request.headers["Authorization"])
        assertEquals("application/json", request.contentType)

        val body = JSONObject(request.bodyText())
        assertEquals(AI_TRANSCRIBE_MODEL, body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(TRANSCRIBE_INSTRUCTIONS, messages.getJSONObject(0).getString("content"))
        val part = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", part.getString("type"))
        assertEquals("wav", part.getJSONObject("input_audio").getString("format"))
        // L'audio part octet pour octet.
        val sent = Base64.getDecoder().decode(part.getJSONObject("input_audio").getString("data"))
        assertTrue(sent.contentEquals(audio))
    }

    @Test
    fun `rend le texte transcrit, sans espaces ni guillemets autour`() {
        val text = transcriber(FakeTransport(HttpResponse(200, reply("  \"deux biscuits Prince et un yaourt\"  "))))
            .transcribe("clef", TranscriptionRequest(audio))
        assertEquals("deux biscuits Prince et un yaourt", text)
    }

    @Test
    fun `un silence est signale comme tel, pas comme une panne`() {
        val error = failure {
            transcriber(FakeTransport(HttpResponse(200, reply("")))).transcribe("clef", TranscriptionRequest(audio))
        }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
        assertTrue(error.message!!.contains("Rien n'a été entendu"))
    }

    @Test
    fun `un enregistrement vide ne part pas sur le reseau`() {
        val transport = FakeTransport(HttpResponse(200, reply("ok")))
        val error = failure { transcriber(transport).transcribe("clef", TranscriptionRequest(ByteArray(0))) }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `clef vide — NOT_CONFIGURED et aucun appel`() {
        val transport = FakeTransport(HttpResponse(200, reply("ok")))
        val error = failure { transcriber(transport).transcribe("", TranscriptionRequest(audio)) }
        assertEquals(MistralErrorKind.NOT_CONFIGURED, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `un delai depasse est traduit`() {
        val error = failure {
            transcriber(ThrowingTransport(SocketTimeoutException("trop long")))
                .transcribe("clef", TranscriptionRequest(audio))
        }
        assertEquals(MistralErrorKind.TIMEOUT, error.kind)
    }
}
