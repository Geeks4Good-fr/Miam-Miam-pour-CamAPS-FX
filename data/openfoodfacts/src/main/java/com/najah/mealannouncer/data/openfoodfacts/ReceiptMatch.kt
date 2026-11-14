package com.najah.mealannouncer.data.openfoodfacts

/**
 * Garde-fou du rattachement d'un article de facture à une fiche Open Food Facts.
 *
 * Essai du 10/11/2026 : « bananes » (fruit frais, ≈ 20 g de glucides pour 100 g) avait été relié
 * à « Bananes Cavendish séchées » (84,5 g pour 100 g) parce qu'un seul mot commun suffisait. Une
 * banane annoncée aurait compté quatre fois trop. Désormais :
 * - le mot principal de l'article doit figurer dans la fiche, et au moins la moitié de ses mots ;
 * - une fiche qui porte une transformation absente de l'article (séché, sirop, compote, jus…)
 *   est refusée ;
 * - une valeur hors de 0–100 g pour 100 g est refusée.
 */
object ReceiptMatch {

    /** Formes transformées : leurs glucides n'ont rien à voir avec l'aliment brut. */
    private val TRANSFORMATIONS = listOf(
        "sec", "seche", "deshydrate", "lyophilise", "confit", "sirop", "compote", "puree",
        "jus", "nectar", "chips", "confiture", "farine", "poudre", "flocon", "caramelise", "enrobe",
    )

    fun accept(itemName: String, candidateName: String, carbsPer100g: Double?): Boolean {
        if (carbsPer100g == null || carbsPer100g < 0.0 || carbsPer100g > 100.0) return false
        val itemWords = OffText.tokens(itemName).map(::stem)
        if (itemWords.isEmpty()) return false
        val candidateWords = OffText.tokens(candidateName).map(::stem)
        if (candidateWords.isEmpty()) return false

        if (candidateWords.none { same(it, itemWords.first()) }) return false
        val matched = itemWords.count { word -> candidateWords.any { same(it, word) } }
        if (matched * 2 < itemWords.size) return false

        return TRANSFORMATIONS.none { transformation ->
            candidateWords.any { same(it, transformation) } && itemWords.none { same(it, transformation) }
        }
    }

    /** Fruits, légumes et herbes du rayon frais (formes ramenées par [stem]). */
    private val FRESH_PRODUCE = setOf(
        "banane", "pomme", "poire", "clementine", "mandarine", "orange", "citron", "pamplemousse",
        "raisin", "fraise", "framboise", "myrtille", "cerise", "abricot", "peche", "nectarine",
        "prune", "kiwi", "anana", "mangue", "melon", "pasteque", "avocat", "figue", "grenade",
        "litchi", "kaki", "carotte", "tomate", "salade", "laitue", "concombre", "courgette",
        "aubergine", "poivron", "oignon", "echalote", "ail", "poireau", "chou", "brocoli",
        "epinard", "haricot", "navet", "radi", "celeri", "champignon", "potiron", "courge",
        "butternut", "endive", "betterave", "patate", "artichaut", "asperge", "fenouil",
        "persil", "basilic", "menthe", "coriandre", "ciboulette",
    )

    /**
     * Vrai pour un fruit, un légume ou une herbe sans marque, sans transformation (« Clémentines »,
     * « Raisins blancs », « Pommes de terre ») : ce n'est pas un produit industriel, la banque de
     * portions s'en charge au moment du repas. Essai du 10/11/2026 : le lecteur de factures n'a
     * marqué aucun article « frais », clémentines et raisins sont partis vers des fiches belges.
     */
    fun isFreshProduce(itemName: String, brand: String?): Boolean {
        if (!brand.isNullOrBlank()) return false
        val words = OffText.tokens(itemName).map(::stem)
        val main = words.firstOrNull() ?: return false
        if (main !in FRESH_PRODUCE) return false
        return TRANSFORMATIONS.none { t -> words.any { same(it, t) } }
    }

    /** Pluriel et féminin ramenés à une forme commune : « séchées » → « seche », « bananes » → « banane ». */
    internal fun stem(word: String): String {
        var w = word
        if (w.length > 3 && (w.endsWith("s") || w.endsWith("x"))) w = w.dropLast(1)
        if (w.length > 4 && w.endsWith("ee")) w = w.dropLast(1)
        return w
    }

    private fun same(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a)))
}
