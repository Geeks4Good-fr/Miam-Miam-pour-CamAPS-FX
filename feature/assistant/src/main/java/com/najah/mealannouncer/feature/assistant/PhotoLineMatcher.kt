package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.data.mistral.PackageReading

/**
 * Rattache une photo d'emballage, lue à l'aveugle, à une ligne du repas.
 *
 * C'est l'application qui choisit, pas le modèle de vision : à qui l'on donnait la liste du repas,
 * il « voyait » l'aliment de la liste (essai du 10/11/2026). Ici, l'aliment vu doit partager un
 * mot significatif avec la ligne (« pâté » avec « Pâté en croûte Monterrat ») ; à égalité ou sans
 * aucun mot commun, rien n'est rattaché — mieux vaut le dire que chiffrer la mauvaise ligne.
 */
internal object PhotoLineMatcher {

    private val STOP_WORDS = setOf(
        "de", "du", "des", "la", "le", "les", "au", "aux", "et", "avec", "sans", "en", "un", "une",
        "paquet", "sachet", "emballage", "boite", "barre", "piece", "pieces", "classique", "classiques",
    )

    fun match(reading: PackageReading, candidates: List<AssistantLine>): AssistantLine? {
        if (candidates.size == 1) {
            // Bouton d'une ligne précise : on vérifie seulement que la photo ne montre pas
            // clairement autre chose (aucun mot commun alors que l'aliment vu est nommé).
            val only = candidates.single()
            val seen = words(listOfNotNull(reading.visibleFood, reading.product).joinToString(" "))
            return only.takeIf { seen.isEmpty() || score(seen, only) > 0 }
        }
        val seen = words(listOfNotNull(reading.visibleFood, reading.product).joinToString(" "))
        if (seen.isEmpty()) return null
        val scored = candidates.map { it to score(seen, it) }.filter { it.second > 0 }
        val best = scored.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { scored.count { it.second == best.second } == 1 }
    }

    private fun score(seen: Set<String>, line: AssistantLine): Int =
        words(line.name + " " + line.rawText).count { word -> seen.any { it.startsWith(word) || word.startsWith(it) } }

    private fun words(text: String): Set<String> =
        FoodNameMatcher.normalize(text).split(' ')
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
}
