package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.CarbBasis
import com.najah.mealannouncer.core.model.CarbCalculator
import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.CarbUnavailableReason
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.data.mistral.WebCarbEstimate
import java.util.Locale

/** D'où viennent les glucides d'une ligne : base, recherche web, ou estimation généraliste. */
enum class CarbOrigin {
    /** Produit trouvé dans Open Food Facts (cache local ou en ligne). */
    OFF,

    /** Valeur trouvée sur Internet, affichée avec sa source. */
    WEB,

    /** Estimation de connaissance commune (ni base ni source) : à présenter comme une estimation. */
    GENERAL,
}

/**
 * Une ligne du repas dicté : un aliment, sa quantité, et ce qu'on sait de ses glucides.
 *
 * La quantité est gardée **telle que tapée** : si l'utilisateur efface le champ pour taper
 * « 2 », il ne doit pas voir « 0 » s'imposer. Une quantité nulle veut dire « pas encore
 * dit », et une ligne sans quantité n'est jamais comptée comme zéro.
 */
data class AssistantLine(
    val id: Long,
    /** Nom affiché : le produit trouvé, sinon le nom dit par l'utilisateur. */
    val name: String,
    /** Mots de la phrase qui ont donné cet aliment. */
    val rawText: String,
    val quantityText: String,
    val unit: MealUnit,
    val product: FoodProduct? = null,
    val estimate: WebCarbEstimate? = null,
    /**
     * Le calcul qui a mené à la quantité, quand elle a été convertie à la voix
     * (« 2 biscuits × 12,5 g = 25 g »). Affiché sous la ligne : une conversion qu'on ne peut
     * pas relire est une conversion qu'il faut croire.
     */
    val portionNote: String? = null,
    /** La photo du produit chiffré, `null` quand aucune ne désigne sûrement ce produit. */
    val image: ProductImage? = null,
    /**
     * Vrai quand la fiche du produit est connue mais pas le poids d'une pièce : le poids vient
     * de l'estimation du modèle. Une photo de l'emballage (ou de ce qui a été mangé) peut lever
     * ce doute ; d'ici là, le repas ne part pas seul vers CamAPS.
     */
    val weightGuessed: Boolean = false,
    /**
     * Vrai pour un produit de marque absent d'Open Food Facts : ses glucides sont une moyenne
     * estimée par le modèle. La photo du tableau nutritionnel peut donner la vraie valeur.
     */
    val carbsGuessed: Boolean = false,
    /**
     * Les portions en images de la banque (`docs/PORTIONS.md`) : l'écran les propose, l'utilisateur
     * touche celle qui ressemble à sa part. Vide pour un aliment hors banque.
     */
    val portionOptions: List<com.najah.mealannouncer.feature.assistant.portions.PortionOption> = emptyList(),
) {

    /** Vrai quand une photo de l'emballage lèverait un doute sur cette ligne. */
    val photoWouldHelp: Boolean get() = weightGuessed || carbsGuessed

    /** Ce qu'on sait de la portion de cet aliment, pour convertir une réponse en grammes. */
    fun portionContext(): PortionContext = PortionContext(
        name = name,
        servingGrams = product?.defaultPortionGrams,
        servingLabel = product?.servingLabel,
        packageGrams = product?.packageGrams,
    )

    val origin: CarbOrigin?
        get() = when {
            product != null -> CarbOrigin.OFF
            estimate?.general == true -> CarbOrigin.GENERAL
            estimate != null -> CarbOrigin.WEB
            else -> null
        }

    val quantity: Double get() = parseQuantityText(quantityText)

    /** Vrai si la portion n'est pas connue : c'est ce que l'écran doit faire dire. */
    fun needsPortion(): Boolean = quantity <= 0.0

    /** Le calcul, ou la raison précise de ne pas pouvoir le faire. */
    fun carbResult(): CarbResult {
        if (quantityText.isNotBlank() && quantity <= 0.0) {
            return CarbResult.Unavailable(
                CarbUnavailableReason.INVALID_QUANTITY,
                "Quantité invalide : « $quantityText »",
            )
        }
        if (needsPortion()) {
            return CarbResult.Unavailable(
                CarbUnavailableReason.INVALID_QUANTITY,
                "Portion à préciser : quelle quantité avez-vous mangée ?",
            )
        }
        product?.let { return CarbCalculator.carbsFor(it, quantity, unit) }
        estimate?.let { value ->
            // Une pièce se chiffre par la fiche de l'emballage : ses glucides par pièce, ou son
            // poids (écrit, ou déduit du paquet) appliqué à la valeur pour 100 g.
            if (unit.isCounted()) {
                value.carbsPerUnit?.let { perUnit ->
                    return CarbResult.Computed(
                        carbs = Carbs.of(perUnit * quantity),
                        basis = CarbBasis.PER_UNIT,
                        referenceQuantity = quantity,
                    )
                }
                value.unitGrams?.let { perUnit ->
                    val grams = perUnit * quantity
                    return CarbResult.Computed(
                        carbs = Carbs.of(grams / 100.0 * value.carbsPer100g),
                        basis = CarbBasis.PER_100_G,
                        referenceQuantity = grams,
                        assumed = true,
                    )
                }
            }
            // Une valeur « pour 100 g » ne vaut que pour un poids : une tranche ou un
            // volume sans équivalent en grammes ne se calcule pas avec elle.
            if (unit != MealUnit.GRAM) {
                return CarbResult.Unavailable(
                    CarbUnavailableReason.UNSUPPORTED_UNIT,
                    "Valeur trouvée pour 100 g : indiquez un poids en grammes.",
                )
            }
            return CarbResult.Computed(
                carbs = Carbs.of(quantity / 100.0 * value.carbsPer100g),
                basis = CarbBasis.PER_100_G,
                referenceQuantity = quantity,
            )
        }
        return CarbResult.Unavailable(
            CarbUnavailableReason.MISSING_PRODUCT,
            "Aucune valeur trouvée : ajoutez ce repas à la main.",
        )
    }

    /** Glucides de la ligne, ou zéro **avec** la raison déjà portée par [carbResult]. */
    val carbs: Carbs get() = (carbResult() as? CarbResult.Computed)?.carbs ?: Carbs.ZERO
}

