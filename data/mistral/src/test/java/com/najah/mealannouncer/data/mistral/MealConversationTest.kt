package com.najah.mealannouncer.data.mistral

import com.najah.mealannouncer.core.model.MealUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MealConversationTest {

    @Test
    fun `lit une mesure de menage avec son poids estime et son hypothese`() {
        val reply = MealConversation.parse(
            """{"foods":[{"id":1,"name":"lait chocolaté","quantity":1,"unit":"cup","grams":250,
               "assumption":"une tasse ≈ 250 ml","raw_text":"un lait chocolaté dans une tasse"}],
               "question":null,"understood":true,"done":true,"cancel":false}""",
        )
        val food = reply.foods.single()
        assertEquals(1, food.id)
        assertEquals(MealUnit.CUP, food.unit)
        assertEquals(250.0, food.grams!!, 0.0)
        assertEquals("une tasse ≈ 250 ml", food.assumption)
        assertTrue(reply.done)
        assertNull(reply.question)
    }

    @Test
    fun `une quantite non dite reste nulle et la question est gardee`() {
        val reply = MealConversation.parse(
            """{"foods":[{"id":1,"name":"biscuits Prince","quantity":null,"unit":"unknown","grams":null}],
               "question":"Combien de biscuits Prince ?","understood":true,"done":false}""",
        )
        assertNull(reply.foods.single().quantity)
        assertNull(reply.foods.single().grams)
        assertEquals("Combien de biscuits Prince ?", reply.question)
    }

    @Test
    fun `un poids hors bornes devient nul sans emporter l'aliment`() {
        val reply = MealConversation.parse(
            """{"foods":[{"id":1,"name":"riz","quantity":15000,"unit":"gram","grams":9000}],"done":false}""",
        )
        val food = reply.foods.single()
        assertNull("Une quantité de 15 kg est une erreur de lecture", food.quantity)
        assertNull("Un poids de 9 kg est une erreur de lecture", food.grams)
    }

    @Test
    fun `une question n'est pas retenue quand le modele dit avoir fini`() {
        val reply = MealConversation.parse(
            """{"foods":[{"id":1,"name":"pomme","quantity":1,"unit":"unit"}],"question":"Autre chose ?","done":true}""",
        )
        assertNull(reply.question)
    }

    @Test
    fun `deux aliments au meme id recoivent des id distincts`() {
        val reply = MealConversation.parse(
            """{"foods":[{"id":1,"name":"pain"},{"id":1,"name":"beurre"},{"name":"confiture"}]}""",
        )
        assertEquals(listOf(1, 2, 3), reply.foods.map { it.id })
    }

    @Test
    fun `un aliment sans nom est signale et non compte`() {
        val reply = MealConversation.parse(
            """{"foods":[{"id":1,"name":"","raw_text":"un truc"},{"id":2,"name":"yaourt"}]}""",
        )
        assertEquals(listOf("yaourt"), reply.foods.map { it.name })
        assertEquals(listOf("un truc"), reply.rejected)
    }

    @Test
    fun `incompris et annulation sont lus`() {
        val reply = MealConversation.parse("""{"foods":[],"understood":false,"cancel":true}""")
        assertFalse(reply.understood)
        assertTrue(reply.cancel)
    }

    @Test
    fun `sans liste d'aliments la reponse est refusee`() {
        assertThrows(MistralException::class.java) {
            MealConversation.parse("""{"question":"Quoi ?"}""")
        }
    }

    @Test
    fun `la consigne interdit les glucides et l'invention de quantite`() {
        val prompt = MealConversation.SYSTEM_PROMPT
        assertTrue(prompt.contains("AUCUNE valeur de glucides"))
        assertTrue(prompt.contains("quantity null"))
        assertTrue(prompt.contains("BILAN DE L'APPLICATION"))
        assertTrue(prompt.contains("UNE question"))
    }

    @Test
    fun `l'historique part entre la consigne et le dernier message`() {
        val transport = FakeTransport(
            HttpResponse(200, """{"choices":[{"message":{"content":"{\"foods\":[]}"}}]}"""),
        )
        HttpMistralChat(transport).askJson(
            apiKey = "clef",
            ask = JsonAsk(
                system = "consigne",
                user = "Réponse : « un bol »",
                history = listOf(
                    ChatTurn(ChatRole.USER, "Repas décrit : « du lait chocolaté »"),
                    ChatTurn(ChatRole.ASSISTANT, """{"foods":[],"question":"Quelle quantité ?"}"""),
                ),
            ),
        )
        val messages = JSONObject(transport.requests.single().bodyText()).getJSONArray("messages")
        assertEquals(4, messages.length())
        assertEquals(
            listOf("system", "user", "assistant", "user"),
            (0 until 4).map { messages.getJSONObject(it).getString("role") },
        )
        assertEquals("Réponse : « un bol »", messages.getJSONObject(3).getString("content"))
    }
}
