package com.najah.mealannouncer.data.mistral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptPhotoTest {

    @Test
    fun `lit l'enseigne et les articles alimentaires d'un ticket de caisse`() {
        val reading = ReceiptExtraction.parse(
            """{"enseigne":"Carrefour","articles":[
                {"nom":"8 pains au chocolat","marque":"Carrefour","code_barres":"3560070711994"},
                {"nom":"Brioche tranchée","marque":"Harrys","code_barres":null}
            ]}""",
        )
        assertEquals("Carrefour", reading.storeChain)
        assertEquals(2, reading.items.size)
        assertEquals(
            ReceiptItem(name = "8 pains au chocolat", brand = "Carrefour", barcode = "3560070711994"),
            reading.items[0],
        )
        assertEquals(
            ReceiptItem(name = "Brioche tranchée", brand = "Harrys", barcode = null),
            reading.items[1],
        )
    }

    @Test
    fun `ignore les codes-barres invalides`() {
        val reading = ReceiptExtraction.parse(
            """{"enseigne":null,"articles":[{"nom":"Orangina","marque":"Orangina","code_barres":"123"}]}""",
        )
        assertNull(reading.storeChain)
        assertNull(reading.items.single().barcode)
    }

    @Test
    fun `repere les produits frais au poids`() {
        val reading = ReceiptExtraction.parse(
            """{"enseigne":"Carrefour","articles":[
                {"nom":"Bananes","marque":null,"code_barres":null,"frais":true},
                {"nom":"Petit Beurre","marque":"Carrefour","code_barres":null}
            ]}""",
        )
        assertEquals("des bananes au poids sont un produit frais", true, reading.items[0].fresh)
        assertEquals("sans le champ, un article n'est pas frais", false, reading.items[1].fresh)
    }
}
