package com.najah.mealannouncer.data.openfoodfacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Essai réel du 10/11/2026 : des bananes fraîches reliées à des bananes séchées. */
class ReceiptMatchTest {

    @Test
    fun `des bananes ne deviennent jamais des bananes sechees`() {
        assertFalse(
            "fruit frais relié à un fruit séché",
            ReceiptMatch.accept("Bananes", "Bananes Cavendish séchées en vrac Bio", 84.5),
        )
    }

    @Test
    fun `une transformation dite sur la facture reste acceptee`() {
        assertTrue(ReceiptMatch.accept("Abricots secs", "Abricots secs moelleux", 60.0))
        assertTrue(ReceiptMatch.accept("Jus de pomme", "Pur jus de pomme", 10.5))
        assertTrue(ReceiptMatch.accept("Saucisse sèche pur porc", "SAUCISSE SÈCHE Pur porc", 3.2))
    }

    @Test
    fun `un fruit frais ne devient ni compote ni sirop`() {
        assertFalse(ReceiptMatch.accept("Pommes", "Compote de pommes", 22.0))
        assertFalse(ReceiptMatch.accept("Fraises", "Sirop de fraise", 80.0))
    }

    @Test
    fun `le mot principal de l article doit figurer dans la fiche`() {
        assertFalse(
            "un seul mot secondaire en commun ne suffit pas",
            ReceiptMatch.accept("Pain au chocolat", "Chocolat noir 70 %", 30.0),
        )
        assertTrue(ReceiptMatch.accept("8 pains au chocolat", "8 pains au chocolat", 46.0))
    }

    @Test
    fun `les vrais produits de la facture du 10 novembre passent`() {
        assertTrue(ReceiptMatch.accept("Côtes de porc désossées marinées", "Côtes de porc désossées marinées", 0.5))
        assertTrue(ReceiptMatch.accept("Biscuits Prince chocolat", "Prince - Biscuits fourrés goût chocolat", 69.0))
        assertTrue(ReceiptMatch.accept("Lait demi-écrémé UHT", "Lait Demi-écrémé stérilisé UHT", 4.0))
        assertTrue(ReceiptMatch.accept("Chips à l'ancienne nature", "Chips à l'Ancienne Nature", 48.0))
    }

    @Test
    fun `fruits et legumes sans marque restent a la banque de portions`() {
        assertTrue(ReceiptMatch.isFreshProduce("Clémentines", null))
        assertTrue(ReceiptMatch.isFreshProduce("Raisins blancs", null))
        assertTrue(ReceiptMatch.isFreshProduce("Bananes", null))
        assertTrue(ReceiptMatch.isFreshProduce("Pommes de terre", null))
        assertFalse("un jus n'est pas un fruit frais", ReceiptMatch.isFreshProduce("Jus de raisin", null))
        assertFalse("une compote non plus", ReceiptMatch.isFreshProduce("Pommes compote", null))
        assertFalse("un produit de marque passe par la base", ReceiptMatch.isFreshProduce("Betteraves rouges", "Carrefour"))
        assertFalse(ReceiptMatch.isFreshProduce("Petit Beurre", null))
    }

    @Test
    fun `une valeur absente ou impossible est refusee`() {
        assertFalse(ReceiptMatch.accept("Riz", "Riz basmati", null))
        assertFalse(ReceiptMatch.accept("Riz", "Riz basmati", 140.0))
    }
}
