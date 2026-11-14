package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.ChatRole
import com.najah.mealannouncer.data.mistral.JsonAsk
import com.najah.mealannouncer.data.mistral.MealConversation
import com.najah.mealannouncer.data.mistral.MealReview
import com.najah.mealannouncer.data.mistral.AI_REVIEW_MODEL
import com.najah.mealannouncer.data.mistral.MistralChat
import com.najah.mealannouncer.data.mistral.MistralWebSearch
import com.najah.mealannouncer.data.mistral.WebCarbEstimate
import com.najah.mealannouncer.data.mistral.WebCarbResearcher
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La conversation du repas : l'IA comprend et fait préciser, l'application cherche et calcule.
 *
 * Le cas d'origine est celui que l'utilisateur a constaté : « un lait chocolaté dans une tasse » ne doit
 * plus finir à 0 g. Et un aliment sans quantité doit faire poser une question, pas être chiffré
 * au hasard ni compté comme zéro.
 */
class MealConversationEngineTest {

    @Test
    fun `une tasse de lait chocolate sans marque est chiffree par estimation`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"lait chocolaté","quantity":1,"unit":"cup","grams":250,
                   "assumption":"une tasse ≈ 250 ml","raw_text":"un lait chocolaté dans une tasse"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            general = """{"name":"lait chocolaté","carbs_per_100g":10}""",
        )
        val engine = engine(chat)

        val step = engine.turn(engine.newSession("cle"), "pour mon petit déjeuner un lait chocolaté dans une tasse")

        val line = step.lines.single()
        assertEquals(CarbOrigin.GENERAL, line.origin)
        assertEquals(MealUnit.GRAM, line.unit)
        assertEquals(25.0, line.carbs.grams, 0.01)
        assertEquals("une tasse ≈ 250 ml", line.portionNote)
        assertTrue(step.done)
        assertEquals("Tout est chiffré : aucun bilan à demander", 1, chat.conversationCalls)
    }

    @Test
    fun `une quantite manquante fait poser une question grace au bilan`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"biscuits Prince","quantity":null,"unit":"unknown"}],
                   "question":null,"understood":true,"done":true}""",
                """{"foods":[{"id":1,"name":"biscuits Prince","quantity":null,"unit":"unknown"}],
                   "question":"Tu en as mangé combien, de biscuits Prince ?","understood":true,"done":false}""",
            ),
        )
        val engine = engine(chat, local = mapOf("biscuits Prince" to listOf(prince())))

        val step = engine.turn(engine.newSession("cle"), "des biscuits Prince")

        assertFalse(step.done)
        assertEquals("Tu en as mangé combien, de biscuits Prince ?", step.question)
        assertTrue(step.lines.single().carbResult() is CarbResult.Unavailable)
        val bilan = chat.asks.last().user
        assertTrue("Le bilan doit dire ce qui manque", bilan.contains("NON CHIFFRÉ"))
        assertTrue("Le bilan doit nommer la fiche trouvée", bilan.contains("fiche « biscuits Prince »"))
    }

    @Test
    fun `la reponse suivante part avec toute l'histoire et la valeur n'est pas recherchee deux fois`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"biscuits Prince","quantity":null,"unit":"unknown"}],
                   "question":"Combien de grammes ?","understood":true,"done":false}""",
                """{"foods":[{"id":1,"name":"biscuits Prince","quantity":30,"unit":"gram"}],
                   "question":null,"understood":true,"done":true}""",
            ),
        )
        val repository = FakeRepository(local = mapOf("biscuits Prince" to listOf(prince())))
        val engine = engine(chat, repository = repository)
        val session = engine.newSession("cle")

        engine.turn(session, "des biscuits Prince")
        val step = engine.turn(session, "trente grammes")

        assertTrue(step.done)
        assertEquals(21.0, step.lines.single().carbs.grams, 0.01)
        val last = chat.asks.last()
        assertTrue(last.user.startsWith("Réponse : « trente grammes »"))
        assertTrue(
            "Le modèle doit savoir quel produit l'écran montre",
            last.user.contains("Produits retenus par l'application : #1 biscuits Prince"),
        )
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), last.history.map { it.role })
        assertEquals(1, repository.localSearches)
    }

    @Test
    fun `une phrase incomprise est signalee`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[],"question":"Pardon, tu as mangé quoi ?","understood":false,"done":false}""",
            ),
        )
        val step = engine(chat).let { it.turn(it.newSession("cle"), "euh bof") }

        assertFalse(step.understood)
        assertEquals("Pardon, tu as mangé quoi ?", step.question)
        assertEquals("Une phrase incomprise ne déclenche pas de bilan", 1, chat.conversationCalls)
    }

    @Test
    fun `annuler arrete la conversation`() = runTest {
        val chat = ScriptedChat(conversation = listOf("""{"foods":[],"cancel":true}"""))
        val step = engine(chat).let { it.turn(it.newSession("cle"), "laisse tomber") }

        assertTrue(step.cancelled)
        assertNull(step.question)
    }

    @Test
    fun `un poids estime ne remplace pas une quantite que la fiche sait chiffrer`() {
        val line = MealConversationEngine.lineFor(
            food = com.najah.mealannouncer.data.mistral.ConversationFood(
                id = 1,
                name = "biscuits Prince",
                quantity = 50.0,
                unit = MealUnit.GRAM,
                grams = 999.0,
                assumption = "ne doit pas servir",
                rawText = "50 g de biscuits",
            ),
            product = prince(),
            estimate = null,
        )
        assertEquals(35.0, line.carbs.grams, 0.01)
        assertNull(line.portionNote)
    }

    @Test
    fun `un produit de marque sans Open Food Facts prend l'estimation et propose la photo`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"pains au chocolat Carrefour","brand":"Carrefour","quantity":2,"unit":"unit","grams":120}],
                   "question":null,"understood":true,"done":true}""",
            ),
            general = """{"name":"pain au chocolat","carbs_per_100g":45}""",
        )
        val web = FakeWebSearch(
            """{"name":"pains au chocolat Carrefour","carbs_per_100g":45,"units_per_package":8,
                "package_weight_g":360,"unit_detail":"paquet de 8 × 45 g","source":"https://exemple.fr"}""",
        )
        val engine = engine(chat, webSearch = web)

        val step = engine.turn(engine.newSession("cle"), "deux pains au chocolat Carrefour")

        val line = step.lines.single()
        assertEquals(CarbOrigin.GENERAL, line.origin)
        assertEquals("120 g estimés à 45 g pour 100 g", 54.0, line.carbs.grams, 0.01)
        assertTrue(line.carbsGuessed)
        assertEquals("Plus de recherche web de 12 à 38 s", 0, web.calls)
    }

    @Test
    fun `les glucides par piece lus sur la photo du tableau remplacent l'estimation`() = runTest {
        val said = """{"foods":[{"id":1,"name":"pains au chocolat Carrefour","brand":"Carrefour","quantity":2,"unit":"unit","grams":120}],"done":true}"""
        // Le modèle reprend le nom lu sur la photo (essai du 10/11/2026) : nom méconnaissable,
        // nouvelle estimation, et pourtant la valeur de la photo doit suivre l'aliment n° 1.
        val renamed = """{"foods":[{"id":1,"name":"Le Classique – viennoiserie Carrefour","brand":"Carrefour","quantity":2,"unit":"unit","grams":120}],"done":true}"""
        val chat = ScriptedChat(
            conversation = listOf(said, renamed),
            general = """{"name":"pain au chocolat","carbs_per_100g":45}""",
        )
        val engine = engine(chat)
        val session = engine.newSession("cle")
        val guessed = engine.turn(session, "deux pains au chocolat").lines.single()
        assertTrue(guessed.carbsGuessed)

        val photo = WebCarbEstimate(
            name = "Pains au chocolat Carrefour (tableau nutritionnel photographié)",
            carbsPer100g = 44.0,
            sourceUrl = null,
            carbsPerUnit = 22.0,
            unitGrams = 45.0,
            unitDetail = "glucides d'une pièce lus sur l'emballage : 22 g",
        )
        assertTrue(engine.rememberCarbsFromPhoto(session, guessed, photo))
        val line = engine.turn(session, "Photo de l'aliment #1").lines.single()

        assertEquals(44.0, line.carbs.grams, 0.01)
        assertFalse(line.carbsGuessed)
    }

    @Test
    fun `sans poids de piece, l'estimation du modele sert tout de suite et la ligne demande une photo`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"biscuits Prince","brand":"Prince","quantity":3,"unit":"unit","grams":60,
                   "assumption":"estimation du modèle"}],"done":true}""",
            ),
        )
        val engine = engine(
            chat,
            local = mapOf("biscuits Prince" to listOf(prince())),
            web = """{"name":"biscuits Prince","carbs_per_100g":70,"unit_weight_g":12.5,"unit_detail":"paquet de 24 × 12,5 g"}""",
        )

        val line = engine.turn(engine.newSession("cle"), "trois biscuits Prince").lines.single()

        assertEquals("60 g estimés × 70 % : pas de recherche web de 17 s", 42.0, line.carbs.grams, 0.01)
        assertTrue(line.weightGuessed)
    }

    @Test
    fun `le poids d'une piece lu sur la photo de l'emballage remplace l'estimation`() = runTest {
        val said = """{"foods":[{"id":1,"name":"biscuits Prince","brand":"Prince","quantity":3,"unit":"unit","grams":60,
                   "assumption":"estimation du modèle"}],"done":true}"""
        val chat = ScriptedChat(conversation = listOf(said, said))
        val engine = engine(chat, local = mapOf("biscuits Prince" to listOf(prince())))
        val session = engine.newSession("cle")
        val guessed = engine.turn(session, "trois biscuits Prince").lines.single()
        assertTrue(guessed.weightGuessed)

        val photo = WebCarbEstimate(
            name = "Prince",
            carbsPer100g = 70.0,
            sourceUrl = null,
            unitGrams = 12.5,
            unitDetail = "une pièce pèse 12,5 g (lu sur l'emballage)",
        )
        assertTrue(engine.rememberUnitFromPhoto(session, guessed, photo))
        val line = engine.turn(session, "J'ai photographié l'emballage").lines.single()

        assertEquals(26.25, line.carbs.grams, 0.01)
        assertFalse(line.weightGuessed)
        assertTrue(line.portionNote!!.contains("lu sur l'emballage"))
    }

    @Test
    fun `un produit Open Food Facts sans poids de piece ni estimation est complete par la fiche web`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"biscuits Prince","brand":"Prince","quantity":3,"unit":"unit"}],"done":true}""",
            ),
        )
        val engine = engine(
            chat,
            local = mapOf("biscuits Prince" to listOf(prince())),
            web = """{"name":"biscuits Prince","carbs_per_100g":70,"unit_weight_g":12.5,"unit_detail":"paquet de 24 × 12,5 g"}""",
        )

        val line = engine.turn(engine.newSession("cle"), "trois biscuits Prince").lines.single()

        assertEquals(CarbOrigin.OFF, line.origin)
        assertEquals(MealUnit.GRAM, line.unit)
        assertEquals("3 × 12,5 g = 37,5 g, et non l'estimation du modèle", 26.25, line.carbs.grams, 0.01)
        assertTrue(line.portionNote!!.contains("paquet de 24"))
    }

    @Test
    fun `une ligne a zero ne termine pas en silence`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"gâteau maison","quantity":null,"unit":"unknown"}],"done":true}""",
                """{"foods":[{"id":1,"name":"gâteau maison","quantity":null,"unit":"unknown"}],"done":true}""",
            ),
            general = """{"name":"gâteau maison","carbs_per_100g":50}""",
        )
        val step = engine(chat).let { it.turn(it.newSession("cle"), "du gâteau maison") }

        assertFalse("L'application doit demander la portion elle-même", step.done)
        assertTrue(step.question!!.isNotBlank())
    }

    @Test
    fun `la photo vient de la fiche Open Food Facts ou de son code-barres`() = runTest {
        val pipeline = pipeline(FakeRepository(images = mapOf("3017620422003" to "https://images.openfoodfacts.org/b.jpg")))

        val withImage = pipeline.imageFor(prince().copy(imageUrl = "https://images.openfoodfacts.org/a.jpg"), null)
        assertEquals("https://images.openfoodfacts.org/a.jpg", withImage!!.url)

        val byBarcode = pipeline.imageFor(prince().copy(barcode = "3017620422003"), null)
        assertEquals("https://images.openfoodfacts.org/b.jpg", byBarcode!!.url)

        assertNull("Ni photo ni code-barres : aucune photo", pipeline.imageFor(prince(), null))
    }

    @Test
    fun `une estimation moyenne n'affiche jamais de photo`() = runTest {
        val pipeline = pipeline(FakeRepository())
        val general = com.najah.mealannouncer.data.mistral.WebCarbEstimate(
            name = "lait chocolaté",
            carbsPer100g = 10.0,
            sourceUrl = null,
            general = true,
            imageUrl = "https://exemple.fr/lait.jpg",
        )
        assertNull(pipeline.imageFor(null, general))
    }

    @Test
    fun `une valeur web montre l'image de sa page ou celle de son code-barres`() = runTest {
        val pipeline = pipeline(FakeRepository(images = mapOf("3560071012345" to "https://images.openfoodfacts.org/c.jpg")))
        val web = com.najah.mealannouncer.data.mistral.WebCarbEstimate(
            name = "pains au chocolat Carrefour",
            carbsPer100g = 45.0,
            sourceUrl = "https://www.carrefour.fr/p",
        )
        assertEquals(
            "https://www.carrefour.fr/img.jpg",
            pipeline.imageFor(null, web.copy(imageUrl = "https://www.carrefour.fr/img.jpg"))!!.url,
        )
        assertEquals(
            "https://images.openfoodfacts.org/c.jpg",
            pipeline.imageFor(null, web.copy(barcode = "3560071012345"))!!.url,
        )
        assertNull(pipeline.imageFor(null, web))
    }

    @Test
    fun `le bouton Pas ce produit ecarte la fiche et en retient une autre`() = runTest {
        val maitreJean = prince().copy(id = "mjp", name = "pain de mie Maître Jean Pierre", carbsPer100g = 16.0)
        val gourmet = prince().copy(id = "gourmet", name = "pain de mie Gourmet Carrefour", carbsPer100g = 46.0)
        val chat = ScriptedChat(
            conversation = List(2) {
                """{"foods":[{"id":1,"name":"pain de mie","quantity":15,"unit":"gram"}],"done":true}"""
            },
        )
        val engine = engine(chat, local = mapOf("pain de mie" to listOf(maitreJean, gourmet)))
        val session = engine.newSession("cle")

        val first = engine.turn(session, "une demi-tranche de pain de mie").lines.single()
        assertEquals("pain de mie Maître Jean Pierre", first.name)

        engine.reject(session, first)
        val second = engine.turn(session, "Ce n'est pas le bon produit").lines.single()

        assertEquals("pain de mie Gourmet Carrefour", second.name)
        assertEquals(6.9, second.carbs.grams, 0.01)
    }

    @Test
    fun `dire ce n'est pas ce produit ecarte la fiche montree`() = runTest {
        val maitreJean = prince().copy(id = "mjp", name = "pain de mie", brand = "Maître Jean Pierre")
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"pain de mie","quantity":15,"unit":"gram"}],"done":true}""",
                """{"foods":[{"id":1,"name":"pain de mie","quantity":15,"unit":"gram","reject_found":true}],
                   "question":"Quelle marque de pain de mie ?","done":false}""",
            ),
        )
        val engine = engine(chat, local = mapOf("pain de mie" to listOf(maitreJean)))
        val session = engine.newSession("cle")
        engine.turn(session, "une demi-tranche de pain de mie")

        val step = engine.turn(session, "non ce n'est pas ce produit")

        assertNull("La fiche refusée ne doit pas revenir", step.lines.single().product)
        assertEquals("Quelle marque de pain de mie ?", step.question)
    }

    private fun pipeline(repository: FakeRepository) = MealCarbPipeline(
        chat = ScriptedChat(conversation = emptyList()),
        webResearcher = WebCarbResearcher(FakeWebSearch("""{"error":"inutile"}""")),
        foodRepository = repository,
    )

    private fun engine(
        chat: MistralChat,
        local: Map<String, List<FoodProduct>> = emptyMap(),
        repository: FakeRepository = FakeRepository(local = local),
        web: String = """{"error":"aucune valeur fiable trouvée"}""",
        webSearch: FakeWebSearch = FakeWebSearch(web),
    ) = MealConversationEngine(
        chat = chat,
        pipeline = MealCarbPipeline(
            chat = chat,
            webResearcher = WebCarbResearcher(webSearch),
            foodRepository = repository,
        ),
        reviewer = MealReviewer(chat),
    )

    private fun prince(): FoodProduct = FoodProduct(
        id = "prince",
        barcode = null,
        name = "biscuits Prince",
        brand = null,
        carbsPer100g = 70.0,
        source = FoodSource.OPEN_FOOD_FACTS_ONLINE,
    )

    /** Rend, dans l'ordre, les réponses de la conversation ; l'estimation générale à part. */
    private class ScriptedChat(
        conversation: List<String>,
        private val general: String = """{"error":"pas d'estimation"}""",
        /** Les relectures, dans l'ordre ; à défaut, une relecture sans avis (rien n'est signalé). */
        review: List<String> = emptyList(),
        /** Vrai : la relecture échoue, comme une panne réseau. */
        private val reviewFails: Boolean = false,
    ) : MistralChat {
        private val queue = ArrayDeque(conversation)
        private val reviews = ArrayDeque(review)
        val asks = mutableListOf<JsonAsk>()
        val reviewAsks = mutableListOf<JsonAsk>()
        var conversationCalls = 0
            private set

        override fun askJson(apiKey: String, ask: JsonAsk): String {
            if (ask.system == MealReview.SYSTEM_PROMPT) {
                reviewAsks += ask
                if (reviewFails) {
                    throw com.najah.mealannouncer.data.mistral.MistralException(
                        com.najah.mealannouncer.data.mistral.MistralErrorKind.NETWORK,
                        "Mistral injoignable",
                    )
                }
                return reviews.removeFirstOrNull() ?: NO_OPINION
            }
            if (ask.system != MealConversation.SYSTEM_PROMPT) return general
            conversationCalls++
            asks += ask
            return queue.removeFirst()
        }
    }

    private companion object {
        const val NO_OPINION = """{"raisonnement":"rien à redire","lignes":[],"total_estime_g":null,"question":null}"""

        fun product(id: String, name: String, per100g: Double? = null, per100ml: Double? = null) = FoodProduct(
            id = id,
            barcode = null,
            name = name,
            brand = null,
            carbsPer100g = per100g,
            carbsPer100ml = per100ml,
            source = FoodSource.OPEN_FOOD_FACTS_ONLINE,
        )
    }

    // ── Relecture et garde-fous (docs/DECISIONS.md § 43) ─────────────────────────────────────

    /**
     * Le cas du 29/10/2026, tel que le petit modèle l'avait compris : « thé » (trouvé en poudre
     * sucrée à 37 g pour 100 g) et trois sucres à 10 g pièce → 122,5 g. Les garde-fous codés en dur
     * doivent, à eux seuls, ramener le total sous 20 g.
     */
    @Test
    fun `the a trois sucres — la poudre est ecartee et le morceau ramene a 5,5 g`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"thé","quantity":1,"unit":"cup","grams":250,"assumption":"une tasse ≈ 250 ml","raw_text":"un thé"},
                   {"id":2,"name":"sucre blanc en morceaux","quantity":3,"unit":"unit","grams":30,"assumption":"1 morceau ≈ 10 g","raw_text":"trois sucres"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            general = """{"name":"thé infusé","carbs_per_100g":0.2}""",
        )
        val engine = engine(
            chat,
            local = mapOf(
                "thé" to listOf(product("lipton", "Thé citron instantané", per100g = 37.0)),
                "sucre blanc en morceaux" to listOf(product("sucre", "Sucre blanc en morceaux", per100g = 100.0)),
            ),
        )

        val step = engine.turn(engine.newSession("cle"), "J'ai mangé un thé avec trois sucres.")

        val total = step.lines.sumOf { it.carbs.grams }
        assertTrue("Le thé à trois sucres ne doit plus faire $total g", total in 15.0..20.0)
        val the = step.lines.first { it.id == 1L }
        assertNull("La poudre ne doit pas chiffrer un thé infusé", the.product)
        val sucre = step.lines.first { it.id == 2L }
        assertEquals(16.5, sucre.carbs.grams, 0.01)
        assertEquals("1 morceau ≈ 5,5 g", sucre.portionNote)
        assertTrue(step.warnings.any { it.contains("écartée") })
        assertTrue("La relecture a eu lieu", chat.reviewAsks.isNotEmpty())
        assertEquals(AI_REVIEW_MODEL, chat.reviewAsks.first().model)
        assertTrue(step.done)
        assertTrue(step.doubts.isEmpty())
    }

    /** La relecture corrige une fiche de pâtes sèches appliquée à une assiette de pâtes cuites. */
    @Test
    fun `relecture — les pates seches sont remplacees par des pates cuites`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"pâtes","quantity":300,"unit":"gram","raw_text":"une assiette de pâtes"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            review = listOf(
                """{"raisonnement":"pâtes sèches appliquées à des pâtes cuites","lignes":[{"id":1,"glucides_estimes_g":75,"plausible":false,
                   "probleme":"pâtes sèches au lieu de cuites","correction":{"nom":"pâtes cuites","grammes":250,"glucides_g":null}}],
                   "total_estime_g":75,"question":null}""",
                """{"raisonnement":"cohérent","lignes":[{"id":1,"glucides_estimes_g":72,"plausible":true,"probleme":null,"correction":null}],
                   "total_estime_g":72,"question":null}""",
            ),
        )
        val engine = engine(
            chat,
            local = mapOf(
                // Le nom de la fiche doit reprendre « pâtes » : sinon FoodNameMatcher la refuse, à juste titre.
                "pâtes" to listOf(product("spaghetti", "Pâtes spaghetti n°5", per100g = 71.0)),
                "pâtes cuites" to listOf(product("cuites", "Pâtes cuites", per100g = 28.0)),
            ),
        )
        val session = engine.newSession("cle")

        val step = engine.turn(session, "Je mange une assiette de pâtes.")

        val line = step.lines.single()
        assertEquals(70.0, line.carbs.grams, 0.01)
        assertTrue("La fiche fautive est écartée pour la suite", "off:spaghetti" in session.rejected)
        assertEquals("Relecture, correction, seconde relecture", 2, chat.reviewAsks.size)
        assertTrue(step.done)
        assertTrue(step.doubts.isEmpty())
        val message = chat.reviewAsks.first().user
        assertTrue("La relecture voit les mots de la personne", message.contains("Je mange une assiette de pâtes."))
        assertTrue("La relecture voit la valeur de la fiche", message.contains("71 g de glucides pour 100 g"))
    }

    /** Aucune fiche ne convient : la valeur du diététicien chiffre la ligne, affichée comme telle. */
    @Test
    fun `relecture — la valeur du dieteticien remplace une fiche absurde`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"coca","quantity":330,"unit":"milliliter","raw_text":"un coca zéro"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            review = listOf(
                """{"raisonnement":"coca zéro","lignes":[{"id":1,"glucides_estimes_g":0,"plausible":false,
                   "probleme":"version sucrée au lieu de zéro","correction":{"nom":null,"grammes":null,"glucides_g":0}}],
                   "total_estime_g":0,"question":null}""",
                """{"raisonnement":"ok","lignes":[{"id":1,"glucides_estimes_g":0,"plausible":true}],"total_estime_g":0,"question":null}""",
            ),
        )
        val engine = engine(chat, local = mapOf("coca" to listOf(product("coca", "Coca-Cola Original", per100ml = 10.6))))

        val step = engine.turn(engine.newSession("cle"), "Je bois un coca zéro.")

        val line = step.lines.single()
        assertEquals(0.0, line.carbs.grams, 0.01)
        assertEquals(CarbOrigin.GENERAL, line.origin)
        assertTrue(line.portionNote.orEmpty().startsWith("estimation du diététicien"))
    }

    /**
     * Essai réel du 29/10 : sucre calculé 18 g, estimé 16,5 g, mais « pas plausible » (la fiche n'était
     * pas « en morceaux »). Un écart de 1,5 g ne vaut ni question ni doute : l'écart chiffré décide.
     */
    @Test
    fun `pas plausible mais ecart minime — ni question ni doute`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"sucre en morceaux","quantity":18,"unit":"gram","raw_text":"trois sucres"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            review = listOf(
                """{"raisonnement":"fiche de sucre en poudre","lignes":[{"id":1,"glucides_estimes_g":16.5,"plausible":false,
                   "probleme":"fiche de sucre blanc, pas en morceaux","correction":null}],"total_estime_g":16.5,"question":null}""",
            ),
        )
        val engine = engine(chat, local = mapOf("sucre en morceaux" to listOf(product("sucre", "Sucre en morceaux", per100g = 100.0))))

        val step = engine.turn(engine.newSession("cle"), "J'ai mangé trois sucres.")

        assertNull(step.question)
        assertTrue(step.done)
        assertTrue(step.doubts.isEmpty())
        assertEquals("Une seule relecture suffit", 1, chat.reviewAsks.size)
    }

    /**
     * Vitesse (29/10/2026 : 28 s puis 2 min d'attente) : une recherche sur Internet coûte 6 à 11 s.
     * Un aliment de base (sans marque) est chiffré par la valeur de référence du modèle, sans Internet.
     */
    @Test
    fun `ni un aliment de base ni un produit de marque n'interrogent Internet`() = runTest {
        val web = FakeWebSearch("""{"name":"thé","carbs_per_100g":0.2,"source":"https://exemple.fr"}""")
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"thé infusé nature","quantity":1,"unit":"cup","grams":250,"raw_text":"un thé"}],
                   "question":null,"understood":true,"done":true}""",
                """{"foods":[{"id":1,"name":"pains au chocolat Carrefour","brand":"Carrefour","quantity":90,"unit":"gram"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            general = """{"name":"thé infusé","carbs_per_100g":0.2}""",
        )
        val engine = engine(chat, webSearch = web)

        val the = engine.turn(engine.newSession("cle"), "un thé").lines.single()
        assertEquals(CarbOrigin.GENERAL, the.origin)
        assertEquals("Aucune recherche sur Internet pour un thé", 0, web.calls)

        engine.turn(engine.newSession("cle"), "des pains au chocolat Carrefour")
        // 10/11/2026 : 12 à 38 s par recherche ; l'estimation et la photo du tableau la remplacent.
        assertEquals("Un produit de marque n'interroge plus Internet", 0, web.calls)
    }

    /** Un écart qui résiste : une question une fois, puis un doute qui bloque l'envoi automatique. */
    @Test
    fun `ecart persistant — une question, puis un doute`() = runTest {
        val wrong = """{"raisonnement":"trop","lignes":[{"id":1,"glucides_estimes_g":20,"plausible":false,"probleme":"portion douteuse","correction":null}],
            "total_estime_g":20,"question":"Ton gâteau, c'était une petite part ?"}"""
        val chat = ScriptedChat(
            conversation = List(2) {
                """{"foods":[{"id":1,"name":"gâteau","quantity":200,"unit":"gram","raw_text":"du gâteau"}],
                   "question":null,"understood":true,"done":true}"""
            },
            review = List(4) { wrong },
        )
        val engine = engine(chat, local = mapOf("gâteau" to listOf(product("gateau", "Gâteau", per100g = 50.0))))
        val session = engine.newSession("cle")

        val first = engine.turn(session, "Je mange du gâteau.")
        assertEquals("Ton gâteau, c'était une petite part ?", first.question)
        assertFalse(first.done)

        val second = engine.turn(session, "Non, une grosse part.")
        assertTrue(second.done)
        assertTrue("L'écart non résolu doit bloquer l'envoi automatique", second.doubts.isNotEmpty())
        val answer = chat.asks.last().user
        assertTrue("Le modèle apprend d'où venait la question", answer.contains("VÉRIFICATION DU DIÉTÉTICIEN"))
    }

    /** Relecture impossible (réseau) : jamais d'envoi automatique sans vérification. */
    @Test
    fun `relecture impossible — doute`() = runTest {
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"biscuits Prince","quantity":30,"unit":"gram"}],
                   "question":null,"understood":true,"done":true}""",
            ),
            reviewFails = true,
        )
        val engine = engine(chat, local = mapOf("biscuits Prince" to listOf(prince())))

        val step = engine.turn(engine.newSession("cle"), "30 grammes de biscuits Prince")

        assertTrue(step.done)
        assertTrue(step.doubts.single().contains("n'ont pas pu être vérifiés"))
    }

    @Test
    fun `une assiette de riz avec portions en photo propose les paliers puis retient le choix`() = runTest {
        val rizPack = com.najah.mealannouncer.feature.assistant.portions.PortionPack.parse(
            """{"format":"miam-miam-portions","version":1,"auteur":"test","aliments":[
                {"id":"rizblanccuit","nom":"Riz blanc cuit","synonymes":["riz cuit","riz blanc cuit","riz"],
                 "pour_100g":{"glucides":28},"portions":[
                     {"grammes":100,"libelle":"petite portion (100 g)"},
                     {"grammes":200,"libelle":"portion moyenne (200 g)"},
                     {"grammes":300,"libelle":"grande portion (300 g)"}
                 ]}
            ]}""",
        ).foods
        val portions = object : com.najah.mealannouncer.feature.assistant.portions.PortionLookup {
            override fun match(name: String) =
                com.najah.mealannouncer.feature.assistant.portions.PortionBank.matchIn(rizPack.map { it to true }, name)
            override fun food(productId: String) = rizPack.firstOrNull()
            override fun options(productId: String) = rizPack.single().portions.map {
                com.najah.mealannouncer.feature.assistant.portions.PortionOption(it.grams, it.label, null)
            }
        }
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"riz cuit","quantity":1,"unit":"portion","grams":250,
                   "assumption":"une assiette ≈ 250 g","raw_text":"une assiette de riz"}],
                   "question":null,"understood":true,"done":true}""",
                """{"foods":[{"id":1,"name":"riz cuit","quantity":1,"unit":"portion","grams":250,
                   "assumption":"une assiette ≈ 250 g","raw_text":"une assiette de riz"}],
                   "question":null,"understood":true,"done":true}""",
            ),
        )
        val engine = MealConversationEngine(
            chat = chat,
            pipeline = MealCarbPipeline(
                chat = chat,
                webResearcher = WebCarbResearcher(FakeWebSearch("""{"error":"inutile"}""")),
                foodRepository = FakeRepository(),
                portions = portions,
            ),
            reviewer = MealReviewer(chat),
        )
        val session = engine.newSession("cle")

        val first = engine.turn(session, "J'ai mangé une assiette de riz")
        assertFalse("La voix doit laisser choisir parmi les 3 grammages visuels", first.done)
        assertEquals(3, first.lines.single().portionOptions.size)
        assertTrue(first.lines.single().needsPortion())
        assertTrue(first.question!!.contains("100, 200 ou 300 grammes"))

        val second = engine.turn(session, "la moyenne")
        assertTrue(second.done)
        assertNull(second.question)
        assertEquals(200.0, second.lines.single().quantity, 0.01)
        assertEquals(56.0, second.lines.single().carbs.grams, 0.01)
    }

    @Test
    fun `un poids de riz sans etat fait demander cuit ou cru par l application`() = runTest {
        val rizPack = com.najah.mealannouncer.feature.assistant.portions.PortionPack.parse(
            """{"format":"miam-miam-portions","version":1,"auteur":"test","aliments":[
                {"id":"rizblanccuit","nom":"Riz blanc cuit","synonymes":["riz cuit","riz blanc cuit","riz"],
                 "pour_100g":{"glucides":28},
                 "cuisson":{"etat":"cuit","rapport_cuit_sur_cru":2.5,"pour_100g_cru":{"glucides":78}},
                 "portions":[{"grammes":200,"libelle":"portion moyenne (200 g)"}]}
            ]}""",
        ).foods
        val portions = object : com.najah.mealannouncer.feature.assistant.portions.PortionLookup {
            override fun match(name: String) =
                com.najah.mealannouncer.feature.assistant.portions.PortionBank.matchIn(rizPack.map { it to true }, name)
            override fun food(productId: String) = rizPack.firstOrNull()
            override fun options(productId: String) = emptyList<com.najah.mealannouncer.feature.assistant.portions.PortionOption>()
        }
        val chat = ScriptedChat(
            conversation = listOf(
                """{"foods":[{"id":1,"name":"riz","quantity":200,"unit":"gram","raw_text":"200 grammes de riz"}],
                   "question":null,"understood":true,"done":true}""",
                """{"foods":[{"id":1,"name":"riz cru","quantity":200,"unit":"gram","raw_text":"200 grammes de riz"}],
                   "question":null,"understood":true,"done":true}""",
            ),
        )
        val engine = MealConversationEngine(
            chat = chat,
            pipeline = MealCarbPipeline(
                chat = chat,
                webResearcher = WebCarbResearcher(FakeWebSearch("""{"error":"inutile"}""")),
                foodRepository = FakeRepository(),
                portions = portions,
            ),
            reviewer = MealReviewer(chat),
        )
        val session = engine.newSession("cle")

        val first = engine.turn(session, "J'ai mangé 200 grammes de riz")
        assertFalse("un poids sans « cuit » ni « cru » doit être précisé", first.done)
        assertTrue(first.question!!.contains("cuit"))
        assertTrue(first.question!!.contains("cru"))

        val second = engine.turn(session, "cru")
        assertTrue(
            "le modèle doit savoir que la question venait de l'application",
            chat.asks[1].user.contains("cuit ou cru"),
        )
        assertTrue("la question n'est posée qu'une fois", second.done)
        assertTrue(second.lines.single().product!!.id.endsWith(":cru"))
        assertEquals(156.0, second.lines.single().carbs.grams, 0.01)
    }

    @Test
    fun `le poids dit avec son etat ou une assiette sans poids ne posent pas la question`() {
        assertTrue(PortionQuestions.weightWithoutCookingState("200 grammes de riz"))
        assertTrue(PortionQuestions.weightWithoutCookingState("150g de pâtes"))
        assertFalse(PortionQuestions.weightWithoutCookingState("200 g de riz cuit"))
        assertFalse(PortionQuestions.weightWithoutCookingState("80 grammes de pâtes à cuire"))
        assertFalse(PortionQuestions.weightWithoutCookingState("une assiette de riz"))
    }

    private class FakeWebSearch(private val answer: String) : MistralWebSearch {
        var calls = 0
            private set

        override fun ask(apiKey: String, question: String, instructions: String): String {
            calls++
            return answer
        }
    }

    private class FakeRepository(
        private val local: Map<String, List<FoodProduct>> = emptyMap(),
        private val images: Map<String, String> = emptyMap(),
    ) : FoodRepository {
        override suspend fun productImage(barcode: String): String? = images[barcode]

        var localSearches = 0
            private set

        override fun observeFavourites(): Flow<List<FoodProduct>> = flowOf(emptyList())

        override suspend fun favourites(): List<FoodProduct> = emptyList()

        override suspend fun searchLocal(query: String, limit: Int): List<FoodProduct> {
            localSearches++
            return local[query].orEmpty()
        }

        override suspend fun searchRemote(query: String, limit: Int): List<FoodProduct> = emptyList()

        override suspend fun findByBarcode(barcode: String): FoodProduct? = null

        override suspend fun saveVerified(product: FoodProduct): FoodProduct = product

        override suspend fun markVerified(id: String): FoodProduct? = null

        override suspend fun clearCache(): Int = 0
    }
}
