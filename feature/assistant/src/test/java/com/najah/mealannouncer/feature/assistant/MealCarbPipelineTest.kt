package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.CarbUnavailableReason
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.DetectedFood
import com.najah.mealannouncer.data.mistral.JsonAsk
import com.najah.mealannouncer.data.mistral.MealExtraction
import com.najah.mealannouncer.data.mistral.MistralChat
import com.najah.mealannouncer.data.mistral.MistralWebSearch
import com.najah.mealannouncer.data.mistral.WebCarbResearcher
import com.najah.mealannouncer.data.openfoodfacts.FoodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La chaîne « phrase dictée → aliments → base de données puis Internet ».
 *
 * Ces tests portent sur la règle qui compte le plus : un aliment n'est jamais chiffré avec
 * les valeurs d'un autre, et un aliment non chiffré n'est jamais compté comme zéro.
 */
class MealCarbPipelineTest {

    @Test
    fun `un produit hors sujet ne sert pas de valeur`() = runTest {
        val pipeline = pipeline(
            extraction = "banane",
            remote = mapOf("banane" to listOf(product("Fromage Blanc Nature", carbs = 4.0))),
            web = """{"name":"banane","carbs_per_100g":20.5,"source":"https://exemple.fr/banane"}""",
        )

        val result = pipeline.analyse("cle", "une banane")

        val line = result.lines.single()
        assertNull("Un produit hors sujet ne doit pas être retenu", line.product)
        assertEquals(20.5, line.estimate?.carbsPer100g!!, 0.001)
        assertTrue(
            result.warnings.any { it.contains("rien proposé qui corresponde") },
        )
    }

    @Test
    fun `un produit sans glucides n'empêche pas la recherche sur Internet`() = runTest {
        val pipeline = pipeline(
            extraction = "tajine",
            remote = mapOf(
                "tajine" to listOf(product("Tajine de poulet", carbs = null)),
            ),
            web = """{"name":"tajine de poulet","carbs_per_100g":9.5,"source":"https://exemple.fr/tajine"}""",
        )

        val line = pipeline.analyse("cle", "un tajine de poulet").lines.single()

        assertNull(line.product)
        assertEquals(9.5, line.estimate?.carbsPer100g!!, 0.001)
    }

    @Test
    fun `un aliment introuvable partout reste non chiffré et le dit`() = runTest {
        val pipeline = pipeline(
            extraction = "inconnu",
            web = """{"error":"aucune valeur fiable trouvée"}""",
        )

        val result = pipeline.analyse("cle", "un inconnu")
        val line = result.lines.single()

        assertNull(line.origin)
        assertEquals(0.0, line.carbs.grams, 0.0001)
        assertTrue(
            result.warnings.any { it.contains("Aucune valeur ni estimation") },
        )
    }

    @Test
    fun `une estimation generaliste chiffre un aliment introuvable partout`() = runTest {
        val pipeline = pipeline(
            extraction = "lait chocolaté",
            quantity = 200.0,
            web = """{"error":"aucune valeur fiable trouvée"}""",
            general = """{"name":"lait chocolaté","carbs_per_100g":10}""",
        )

        val result = pipeline.analyse("cle", "200 g de lait chocolaté")
        val line = result.lines.single()

        assertEquals(CarbOrigin.GENERAL, line.origin)
        assertTrue("L'estimation doit être marquée comme généraliste", line.estimate?.general == true)
        assertEquals(20.0, line.carbs.grams, 0.01)
        assertTrue(result.warnings.any { it.contains("estimation généraliste") })
    }

    @Test
    fun `la valeur d'Internet ne se calcule qu'en grammes`() = runTest {
        val pipeline = pipeline(
            extraction = "banane",
            // « une banane » : la portion est connue en unités, pas en grammes.
            quantity = 1.0,
            unit = "unit",
            web = """{"name":"banane","carbs_per_100g":20.5,"source":"https://exemple.fr/banane"}""",
        )

        val line = pipeline.analyse("cle", "une banane").lines.single()

        val result = line.carbResult()
        assertTrue(result is CarbResult.Unavailable)
        assertEquals(
            CarbUnavailableReason.UNSUPPORTED_UNIT,
            (result as CarbResult.Unavailable).reason,
        )
    }

