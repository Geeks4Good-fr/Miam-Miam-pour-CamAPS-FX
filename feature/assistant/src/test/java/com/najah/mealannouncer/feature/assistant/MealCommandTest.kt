package com.najah.mealannouncer.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MealCommandTest {

    @Test
    fun `annuler ou annule tout ou tous le repas est reconnu`() {
        listOf(
            "annuler tout le repas",
            "annuler tous le repas",
            "annuler tous les repas",
            "annule tout le repas",
            "annule tous le repas",
            "annule tous les repas",
            "Annuler tous le repas.",
            "Annule tout le repas !",
            "annuler le repas",
            "annule le repas",
            "annuler tout",
            "annule tout",
            "tout annuler",
            "Miam-miam, annule tout le repas",
            "non, annule tout le repas s'il te plaît",
        ).forEach { phrase ->
            assertEquals("Pour « $phrase »", MealCommand.CancelAll, MealCommand.parse(phrase))
        }
    }

    @Test
    fun `annuler ou annule le dernier aliment est reconnu`() {
        listOf(
            "annuler le dernier aliment",
            "Annuler le dernier aliment",
            "annule le dernier aliment",
            "Annule le dernier aliment.",
            "annuler dernier aliment",
            "annule dernier aliment",
            "annuler le dernier produit",
            "annule le dernier produit",
            "retirer le dernier aliment",
            "retire le dernier aliment",
            "supprimer le dernier aliment",
            "supprime le dernier aliment",
            "enlever le dernier aliment",
            "enlève le dernier aliment",
            "Miam-miam annule le dernier aliment",
        ).forEach { phrase ->
            assertEquals("Pour « $phrase »", MealCommand.RemoveLastFood, MealCommand.parse(phrase))
        }
    }

    @Test
    fun `ajoute et rajoute ont la meme fonction avec ou sans aliment`() {
        assertEquals(MealCommand.AddFood(""), MealCommand.parse("ajoute"))
        assertEquals(MealCommand.AddFood(""), MealCommand.parse("rajoute"))
        assertEquals(MealCommand.AddFood(""), MealCommand.parse("Ajouter"))
        assertEquals(MealCommand.AddFood(""), MealCommand.parse("Rajouter."))
        assertEquals(MealCommand.AddFood(""), MealCommand.parse("Miam-miam, ajoute"))
        assertEquals(MealCommand.AddFood(""), MealCommand.parse("rajoute au repas"))

        assertEquals(
            MealCommand.AddFood("une compote Andros"),
            MealCommand.parse("ajoute une compote Andros"),
        )
        assertEquals(
            MealCommand.AddFood("une compote Andros"),
            MealCommand.parse("rajoute une compote Andros"),
        )
        assertEquals(
            MealCommand.AddFood("deux biscuits Prince"),
            MealCommand.parse("Ajoute aussi deux biscuits Prince."),
        )
        assertEquals(
            MealCommand.AddFood("150 g de riz"),
            MealCommand.parse("Rajoute au repas 150 g de riz, s'il te plaît."),
        )
        assertEquals(
            MealCommand.AddFood("un yaourt nature"),
            MealCommand.parse("Miam-miam, rajoute un yaourt nature"),
        )
    }

    @Test
    fun `une description de repas ordinaire n'est pas une commande`() {
        listOf(
            "Je mange une pomme et deux biscuits",
            "1 gourde Andros pomme nature",
            "25 grammes de glucides",
            "repas ou collation",
            "hypoglycémie",
            "Non, ce n'est pas ce produit",
        ).forEach { phrase ->
            assertNull("« $phrase » ne doit pas être une commande", MealCommand.parse(phrase))
        }
    }
}
