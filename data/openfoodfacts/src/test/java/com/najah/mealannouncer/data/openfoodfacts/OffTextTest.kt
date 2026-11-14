package com.najah.mealannouncer.data.openfoodfacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La normalisation doit être identique à celle du script qui construit la base : c'est elle
 * qui fait qu'une recherche accentuée trouve un produit indexé sans accent.
 */
class OffTextTest {

    @Test
    fun `les accents et les majuscules disparaissent`() {
        assertEquals("creme fraiche", OffText.normalize("Crème Fraîche"))
        assertEquals("peche", OffText.normalize("PÊCHE"))
    }

    @Test
    fun `la ponctuation devient une espace`() {
        assertEquals("riz basmati 100 g", OffText.normalize("Riz basmati, 100 g."))
        assertEquals("lait demi ecreme", OffText.normalize("lait demi-écrémé"))
    }

    @Test
    fun `les mots de liaison et les lettres seules sont écartés`() {
        assertEquals(listOf("riz", "basmati", "cuit"), OffText.tokens("200 g de riz basmati cuit"))
    }

    @Test
    fun `une requête sans mot utile ne rend rien`() {
        assertTrue(OffText.tokens("de la").isEmpty())
        assertTrue(OffText.tokens("   ").isEmpty())
    }

    @Test
    fun `les mots répétés ne comptent qu'une fois`() {
        assertEquals(listOf("banane"), OffText.tokens("banane banane"))
    }

    @Test
    fun `les mesures et les contenants ne sont jamais cherchés`() {
        // « grammes » est un mot de huit lettres : sans cette règle, il passait pour le mot
        // le plus important et ramenait des purées Picard au lieu du riz demandé.
        assertEquals(listOf("riz", "basmati", "cuit"), OffText.tokens("200 grammes de riz basmati cuit"))
        assertEquals(listOf("yaourt", "nature"), OffText.tokens("un pot de yaourt nature"))
        assertEquals(listOf("jus", "orange"), OffText.tokens("un verre de jus d'orange"))
        assertEquals(listOf("coca"), OffText.tokens("33 cl de coca"))
    }

    @Test
    fun `les essais vont de la phrase entière au mot le plus long`() {
        // On retire d'abord « cuit », le qualificatif final, et non « riz », qui nomme l'aliment.
        assertEquals(
            listOf(
                "riz* basmati* cuit*",
                "riz* basmati*",
                "riz* cuit*",
                "basmati* cuit*",
                "basmati*",
            ),
            OffText.matchAttempts("riz basmati cuit"),
        )
    }

    @Test
    fun `les essais restent peu nombreux`() {
        val essais = OffText.matchAttempts("riz basmati cuit avec des légumes et du poulet")
        assertTrue("trop d'essais : $essais", essais.size <= 5)
        assertEquals("riz* basmati* cuit* legumes* poulet*", essais.first())
    }

    @Test
    fun `un seul mot ne donne qu'un essai`() {
        assertEquals(listOf("banane*"), OffText.matchAttempts("une banane"))
        assertTrue(OffText.matchAttempts("de la").isEmpty())
    }
}
