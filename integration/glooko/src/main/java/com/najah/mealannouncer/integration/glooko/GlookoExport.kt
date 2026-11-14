package com.najah.mealannouncer.integration.glooko

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Un export Glooko déjà décodé.
 *
 * Le bloc patient du fichier (nom, prénom, adresse électronique) n'est **jamais** lu : le
 * parseur ne s'intéresse qu'aux lignes d'évènements. Un export de santé ne doit pas laisser
 * de données d'identité dans la mémoire de l'application ni dans un journal.
 */
data class GlookoExport(
    /** Zone de l'export, lue dans l'en-tête (`TIMEZONE;Europe/Paris`). */
    val timezone: ZoneId,
    /** Premier jour annoncé par `PERIOD`, si l'export le donne. */
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val rows: List<GlookoRow>,
    /** Copies des réglages de pompe rencontrées, dans l'ordre du fichier. */
    val pumpSettings: List<GlookoPumpSettings>,
) {
    val isEmpty: Boolean get() = rows.isEmpty() && pumpSettings.isEmpty()
}

/**
 * Une ligne du CSV, ramenée à ce que l'import utilise.
 *
 * Les colonnes 15, 16 et 28 de l'export réel ne contiennent que `false` : elles sont donc
 * ignorées, et les glucides ne sont lus que dans la colonne `Carbs`.
 */
data class GlookoRow(
    val lineNumber: Int,
    /** Horodatage UTC : la date du CSV est locale, convertie avec la zone de l'en-tête. */
    val timestamp: Instant,
    val pumpDevice: String? = null,
    val bgDevice: String? = null,
    /** Glycémie brute (avec l'artefact mmol × 18,018, arrondie plus tard). */
    val bgMgdl: Double? = null,
    val bolusUnits: Double? = null,
    /** `normal`, `calculator`, `extended`… */
    val bolusType: String? = null,
    /** Durée annoncée par la pompe, en millisecondes (modes, overrides, bolus étendus). */
    val durationMs: Long? = null,
    val basalRate: Double? = null,
    val basalDeliveryType: String? = null,
    /** Libellé d'évènement (`pumpMode - …`, `ReservoirChange`, alertes…). */
    val event: String? = null,
    val carbsGrams: Double? = null,
    /** Réglages de pompe, encore dans leur forme CSV (guillemets à retirer). */
    val settingsRaw: String? = null,
) {
    val timestampMs: Long get() = timestamp.toEpochMilli()

    /** Vrai si la ligne ne porte rien d'autre que sa date. */
    val isNoise: Boolean
        get() = bgMgdl == null &&
            bolusUnits == null &&
            durationMs == null &&
            basalRate == null &&
            event == null &&
            carbsGrams == null &&
            settingsRaw == null
}

/** Une copie datée des réglages de pompe, telle que Glooko l'insère dans le CSV. */
data class GlookoPumpSettings(
    val timestamp: Instant,
    /** Champ brut, encore entouré de guillemets et avec les guillemets internes doublés. */
    val raw: String,
)

/** Ce qui empêche de lire un export, dit sans détour. */
enum class GlookoParseProblem {
    /** Aucune ligne d'en-tête `Date;…` : ce n'est pas un export Glooko XT. */
    NO_HEADER,

    /** L'en-tête `TIMEZONE;…` manque : sans lui, toute heure serait fausse. */
    NO_TIMEZONE,

    /** Zone inconnue de la machine (faute de frappe, fuseau exotique). */
    UNKNOWN_TIMEZONE,

    /** Une date illisible : mieux vaut refuser que d'inventer un horodatage. */
    BAD_DATE,

    /** Une ligne trop courte : fichier tronqué ou corrompu. */
    TRUNCATED_ROW,

    /** En-tête présent mais aucune ligne exploitable. */
    EMPTY,
}

/** Échec de lecture d'un export Glooko, avec la ligne fautive quand elle existe. */
class GlookoParseException(
    val problem: GlookoParseProblem,
    message: String,
    val lineNumber: Int? = null,
) : Exception(message)

/**
 * Retire l'emballage CSV d'un champ : `"{""a"":1}"` devient `{"a":1}`.
 *
 * Glooko entoure le JSON de guillemets et double les guillemets internes ; `org.json` ne
 * sait pas lire cette forme, il faut donc la défaire avant de parser.
 */
internal fun unquoteCsvField(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return trimmed
    return trimmed.substring(1, trimmed.length - 1).replace("\"\"", "\"")
}
