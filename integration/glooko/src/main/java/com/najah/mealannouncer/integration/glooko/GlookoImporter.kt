package com.najah.mealannouncer.integration.glooko

import com.najah.mealannouncer.data.nightscout.NightscoutApi
import com.najah.mealannouncer.data.nightscout.NightscoutCredentials
import com.najah.mealannouncer.data.nightscout.NightscoutDocument
import com.najah.mealannouncer.data.nightscout.NightscoutErrorKind
import com.najah.mealannouncer.data.nightscout.NightscoutException
import com.najah.mealannouncer.data.nightscout.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Ce qu'on dit après un échec : l'import est rejouable sans doublon, et c'est ce qui compte
 * pour quelqu'un qui vient de lire un message d'erreur.
 */
private const val RETRY_HINT = " — relancez, rien ne sera doublé."

/** Pourquoi un document n'a pas été envoyé, alors qu'il était valide. */
enum class GlookoImportSkip(val userMessage: String) {
    /** Identifiant déjà connu de Nightscout : le réessayer serait un envoi pour rien. */
    ALREADY_PRESENT("déjà présent"),

    /** Un document d'une autre source couvre le même moment : elle a la priorité. */
    FOREIGN_WINS("remplacé par une source plus récente"),

    /** Notre ancienne copie a été retirée au profit d'une source prioritaire. */
    SUPERSEDED("écarté au profit de xDrip+"),
}

/**
 * Ce que l'import vient de faire, en comptes exacts.
 *
 * Le rapport ne dit jamais « tout s'est bien passé » : il dit combien de documents ont été
 * écrits, combien étaient déjà là, et ce qui a échoué.
 */
data class GlookoImportReport(
    val dryRun: Boolean,
    val entriesWritten: Int = 0,
    val treatmentsWritten: Int = 0,
    val profileWritten: Boolean = false,
    val profileUnchanged: Boolean = false,
    /**
     * Le serveur n'écrit le profil qu'en v3, et la clé d'API n'y donne pas accès : le
     * profil n'a pas été envoyé, sans que l'import ait échoué pour autant.
     */
    val profileUnsupported: Boolean = false,
    val supersededDeleted: Int = 0,
    val wouldWrite: Int = 0,
    /** Détail de [wouldWrite] : sans lui, la simulation annoncerait un total sans nature. */
    val wouldWriteEntries: Int = 0,
    val wouldWriteTreatments: Int = 0,
    val importSkips: Map<GlookoImportSkip, Int> = emptyMap(),
    val counts: Map<GlookoDocumentKind, Int> = emptyMap(),
    val skipped: Map<GlookoSkip, Int> = emptyMap(),
    val failures: List<String> = emptyList(),
    val windowFromMs: Long = 0L,
    val windowToMs: Long = 0L,
) {
    val written: Int get() = entriesWritten + treatmentsWritten
    val ok: Boolean get() = failures.isEmpty()
    val importedSkips: Int get() = importSkips.values.sum()

    /** Phrase courte pour l'écran et la notification, en français. */
    fun summary(): String {
        if (!ok) {
            // Un import interrompu n'est ni un succès ni un échec total. Le dire en comptes —
            // « 326 document(s) écrit(s), puis : … » — évite de croire qu'il faut tout refaire.
            val head = if (!dryRun && written > 0) "$written document(s) écrit(s), puis : " else ""
            return "$head${failures.first()}$RETRY_HINT"
        }
        val prefix = if (dryRun) "Simulation : " else ""
        // Une limite du serveur se dit, mais ne prend pas la place du résultat.
        val note = if (profileUnsupported) " · profil non envoyé (token d'API nécessaire)" else ""
        val entries = if (dryRun) wouldWriteEntries else entriesWritten
        val treatments = if (dryRun) wouldWriteTreatments else treatmentsWritten
        val total = entries + treatments
        if (total == 0 && !profileWritten) {
            return "${prefix}rien de neuf — ${importedSkips} document(s) déjà à jour$note"
        }
        val parts = buildList {
            if (entries > 0) add("$entries glycémie(s)")
            if (treatments > 0) add("$treatments traitement(s)")
            if (profileWritten) add("profil")
            if (supersededDeleted > 0) add("$supersededDeleted doublon(s) retiré(s)")
        }
        val verb = when {
            dryRun -> "seraient importés"
            total == 1 && parts.size == 1 -> "importé"
            else -> "importés"
        }
        return "$prefix${parts.joinToString(", ")} $verb$note"
    }
}

