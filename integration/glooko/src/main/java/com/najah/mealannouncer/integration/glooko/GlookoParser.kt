package com.najah.mealannouncer.integration.glooko

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Lecteur d'un export Glooko XT.
 *
 * Le fichier réel a été disséqué avant d'écrire ce code (export du 24/10/2026) :
 * 31 colonnes, séparateur `;`, en-tête `Date;…` en huitième ligne, `TIMEZONE;Europe/Paris`
 * trois lignes plus haut, dates `JJ/MM/AAAA HH:MM` sans secondes. Rien ici ne dépend de la
 * position absolue des lignes : on cherche la structure, on ne compte pas les lignes.
 */
object GlookoParser {

    const val COLUMN_COUNT = 31

    private const val SEPARATOR = ';'

    private val HEADER_PREFIX = "Date$SEPARATOR"
    private val TIMEZONE_PREFIX = "TIMEZONE$SEPARATOR"
    private val PERIOD_PREFIX = "PERIOD$SEPARATOR"

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT)

    private val PERIOD_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    /** Bornes de recherche de l'en-tête : au-delà, ce n'est pas un export Glooko. */
    private const val HEADER_SEARCH_LINES = 60

    // Indices des colonnes utiles, relevés sur l'export réel.
    private const val DATE = 0
    private const val PUMP_DEVICE = 1
    private const val BG_DEVICE = 2
    private const val BG = 3
    private const val BOLUS = 4
    private const val DURATION_MS = 13
    private const val BOLUS_TYPE = 14
    private const val BASAL = 10
    private const val BASAL_DELIVERY = 12
    private const val EVENT = 17
    private const val SETTINGS = 18
    private const val CARBS = 19

    /**
     * Lit un export complet.
     *
     * @throws GlookoParseException si le fichier n'est pas un export exploitable ; l'appelant
     *   peut alors le dire sans rien envoyer et réessayer au cycle suivant.
     */
    fun parse(text: String): GlookoExport {
        val lines = text.lineSequence().toList()

        val headerIndex = lines.indexOfFirst { it.startsWith(HEADER_PREFIX) }
            .takeIf { it >= 0 && it < HEADER_SEARCH_LINES }
            ?: throw GlookoParseException(
                GlookoParseProblem.NO_HEADER,
                "Aucune ligne d'en-tête « Date;… » : ce fichier n'est pas un export Glooko XT.",
            )

        val header = lines[headerIndex].split(SEPARATOR)
        if (header.size < COLUMN_COUNT) {
            throw GlookoParseException(
                GlookoParseProblem.NO_HEADER,
                "En-tête incomplet : ${header.size} colonnes au lieu de $COLUMN_COUNT.",
                headerIndex + 1,
            )
        }

        val zone = readTimezone(lines, headerIndex)
        val (periodStart, periodEnd) = readPeriod(lines, headerIndex)

        val rows = ArrayList<GlookoRow>(lines.size - headerIndex)
        val settings = ArrayList<GlookoPumpSettings>()
        var lineNumber = headerIndex

        for (index in headerIndex + 1 until lines.size) {
            lineNumber = index + 1
            val line = lines[index]
            if (line.isBlank()) continue
            val fields = line.split(SEPARATOR)
            if (fields.size < COLUMN_COUNT) {
                // Une dernière ligne courte est le signe d'un fichier encore en écriture :
                // on préfère refuser l'export que poster un repas amputé.
                throw GlookoParseException(
                    GlookoParseProblem.TRUNCATED_ROW,
                    "Ligne $lineNumber incomplète : ${fields.size} colonnes au lieu de $COLUMN_COUNT.",
                    lineNumber,
                )
            }

            val row = readRow(fields, zone, lineNumber)
            if (row.isNoise) continue
            rows += row
            row.settingsRaw?.let { settings += GlookoPumpSettings(row.timestamp, it) }
        }

        if (rows.isEmpty()) {
            throw GlookoParseException(
                GlookoParseProblem.EMPTY,
                "L'export ne contient aucun évènement exploitable.",
            )
        }

        return GlookoExport(
            timezone = zone,
            periodStart = periodStart,
            periodEnd = periodEnd,
            rows = rows,
            pumpSettings = settings,
        )
    }

    private fun readTimezone(lines: List<String>, headerIndex: Int): ZoneId {
        val line = lines.take(headerIndex).firstOrNull { it.startsWith(TIMEZONE_PREFIX) }
            ?: throw GlookoParseException(
                GlookoParseProblem.NO_TIMEZONE,
                "L'en-tête « TIMEZONE;… » manque : les heures ne peuvent pas être situées.",
            )
        val name = line.removePrefix(TIMEZONE_PREFIX).trim()
        return try {
            ZoneId.of(name)
        } catch (error: Exception) {
            throw GlookoParseException(
                GlookoParseProblem.UNKNOWN_TIMEZONE,
                "Zone horaire inconnue : « $name ».",
            )
        }
    }

    private fun readPeriod(lines: List<String>, headerIndex: Int): Pair<LocalDate?, LocalDate?> {
        val line = lines.take(headerIndex).firstOrNull { it.startsWith(PERIOD_PREFIX) } ?: return null to null
        val value = line.removePrefix(PERIOD_PREFIX).trim()
        val parts = value.split(" to ", " – ", " - ")
        if (parts.size != 2) return null to null
        return parseDateOrNull(parts[0]) to parseDateOrNull(parts[1])
    }

    private fun parseDateOrNull(value: String): LocalDate? = try {
        LocalDate.parse(value.trim(), PERIOD_DATE)
    } catch (error: DateTimeParseException) {
        null
    }

    private fun readRow(fields: List<String>, zone: ZoneId, lineNumber: Int): GlookoRow {
        val rawDate = fields[DATE].trim()
        val timestamp = try {
            LocalDateTime.parse(rawDate, DATE_FORMAT).atZone(zone).toInstant()
        } catch (error: DateTimeParseException) {
            throw GlookoParseException(
                GlookoParseProblem.BAD_DATE,
                "Ligne $lineNumber : date illisible « $rawDate ».",
                lineNumber,
            )
        }

        return GlookoRow(
            lineNumber = lineNumber,
            timestamp = timestamp,
            pumpDevice = fields.text(PUMP_DEVICE),
            bgDevice = fields.text(BG_DEVICE),
            bgMgdl = fields.number(BG),
            bolusUnits = fields.number(BOLUS),
            bolusType = fields.text(BOLUS_TYPE)?.lowercase(Locale.ROOT),
            durationMs = fields.number(DURATION_MS)?.toLong(),
            basalRate = fields.number(BASAL),
            basalDeliveryType = fields.text(BASAL_DELIVERY)?.lowercase(Locale.ROOT),
            event = fields.text(EVENT),
            carbsGrams = fields.number(CARBS),
            settingsRaw = fields.text(SETTINGS),
        )
    }

    private fun List<String>.text(index: Int): String? =
        getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Un nombre, ou `null`. Le fichier écrit ses décimales avec un point ; une valeur
     * illisible (cellule bricolée) est ignorée plutôt que de faire échouer tout l'import.
     */
    private fun List<String>.number(index: Int): Double? =
        text(index)?.replace(',', '.')?.toDoubleOrNull()
}

/** Horodatage lisible pour les journaux, toujours en UTC. */
internal fun Instant.logLabel(): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(this)
