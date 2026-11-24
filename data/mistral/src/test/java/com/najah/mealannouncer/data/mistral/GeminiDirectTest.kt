package com.najah.mealannouncer.data.mistral

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiDirectTest {

    private fun answer(text: String) =
        JSONObject().put(
            "choices",
            org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", text))),
        ).toString()

    private fun direct(transport: HttpTransport) = HttpGeminiDirectChat(transport, HttpMistralChat(transport))

    @Test
    fun `la conversation part telle quelle, sans consigne, avec ses photos`() {
        val transport = FakeTransport(HttpResponse(200, answer("Environ 60 g de glucides.")))
        val messages = listOf(
            DirectMessage(ChatRole.USER, "Calcule mes glucides", listOf("data:image/jpeg;base64,AAAA", "data:image/jpeg;base64,BBBB")),
            DirectMessage(ChatRole.ASSISTANT, "Il manque le plat chaud."),
            DirectMessage(ChatRole.USER, "La totalité"),
        )
        val reply = direct(transport).reply("clef", messages)
        assertEquals("Environ 60 g de glucides.", reply)

        val body = JSONObject(transport.requests.single().bodyText())
        assertEquals("google/gemini-4-hydrogen", body.getString("model"))
        assertFalse("Pas de mode JSON imposé", body.has("response_format"))
        val sent = body.getJSONArray("messages")
        assertEquals(4, sent.length())
        // Seule consigne : le contexte permanent (comme les « infos enregistrées » de Gemini).
        assertEquals("system", sent.getJSONObject(0).getString("role"))
        assertEquals(DIRECT_CONTEXT, sent.getJSONObject(0).getString("content"))
        assertEquals("user", sent.getJSONObject(1).getString("role"))
        val parts = sent.getJSONObject(1).getJSONArray("content")
        assertEquals(3, parts.length())
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("Calcule mes glucides", parts.getJSONObject(2).getString("text"))
        assertEquals("assistant", sent.getJSONObject(2).getString("role"))
        assertEquals("La totalité", sent.getJSONObject(3).getString("content"))
    }

    @Test
    fun `le lecteur rend le total, la fourchette et la question`() {
        val raw = """{"total_g": 60, "min_g": 54, "max_g": 67, "a_dire": "Environ 60 grammes.",
            "question": "Combien de pain manges-tu ?", "repas": "daube, pommes de terre, pain",
            "detail": "pain 45 g : 25 g, pommes de terre 175 g : 30 g", "complet": true}"""
        val summary = HttpGeminiDirectChat.parseSummary(raw, "réponse")
        assertEquals(60.0, summary.totalGrams!!, 0.0)
        assertEquals(54.0, summary.minGrams!!, 0.0)
        assertEquals(67.0, summary.maxGrams!!, 0.0)
        assertEquals("Combien de pain manges-tu ?", summary.question)
        assertEquals("daube, pommes de terre, pain", summary.meal)
        assertEquals("pain 45 g : 25 g, pommes de terre 175 g : 30 g", summary.detail)
        assertFalse("Une question en attente : le repas n'est pas complet", summary.complete)
    }

    @Test
    fun `le lecteur rend les produits industriels, les aliments sans marque et les fiches retenues`() {
        val raw = """{"total_g": 40, "a_dire": "40 grammes.", "question": null, "complet": false,
            "produits": [{"nom": "Pains au chocolat", "marque": "Carrefour", "code_barres": "3560 0707 11994"},
                         {"nom": "", "marque": null}],
            "aliments_sans_marque": ["riz", "purée"],
            "fiches_retenues": ["3560070711994", "abc"]}"""
        val summary = HttpGeminiDirectChat.parseSummary(raw, "réponse")
        assertEquals(listOf(DirectProduct("Pains au chocolat", "Carrefour", "3560070711994")), summary.products)
        assertEquals(listOf("riz", "purée"), summary.plainFoods)
        assertEquals(listOf("3560070711994"), summary.chosenBarcodes)
    }

    @Test
    fun `sans question et complet, le total peut partir`() {
        val raw = """{"total_g": 55.5, "min_g": null, "max_g": null, "a_dire": "55 grammes.", "question": null, "complet": true}"""
        val summary = HttpGeminiDirectChat.parseSummary(raw, "réponse")
        assertEquals(55.5, summary.totalGrams!!, 0.0)
        assertNull(summary.minGrams)
        assertNull(summary.question)
        assertTrue(summary.complete)
    }

    @Test
    fun `un total absurde ou absent n'est pas retenu`() {
        val summary = HttpGeminiDirectChat.parseSummary("""{"total_g": 5000, "a_dire": "?"}""", "réponse")
        assertNull(summary.totalGrams)
        val none = HttpGeminiDirectChat.parseSummary("pas de JSON", "**Bonjour.** Il me faut une photo.")
        assertNull(none.totalGrams)
        assertEquals("Bonjour. Il me faut une photo.", none.spoken)
    }

    @Test
    fun `le markdown devient du texte lisible`() {
        val md = """
            ### Récapitulatif
            | Élément | Glucides |
            | :--- | :--- |
            | **Pain** | 20 à 25 g |
            * **Pommes de terre** : *environ* 25 g
            ---
        """.trimIndent()
        val plain = HttpGeminiDirectChat.plainText(md)
        assertEquals("Récapitulatif\nÉlément · Glucides\nPain · 20 à 25 g\n• Pommes de terre : environ 25 g", plain)
    }
}
