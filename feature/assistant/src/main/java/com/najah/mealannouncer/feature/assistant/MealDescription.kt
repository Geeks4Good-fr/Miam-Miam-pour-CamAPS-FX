package com.najah.mealannouncer.feature.assistant

/**
 * Compose la description écrite d'un repas, pour le médecin (Carnet Glooko, note Nightscout).
 *
 * Pure et testable. La description reprend les aliments et leurs portions, par exemple
 * « biscuits Prince tout choco (40 g), riz (150 g) ». Un aliment sans portion précisée est
 * nommé sans quantité, jamais compté. **Aucune glycémie, aucune insuline** : seulement ce qui
 * a été mangé — c'est ce qui aide le diabétologue à relire la courbe.
 */
object MealDescription {

    fun of(lines: List<AssistantLine>): String =
        lines
            .filter { it.name.isNotBlank() }
            .joinToString(", ") { line -> describe(line) }

    private fun describe(line: AssistantLine): String {
        val quantity = line.quantityText.trim()
        return if (quantity.isBlank() || line.quantity <= 0.0) {
            line.name
        } else {
            "${line.name} ($quantity ${unitLabel(line.unit)})"
        }
    }
}