/**
 * Lit une quantité tapée ou dictée.
 *
 * La virgule française est acceptée comme le point, et les espaces de séparation des
 * milliers sont ignorés : la saisie ne doit pas être une source d'erreur.
 */
internal fun parseQuantityText(text: String): Double {
    val cleaned = text.trim()
        .replace(" ", "")
        .replace('\u00A0', ' ')
        .replace(',', '.')
    return cleaned.toDoubleOrNull() ?: 0.0
}

/** Affiche une quantité sans décimale inutile : « 150 », « 12,5 ». */
internal fun formatQuantityText(value: Double): String {
    val rounded = Carbs.of(value).rounded(2)
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.2f", rounded).trimEnd('0').trimEnd('.').replace('.', ',')
    }
}

/**
 * La photo du produit chiffré, et d'où elle vient : elle permet de reconnaître l'article d'un
 * coup d'œil. Elle n'existe que si elle désigne **ce** produit (fiche Open Food Facts du code-barres,
 * ou image de la page même qui a donné la valeur) — sinon aucune photo.
 */
data class ProductImage(
    val url: String,
    val origin: String,
)

/**
 * L'identité de ce qui chiffre une ligne — la fiche Open Food Facts, la page web ou l'estimation —
 * pour qu'un produit refusé par l'utilisateur ne revienne pas. `null` quand rien n'est retenu.
 */
fun productIdentity(product: FoodProduct?, estimate: WebCarbEstimate?): String? = when {
    product != null -> "off:${product.id}"
    estimate?.general == true -> "moyenne:${estimate.name.trim().lowercase(Locale.ROOT)}"
    estimate != null -> "web:${estimate.sourceUrl ?: estimate.name.trim().lowercase(Locale.ROOT)}"
    else -> null
}

/** Vrai pour une unité qui se compte (pièce, tranche, portion) : elle se chiffre par pièce. */
internal fun MealUnit.isCounted(): Boolean =
    this == MealUnit.UNIT || this == MealUnit.SLICE || this == MealUnit.PORTION

/** Libellé de l'unité, tel qu'il s'affiche sous la quantité. */
internal fun unitLabel(unit: MealUnit): String = when (unit) {
    MealUnit.GRAM -> "g"
    MealUnit.MILLILITER -> "ml"
    MealUnit.UNIT -> "unité(s)"
    MealUnit.PORTION -> "portion(s)"
    MealUnit.SLICE -> "tranche(s)"
    MealUnit.CUP -> "tasse(s)"
    MealUnit.UNKNOWN -> "unité(s)"
}

/** Unité suivante dans le cycle du bouton d'unité. */
internal fun nextUnit(unit: MealUnit): MealUnit = when (unit) {
    MealUnit.GRAM -> MealUnit.MILLILITER
    MealUnit.MILLILITER -> MealUnit.UNIT
    MealUnit.UNIT -> MealUnit.SLICE
    MealUnit.SLICE -> MealUnit.PORTION
    MealUnit.PORTION -> MealUnit.GRAM
    MealUnit.CUP -> MealUnit.GRAM
    MealUnit.UNKNOWN -> MealUnit.GRAM
}

/**
 * Unité proposée au premier appui sur la puce d'unité.
 *
 * Une valeur trouvée sur Internet est donnée pour 100 grammes : seule une masse peut la
 * calculer. Pour ces lignes, la puce va donc directement au gramme plutôt que de faire
 * défiler des unités que le calcul refusera.
 */
internal fun nextUnitFor(line: AssistantLine): MealUnit =
    if (line.estimate != null && line.unit != MealUnit.GRAM) MealUnit.GRAM else nextUnit(line.unit)
