package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.data.homeassistant.ExpectedAnswer
import com.najah.mealannouncer.data.homeassistant.HomeAnswer
import com.najah.mealannouncer.data.homeassistant.HomeAssistantErrorKind
import com.najah.mealannouncer.data.homeassistant.HomeAssistantException
import com.najah.mealannouncer.data.homeassistant.HomeVoiceLink
import com.najah.mealannouncer.integration.camaps.CamapsMealType
import com.najah.mealannouncer.integration.camaps.CamapsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HomeVoiceChannelTest {

    private val enceinte = "assist_satellite.home_assistant_voice_09e904_satellite_assist"

    /** Fausse enceinte : rend les réponses prévues, ou lève l'erreur prévue. */
    private class FakeLink(
        var answer: HomeAnswer = HomeAnswer(sentence = null),
        var failure: Exception? = null,
        /** Réponses successives ; à défaut, [answer]. */
        queue: List<HomeAnswer> = emptyList(),
    ) : HomeVoiceLink {
        private val pending = ArrayDeque(queue)
        val said = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<Triple<String, String, List<ExpectedAnswer>>>()

        override suspend fun say(satellite: String, text: String) {
            failure?.let { throw it }
            said += satellite to text
            steps += "dit"
        }

        /** Ordre des gestes : « dit », « silence », « question ». */
        val steps = mutableListOf<String>()
        var quietFailure: Exception? = null

        override suspend fun awaitQuiet(satellite: String) {
            steps += "silence"
            quietFailure?.let { throw it }
        }

        override suspend fun ask(satellite: String, question: String, answers: List<ExpectedAnswer>): HomeAnswer {
            failure?.let { throw it }
            steps += "question"
            asked += Triple(satellite, question, answers)
            return pending.removeFirstOrNull() ?: answer
        }
    }

    @Test
    fun `dit et demande toujours par l'enceinte qui a entendu`() = runTest {
        val link = FakeLink(answer = HomeAnswer("Deux."))
        val channel = HomeVoiceChannel(link, enceinte)
        assertTrue(channel.say("C'est noté."))
        assertEquals(HomeVoiceChannel.Heard.Text("Deux."), channel.ask("Combien en manges-tu ?"))
        assertEquals(enceinte, link.said.single().first)
        assertEquals(enceinte, link.asked.single().first)
    }

    @Test
    fun `silence — Silence, pas une reponse vide`() = runTest {
        val channel = HomeVoiceChannel(FakeLink(answer = HomeAnswer("  ")), enceinte)
        assertEquals(HomeVoiceChannel.Heard.Silence, channel.ask("Combien ?"))
    }

    @Test
    fun `coupure du lien — LinkLost et say faux, sans exception`() = runTest {
        val link = FakeLink(failure = HomeAssistantException(HomeAssistantErrorKind.NETWORK, "Wi-Fi perdu"))
        val channel = HomeVoiceChannel(link, enceinte)
        assertFalse(channel.say("Bonjour."))
        assertEquals(HomeVoiceChannel.Heard.LinkLost, channel.ask("Combien ?"))
    }

    @Test
    fun `enceinte occupee ou refus de HA — on bascule aussi sur le telephone`() = runTest {
        val link = FakeLink(failure = HomeAssistantException(HomeAssistantErrorKind.SERVICE, "occupée"))
        assertEquals(HomeVoiceChannel.Heard.LinkLost, HomeVoiceChannel(link, enceinte).ask("Combien ?"))
    }

    @Test
    fun `apres chaque phrase, on attend que l'enceinte se taise avant la question`() = runTest {
        val link = FakeLink(answer = HomeAnswer("Repas."))
        val longue = "Un steak haché : 0 gramme. Deux tranches de pain : 30 grammes. Repas ou collation ?"
        HomeVoiceChannel(link, enceinte).ask(longue, HomeVoiceChannel.MEAL_TYPE_ANSWERS)
        assertEquals(listOf("dit", "silence", "question"), link.steps)
    }

    @Test
    fun `l'attente du silence qui echoue ne fait pas basculer sur le telephone`() = runTest {
        val link = FakeLink().apply { quietFailure = HomeAssistantException(HomeAssistantErrorKind.SERVICE, "registre") }
        assertTrue(HomeVoiceChannel(link, enceinte).say("C'est noté."))
    }

    @Test
    fun `la question coupee garde toujours des mots — jamais un point d'interrogation seul`() {
        val (avant, question) = HomeVoiceChannel.split(
            "Deux steaks hachés Charal 5 % : 0 gramme. Quel type de repas, hypoglycémie ou glucides lents. ?",
        )
        assertEquals("Deux steaks hachés Charal 5 % : 0 gramme.", avant)
        assertEquals("Quel type de repas, hypoglycémie ou glucides lents. ?", question)
    }

    @Test
    fun `une phrase sans mots n'est pas envoyee a l'enceinte`() = runTest {
        val link = FakeLink()
        assertTrue(HomeVoiceChannel(link, enceinte).say(" ? "))
        assertTrue(link.said.isEmpty())
    }

    @Test
    fun `une annulation n'est jamais avalee`() = runTest {
        val channel = HomeVoiceChannel(FakeLink(failure = CancellationException("stop")), enceinte)
        try {
            channel.ask("Combien ?")
            fail("L'annulation doit remonter")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `reponse reconnue — la phrase canonique, lue sans ambiguite par CamapsVoice`() = runTest {
        val link = FakeLink(answer = HomeAnswer(sentence = "Au repas.", answerId = "repas"))
        val heard = HomeVoiceChannel(link, enceinte).ask("Quel type ?", HomeVoiceChannel.MEAL_TYPE_ANSWERS)
        assertEquals(HomeVoiceChannel.Heard.Text("repas"), heard)
        assertEquals(HomeVoiceChannel.MEAL_TYPE_ANSWERS, link.asked.single().third)
    }

    @Test
    fun `reponse non reconnue — la phrase entendue arrive intacte (contestation)`() = runTest {
        val link = FakeLink(answer = HomeAnswer(sentence = "Non, ce n'est pas ce produit.", answerId = null))
        val heard = HomeVoiceChannel(link, enceinte).ask("Quel type ?", HomeVoiceChannel.MEAL_TYPE_ANSWERS)
        assertEquals(HomeVoiceChannel.Heard.Text("Non, ce n'est pas ce produit."), heard)
    }

    @Test
    fun `chaque reponse attendue mene au bon type CamAPS`() {
        val expected = mapOf(
            "repas" to CamapsMealType.MEAL,
            "hypo" to CamapsMealType.HYPO,
            "lent" to CamapsMealType.SLOW,
        )
        HomeVoiceChannel.MEAL_TYPE_ANSWERS.forEach { answer ->
            answer.sentences.forEach { sentence ->
                assertEquals(
                    "« $sentence » (réponse ${answer.id}) doit donner ${expected[answer.id]}",
                    expected[answer.id],
                    CamapsVoice.matchMealType(sentence),
                )
            }
        }
    }

    // ── Écho : l'enceinte qui s'entend elle-même (essai réel du 29/10/2026) ─────────────────────

    private val questionLongue = "J'ai compris : thé infusé nature : 0,5 grammes, sucre en morceaux : 16,5 grammes. " +
        "17 grammes de glucides. Quel type : repas ou collation, hypoglycémie, ou repas à glucides lents ?"

    @Test
    fun `une question longue est dite en annonce, et seule la derniere phrase est posee`() = runTest {
        val link = FakeLink(answer = HomeAnswer("Repas."))
        val heard = HomeVoiceChannel(link, enceinte).ask(questionLongue, HomeVoiceChannel.MEAL_TYPE_ANSWERS)
        assertEquals(HomeVoiceChannel.Heard.Text("Repas."), heard)
        assertEquals(
            "J'ai compris : thé infusé nature : 0,5 grammes, sucre en morceaux : 16,5 grammes. 17 grammes de glucides.",
            link.said.single().second,
        )
        assertEquals("Quel type : repas ou collation, hypoglycémie, ou repas à glucides lents ?", link.asked.single().second)
    }

    @Test
    fun `une question courte reste entiere, sans annonce`() = runTest {
        val link = FakeLink(answer = HomeAnswer("Deux."))
        HomeVoiceChannel(link, enceinte).ask("Combien en manges-tu ?")
        assertTrue(link.said.isEmpty())
        assertEquals("Combien en manges-tu ?", link.asked.single().second)
    }

    @Test
    fun `les trois transcriptions de l'essai sont reconnues comme un echo`() {
        assertTrue(HomeVoiceChannel.isEcho("J'ai compris.", questionLongue))
        assertTrue(HomeVoiceChannel.isEcho("17 grammes de glucides. Quel type?", questionLongue))
        assertTrue(
            HomeVoiceChannel.isEcho(
                "Je n'ai pas compris. Repart ou collection? Hypoglycémie ou repart du siglan? Repart.",
                "Je n'ai pas compris. Repas ou collation, hypoglycémie, ou repas à glucides lents ?",
            ),
        )
    }

    @Test
    fun `une vraie reponse n'est jamais un echo`() {
        assertFalse("Un mot seul", HomeVoiceChannel.isEcho("Repas.", questionLongue))
        assertFalse(HomeVoiceChannel.isEcho("Non, ce n'est pas ce produit.", questionLongue))
        assertFalse(HomeVoiceChannel.isEcho("Un seul.", "Combien en manges-tu ?"))
        assertFalse(
            "Une réponse attendue telle quelle",
            HomeVoiceChannel.isEcho("repas ou collation", questionLongue, HomeVoiceChannel.MEAL_TYPE_ANSWERS),
        )
    }

    @Test
    fun `un echo est ignore et la question courte reposee une fois`() = runTest {
        val link = FakeLink(queue = listOf(HomeAnswer("17 grammes de glucides. Quel type?"), HomeAnswer("Repas.")))
        val heard = HomeVoiceChannel(link, enceinte).ask(questionLongue)
        assertEquals(HomeVoiceChannel.Heard.Text("Repas."), heard)
        assertEquals(2, link.asked.size)
        assertEquals("Le préambule n'est dit qu'une fois", 1, link.said.size)
    }

    @Test
    fun `deux echos de suite — silence, sans boucle`() = runTest {
        val echo = HomeAnswer("J'ai compris. 17 grammes de glucides.")
        val link = FakeLink(queue = listOf(echo, echo, echo))
        assertEquals(HomeVoiceChannel.Heard.Silence, HomeVoiceChannel(link, enceinte).ask(questionLongue))
        assertEquals(2, link.asked.size)
    }

    @Test
    fun `les phrases dites par l'enceinte ne parlent ni de glycemie ni d'insuline`() {
        listOf(
            HomeVoiceChannel.LINK_LOST, HomeVoiceChannel.BUSY,
            HomeVoiceChannel.UNLOCK_PHONE, HomeVoiceChannel.NO_KEY, HomeVoiceChannel.STILL_LOCKED,
        ).forEach { phrase ->
            val t = phrase.lowercase()
            assertFalse("« $phrase » ne doit pas parler de glycémie", t.contains("glycémie"))
            assertFalse("« $phrase » ne doit pas parler d'insuline", t.contains("insuline") || t.contains("bolus"))
        }
    }
}
