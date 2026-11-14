package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.core.model.CarbResult
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.data.mistral.JsonAsk
import com.najah.mealannouncer.data.mistral.AI_REVIEW_MODEL
import com.najah.mealannouncer.data.mistral.MealReview
import com.najah.mealannouncer.data.mistral.MealReviewResult
import com.najah.mealannouncer.data.mistral.MistralChat
import javax.inject.Inject

/**
 * Fait relire les glucides calculés par un modèle qui raisonne (`docs/DECISIONS.md` § 43, point B).
 *
 * Le message reprend exactement la forme éprouvée sur l'API le 29/10/2026 : les mots de la
 * personne, puis, ligne par ligne, la fiche retenue, sa valeur pour 100 g ou 100 ml, la quantité
 * et le résultat. Lève [com.najah.mealannouncer.data.mistral.MistralException] en cas d'échec.
 */
class MealReviewer @Inject constructor(
    private val chat: MistralChat,
) {

    fun review(apiKey: String, phrases: List<String>, lines: List<AssistantLine>): MealReviewResult =
        MealReview.parse(
            chat.askJson(
                apiKey = apiKey,
                ask = JsonAsk(
                    system = MealReview.SYSTEM_PROMPT,
                    user = message(phrases, lines),
                    temperature = 0.0,
                    model = AI_REVIEW_MODEL,
                ),
            ),
        )

    internal companion object {

        fun message(phrases: List<String>, lines: List<AssistantLine>): String = buildString {
            append("Ce que la personne a dit : ")
            append(phrases.joinToString(" puis ") { "« ${it.trim()} »" })
            appendLine()
            appendLine("Lignes calculées par l'application :")
            lines.forEach { line ->
                append("- #").append(line.id).append(" « ").append(line.rawText).append(" » → ")
                append(source(line))
                append(" ; quantité retenue : ").append(line.quantityText).append(' ').append(unitLabel(line.unit))
                line.portionNote?.let { append(" (« ").append(it).append(" »)") }
                when (val result = line.carbResult()) {
                    is CarbResult.Computed -> append(" → ").append(result.carbs.format()).append(" g de glucides.")
                    is CarbResult.Unavailable -> append(" → non chiffré (").append(result.explanation).append(").")
                }
                appendLine()
            }
            append("Total calculé : ").append(Carbs.of(lines.sumOf { it.carbs.grams }).format()).append(" g.")
        }

        private fun source(line: AssistantLine): String {
            val product = line.product
            val estimate = line.estimate
            return when {
                product != null -> buildString {
                    append("fiche « ").append(product.displayName()).append(" » : ")
                    val values = listOfNotNull(
                        product.carbsPer100g?.let { "${Carbs.of(it).format()} g de glucides pour 100 g" },
                        product.carbsPer100ml?.let { "${Carbs.of(it).format()} g pour 100 ml" },
                        product.carbsPerUnit?.let { "${Carbs.of(it).format()} g par pièce" },
                    )
                    append(values.ifEmpty { listOf("aucune valeur") }.joinToString(", "))
                    if (product.carbsPer100ml == null && product.carbsPer100g != null) append(" (aucune valeur pour 100 ml)")
                    // La portion imprimée sur l'emballage (« Un biscuit 20g ») : sans elle, le relecteur
                    // prenait un poids moyen et signalait à tort 13,3 g par biscuit Prince — d'où une
                    // seconde relecture de 3 s (essai du 09/11/2026 ; 30/30 justes avec elle).
                    product.servingLabel?.let { append(" (portion de l'emballage : « ").append(it).append(" »)") }
                }
                estimate?.general == true ->
                    "estimation moyenne « ${estimate.name} » : ${Carbs.of(estimate.carbsPer100g).format()} g pour 100 g"
                estimate != null ->
                    "valeur web « ${estimate.name} » : ${Carbs.of(estimate.carbsPer100g).format()} g pour 100 g"
                else -> "aucune valeur trouvée"
            }
        }
    }
}
