package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Une contestation dite à la question du type ne doit jamais être prise pour un type de repas. */
class MealCorrectionTest {

    @Test
    fun `reconnait les contestations courantes`() {
        listOf(
            "Non, ce n'est pas ce produit",
            "non c'est du pain Gourmet de chez Carrefour",
            "c'est pas la bonne marque",
            "pas cette marque-là",
            "Il y a une erreur sur le pain",
            "ce n'est pas le bon pain pour mon repas",
        ).forEach { assertTrue("« $it » est une contestation", MealCorrection.looksLikeCorrection(it)) }
    }

    @Test
    fun `un type de repas n'est pas une contestation`() {
        listOf("repas", "Repas ou collation", "hypoglycémie", "glucides lents", "un goûter", "")
            .forEach { assertFalse("« $it » n'est pas une contestation", MealCorrection.looksLikeCorrection(it)) }
    }

    @Test
    fun `nonante ou nonobstant ne sont pas un non`() {
        assertFalse(MealCorrection.looksLikeCorrection("nonobstant un repas"))
    }
}
