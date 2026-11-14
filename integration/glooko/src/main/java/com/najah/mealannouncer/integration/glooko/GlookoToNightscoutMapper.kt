package com.najah.mealannouncer.integration.glooko

import com.najah.mealannouncer.data.nightscout.NightscoutDocument
import org.json.JSONObject
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Ce que devient une ligne du CSV : un document Nightscout d'une famille donnée. */
enum class GlookoDocumentKind(val collection: NightscoutDocument, val label: String) {
    SGV(NightscoutDocument.ENTRIES, "Glycémies"),
    MEAL_BOLUS(NightscoutDocument.TREATMENTS, "Bolus repas"),
    CORRECTION_BOLUS(NightscoutDocument.TREATMENTS, "Bolus correction"),
    COMBO_BOLUS(NightscoutDocument.TREATMENTS, "Bolus prolongés"),
    CARB_CORRECTION(NightscoutDocument.TREATMENTS, "Glucides seuls"),
    TEMP_BASAL(NightscoutDocument.TREATMENTS, "Débits temporaires"),
    PUMP_MODE(NightscoutDocument.TREATMENTS, "Modes de pompe"),
    OVERRIDE(NightscoutDocument.TREATMENTS, "Objectifs temporaires"),
    RESERVOIR(NightscoutDocument.TREATMENTS, "Changements de réservoir"),
    TOTAL_DAILY_DOSE(NightscoutDocument.TREATMENTS, "Dose totale quotidienne"),
    SAFETY_ALERT(NightscoutDocument.TREATMENTS, "Alertes de sécurité"),
}

/** Pourquoi une ligne n'a pas produit de document. Compté, jamais deviné. */
enum class GlookoSkip {
    DISABLED,
    OUTSIDE_WINDOW,
    DUPLICATE_IN_FILE,
    UNMAPPED_EVENT,
    PROFILE_ONLY,
    INVALID_VALUE,
}

/** Un document prêt à être écrit. */
data class GlookoDocument(
    val kind: GlookoDocumentKind,
    val identifier: String,
    val timestampMs: Long,
    val payload: String,
)

/** Le profil de traitement, écrit à part et mis à jour en place. */
data class GlookoProfileDocument(
    val identifier: String,
    val payload: String,
    val contentHash: String,
)

/** Le résultat du convertisseur : tout ce qu'il faut écrire, et le compte de tout ce qui est écarté. */
data class GlookoPlan(
    val documents: List<GlookoDocument>,
    val profile: GlookoProfileDocument?,
    val counts: Map<GlookoDocumentKind, Int>,
    val skipped: Map<GlookoSkip, Int>,
) {
    val entryCount: Int get() = documents.count { it.kind.collection == NightscoutDocument.ENTRIES }
    val treatmentCount: Int get() = documents.size - entryCount
    val isEmpty: Boolean get() = documents.isEmpty() && profile == null
}

/** Réglages de la conversion, exposés dans les préférences. */
data class GlookoPlanOptions(
    val nowMs: Long,
    val windowHours: Long = DEFAULT_WINDOW_HOURS,
    val includeSgv: Boolean = true,
    val includeProfile: Boolean = true,
    val enteredBy: String = DEFAULT_ENTERED_BY,
    val device: String = DEFAULT_DEVICE,
) {
    val cutoffMs: Long get() = nowMs - windowHours * 3_600_000L

    companion object {
        const val DEFAULT_WINDOW_HOURS = 48L
        const val DEFAULT_ENTERED_BY = "Glooko-CamAPS"
        const val DEFAULT_DEVICE = "Glooko-CamAPS"
    }
}

/**
 * Convertit un export Glooko en documents Nightscout.
 *
 * Le CSV n'est pas une base de données : il répète, il arrondit, il laisse des trous. Le
 * convertisseur prend le parti de ce qui est vérifiable — une valeur par minute pour les
 * débits, une valeur par horodatage pour les glycémies — et compte, sans les taire, les
 * lignes qu'il écarte.
 *
 * Fonction pure : ni accès réseau, ni horloge implicite, ni état. C'est ce qui permet de la
 * tester sur l'export réel.
 */
class GlookoToNightscoutMapper {

