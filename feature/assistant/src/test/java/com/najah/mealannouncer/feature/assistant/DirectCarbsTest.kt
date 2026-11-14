package com.najah.mealannouncer.feature.assistant

import com.najah.mealannouncer.integration.camaps.CamapsMealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectCarbsTest {

    @Test
    fun `un nombre de grammes seul est un total dicte`() {
        assertEquals(DirectCarbs(25, null, false), DirectCarbs.parse("25 grammes"))
        assertEquals(DirectCarbs(40, null, false), DirectCarbs.parse("40 g de glucides."))
        assertEquals(DirectCarbs(25, null, false), DirectCarbs.parse("Annonce 25 grammes"))
    }

    @Test
    fun `type et Carnet Glooko dans la meme phrase`() {
        assertEquals(
            DirectCarbs(25, CamapsMealType.MEAL, true),
            DirectCarbs.parse("annonce 25 grammes, repas, et note Glooko"),
        )
        assertEquals(DirectCarbs(15, CamapsMealType.HYPO, false), DirectCarbs.parse("15 grammes hypo"))
        assertEquals(
            DirectCarbs(60, CamapsMealType.SLOW, true),
            DirectCarbs.parse("60 grammes de glucides lents et annote le carnet Glooko"),
        )
    }

    @Test
    fun `un aliment n'est jamais pris pour un total`() {
        assertNull(DirectCarbs.parse("25 grammes de pâtes"))
        assertNull(DirectCarbs.parse("200 grammes de riz et une pomme"))
        assertNull(DirectCarbs.parse("deux steaks charal 5%"))
        assertNull(DirectCarbs.parse("une tasse de lait chocolaté"))
    }

    @Test
    fun `hors bornes — la phrase part au calcul normal`() {
        assertNull(DirectCarbs.parse("0 gramme"))
        assertNull(DirectCarbs.parse("450 grammes"))
    }
}
