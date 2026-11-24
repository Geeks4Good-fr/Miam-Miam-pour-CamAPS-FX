package com.najah.mealannouncer.core.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode Liberty (décision de l'équipe médicale du 24/11/2026) : sous Liberty, l'automate ne touche
 * plus jamais à « Ajouter un repas » de CamAPS FX, même si l'insertion automatique était cochée.
 */
class MealRecordConfigLibertyTest {

    @Test
    fun `par defaut la question liberty reste a poser`() {
        val config = MealRecordConfig()

        assertFalse(config.libertyAnswered)
        assertFalse(config.liberty)
        assertTrue(config.announcesToCamaps())
    }

    @Test
    fun `sous liberty rien ne va dans camaps meme avec l insertion automatique cochee`() {
        val config = MealRecordConfig(camapsAutoInsert = true, liberty = true, libertyAnswered = true)

        assertFalse(config.announcesToCamaps())
        assertFalse(config.camapsAutoActive())
    }

    @Test
    fun `sans liberty l insertion automatique reste celle du reglage`() {
        assertTrue(MealRecordConfig(camapsAutoInsert = true).camapsAutoActive())
        assertFalse(MealRecordConfig(camapsAutoInsert = false).camapsAutoActive())
    }

    @Test
    fun `liberty ne coupe pas le suivi`() {
        val config = MealRecordConfig(liberty = true, suiviDiabetologue = true)

        assertTrue(config.writesGlookoCarnet())
        assertTrue(config.writesNightscoutNote())
    }
}