    @Test
    fun `une quantité dictée suffit à calculer avec Internet`() = runTest {
        val pipeline = pipeline(
            extraction = "banane",
            quantity = 120.0,
            web = """{"name":"banane","carbs_per_100g":20.5,"source":"https://exemple.fr/banane"}""",
        )

        val line = pipeline.analyse("cle", "120 g de banane").lines.single()

        assertEquals("120", line.quantityText)
        assertEquals(MealUnit.GRAM, line.unit)
        assertEquals(24.6, line.carbs.grams, 0.01)
    }

    @Test
    fun `un produit Open Food Facts valide est calculé sans Internet`() = runTest {
        val pipeline = pipeline(
            extraction = "riz basmati cuit",
            quantity = 200.0,
            remote = mapOf("riz basmati cuit" to listOf(product("Riz basmati", carbs = 28.0))),
            web = """{"error":"ne doit pas être appelé"}""",
        )

        val result = pipeline.analyse("cle", "200 g de riz basmati cuit")
        val line = result.lines.single()

        assertEquals(CarbOrigin.OFF, line.origin)
        assertEquals(56.0, line.carbs.grams, 0.01)
        assertTrue("Aucun avertissement attendu : ${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun `une phrase vide ne lance aucune recherche`() = runTest {
        val chat = FakeChat("""{"foods":[]}""")
        val web = CountingWebSearch("""{"error":"rien"}""")
        val pipeline = MealCarbPipeline(chat, WebCarbResearcher(web), FakeRepository())

        val result = pipeline.analyse("cle", "   ")

        assertTrue(result.lines.isEmpty())
        assertEquals("Le modèle ne doit pas être interrogé", 0, chat.calls)
        assertEquals("Internet ne doit pas être interrogé", 0, web.calls)
    }

    @Test
    fun `des aliments deja nommes suivent le meme chemin qu une phrase`() = runTest {
        // C'est le chemin de la photo d'assiette : les aliments sont déjà nommés, avec un poids
        // estimé. Ils doivent être cherchés, nommés et chiffrés exactement comme après une dictée.
        val chat = FakeChat("""{"error":"le modèle ne doit pas être interrogé"}""")
        val web = CountingWebSearch("""{"error":"Internet ne doit pas être interrogé"}""")
        val pipeline = MealCarbPipeline(
            chat = chat,
            webResearcher = WebCarbResearcher(web),
            foodRepository = FakeRepository(
                local = mapOf("riz basmati" to listOf(product("Riz basmati", carbs = 28.0))),
            ),
        )

        val resolved = pipeline.resolveLines(
            apiKey = "cle",
            foods = listOf(
                DetectedFood(name = "riz basmati", quantity = 150.0, unit = MealUnit.GRAM, rawText = "riz basmati"),
            ),
        )

        val line = resolved.lines.single()
        assertEquals("Riz basmati", line.name)
        assertEquals("150", line.quantityText)
        assertEquals(42.0, line.carbs.grams, 0.01)
        assertTrue("Aucun avertissement attendu : ${resolved.warnings}", resolved.warnings.isEmpty())
        assertEquals("Le modèle ne doit pas être interrogé", 0, chat.calls)
        assertEquals("Internet ne doit pas être interrogé", 0, web.calls)
    }

    @Test
    fun `les aliments d une photo sont numerotes dans l ordre`() = runTest {
        val pipeline = MealCarbPipeline(
            chat = FakeChat("""{"error":"inutile"}"""),
            webResearcher = WebCarbResearcher(FakeWebSearch("""{"error":"inutile"}""")),
            foodRepository = FakeRepository(
                local = mapOf(
                    "poulet roti" to listOf(product("Poulet rôti", carbs = 1.0)),
                    "riz basmati" to listOf(product("Riz basmati", carbs = 28.0)),
                ),
            ),
        )

        val resolved = pipeline.resolveLines(
            apiKey = "cle",
            foods = listOf(
                DetectedFood("poulet roti", 120.0, MealUnit.GRAM, "poulet roti"),
                DetectedFood("riz basmati", 150.0, MealUnit.GRAM, "riz basmati"),
            ),
        )

        assertEquals(listOf(1L, 2L), resolved.lines.map { it.id })
        assertEquals(listOf("Poulet rôti", "Riz basmati"), resolved.lines.map { it.name })
    }

    @Test
    fun `un aliment de photo sans poids le dit au lieu d inventer`() = runTest {
        // La photo a vu l'aliment mais n'a pas osé le peser : la question de portion viendra.
        val pipeline = MealCarbPipeline(
            chat = FakeChat("""{"error":"inutile"}"""),
            webResearcher = WebCarbResearcher(FakeWebSearch("""{"error":"inutile"}""")),
            foodRepository = FakeRepository(
                local = mapOf("salade verte" to listOf(product("Salade verte", carbs = 2.0))),
            ),
        )

        val resolved = pipeline.resolveLines(
            apiKey = "cle",
            foods = listOf(DetectedFood("salade verte", null, MealUnit.GRAM, "salade verte")),
        )

        val line = resolved.lines.single()
        assertTrue("Aucune quantité inventée", line.quantityText.isEmpty())
        assertEquals(0.0, line.carbs.grams, 0.0001)
        assertTrue(line.needsPortion())
    }

    // --- Outils de test -------------------------------------------------------

    /** Une phrase dictée réduite à un seul aliment. */
    private fun pipeline(
        extraction: String,
        quantity: Double? = null,
        unit: String = if (quantity == null) "unknown" else "gram",
        local: Map<String, List<FoodProduct>> = emptyMap(),
        remote: Map<String, List<FoodProduct>> = emptyMap(),
        web: String,
        general: String = """{"error":"pas d'estimation"}""",
    ): MealCarbPipeline {
        val quantityJson = quantity?.toString() ?: "null"
        val chat = RoutingChat(
            foods = """{"foods":[{"name":"$extraction","quantity":$quantityJson,"unit":"$unit","raw_text":"$extraction"}]}""",
            general = general,
        )
        return MealCarbPipeline(
            chat = chat,
            webResearcher = WebCarbResearcher(FakeWebSearch(web)),
            foodRepository = FakeRepository(local = local, remote = remote),
        )
    }

    /** Aiguille selon la consigne : la liste d'aliments pour l'extraction, l'estimation sinon. */
    private class RoutingChat(private val foods: String, private val general: String) : MistralChat {
        override fun askJson(apiKey: String, ask: JsonAsk): String =
            if (ask.system == MealExtraction.SYSTEM_PROMPT) foods else general
    }

    private fun product(name: String, carbs: Double?): FoodProduct = FoodProduct(
        id = name,
        barcode = null,
        name = name,
        brand = null,
        carbsPer100g = carbs,
        source = FoodSource.OPEN_FOOD_FACTS_ONLINE,
    )

    private class FakeChat(private val answer: String) : MistralChat {
        var calls = 0
            private set

        override fun askJson(apiKey: String, ask: JsonAsk): String {
            calls++
            return answer
        }
    }

    private class FakeWebSearch(private val answer: String) : MistralWebSearch {
        override fun ask(apiKey: String, question: String, instructions: String): String = answer
    }

    private class CountingWebSearch(private val answer: String) : MistralWebSearch {
        var calls = 0
            private set

        override fun ask(apiKey: String, question: String, instructions: String): String {
            calls++
            return answer
        }
    }

    private class FakeRepository(
        private val local: Map<String, List<FoodProduct>> = emptyMap(),
        private val remote: Map<String, List<FoodProduct>> = emptyMap(),
    ) : FoodRepository {

        override fun observeFavourites(): Flow<List<FoodProduct>> = flowOf(emptyList())

        override suspend fun favourites(): List<FoodProduct> = emptyList()

        override suspend fun searchLocal(query: String, limit: Int): List<FoodProduct> =
            local[query].orEmpty()

        override suspend fun searchRemote(query: String, limit: Int): List<FoodProduct> =
            remote[query].orEmpty()

        override suspend fun findByBarcode(barcode: String): FoodProduct? = null

        override suspend fun saveVerified(product: FoodProduct): FoodProduct = product

        override suspend fun markVerified(id: String): FoodProduct? = null

        override suspend fun clearCache(): Int = 0
    }
}
