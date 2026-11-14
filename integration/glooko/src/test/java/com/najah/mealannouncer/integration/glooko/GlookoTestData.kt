package com.najah.mealannouncer.integration.glooko

import java.time.Instant

/**
 * Fabrique un CSV Glooko XT de synthèse, avec la même forme que l'export réel.
 *
 * Les vraies données de l'export du 24/10/2026 ne sont pas versionnées : une semaine de
 * glycémies n'a rien à faire dans un dépôt. En revanche les **particularités** observées,
 * elles, sont reproduites ici — glycémies triplées, bolus « normal » et « calculator » qui se
 * contredisent, glucides sans bolus, débits incohérents dans la même minute, alertes en
 * double. Ce sont elles qui cassent un importeur naïf.
 */
class GlookoCsv {

    private val rows = mutableListOf<String>()

    fun row(
        time: String = "",
        bg: String = "",
        bolus: String = "",
        bolusType: String = "",
        durationMs: String = "",
        basal: String = "",
        event: String = "",
        carbs: String = "",
        settings: String = "",
        bgDevice: String = "",
    ): GlookoCsv {
        val cells = MutableList(COLUMN_COUNT) { "" }
        cells[0] = time
        cells[1] = PUMP_DEVICE
        cells[2] = bgDevice
        cells[3] = bg
        cells[4] = bolus
        cells[10] = basal
        cells[12] = if (basal.isNotEmpty()) "scheduled" else ""
        cells[13] = durationMs
        cells[14] = bolusType
        cells[17] = event
        cells[18] = settings
        cells[19] = carbs
        rows += cells.joinToString(SEPARATOR)
        return this
    }

    fun build(timezone: String = TIMEZONE): String = buildString {
        appendLine("sep=;")
        appendLine("VERSION;3")
        appendLine("TIMEZONE;$timezone")
        appendLine("PERIOD;17/10/2026 to 24/10/2026")
        appendLine(HEADER)
        rows.forEach { appendLine(it) }
    }

    companion object {
        const val SEPARATOR = ";"
        const val COLUMN_COUNT = 31
        const val TIMEZONE = "Europe/Paris"
        const val PUMP_DEVICE = "CamAPS_10110670_Y_"

        private val HEADER = listOf(
            "Date", "Pump device", "BG device", "BG", "Bolus", "Bolus source",
            "Bolus extra", "Bolus total", "Bolus delay", "Bolus duration",
            "Basal", "Basal source", "Basal type", "Duration", "Bolus type",
            "Injection correction", "Priming", "Event", "Settings", "Carbs",
            "Carbs source", "Food", "Note", "Glucose source", "Glucose type",
            "Glucose note", "Exercise", "Illness", "Injection", "Site",
            "Comment",
        ).joinToString(SEPARATOR)

        /** Un champ CSV entouré de guillemets, comme Glooko écrit le JSON des réglages. */
        fun quoted(json: String): String = "\"" + json.replace("\"", "\"\"") + "\""

        /** Réglages de pompe de référence, proches de ceux de l'export réel. */
        fun settingsJson(
            time: String = "2026-10-17T03:29:07.942Z",
            schedule: String = "1",
        ): String = """
            {"time":"$time","deviceId":"CamAPS_10110670_Y_","activeSchedule":"$schedule",
            "basalSchedules":{"$schedule":[{"rate":2,"start":0}]},
            "carbRatios":{"$schedule":[{"amount":1.5,"start":0}]},
            "insulinSensitivities":{"$schedule":[{"amount":15,"start":0}]},
            "glucoseTargetLow":70.26,"glucoseTargetHigh":180.15,"glucoseTarget":104.49,
            "iobDuration":18000,"units":{"bg":"mg/dl"},"automatedDelivery":true}
        """.trimIndent().replace("\n", "")

        /** Instant UTC correspondant à une heure locale d'Europe/Paris. */
        fun paris(iso: String): Long =
            java.time.OffsetDateTime.parse(iso + "+02:00").toInstant().toEpochMilli()
    }
}

