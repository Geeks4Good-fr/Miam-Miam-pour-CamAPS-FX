package com.najah.mealannouncer.data.mistral

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MistralWebSearchTest {

    private fun answer(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":${JSONObject.quote(text)}}}]}"""

    private fun search(transport: HttpTransport) = HttpMistralWebSearch(transport)

    private fun failure(block: () -> Unit): MistralException =
        assertThrows(MistralException::class.java) { block() }

    @Test
    fun `demande l'outil web d'OpenRouter avec la consigne`() {
        val transport = FakeTransport(HttpResponse(200, answer("{}")))
        search(transport).ask(
            apiKey = "clef-123",
            question = "Combien de glucides dans le riz basmati ?",
            instructions = "Réponds en JSON.",
        )
        val request = transport.requests.single()
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url)
        assertEquals("Bearer clef-123", request.headers["Authorization"])

        val body = JSONObject(request.bodyText())
        assertEquals(AI_WEB_MODEL, body.getString("model"))
        assertEquals("web", body.getJSONArray("plugins").getJSONObject(0).getString("id"))
        val messages = body.getJSONArray("messages")
        assertEquals("Réponds en JSON.", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("riz basmati"))
    }

    @Test
    fun `rend le texte de la reponse`() {
        val text = search(FakeTransport(HttpResponse(200, answer("{\"carbs_per_100g\":28.2}"))))
            .ask("clef", "question", "instructions")
        assertEquals("{\"carbs_per_100g\":28.2}", text)
    }

    @Test
    fun `accepte un contenu en fragments`() {
        val body = """
            {"choices":[{"message":{"content":[
              {"type":"text","text":"{\"carbs_per_100g\":"},
              {"type":"text","text":"28}"}
            ]}}]}
        """.trimIndent()
        assertEquals("{\"carbs_per_100g\":28}", search(FakeTransport(HttpResponse(200, body))).ask("clef", "q", "i"))
    }

    @Test
    fun `une reponse vide est refusee`() {
        val error = failure { search(FakeTransport(HttpResponse(200, answer("")))).ask("clef", "q", "i") }
        assertEquals(MistralErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `clef vide — rien ne part`() {
        val transport = FakeTransport(HttpResponse(200, answer("ok")))
        val error = failure { search(transport).ask("", "q", "i") }
        assertEquals(MistralErrorKind.NOT_CONFIGURED, error.kind)
        assertTrue(transport.requests.isEmpty())
    }
}
