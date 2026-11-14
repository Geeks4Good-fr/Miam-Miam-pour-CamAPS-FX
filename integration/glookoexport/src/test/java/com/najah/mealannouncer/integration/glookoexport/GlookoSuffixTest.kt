package com.najah.mealannouncer.integration.glookoexport

import org.junit.Assert.assertEquals
import org.junit.Test

/** Le champ glucides de Glooko porte son unité dans son texte : l'effacer fait planter Glooko. */
class GlookoSuffixTest {

    @Test
    fun `le suffixe d'un champ vide est garde`() {
        assertEquals(" g", glookoSuffixOf(" g"))
    }

    @Test
    fun `le suffixe d'un champ deja rempli est garde`() {
        assertEquals(" g", glookoSuffixOf("12 g"))
        assertEquals(" g", glookoSuffixOf("12,5 g"))
    }

    @Test
    fun `sans texte on remet le suffixe des glucides`() {
        assertEquals(" g", glookoSuffixOf(null))
        assertEquals(" g", glookoSuffixOf("42"))
    }

    @Test
    fun `une autre unite est respectee`() {
        assertEquals(" mg/dl", glookoSuffixOf(" mg/dl"))
    }
}