/** Réglages d'un passage, tous explicites pour que l'import reste testable. */
data class GlookoImportOptions(
    val nowMs: Long,
    val windowHours: Long = GlookoPlanOptions.DEFAULT_WINDOW_HOURS,
    val includeSgv: Boolean = true,
    val enteredBy: String = GlookoPlanOptions.DEFAULT_ENTERED_BY,
    val device: String = GlookoPlanOptions.DEFAULT_DEVICE,
    val dryRun: Boolean = false,
    /**
     * Pause avant de réessayer une écriture qu'une panne passagère a fait échouer. Zéro dans
     * les tests, pour qu'ils n'attendent pas deux secondes.
     */
    val retryPauseMs: Long = GlookoImporter.WRITE_RETRY_PAUSE_MS,
) {
    fun planOptions(): GlookoPlanOptions = GlookoPlanOptions(
        nowMs = nowMs,
        windowHours = windowHours,
        includeSgv = includeSgv,
        includeProfile = true,
        enteredBy = enteredBy,
        device = device,
    )
}

/**
 * Importe un export Glooko dans Nightscout.
 *
 * Trois règles gouvernent l'écriture, dans cet ordre :
 * 1. un identifiant déjà présent n'est pas renvoyé — c'est l'idempotence de la v3 qui fait
 *    le travail, sans une requête par document ;
 * 2. une mesure de glycémie ou un repas déjà couvert par une autre source (xDrip+, une
 *    saisie manuelle, Meal Announcer) n'est pas écrit — la source la plus proche du temps
 *    réel gagne ;
 * 3. nos propres glycémies qu'une source prioritaire a doublées sont retirées.
 *
 * Rien n'est écrit avant que la lecture de l'existant ait réussi : un import qui ne peut pas
 * savoir ce qui est déjà là ne doit pas deviner.
 */
