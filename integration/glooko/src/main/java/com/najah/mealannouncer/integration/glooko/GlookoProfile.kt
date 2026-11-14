package com.najah.mealannouncer.integration.glooko

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** Un point d'un profil Nightscout : l'heure de début et la valeur qui court à partir d'elle. */
data class ProfileEntry(val time: String, val value: Double)

/**
 * Les réglages de CamAPS FX, dans la forme que Nightscout attend.
 *
 * Ce profil n'est pas décoratif : c'est lui qui donne son cadre à la courbe d'insuline. Sans
 * lui, la basale paraîtrait plate et les rapports seraient faux.
 */
data class CamapsProfile(
    val basal: List<ProfileEntry>,
    val carbRatio: List<ProfileEntry>,
    val sensitivity: List<ProfileEntry>,
    val targetLow: Double?,
    val targetHigh: Double?,
    val diaHours: Double,
    val units: String,
    val timezone: String,
) {
    /**
     * Empreinte du contenu thérapeutique, pour ne pas réécrire un profil identique.
     *
     * Les douze copies de réglages de l'export réel ne diffèrent que par leur date : c'est
     * cette empreinte, et non le JSON brut, qui décide s'il faut écrire.
     */
    fun contentHash(): String {
        val canonical = buildString {
            append("basal=").append(basal.joinToString(",") { "${it.time}:${it.value}" })
            append(";carbratio=").append(carbRatio.joinToString(",") { "${it.time}:${it.value}" })
            append(";sens=").append(sensitivity.joinToString(",") { "${it.time}:${it.value}" })
            append(";low=").append(targetLow ?: "-")
            append(";high=").append(targetHigh ?: "-")
            append(";dia=").append(diaHours)
            append(";units=").append(units)
            append(";tz=").append(timezone)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

/**
 * Construction du profil à partir du JSON `pump settings` de l'export.
 *
 * Le JSON est déjà presque au format Nightscout : on en reprend les débits, ratios,
 * sensibilités, cibles et la durée d'action de l'insuline, sans rien inventer. Une valeur
 * absente reste absente.
 */
object GlookoProfileBuilder {

    const val PROFILE_NAME = "Glooko-CamAPS"

    /** Nom de la clé stable du document de profil : un seul profil, mis à jour en place. */
    const val BUSINESS_KEY = "camaps"

    fun from(settings: GlookoPumpSettings, timezone: java.time.ZoneId): CamapsProfile? {
        val json = try {
            JSONObject(unquoteCsvField(settings.raw))
        } catch (error: JSONException) {
            return null
        }
        val schedule = json.optString("activeSchedule").ifBlank { "1" }

        val basal = scheduleEntries(json.optJSONObject("basalSchedules"), schedule, "rate")
        val carbRatio = scheduleEntries(json.optJSONObject("carbRatios"), schedule, "amount")
        val sensitivity = scheduleEntries(json.optJSONObject("insulinSensitivities"), schedule, "amount")
        if (basal.isEmpty() && carbRatio.isEmpty() && sensitivity.isEmpty()) return null

        return CamapsProfile(
            basal = basal,
            carbRatio = carbRatio,
            sensitivity = sensitivity,
            targetLow = json.optDouble("glucoseTargetLow").takeIf { !it.isNaN() },
            targetHigh = json.optDouble("glucoseTargetHigh").takeIf { !it.isNaN() },
            diaHours = json.optDouble("iobDuration", 0.0).takeIf { it > 0 }?.div(3600.0) ?: DEFAULT_DIA_HOURS,
            units = json.optJSONObject("units")?.optString("bg")?.ifBlank { null } ?: "mg/dl",
            timezone = timezone.id,
        )
    }

    /** Document de profil v3, prêt à poster. */
    fun toDocument(profile: CamapsProfile, identifier: String, start: Instant): String {
        val store = JSONObject().apply {
            put("basal", profile.basal.toJsonArray())
            put("carbratio", profile.carbRatio.toJsonArray())
            put("sens", profile.sensitivity.toJsonArray())
            put("target_low", profile.targetLow.toTargetArray())
            put("target_high", profile.targetHigh.toTargetArray())
            put("units", profile.units)
            put("dia", profile.diaHours)
            put("timezone", profile.timezone)
        }
        return JSONObject().apply {
            put("identifier", identifier)
            put("defaultProfile", PROFILE_NAME)
            put("startDate", isoMillis(start))
            put("store", JSONObject().put(PROFILE_NAME, store))
        }.toString()
    }

    private fun List<ProfileEntry>.toJsonArray(): JSONArray {
        val array = JSONArray()
        for (entry in this) {
            array.put(
                JSONObject().apply {
                    put("time", entry.time)
                    put("value", entry.value)
                    put("timeAsSeconds", entry.time.toSecondsOfDay())
                },
            )
        }
        return array
    }

    private fun Double?.toTargetArray(): JSONArray {
        val array = JSONArray()
        val value = this ?: return array
        array.put(
            JSONObject().apply {
                put("time", "00:00")
                put("value", value)
            },
        )
        return array
    }

    private fun String.toSecondsOfDay(): Int {
        val parts = split(":")
        val hours = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return hours * 3600 + minutes * 60
    }

    /**
     * Entrées d'un horaire de pompe (`{ "1": [ { "rate": 2, "start": 0 } ] }`).
     *
     * `start` est en minutes depuis minuit ; Nightscout veut `HH:MM`.
     */
    private fun scheduleEntries(
        schedules: JSONObject?,
        schedule: String,
        field: String,
    ): List<ProfileEntry> {
        if (schedules == null) return emptyList()
        val array = scheduleArray(schedules, schedule) ?: return emptyList()

        val entries = ArrayList<ProfileEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val value = item.optDouble(field).takeIf { !it.isNaN() } ?: continue
            val startMinutes = item.optDouble("start", 0.0).toInt().coerceIn(0, MINUTES_PER_DAY - 1)
            entries += ProfileEntry(timeOfDay(startMinutes), round(value))
        }
        return entries.sortedBy { it.time.toSecondsOfDay() }
    }

    /**
     * La liste d'un horaire, quelle que soit la forme qu'elle prend dans l'export.
     *
     * Le même réglage s'écrit de trois façons selon la version de Glooko : la liste
     * directement, la liste enveloppée sous son propre nom, ou un objet `values`. On les
     * essaie dans cet ordre ; une forme inconnue rend `null`, jamais une entrée inventée.
     */
    private fun scheduleArray(schedules: JSONObject, schedule: String): JSONArray? {
        schedules.optJSONArray(schedule)?.let { return it }
        val wrapper = schedules.optJSONObject(schedule) ?: return null
        wrapper.optJSONArray(schedule)?.let { return it }
        return wrapper.optJSONArray("values")
    }

    private fun timeOfDay(minutes: Int): String =
        String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)

    private fun round(value: Double): Double = Math.round(value * 100.0) / 100.0

    private fun isoMillis(instant: Instant): String =
        java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC)
            .format(instant)

    private const val MINUTES_PER_DAY = 24 * 60
    private const val DEFAULT_DIA_HOURS = 5.0
}
