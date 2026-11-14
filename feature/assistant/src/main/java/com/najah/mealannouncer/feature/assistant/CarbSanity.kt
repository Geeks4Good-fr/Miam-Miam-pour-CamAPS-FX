package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.ConversationFood
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Garde-fous des glucides **codés en dur** (`docs/DECISIONS.md` § 43, point C).
 *
 * Ils ne dépendent d'aucune IA et passent avant la relecture : ce sont les erreurs grossières
 * constatées le 29/10/2026 (thé à trois sucres compté 123 g) qui ne doivent plus jamais pouvoir
 * se reproduire, quel que soit le modèle.
 */
object CarbSanity {

    /** Poids d'un morceau de sucre (g) : 4 à 7 g en France, 5,5 g en moyenne. */
    private const val SUGAR_CUBE_MIN = 4.0
    private const val SUGAR_CUBE_MAX = 7.0
    const val SUGAR_CUBE_GRAMS = 5.5

    /** Au-delà, le repas n'est jamais envoyé seul vers CamAPS : il se vérifie à l'écran. */
    const val MAX_LINE_CARBS = 120.0
    const val MAX_TOTAL_CARBS = 150.0

    private val BREWED = listOf("the", "cafe", "infusion", "tisane", "expresso", "espresso", "rooibos", "matcha latte")
    private val DRY_FORMS = listOf(
        "poudre", "instantane", "soluble", "granule", "lyophilise", "sachet", "dosette", "capsule",
        "feuille", "vrac", "en grains", "moulu", "concentre", "sirop",
    )

    /**
     * Vrai si [product] ne peut pas chiffrer [food] : une boisson infusée (thé, café, tisane) ou
     * mesurée en volume ne prend jamais la valeur d'une poudre, de feuilles, d'un sirop ou d'un
     * produit sec sans valeur « pour 100 ml ».
     */
    fun incompatible(food: ConversationFood, product: FoodProduct?): Boolean {
        product ?: return false
        val said = normalize("${food.name} ${food.rawText}")
        val sheet = normalize("${product.name} ${product.brand.orEmpty()}")
        val brewed = BREWED.any { word(said, it) }
        val drink = brewed || food.unit == MealUnit.MILLILITER || food.unit == MealUnit.CUP
        if (!drink) return false
        if (DRY_FORMS.any { sheet.contains(it) } && DRY_FORMS.none { said.contains(it) }) return true
        // Un thé, un café ou une tisane infusés n'ont presque pas de glucides : une fiche qui en
        // annonce plus de 5 g pour 100 g sans valeur « pour 100 ml » est une forme sèche ou sucrée.
        val per100g = product.carbsPer100g
        return brewed && product.carbsPer100ml == null && per100g != null && per100g > BREWED_MAX_PER_100G
    }

    private const val BREWED_MAX_PER_100G = 5.0

    /**
     * Ramène le poids d'un morceau de sucre dans la réalité : « trois sucres » comptés à 10 g pièce
     * deviennent 3 × 5,5 g = 16,5 g, avec l'hypothèse affichée.
     */
    fun fixSugarCubes(food: ConversationFood): ConversationFood {
        val said = normalize("${food.name} ${food.rawText}")
        // L'aliment EST du sucre (« sucre en morceaux », « morceaux de sucre ») — pas un aliment
        // « sucré » : sans accents, « biscuits sucrés » contient aussi « sucres ».
        val name = normalize(food.name).trim()
        val cubes = name.startsWith("sucre") || name.contains("morceau de sucre") ||
            name.contains("morceaux de sucre")
        if (!cubes || food.unit != MealUnit.UNIT) return food
        if (said.contains("poudre") || said.contains("cuill")) return food
        val count = food.quantity?.takeIf { it > 0 } ?: return food
        val perPiece = food.grams?.let { it / count }
        if (perPiece != null && perPiece in SUGAR_CUBE_MIN..SUGAR_CUBE_MAX) return food
        return food.copy(
            name = if (said.contains("morceau")) food.name else "sucre en morceaux",
            grams = count * SUGAR_CUBE_GRAMS,
            assumption = "1 morceau ≈ 5,5 g",
        )
    }

    /**
     * Un écart qui compte entre le calcul de l'application et l'estimation du diététicien :
     * plus de 8 g, et plus d'un quart de la plus grande des deux valeurs.
     */
    fun discrepant(app: Double, review: Double): Boolean {
        val gap = abs(app - review)
        return gap > MIN_GAP && gap > RELATIVE_GAP * max(app, review)
    }

    private const val MIN_GAP = 8.0
    private const val RELATIVE_GAP = 0.25

    /** Un total ou une ligne trop gros pour partir seuls vers CamAPS. */
    fun tooLarge(lineCarbs: List<Double>): Boolean =
        lineCarbs.any { it > MAX_LINE_CARBS } || lineCarbs.sum() > MAX_TOTAL_CARBS

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('’', '\'')

    /** [needle] comme mot entier (« the » ne doit pas trouver « theorique »). */
    private fun word(haystack: String, needle: String): Boolean =
        Regex("(^|[^a-z])${Regex.escape(needle)}([^a-z]|$)").containsMatchIn(haystack)
}
