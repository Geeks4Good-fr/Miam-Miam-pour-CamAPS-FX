package com.najah.mealannouncer.data.mistral

import org.json.JSONObject

/** Ce que le diététicien propose pour une ligne jugée fausse. */
data class ReviewCorrection(
    /** Un meilleur nom pour relancer la recherche (« thé infusé nature »), ou `null`. */
    val name: String?,
    /** Le bon poids total (g, ou ml pour une boisson), ou `null`. */
    val grams: Double?,
    /** Sa valeur de glucides pour la ligne, quand aucune fiche ne conviendra, ou `null`. */
    val carbs: Double?,
)

/** L'avis du diététicien sur une ligne du repas. */
data class ReviewedLine(
    val id: Long,
    /** Son estimation indépendante des glucides de la ligne. */
    val estimatedCarbs: Double?,
    val plausible: Boolean,
    val problem: String?,
    val correction: ReviewCorrection?,
)

/** La relecture complète d'un repas. */
data class MealReviewResult(
    val reasoning: String,
    val lines: List<ReviewedLine>,
    val totalEstimate: Double?,
    /** Une question à poser à voix haute quand il ne peut pas trancher seul, sinon `null`. */
    val question: String?,
) {
    fun line(id: Long): ReviewedLine? = lines.firstOrNull { it.id == id }
}

/**
 * La relecture des glucides par un modèle qui raisonne (`docs/DECISIONS.md` § 43).
 *
 * Né du thé à trois sucres compté 123 g (29/10/2026) : l'application chiffrait chaque ligne avec
 * la fiche trouvée sans jamais se demander si le résultat avait du sens. Le modèle donne ici **sa
 * propre estimation**, ligne par ligne, avec son raisonnement ; l'application la compare au calcul
 * et ne laisse rien partir vers CamAPS tant qu'un écart n'est pas résolu. La consigne a été
 * éprouvée sur l'API réelle avant d'être écrite ici (thé + sucres, pâtes sèches, coca zéro,
 * et deux repas justes qui ne doivent pas être signalés).
 */
object MealReview {

    private const val MAX_CARBS = 500.0
    private const val MIN_GRAMS = 1.0
    private const val MAX_GRAMS = 3_000.0
    private const val MAX_TEXT = 300

    val SYSTEM_PROMPT: String = """
        Tu es diététicien, spécialiste du comptage des glucides pour les personnes diabétiques de type 1 en boucle fermée. Une application vient de calculer les glucides d'un repas à partir d'une base de produits (Open Food Facts) ou d'Internet. Ces bases se trompent souvent de produit : produit sec ou en poudre appliqué à une boisson ou à un plat cuit, version sucrée au lieu de « zéro », poids d'une pièce absurde, portion mal estimée. Une erreur de glucides fait injecter trop ou pas assez d'insuline : c'est dangereux.

        Ton travail : RELIRE chaque ligne avec ton bon sens et tes connaissances nutritionnelles, comme le ferait un diététicien expérimenté, puis dire si le calcul est plausible.

        Méthode, pour chaque ligne :
        1. Comprends ce que la personne a réellement mangé ou bu, d'après SES mots (pas d'après le nom de la fiche).
        2. Estime toi-même les glucides de cette ligne avec des valeurs de référence françaises (Ciqual) et des portions courantes. Repères utiles : thé, café, infusion nature ≈ 0 g ; un morceau de sucre ≈ 5 à 6 g (5,5 g de glucides) ; une cuillère à café de sucre ≈ 5 g ; pâtes, riz, semoule CUITS ≈ 25 à 30 g pour 100 g (secs ≈ 70 à 75 g) ; pain ≈ 50 g pour 100 g, une baguette entière ≈ 250 g ; une canette = 330 ml ; soda normal ≈ 10,6 g pour 100 ml, soda « zéro » ou « light » ≈ 0 g ; lait ≈ 4,8 g pour 100 ml ; une pomme moyenne ≈ 15 g ; une banane moyenne ≈ 22 g.
        3. Compare avec le calcul de l'application. Vérifie la fiche retenue (le bon produit ? sec ou cuit ? en poudre ou prêt à boire ? sucré ou zéro ?), la quantité (unité, poids d'une pièce, volume) et l'arithmétique.
        4. La ligne est « plausible » si ton estimation et le calcul de l'application sont proches (écart de moins de 10 g, ou de moins de 30 % pour un gros repas). Sinon, elle ne l'est pas : explique en une phrase courte le problème, et propose une correction.

        Une correction possible pour une ligne (sinon null) :
        - « nom » : un meilleur nom d'aliment pour relancer la recherche dans la base (ex. « thé infusé nature », « spaghetti cuits », « Coca-Cola zéro »), ou null ;
        - « grammes » : le bon poids total en grammes (ou ml pour une boisson) quand c'est la quantité qui est fausse (ex. 3 morceaux de sucre → 16,5), ou null ;
        - « glucides_g » : ta valeur de glucides pour cette ligne quand aucune fiche ne conviendra (thé nature → 0), ou null.

        Si tu ne peux PAS trancher sans demander (ex. on ne sait pas si le soda était normal ou zéro, ou si les pâtes étaient pesées sèches), écris une seule « question » courte, au tutoiement, qui se pose à l'oral, sur la ligne la plus importante. Sinon question = null. Ne pose pas de question quand ton estimation suffit.

        Réfléchis d'abord, pas à pas, dans « raisonnement » (quelques phrases), PUIS donne le résultat. Réponds UNIQUEMENT par un objet JSON de cette forme :
        {"raisonnement":"…","lignes":[{"id":1,"glucides_estimes_g":0,"plausible":false,"probleme":"…","correction":{"nom":"thé infusé nature","grammes":null,"glucides_g":0}}],"total_estime_g":16.5,"question":null}
    """.trimIndent()

    /** Lit la relecture sans rien lui céder : nombres bornés, textes tronqués, lignes sans id ignorées. */
    fun parse(json: String): MealReviewResult {
        val root = extractJsonObject(json)
        val array = root.optJSONArray("lignes") ?: throw MistralException(
            MistralErrorKind.INVALID_RESPONSE,
            "La relecture ne contient pas de lignes",
        )
        val lines = (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", 0L).takeIf { it > 0L } ?: return@mapNotNull null
            ReviewedLine(
                id = id,
                estimatedCarbs = number(item, "glucides_estimes_g", 0.0, MAX_CARBS),
                plausible = item.optBoolean("plausible", true),
                problem = text(item, "probleme"),
                correction = item.optJSONObject("correction")?.let { c ->
                    ReviewCorrection(
                        name = text(c, "nom")?.take(80),
                        grams = number(c, "grammes", MIN_GRAMS, MAX_GRAMS),
                        carbs = number(c, "glucides_g", 0.0, MAX_CARBS),
                    ).takeIf { it.name != null || it.grams != null || it.carbs != null }
                },
            )
        }
        return MealReviewResult(
            reasoning = text(root, "raisonnement").orEmpty(),
            lines = lines,
            totalEstimate = number(root, "total_estime_g", 0.0, MAX_CARBS * 4),
            question = text(root, "question"),
        )
    }

    private fun text(obj: JSONObject, key: String): String? =
        if (obj.isNull(key)) null else obj.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }?.take(MAX_TEXT)

    private fun number(obj: JSONObject, key: String, min: Double, max: Double): Double? {
        if (obj.isNull(key)) return null
        val value = when (val raw = obj.opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return value.takeIf { !it.isNaN() && it in min..max }
    }
}