    fun plan(export: GlookoExport, options: GlookoPlanOptions): GlookoPlan {
        val counts = LinkedHashMap<GlookoDocumentKind, Int>()
        val skipped = LinkedHashMap<GlookoSkip, Int>()
        val documents = ArrayList<GlookoDocument>()

        fun count(kind: GlookoDocumentKind) {
            counts[kind] = (counts[kind] ?: 0) + 1
        }

        fun skip(reason: GlookoSkip, times: Int = 1) {
            skipped[reason] = (skipped[reason] ?: 0) + times
        }

        fun add(kind: GlookoDocumentKind, timestampMs: Long, businessKey: String, payload: String) {
            val identifier = GlookoIdentifier.of(kind.name.lowercase(Locale.ROOT), timestampMs, businessKey)
            // L'identifiant voyage avec le document : c'est lui que la v3 reconnaît pour
            // mettre à jour au lieu de créer.
            documents += GlookoDocument(
                kind = kind,
                identifier = identifier,
                timestampMs = timestampMs,
                payload = JSONObject(payload).put("identifier", identifier).toString(),
            )
            count(kind)
        }

        // ---- Glycémies -------------------------------------------------------------
        // Trois lignes par minute, valeurs identiques : une seule suffit.
        if (!options.includeSgv) {
            skip(GlookoSkip.DISABLED, export.rows.count { it.bgMgdl != null })
        } else {
            val byTimestamp = LinkedHashMap<Long, Double>()
            for (row in export.rows) {
                val bg = row.bgMgdl ?: continue
                if (byTimestamp.containsKey(row.timestampMs)) {
                    skip(GlookoSkip.DUPLICATE_IN_FILE)
                } else {
                    byTimestamp[row.timestampMs] = bg
                }
            }
            for ((timestampMs, raw) in byTimestamp) {
                if (timestampMs < options.cutoffMs) {
                    skip(GlookoSkip.OUTSIDE_WINDOW)
                    continue
                }
                // Le CSV mêle mg/dL et une valeur recalculée : 225,01 pour 225.
                val sgv = raw.roundToInt()
                if (sgv <= 0 || sgv > MAX_SGV) {
                    skip(GlookoSkip.INVALID_VALUE)
                    continue
                }
                add(GlookoDocumentKind.SGV, timestampMs, "sgv", entryPayload(timestampMs, sgv, options.device))
            }
        }

        // ---- Repas -----------------------------------------------------------------
        val carbsByTimestamp = export.rows
            .mapNotNull { row -> row.carbsGrams?.let { row.timestampMs to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.sum() }

        val bolusByTimestamp = export.rows
            .filter { it.bolusUnits != null }
            .groupBy { it.timestampMs }

        for ((timestampMs, rows) in bolusByTimestamp) {
            if (timestampMs < options.cutoffMs) {
                skip(GlookoSkip.OUTSIDE_WINDOW, rows.size)
                continue
            }
            val normal = rows.firstOrNull { it.bolusType == BOLUS_NORMAL }?.bolusUnits
            val calculator = rows.firstOrNull { it.bolusType == BOLUS_CALCULATOR }?.bolusUnits
            val extended = rows.firstOrNull { it.bolusType == BOLUS_EXTENDED }
            val delivered = normal ?: calculator ?: extended?.bolusUnits
            if (delivered == null || delivered <= 0.0) {
                skip(GlookoSkip.INVALID_VALUE, rows.size)
                continue
            }

            val carbs = carbsByTimestamp[timestampMs]?.takeIf { it > 0.0 }
            val extendedMinutes = extended?.durationMs?.let { it / 60_000L }?.toInt()?.takeIf { it >= 1 }

            val notes = buildList {
                if (normal != null && calculator != null && normal != calculator) {
                    add("CamAPS a calculé $calculator U")
                }
                if (extended != null && extendedMinutes == null) {
                    add("bolus étendu ${formatUnits(extended.bolusUnits ?: 0.0)} U en ${extended.durationMs ?: 0} ms")
                }
            }.joinToString(". ").takeIf { it.isNotEmpty() }

            val kind = when {
                extended != null && extendedMinutes != null -> GlookoDocumentKind.COMBO_BOLUS
                carbs != null -> GlookoDocumentKind.MEAL_BOLUS
                else -> GlookoDocumentKind.CORRECTION_BOLUS
            }
            val eventType = when (kind) {
                GlookoDocumentKind.COMBO_BOLUS -> EVENT_COMBO_BOLUS
                GlookoDocumentKind.MEAL_BOLUS -> EVENT_MEAL_BOLUS
                else -> EVENT_CORRECTION_BOLUS
            }
            add(
                kind = kind,
                timestampMs = timestampMs,
                businessKey = if (extendedMinutes != null) "bolus|$extendedMinutes" else "bolus",
                payload = treatmentPayload(
                    timestampMs = timestampMs,
                    eventType = eventType,
                    enteredBy = options.enteredBy,
                    notes = notes,
                    carbs = carbs,
                    insulin = delivered,
                    durationMinutes = extendedMinutes?.toLong(),
                ),
            )
        }

        for ((timestampMs, carbs) in carbsByTimestamp) {
            if (carbs <= 0.0) continue
            if (bolusByTimestamp.containsKey(timestampMs)) continue
            if (timestampMs < options.cutoffMs) {
                skip(GlookoSkip.OUTSIDE_WINDOW)
                continue
            }
            add(
                kind = GlookoDocumentKind.CARB_CORRECTION,
                timestampMs = timestampMs,
                businessKey = "carbs",
                payload = treatmentPayload(
                    timestampMs = timestampMs,
                    eventType = EVENT_CARB_CORRECTION,
                    enteredBy = options.enteredBy,
                    carbs = carbs,
                ),
            )
        }

        // ---- Débits temporaires ----------------------------------------------------
        // Une valeur par minute : la dernière ligne d'une minute l'emporte, et chaque
        // segment court jusqu'au changement suivant.
        val basalSeries = basalSeries(export)
        val anchorIndex = basalWindowStart(basalSeries, options.cutoffMs)
        for ((index, point) in basalSeries.withIndex()) {
            val timestampMs = point.first
            // Le dernier débit d'avant la fenêtre est conservé comme point de départ : sans
            // lui, la courbe commencerait par un trou.
            if (timestampMs < options.cutoffMs && index != anchorIndex) {
                skip(GlookoSkip.OUTSIDE_WINDOW)
                continue
            }
            val next = basalSeries.getOrNull(index + 1)?.first
            val minutes = if (next != null) {
                ((next - timestampMs) / 60_000L).coerceAtLeast(1L).coerceAtMost(MAX_BASAL_MINUTES)
            } else {
                (((options.nowMs - timestampMs) / 60_000L).coerceAtLeast(1L)).coerceAtMost(LAST_BASAL_MINUTES)
            }
            if (point.second !in 0.0..MAX_BASAL_RATE) {
                skip(GlookoSkip.INVALID_VALUE)
                continue
            }
            add(
                kind = GlookoDocumentKind.TEMP_BASAL,
                timestampMs = timestampMs,
                businessKey = "tempbasal",
                payload = treatmentPayload(
                    timestampMs = timestampMs,
                    eventType = EVENT_TEMP_BASAL,
                    enteredBy = options.enteredBy,
                    durationMinutes = minutes,
                    absolute = true,
                    rate = point.second,
                ),
            )
        }

        // ---- Évènements ------------------------------------------------------------
        val seen = HashSet<String>()
        for (row in export.rows) {
            val label = row.event ?: continue
            if (row.timestampMs < options.cutoffMs) {
                skip(GlookoSkip.OUTSIDE_WINDOW)
                continue
            }
            // La durée fait partie de l'identité : deux modes différents à la même minute
            // doivent coexister.
            val dedupeKey = "${row.timestampMs}|$label|${row.durationMs ?: -1}"
            if (!seen.add(dedupeKey)) {
                skip(GlookoSkip.DUPLICATE_IN_FILE)
                continue
            }

            val businessKey = "$label|${row.durationMs ?: 0}"
            val durationMinutes = row.durationMs?.div(60_000L)?.takeIf { it >= 1 }
            when {
                label == EVENT_PUMP_SETTINGS -> {
                    skip(GlookoSkip.PROFILE_ONLY)
                }

                label in PUMP_MODE_LABELS || label.startsWith(PUMP_MODE_PREFIX) -> {
                    val mode = label.removePrefix(PUMP_MODE_PREFIX)
                    add(
                        kind = GlookoDocumentKind.PUMP_MODE,
                        timestampMs = row.timestampMs,
                        businessKey = businessKey,
                        payload = treatmentPayload(
                            timestampMs = row.timestampMs,
                            eventType = EVENT_NOTE,
                            enteredBy = options.enteredBy,
                            notes = noteWithDuration("CamAPS $mode", durationMinutes),
                        ),
                    )
                }

                label == EVENT_BOOST -> {
                    add(
                        kind = GlookoDocumentKind.OVERRIDE,
                        timestampMs = row.timestampMs,
                        businessKey = businessKey,
                        payload = treatmentPayload(
                            timestampMs = row.timestampMs,
                            eventType = EVENT_NOTE,
                            enteredBy = options.enteredBy,
                            notes = noteWithDuration("CamAPS Boost", durationMinutes),
                        ),
                    )
                }

                label == EVENT_EASE_OFF -> {
                    add(
                        kind = GlookoDocumentKind.OVERRIDE,
                        timestampMs = row.timestampMs,
                        businessKey = businessKey,
                        payload = treatmentPayload(
                            timestampMs = row.timestampMs,
                            eventType = EVENT_NOTE,
                            enteredBy = options.enteredBy,
                            notes = noteWithDuration("CamAPS Ease-off", durationMinutes),
                        ),
                    )
                }

                label == EVENT_RESERVOIR_CHANGE -> {
                    add(
                        kind = GlookoDocumentKind.RESERVOIR,
                        timestampMs = row.timestampMs,
                        businessKey = businessKey,
                        payload = treatmentPayload(
                            timestampMs = row.timestampMs,
                            eventType = EVENT_INSULIN_CHANGE,
                            enteredBy = options.enteredBy,
                        ),
                    )
                }

                label == EVENT_TOTAL_DAILY_DOSE -> {
                    add(
                        kind = GlookoDocumentKind.TOTAL_DAILY_DOSE,
                        timestampMs = row.timestampMs,
                        businessKey = businessKey,
                        payload = treatmentPayload(
                            timestampMs = row.timestampMs,
                            eventType = EVENT_NOTE,
                            enteredBy = options.enteredBy,
                            notes = "CamAPS : dose totale quotidienne",
                        ),
                    )
                }

                label.startsWith(ALERT_PREFIX) -> {
                    add(
                        kind = GlookoDocumentKind.SAFETY_ALERT,
                        timestampMs = row.timestampMs,
                        businessKey = businessKey,
                        payload = treatmentPayload(
                            timestampMs = row.timestampMs,
                            eventType = EVENT_ANNOUNCEMENT,
                            enteredBy = options.enteredBy,
                            notes = noteWithDuration(label, durationMinutes),
                            isAnnouncement = true,
                        ),
                    )
                }

                else -> skip(GlookoSkip.UNMAPPED_EVENT)
            }
        }

        // ---- Profil ----------------------------------------------------------------
        val profile = if (!options.includeProfile) {
            null
        } else {
            profileDocument(export)
        }

        documents.sortBy { it.timestampMs }
        return GlookoPlan(
            documents = documents,
            profile = profile,
            counts = counts,
            skipped = skipped,
        )
    }

    /**
     * Série des débits : un point par minute, la dernière valeur de la minute gagne.
     *
     * Les lignes arrivent dans l'ordre du fichier, avec des doublons qui se contredisent —
     * 203 minutes portent deux débits différents dans l'export réel. Garder la dernière est
     * le seul choix qui garde une valeur par minute sans en inventer.
     *
     * Aucun filtre sur le type de délivrance : seules les lignes de basal portent un débit,
     * et filtrer sur une valeur dont la graphie exacte n'est pas vérifiée risquerait de
     * perdre une semaine entière en silence.
     */
    private fun basalSeries(export: GlookoExport): List<Pair<Long, Double>> {
        val byMinute = LinkedHashMap<Long, Double>()
        for (row in export.rows.sortedBy { it.timestampMs }) {
            val rate = row.basalRate ?: continue
            byMinute[row.timestampMs] = rate
        }
        return byMinute.map { (timestampMs, rate) -> timestampMs to rate }
    }

    private fun basalWindowStart(series: List<Pair<Long, Double>>, cutoffMs: Long): Int {
        val firstInside = series.indexOfFirst { it.first >= cutoffMs }
        return when {
            firstInside < 0 -> (series.size - 1).coerceAtLeast(0)
            firstInside == 0 -> 0
            else -> firstInside - 1
        }
    }

    private fun profileDocument(export: GlookoExport): GlookoProfileDocument? {
        // Les réglages les plus récents font foi ; le fichier peut en contenir plusieurs
        // copies (une par changement d'heure ou de réglage).
        val candidates = export.pumpSettings + export.rows.mapNotNull { row ->
            row.settingsRaw?.let { GlookoPumpSettings(row.timestamp, it) }
        }
        val settings = candidates.maxByOrNull { it.timestamp } ?: return null
        val profile = GlookoProfileBuilder.from(settings, export.timezone) ?: return null
        val identifier = GlookoIdentifier.of("profile", 0L, GlookoProfileBuilder.BUSINESS_KEY)
        return GlookoProfileDocument(
            identifier = identifier,
            payload = GlookoProfileBuilder.toDocument(
                profile = profile,
                identifier = identifier,
                start = settings.timestamp,
            ),
            contentHash = profile.contentHash(),
        )
    }

    private fun entryPayload(timestampMs: Long, sgv: Int, device: String): String =
        JSONObject().apply {
            put("type", "sgv")
            put("sgv", sgv)
            put("date", timestampMs)
            put("dateString", isoMillisUtc(timestampMs))
            put("device", device)
        }.toString()

    private fun treatmentPayload(
        timestampMs: Long,
        eventType: String,
        enteredBy: String,
        notes: String? = null,
        carbs: Double? = null,
        insulin: Double? = null,
        durationMinutes: Long? = null,
        rate: Double? = null,
        absolute: Boolean? = null,
        isAnnouncement: Boolean? = null,
    ): String = JSONObject().apply {
        put("eventType", eventType)
        put("created_at", isoMillisUtc(timestampMs))
        put("enteredBy", enteredBy)
        put("utcOffset", 0)
        if (notes != null) put("notes", notes)
        if (carbs != null) put("carbs", round1(carbs))
        if (insulin != null) put("insulin", round2(insulin))
        if (durationMinutes != null) put("duration", durationMinutes)
        if (rate != null) put("rate", round2(rate))
        if (absolute != null) put("absolute", absolute)
        if (isAnnouncement != null) put("isAnnouncement", isAnnouncement)
    }.toString()

    private fun noteWithDuration(text: String, minutes: Long?): String =
        if (minutes != null && minutes > 0) "$text (${formatDuration(minutes)})" else text

    private fun formatDuration(minutes: Long): String =
        if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min".replace(" 0 min", "")

    private fun formatUnits(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    companion object {
        const val MAX_SGV = 1000
        const val MAX_BASAL_MINUTES = 180L
        const val LAST_BASAL_MINUTES = 30L
        const val MAX_BASAL_RATE = 50.0

        private const val BOLUS_NORMAL = "normal"
        private const val BOLUS_CALCULATOR = "calculator"
        private const val BOLUS_EXTENDED = "extended"

        const val EVENT_MEAL_BOLUS = "Meal Bolus"
        const val EVENT_CORRECTION_BOLUS = "Correction Bolus"
        const val EVENT_COMBO_BOLUS = "Combo Bolus"
        const val EVENT_CARB_CORRECTION = "Carb Correction"
        const val EVENT_TEMP_BASAL = "Temp Basal"
        const val EVENT_NOTE = "Note"
        const val EVENT_ANNOUNCEMENT = "Announcement"
        const val EVENT_INSULIN_CHANGE = "Insulin Change"

        const val PUMP_MODE_PREFIX = "pumpMode - "
        private val PUMP_MODE_LABELS = setOf("Closed loop", "Liberty", "Pining")

        private const val EVENT_PUMP_SETTINGS = "pump settings"
        private const val EVENT_BOOST = "pumpSettingsOverride - boost"
        private const val EVENT_EASE_OFF = "pumpSettingsOverride - easeOff"
        private const val EVENT_RESERVOIR_CHANGE = "ReservoirChange"
        private const val EVENT_TOTAL_DAILY_DOSE = "info - Total daily dose"
        private const val ALERT_PREFIX = "Alerte de sécurité"

        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

        internal fun isoMillisUtc(timestampMs: Long): String = ISO_MILLIS.format(java.time.Instant.ofEpochMilli(timestampMs))
    }
}