@Singleton
class GlookoImporter @Inject constructor(
    private val api: NightscoutApi,
    private val state: GlookoState,
    private val mapper: GlookoToNightscoutMapper,
) {

    suspend fun import(
        csv: String,
        credentials: NightscoutCredentials,
        options: GlookoImportOptions,
    ): GlookoImportReport = withContext(Dispatchers.IO) {
        val export = try {
            GlookoParser.parse(csv)
        } catch (error: GlookoParseException) {
            return@withContext GlookoImportReport(
                dryRun = options.dryRun,
                failures = listOf(error.message ?: "Export Glooko illisible."),
            )
        }

        val plan = mapper.plan(export, options.planOptions())
        if (plan.isEmpty) {
            return@withContext GlookoImportReport(
                dryRun = options.dryRun,
                counts = plan.counts,
                skipped = plan.skipped,
                failures = listOf("Aucun document à importer dans cet export."),
            )
        }

        val fromMs = plan.documents.first().timestampMs
        val toMs = plan.documents.last().timestampMs
        val margin = SGV_MARGIN_MS
        val existingEntries = try {
            api.entries(credentials, fromMs - margin, toMs + margin)
        } catch (error: NightscoutException) {
            return@withContext failure(error, plan, options)
        }
        val existingTreatments = try {
            api.treatments(credentials, fromMs - margin, toMs + margin)
        } catch (error: NightscoutException) {
            return@withContext failure(error, plan, options)
        }

        val foreignEntryTimes = existingEntries
            .filter { it.device != options.device }
            .map { it.dateMs }

        // Nos glycémies doublées par xDrip+ : on les a écrites avant de savoir que la source
        // prioritaire existait, elles n'ont plus lieu d'être.
        val superseded = existingEntries.filter { entry ->
            entry.device == options.device &&
                entry.identifier != null &&
                foreignEntryTimes.any { other -> abs(other - entry.dateMs) <= SGV_MARGIN_MS }
        }
        val supersededIds = superseded.mapNotNull { it.identifier }.toSet()

        val existingIds = buildSet {
            existingEntries.mapNotNullTo(this) { it.identifier }
            existingTreatments.mapNotNullTo(this) { it.identifier }
        } - supersededIds

        val foreignMeals = existingTreatments
            .filter { it.enteredBy != options.enteredBy && isMealFamily(it.eventType) }
            .map { it.createdAtMs }

        var entriesWritten = 0
        var treatmentsWritten = 0
        var wouldWrite = 0
        var wouldWriteEntries = 0
        var wouldWriteTreatments = 0
        var deleted = 0
        val importSkips = LinkedHashMap<GlookoImportSkip, Int>()
        val failures = ArrayList<String>()

        fun importSkip(reason: GlookoImportSkip) {
            importSkips[reason] = (importSkips[reason] ?: 0) + 1
        }

        // Retraits d'abord : les documents écartés ne doivent pas être réécrits juste après.
        if (!options.dryRun) {
            for (entry in superseded) {
                val identifier = entry.identifier ?: continue
                try {
                    retried(options.retryPauseMs) {
                        api.delete(
                            credentials = credentials,
                            document = NightscoutDocument.ENTRIES,
                            identifier = identifier,
                            itemId = entry.id,
                        )
                    }
                    deleted++
                } catch (error: NightscoutException) {
                    failures += "Retrait impossible : ${error.kind.userMessage}"
                    if (failures.size >= MAX_FAILURES) break
                }
            }
        }

        for (document in plan.documents) {
            if (failures.size >= MAX_FAILURES) break
            if (document.identifier in supersededIds) {
                importSkip(GlookoImportSkip.SUPERSEDED)
                continue
            }
            if (document.identifier in existingIds) {
                importSkip(GlookoImportSkip.ALREADY_PRESENT)
                continue
            }
            if (document.kind.collection == NightscoutDocument.ENTRIES &&
                foreignEntryTimes.any { other -> abs(other - document.timestampMs) <= SGV_MARGIN_MS }
            ) {
                importSkip(GlookoImportSkip.FOREIGN_WINS)
                continue
            }
            if (document.kind in MEAL_KINDS &&
                foreignMeals.any { other -> abs(other - document.timestampMs) <= MEAL_MARGIN_MS }
            ) {
                importSkip(GlookoImportSkip.FOREIGN_WINS)
                continue
            }
            if (options.dryRun) {
                when (document.kind.collection) {
                    NightscoutDocument.ENTRIES -> wouldWriteEntries++
                    NightscoutDocument.TREATMENTS -> wouldWriteTreatments++
                    NightscoutDocument.PROFILE -> Unit
                }
                wouldWrite = wouldWriteEntries + wouldWriteTreatments
                continue
            }
            try {
                // Une panne d'une seconde ne doit pas coûter un document : on retente une
                // fois avant de le compter comme perdu. Le filet de sécurité repasserait de
                // toute façon, mais autant ne pas lui laisser ce travail.
                retried(options.retryPauseMs) {
                    api.upsert(credentials, document.kind.collection, document.payload)
                }
                when (document.kind.collection) {
                    NightscoutDocument.ENTRIES -> entriesWritten++
                    NightscoutDocument.TREATMENTS -> treatmentsWritten++
                    NightscoutDocument.PROFILE -> Unit
                }
            } catch (error: NightscoutException) {
                val moment = java.time.Instant.ofEpochMilli(document.timestampMs).logLabel()
                failures += "${document.kind.label} de $moment : ${error.kind.userMessage}"
                if (!error.kind.isRetryable) break
            }
        }

        val profile = plan.profile
        var profileWritten = false
        var profileUnchanged = false
        var profileUnsupported = false
        if (profile != null && failures.isEmpty()) {
            if (state.profileHash == profile.contentHash) {
                profileUnchanged = true
            } else if (options.dryRun) {
                profileWritten = true
            } else {
                try {
                    api.upsert(credentials, NightscoutDocument.PROFILE, profile.payload)
                    state.profileHash = profile.contentHash
                    profileWritten = true
                } catch (error: NightscoutException) {
                    if (error.kind == NightscoutErrorKind.PROFILE_UNSUPPORTED) {
                        // Le serveur ne sait pas écrire de profil avec une clé d'API. Les
                        // repas, eux, sont passés : ce n'est pas un import en échec, et le
                        // hash n'est pas retenu pour que le profil parte le jour où un
                        // token sera configuré.
                        profileUnsupported = true
                    } else {
                        failures += "Profil : ${error.kind.userMessage}"
                    }
                }
            }
        }

        val report = GlookoImportReport(
            dryRun = options.dryRun,
            entriesWritten = entriesWritten,
            treatmentsWritten = treatmentsWritten,
            profileWritten = profileWritten,
            profileUnchanged = profileUnchanged,
            profileUnsupported = profileUnsupported,
            supersededDeleted = deleted,
            wouldWrite = wouldWrite,
            wouldWriteEntries = wouldWriteEntries,
            wouldWriteTreatments = wouldWriteTreatments,
            importSkips = importSkips,
            counts = plan.counts,
            skipped = plan.skipped,
            failures = failures,
            windowFromMs = fromMs,
            windowToMs = toMs,
        )

        if (!options.dryRun && report.ok) {
            state.lastSuccessMs = options.nowMs
        }
        state.lastRunMs = options.nowMs
        report
    }

    /**
     * Exécute [block], en le retentant une fois si l'échec est passager.
     *
     * Ce qui n'est pas passager ne se réessaie pas : une clé refusée le sera encore dans deux
     * secondes, et insister ne fait qu'user la batterie et le serveur.
     */
    private suspend fun <T> retried(pauseMs: Long, block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: NightscoutException) {
                attempt++
                if (!error.kind.isRetryable || attempt >= WRITE_ATTEMPTS) throw error
                if (pauseMs > 0) delay(pauseMs)
            }
        }
    }

    private fun failure(
        error: NightscoutException,
        plan: GlookoPlan,
        options: GlookoImportOptions,
    ): GlookoImportReport = GlookoImportReport(
        dryRun = options.dryRun,
        counts = plan.counts,
        skipped = plan.skipped,
        failures = listOf("Lecture de Nightscout impossible : ${error.kind.userMessage}"),
    )

    private fun isMealFamily(eventType: String?): Boolean = eventType in MEAL_EVENTS

    companion object {
        /** Tolérance de rapprochement des glycémies : deux minutes et demie. */
        const val SGV_MARGIN_MS = 150_000L

        /** Tolérance de rapprochement des repas : cinq minutes. */
        const val MEAL_MARGIN_MS = 300_000L

        /** Au-delà, l'import s'arrête et dit ce qui a échoué. */
        const val MAX_FAILURES = 8

        /** Une écriture, puis une seule reprise : au-delà, ce n'est plus une panne passagère. */
        const val WRITE_ATTEMPTS = 2

        /** Pause avant la reprise : le temps qu'une bascule réseau se fasse. */
        const val WRITE_RETRY_PAUSE_MS = 2_000L

        private val MEAL_KINDS = setOf(
            GlookoDocumentKind.MEAL_BOLUS,
            GlookoDocumentKind.CORRECTION_BOLUS,
            GlookoDocumentKind.COMBO_BOLUS,
            GlookoDocumentKind.CARB_CORRECTION,
        )

        private val MEAL_EVENTS = setOf("Meal Bolus", "Correction Bolus", "Combo Bolus", "Carb Correction")
    }
}