/** Un export minimal mais complet, réutilisé par plusieurs tests. */
object GlookoSample {

    /** Instant de référence : 17/10/2026 16:00 à Paris, soit 14:00 UTC. */
    val NOW_MS: Long = Instant.parse("2026-10-17T14:00:00Z").toEpochMilli()

    fun options(
        nowMs: Long = NOW_MS,
        windowHours: Long = 48,
        includeSgv: Boolean = true,
        dryRun: Boolean = false,
        /** Zéro : les tests ne font jamais attendre la reprise d'une écriture. */
        retryPauseMs: Long = 0L,
    ): GlookoImportOptions = GlookoImportOptions(
        nowMs = nowMs,
        windowHours = windowHours,
        includeSgv = includeSgv,
        dryRun = dryRun,
        retryPauseMs = retryPauseMs,
    )

    fun export(): GlookoExport = GlookoParser.parse(
        GlookoCsv()
            // Glycémie triplée, identique à la décimale près.
            .row(time = "17/10/2026 12:00", bg = "225.01")
            .row(time = "17/10/2026 12:00", bg = "225.01")
            .row(time = "17/10/2026 12:00", bg = "225.01")
            .row(time = "17/10/2026 12:05", bg = "180")
            // Repas : bolus normal et calculator d'accord, avec des glucides.
            .row(time = "17/10/2026 12:30", bolus = "5.0", bolusType = "normal", carbs = "60")
            .row(time = "17/10/2026 12:30", bolus = "5.0", bolusType = "calculator")
            // Bolus calculé qui contredit la pompe.
            .row(time = "17/10/2026 22:59", bolus = "9.89", bolusType = "normal")
            .row(time = "17/10/2026 22:59", bolus = "30", bolusType = "calculator")
            // Glucides sans bolus.
            .row(time = "17/10/2026 13:15", carbs = "20")
            // Micro-bolus étendu.
            .row(time = "17/10/2026 13:40", bolus = "0.03", bolusType = "extended", durationMs = "480")
            // Débits : minute 0, minute 1, puis trou de deux minutes.
            .row(time = "17/10/2026 10:00", basal = "2")
            .row(time = "17/10/2026 10:01", basal = "7.36")
            .row(time = "17/10/2026 10:01", basal = "7.36")
            .row(time = "17/10/2026 10:03", basal = "1.5")
            // Modes de pompe, dont deux à la même minute avec des durées différentes.
            .row(time = "17/10/2026 12:10", event = "pumpMode - Attempting", durationMs = "26000")
            .row(time = "17/10/2026 12:10", event = "pumpMode - Attempting", durationMs = "6232000")
            .row(time = "17/10/2026 13:00", event = "pumpMode - Closed loop", durationMs = "3600000")
            // Alerte de sécurité en double, strictement identique.
            .row(time = "17/10/2026 14:00", event = "Alerte de sécurité - Capteur", durationMs = "60000")
            .row(time = "17/10/2026 14:00", event = "Alerte de sécurité - Capteur", durationMs = "60000")
            .row(time = "17/10/2026 15:00", event = "ReservoirChange")
            .row(time = "17/10/2026 15:30", event = "pumpSettingsOverride - boost", durationMs = "7200000")
            .row(time = "17/10/2026 15:45", event = "info - Total daily dose")
            .row(time = "17/10/2026 15:50", event = "cgm settings")
            // Réglages, en double comme dans l'export réel.
            .row(time = "17/10/2026 03:29", event = "pump settings", settings = GlookoCsv.quoted(GlookoCsv.settingsJson()))
            .row(time = "17/10/2026 05:29", event = "pump settings", settings = GlookoCsv.quoted(GlookoCsv.settingsJson(time = "2026-10-17T05:29:00.000Z")))
            // Hors fenêtre de 48 h.
            .row(time = "10/10/2026 08:00", bg = "120")
            .build(),
    )
}
