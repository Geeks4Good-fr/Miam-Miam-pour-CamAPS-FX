package com.najah.mealannouncer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServingTextTest {

    @Test
    fun `un libelle de deux biscuits donne le compte d'articles`() {
        assertEquals(2.0, ServingText.itemsPerServing("2 biscuits (25 g)")!!, 0.0)
        assertEquals(1.0, ServingText.itemsPerServing("1 verre (200 ml)")!!, 0.0)
        assertEquals(3.0, ServingText.itemsPerServing("3 tranches (30 g)")!!, 0.0)
    }

    @Test
    fun `un poids seul n'est pas un compte d'articles`() {
        assertNull(ServingText.itemsPerServing("25 g"))
        assertNull(ServingText.itemsPerServing("100 ml"))
        assertNull(ServingText.itemsPerServing("1,5 kg"))
    }

    @Test
    fun `un poids suivi de l'article donne le compte d'articles`() {
        // « 20 g, un biscuit » : c'est la fiche réelle des Prince. Le poids vient en premier,
        // mais l'article dit à quoi il correspond — sans quoi « deux biscuits » resterait
        // intraduisible pour le produit le plus courant.
        assertEquals(1.0, ServingText.itemsPerServing("20 g, un biscuit")!!, 0.0)
        assertEquals("biscuit", ServingText.itemWord("20 g, un biscuit"))
    }

    @Test
    fun `un poids suivi d'un article entre parentheses est lu aussi`() {
        assertEquals(1.0, ServingText.itemsPerServing("8.4g (1 biscuit)")!!, 0.0)
        assertEquals("biscuit", ServingText.itemWord("8.4g (1 biscuit)"))
        assertEquals(2.0, ServingText.itemsPerServing("50 g (2 biscuits)")!!, 0.0)
    }

    @Test
    fun `un poids suivi d'un nombre d'articles donne ce nombre`() {
        assertEquals(2.0, ServingText.itemsPerServing("20 g, 2 biscuits")!!, 0.0)
        assertEquals("biscuit", ServingText.itemWord("20 g, 2 biscuits"))
    }

    @Test
    fun `un nombre rencontre plus loin n'est pas la portion`() {
        // Une phrase de conseil ne doit pas se transformer en « 3 jours de 6,67 g ».
        assertNull(ServingText.itemsPerServing("20 g, à consommer dans les 3 jours"))
        assertNull(ServingText.itemsPerServing("100 g (dont 5 g de sucres)"))
        assertNull(ServingText.itemWord("20 g, à consommer dans les 3 jours"))
    }

    @Test
    fun `un libelle sans nombre ne dit rien`() {
        assertNull(ServingText.itemsPerServing("33"))
        assertNull(ServingText.itemsPerServing(null))
        assertNull(ServingText.itemsPerServing(""))
        // « une portion » compte pour **une** portion : c'est ce que dit la fiche, et
        // « deux portions » doit pouvoir devenir deux fois le poids de la portion.
        assertEquals(1.0, ServingText.itemsPerServing("une portion")!!, 0.0)
        assertNull(ServingText.itemWord("33"))
    }

    @Test
    fun `un article ecrit en toutes lettres compte pour un`() {
        // Fiches réelles : « Un biscuit 20 g » (Prince tout choco), « Une gaufre 70 g ».
        // Avant, ces libellés n'étaient pas lus : le produit était refusé en mode « unité(s) »
        // alors que sa fiche nommait pourtant l'article.
        assertEquals(1.0, ServingText.itemsPerServing("Un biscuit 20 g")!!, 0.0)
        assertEquals("biscuit", ServingText.itemWord("Un biscuit 20 g"))
        assertEquals(20.0, ServingText.gramsPerItem("Un biscuit 20 g", 20.0)!!, 0.0)

        assertEquals(1.0, ServingText.itemsPerServing("Une gaufre 70 g")!!, 0.0)
        assertEquals("gaufre", ServingText.itemWord("Une gaufre 70 g"))
        assertEquals(70.0, ServingText.gramsPerItem("Une gaufre 70 g", 70.0)!!, 0.0)

        // Un mot qui commence par « un » n'est pas un compte.
        assertNull(ServingText.itemsPerServing("Unique (30 g)"))
        assertNull(ServingText.itemsPerServing("Union jack (30 g)"))
    }

    @Test
    fun `une fraction d'article n'est pas un compte d'articles`() {
        // « 1/2 biscuit » n'est pas « 1 biscuit » : le lire comme un entier doublerait les
        // glucides. On préfère ne rien lire, et laisser l'application demander des grammes.
        assertNull(ServingText.itemsPerServing("1/2 biscuit 15 g"))
        assertNull(ServingText.gramsPerItem("1/2 biscuit 15 g", 15.0))
    }

    @Test
    fun `un compte deraisonnable est refuse`() {
        assertNull(ServingText.itemsPerServing("200 biscuits (25 g)"))
        assertNull(ServingText.itemsPerServing("0 biscuits (25 g)"))
    }

    @Test
    fun `le mot de l'article est au singulier et sans accent`() {
        assertEquals("biscuit", ServingText.itemWord("2 biscuits (25 g)"))
        assertEquals("verre", ServingText.itemWord("1 verre (200 ml)"))
        assertEquals("tranche", ServingText.itemWord("3 tranches (30 g)"))
        assertEquals("peche", ServingText.itemWord("2 pêches (150 g)"))
        assertEquals("biscuit", ServingText.itemWord("1 Biscuit (12 g)"))
    }

    @Test
    fun `un libelle qui commence par un poids n'a pas de mot d'article`() {
        assertNull(ServingText.itemWord("25 g"))
        assertNull(ServingText.itemWord("100 ml"))
        assertNull(ServingText.itemWord(""))
    }

    @Test
    fun `un poids se lit dans un texte libre`() {
        assertEquals(200.0, ServingText.gramsIn("200 g")!!, 0.0)
        assertEquals(1500.0, ServingText.gramsIn("1,5 kg")!!, 0.0)
        assertEquals(330.0, ServingText.gramsIn("33 cl")!!, 0.0)
        assertEquals(500.0, ServingText.gramsIn("0,5 l")!!, 0.0)
        assertEquals(0.5, ServingText.gramsIn("500 mg")!!, 0.0)
        assertEquals(250.0, ServingText.gramsIn("Poids net : 250 grammes")!!, 0.0)
    }

    @Test
    fun `un texte sans poids ne donne rien`() {
        assertNull(ServingText.gramsIn("un paquet"))
        assertNull(ServingText.gramsIn(""))
        assertNull(ServingText.gramsIn(null))
        assertNull(ServingText.gramsIn("40 000 g"))
    }

    @Test
    fun `le poids d'un article se deduit de la portion`() {
        // « 2 biscuits (25 g) » : une portion de 25 g compte deux biscuits, donc 12,5 g chacun.
        assertEquals(12.5, ServingText.gramsPerItem("2 biscuits (25 g)", 25.0)!!, 0.0)
        // « 20 g, un biscuit » : la fiche réelle des Prince goût vanille.
        assertEquals(20.0, ServingText.gramsPerItem("20 g, un biscuit", 20.0)!!, 0.0)
        // « 8.4g (1 biscuit) » : un biscuit de 8,4 g.
        assertEquals(8.4, ServingText.gramsPerItem("8.4g (1 biscuit)", 8.4)!!, 0.0)
        // « 15 biscuits (85g/2,998 Oz) » : 5,67 g par biscuit environ.
        assertEquals(85.0 / 15.0, ServingText.gramsPerItem("15 biscuits (85g/2,998 Oz)", 85.0)!!, 0.0001)
    }

    @Test
    fun `un libelle sans article ne donne pas de poids par article`() {
        // Sans article nommé, une portion n'est pas un article : on refuse de le supposer.
        assertNull(ServingText.gramsPerItem("20g", 20.0))
        assertNull(ServingText.gramsPerItem("12,5g", 12.5))
        assertNull(ServingText.gramsPerItem("25 g", 25.0))
        assertNull(ServingText.gramsPerItem(null, 20.0))
        assertNull(ServingText.gramsPerItem("20 g, un biscuit", null))
    }

    @Test
    fun `un poids par article deraisonnable est refuse`() {
        // 200 articles pour 20 g : le compte dépasse ce qu'une portion peut être (100).
        assertNull(ServingText.gramsPerItem("200 biscuits (20 g)", 20.0))
        // Un biscuit de 0,4 g : sous la borne basse de 0,5 g par article.
        assertNull(ServingText.gramsPerItem("1 biscuit (0,4 g)", 0.4))
        // Un biscuit de 5 kg : au-delà de la borne haute de 2 000 g.
        assertNull(ServingText.gramsPerItem("1 biscuit (5000 g)", 5_000.0))
        // Une portion de poids nul n'a pas d'article.
        assertNull(ServingText.gramsPerItem("2 biscuits (25 g)", 0.0))
    }
}
