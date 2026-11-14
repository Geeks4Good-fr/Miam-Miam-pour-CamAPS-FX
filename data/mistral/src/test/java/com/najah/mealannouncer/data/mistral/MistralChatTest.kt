package com.najah.mealannouncer.data.mistral

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class MistralChatTest {

    private val reply = """{"choices":[{"message":{"role":"assistant","content":"{\"foods\":[]}"}}]}"""

    private fun chat(transport: HttpTransport) = HttpMistralChat(transport)

    private fun ask(user: String = "Phrase dictée : « rien »") =
        JsonAsk(system = "Tu réponds en JSON.", user = user)

    private fun failure(block: () -> Unit): MistralException =
        assertThrows(MistralException::class.java) { block() }

    @Test
    fun `construit la requete attendue`() {
        val transport = FakeTransport(HttpResponse(200, reply))
        chat(transport).askJson(apiKey = "clef-123", ask = ask())
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals("Miam-Miam pour CamAPS FX", request.headers["X-Title"])
        assertEquals("Bearer clef-123", request.headers["Authorization"])
        assertEquals("application/json", request.contentType)

        val body = JSONObject(request.bodyText())
        assertEquals("google/gemini-3.1-flash-lite", body.getString("model"))
        assertEquals(0.0, body.getDouble("temperature"), 0.0)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))

        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Tu réponds en JSON.", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("rien"))
    }

    @Test
    fun `sans photo le contenu reste une simple chaine`() {
        val transport = FakeTransport(HttpResponse(200, reply))
        chat(transport).askJson(apiKey = "clef", ask = ask())
        val content = JSONObject(transport.requests.single().bodyText())
            .getJSONArray("messages")
            .getJSONObject(1)
            .get("content")
        assertTrue("Le contenu doit rester une chaîne", content is String)
    }

    @Test
    fun `une photo part en morceaux texte et image`() {
        val transport = FakeTransport(HttpResponse(200, reply))
        chat(transport).askJson(
            apiKey = "clef",
            ask = ask(user = "Photo : une assiette.").copy(imageDataUri = "data:image/jpeg;base64,//8="),
        )
        val content = JSONObject(transport.requests.single().bodyText())
            .getJSONArray("messages")
            .getJSONObject(1)
            .getJSONArray("content")

        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("Photo : une assiette.", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        // Format OpenAI (OpenRouter) : un objet { url }.
        assertEquals(
            "data:image/jpeg;base64,//8=",
            content.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `rend le contenu du premier choix`() {
        val transport = FakeTransport(HttpResponse(200, reply))
        assertEquals("{\"foods\":[]}", chat(transport).askJson("clef", ask()))
    }

    @Test
    fun `clef vide — rien ne part sur le reseau`() {
        val transport = FakeTransport(HttpResponse(200, reply))
        val error = failure { chat(transport).askJson(apiKey = "   ", ask = ask()) }
        assertEquals(MistralErrorKind.NOT_CONFIGURED, error.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `un refus 401 porte le corps du serveur`() {
        val transport = FakeTransport(HttpResponse(401, """{"message":"Invalid API Key"}"""))
        val error = failure { chat(transport).askJson("clef", ask()) }
        assertEquals(MistralErrorKind.UNAUTHORIZED, error.kind)
        assertTrue(error.message!!.contains("Invalid API Key"))
    }

    @Test
    fun `un quota 429 est classe a part`() {
        val error = failure { chat(FakeTransport(HttpResponse(429, ""))).askJson("clef", ask()) }
        assertEquals(MistralErrorKind.QUOTA, error.kind)
    }

    @Test
    fun `une panne serveur est classee`() {
        val error = failure { chat(FakeTransport(HttpResponse(503, "indisponible"))).askJson("clef", ask()) }
        assertEquals(MistralErrorKind.SERVER, error.kind)
    }

    @Test
    fun `une reponse sans choix est refusee`() {
        val error = failure { chat(FakeTransport(HttpResponse(200, """{"choices":[]}"""))).askJson("clef", ask()) }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `un contenu vide est refuse`() {
        val body = """{"choices":[{"message":{"content":"   "}}]}"""
        val error = failure { chat(FakeTransport(HttpResponse(200, body))).askJson("clef", ask()) }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `un delai depasse est traduit`() {
        val error = failure {
            chat(ThrowingTransport(SocketTimeoutException("trop long"))).askJson("clef", ask())
        }
        assertEquals(MistralErrorKind.TIMEOUT, error.kind)
    }

    @Test
    fun `une panne reseau est traduite`() {
        val error = failure { chat(ThrowingTransport(IOException("coupé"))).askJson("clef", ask()) }
        assertEquals(MistralErrorKind.NETWORK, error.kind)
    }

    @Test
    fun `extrait le JSON d'une reponse entouree de markdown`() {
        val wrapped = "Voici :\n```json\n{\"foods\":[]}\n```\nBonne journée."
        assertEquals(0, extractJsonObject(wrapped).getJSONArray("foods").length())
    }

    @Test
    fun `une reponse sans JSON est refusee`() {
        val error = failure { extractJsonObject("je ne sais pas") }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }
}
