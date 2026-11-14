package com.najah.mealannouncer.integration.glooko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Lecture de l'export : structure, fuseau, et refus net quand le fichier n'est pas le bon. */
class GlookoParserTest {

    @Test
    fun `lit la zone et la periode de l entete`() {
        val export = GlookoSample.export()
        assertEquals("Europe/Paris", export.timezone.id)
        assertEquals("2026-10-17", export.periodStart?.toString())
        assertEquals("2026-10-24", export.periodEnd?.toString())
        assertTrue(export.rows.isNotEmpty())
    }

    @Test
    fun `convertit une heure locale en UTC`() {
        val export = GlookoSample.export()
        val firstBg = export.rows.first { it.bgMgdl != null && it.event == null }
        // 17/10/2026 12:00 à Paris (UTC+2 avant le changement d'heure) = 10:00 UTC.
        assertEquals(Instant.parse("2026-10-17T10:00:00Z"), firstBg.timestamp)
        assertEquals(225.01, firstBg.bgMgdl!!, 0.001)
    }

    @Test
    fun `ne lit aucune donnee d identite`() {
        val export = GlookoSample.export()
        // Le bloc patient du fichier n'est jamais conservé : aucune ligne ne porte de nom.
        assertTrue(export.rows.none { it.pumpDevice?.contains("@") == true })
    }

    @Test
    fun `refuse un fichier sans entete`() {
        val error = assertThrows(GlookoParseException::class.java) {
            GlookoParser.parse("TIMEZONE;Europe/Paris\n1;2;3\n")
        }
        assertEquals(GlookoParseProblem.NO_HEADER, error.problem)
    }

    @Test
    fun `refuse un fichier sans zone horaire`() {
        val csv = GlookoCsv().row(time = "17/10/2026 12:00", bg = "120").build()
            .lineSequence()
            .filterNot { it.startsWith("TIMEZONE;") }
            .joinToString("\n")
        val error = assertThrows(GlookoParseException::class.java) { GlookoParser.parse(csv) }
        assertEquals(GlookoParseProblem.NO_TIMEZONE, error.problem)
    }

    @Test
    fun `refuse une zone horaire inconnue`() {
        val csv = GlookoCsv().row(time = "17/10/2026 12:00", bg = "120").build(timezone = "Mars/Olympus")
        val error = assertThrows(GlookoParseException::class.java) { GlookoParser.parse(csv) }
        assertEquals(GlookoParseProblem.UNKNOWN_TIMEZONE, error.problem)
    }

    @Test
    fun `refuse une ligne tronquee plutot que d importer un repas ampute`() {
        val csv = GlookoCsv().row(time = "17/10/2026 12:00", bg = "120").build() +
            "17/10/2026 12:05;CamAPS\n"
        val error = assertThrows(GlookoParseException::class.java) { GlookoParser.parse(csv) }
        assertEquals(GlookoParseProblem.TRUNCATED_ROW, error.problem)
    }

    @Test
    fun `refuse une date illisible`() {
        val csv = GlookoCsv().row(time = "32/13/2026 99:99", bg = "120").build()
        val error = assertThrows(GlookoParseException::class.java) { GlookoParser.parse(csv) }
        assertEquals(GlookoParseProblem.BAD_DATE, error.problem)
    }

    @Test
    fun `refuse un export sans aucune ligne exploitable`() {
        val csv = GlookoCsv().row(time = "17/10/2026 12:00").build()
        val error = assertThrows(GlookoParseException::class.java) { GlookoParser.parse(csv) }
        assertEquals(GlookoParseProblem.EMPTY, error.problem)
    }

    @Test
    fun `lit un nombre a virgule et ignore le bruit`() {
        val csv = GlookoCsv()
            .row(time = "17/10/2026 12:00", bolus = "1,25", bolusType = "normal")
            .row(time = "17/10/2026 12:01")
            .build()
        val export = GlookoParser.parse(csv)
        assertEquals(1, export.rows.size)
        assertEquals(1.25, export.rows.single().bolusUnits!!, 0.001)
    }

    @Test
    fun `lit les reglages de pompe en double`() {
        val export = GlookoSample.export()
        assertEquals(2, export.pumpSettings.size)
        assertNotNull(export.pumpSettings.first().raw)
    }
}
