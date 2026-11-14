package com.najah.mealannouncer.integration.glooko

import com.najah.mealannouncer.core.datastore.CredentialType
import com.najah.mealannouncer.data.nightscout.NightscoutApi
import com.najah.mealannouncer.data.nightscout.NightscoutCredentials
import com.najah.mealannouncer.data.nightscout.NightscoutDocument
import com.najah.mealannouncer.data.nightscout.NightscoutEntryRef
import com.najah.mealannouncer.data.nightscout.NightscoutErrorKind
import com.najah.mealannouncer.data.nightscout.NightscoutException
import com.najah.mealannouncer.data.nightscout.NightscoutServerInfo
import com.najah.mealannouncer.data.nightscout.NightscoutTreatmentRef
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Import de bout en bout, avec un Nightscout simulé.
 *
 * Ce qui est vérifié ici n'est pas la conversion mais la **politique d'écriture** :
 * idempotence, priorité des sources et refus de deviner.
 */
class GlookoImporterTest {

    private val credentials = NightscoutCredentials(
        baseUrl = "https://ns.example",
        credentialType = CredentialType.API_SECRET,
        secret = "secret-de-test",
    )

    private class FakeApi : NightscoutApi {
        val upserts = mutableListOf<Upsert>()
        val deletes = mutableListOf<String>()
        var entries: List<NightscoutEntryRef> = emptyList()
        var treatments: List<NightscoutTreatmentRef> = emptyList()

        /**
         * Décide, appel par appel, si l'écriture échoue (l'appel part de 1). Renvoyer null
         * laisse passer l'écriture ; c'est ce qui permet de simuler une panne passagère.
         */
        var upsertBehaviour: ((attempt: Int) -> NightscoutException?)? = null

        /** Nombre de tentatives d'écriture reçues, reprises comprises. */
        var attempts = 0

        data class Upsert(val document: NightscoutDocument, val identifier: String, val payload: String)

        override fun status(credentials: NightscoutCredentials) = NightscoutServerInfo(
            name = "test",
            version = "15.0.0",
            apiEnabled = true,
            careportalEnabled = true,
            units = "mg/dl",
        )

        override fun postTreatment(credentials: NightscoutCredentials, payload: String) = Unit

        override fun upsert(
            credentials: NightscoutCredentials,
            document: NightscoutDocument,
            payload: String,
        ): Int {
            attempts++
            upsertBehaviour?.invoke(attempts)?.let { throw it }
            upserts += Upsert(document, JSONObject(payload).getString("identifier"), payload)
            return 201
        }

        override fun delete(
            credentials: NightscoutCredentials,
            document: NightscoutDocument,
            identifier: String,
            itemId: String?,
        ): Int {
            deletes += identifier
            return 200
        }

        override fun entries(credentials: NightscoutCredentials, fromMs: Long, toMs: Long) = entries

        override fun treatments(credentials: NightscoutCredentials, fromMs: Long, toMs: Long) = treatments

        /** Rejoue ce que Nightscout renverrait après ce passage. */
        fun snapshotExisting() {
            entries = upserts
                .filter { it.document == NightscoutDocument.ENTRIES }
                .map {
                    val json = JSONObject(it.payload)
                    NightscoutEntryRef(
                        dateMs = json.getLong("date"),
                        device = json.optString("device"),
                        identifier = it.identifier,
                    )
                }
            treatments = upserts
                .filter { it.document == NightscoutDocument.TREATMENTS }
                .map {
                    val json = JSONObject(it.payload)
                    NightscoutTreatmentRef(
                        createdAtMs = Instant.parse(json.getString("created_at")).toEpochMilli(),
                        eventType = json.optString("eventType"),
                        enteredBy = json.optString("enteredBy"),
                        identifier = it.identifier,
                    )
                }
        }
    }

    private class FakeState : GlookoState {
        override var lastSuccessMs: Long = 0L
        override var lastRunMs: Long = 0L
        override var profileHash: String? = null
    }

    private fun import(
        api: NightscoutApi,
        state: FakeState,
        csv: String = GlookoCsv().row(time = "17/10/2026 12:00", bg = "120").build(),
        options: GlookoImportOptions = GlookoSample.options(),
    ): GlookoImportReport = runBlocking {
        GlookoImporter(api, state, GlookoToNightscoutMapper())
            .import(csv, credentials, options)
    }

    /**
     * Un export court, mais avec ses réglages : le profil fait partie de ce qu'un passage écrit.
     *
     * Un export sans ligne « pump settings » n'a pas de profil, et c'est normal : rien à écrire.
     */
    private fun csvWithSettings(): String = GlookoCsv()
        .row(time = "17/10/2026 12:00", bg = "225.01")
        .row(time = "17/10/2026 12:30", bolus = "5.0", bolusType = "normal", carbs = "60")
        .row(
            time = "17/10/2026 03:29",
            event = "pump settings",
            settings = GlookoCsv.quoted(GlookoCsv.settingsJson()),
        )
        .build()

    @Test
    fun `un premier import écrit tout ce qui est nouveau`() {
        val api = FakeApi()
        val state = FakeState()
        val report = import(api, state, csv = csvWithSettings())

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(1, report.entriesWritten)
        assertEquals(1, report.treatmentsWritten)
        assertTrue(report.profileWritten)
        assertEquals(3, api.upserts.size)
        assertEquals(GlookoSample.NOW_MS, state.lastSuccessMs)
    }

    @Test
    fun `un second import n écrit rien et ne renvoie rien`() {
        val api = FakeApi()
        val state = FakeState()

        val csv = csvWithSettings()
        val first = import(api, state, csv = csv)
        assertTrue(first.written > 0)

        api.snapshotExisting()
        val upsertsBefore = api.upserts.size

        val second = import(api, state, csv = csv)
        assertEquals(0, second.written)
        assertEquals(upsertsBefore, api.upserts.size)
        assertTrue((second.importSkips[GlookoImportSkip.ALREADY_PRESENT] ?: 0) > 0)
        assertTrue(second.profileUnchanged)
        // Un import sans rien à faire reste un succès : la synchro a eu lieu.
        assertEquals(GlookoSample.NOW_MS, state.lastSuccessMs)
    }

    @Test
    fun `une glycémie xDrip prime sur celle du CSV`() {
        val api = FakeApi()
        val state = FakeState()
        val sgvTime = GlookoCsv.paris("2026-10-17T12:00")
        api.entries = listOf(NightscoutEntryRef(sgvTime + 60_000L, "xDrip+", "xdrip-1"))

        val report = import(
            api,
            state,
            csv = GlookoCsv()
                .row(time = "17/10/2026 12:00", bg = "225")
                .row(time = "17/10/2026 12:30", bg = "130")
                .build(),
        )

        assertEquals(1, report.entriesWritten)
        assertTrue((report.importSkips[GlookoImportSkip.FOREIGN_WINS] ?: 0) >= 1)
        // Aucune glycémie n'a été écrite pour 12:00.
        assertFalse(api.upserts.any { it.document == NightscoutDocument.ENTRIES && it.payload.contains("225") })
    }

    @Test
    fun `nos glycémies doublées par xDrip sont retirées`() {
        val api = FakeApi()
        val state = FakeState()
        import(api, state, csv = GlookoCsv().row(time = "17/10/2026 12:00", bg = "225").build())
        api.snapshotExisting()

        val ours = api.entries.single()
        // xDrip+ a publié sa propre mesure à la même minute : la nôtre devient un doublon.
        api.entries = api.entries + NightscoutEntryRef(ours.dateMs + 60_000L, "xDrip+", "xdrip-1")

        val report = import(api, state, csv = GlookoCsv().row(time = "17/10/2026 12:00", bg = "225").build())

        assertEquals(1, report.supersededDeleted)
        assertEquals(listOf(ours.identifier), api.deletes)
        // Et surtout : la glycémie retirée n'est pas réécrite dans la foulée.
        assertEquals(0, report.entriesWritten)
        assertTrue((report.importSkips[GlookoImportSkip.SUPERSEDED] ?: 0) >= 1)
    }

    @Test
    fun `une simulation n écrit rien`() {
        val api = FakeApi()
        val state = FakeState()
        val report = import(
            api,
            state,
            csv = GlookoCsv()
                .row(time = "17/10/2026 12:00", bg = "120")
                .row(time = "17/10/2026 12:30", bolus = "3", bolusType = "normal")
                .build(),
            options = GlookoSample.options(dryRun = true),
        )

        assertTrue(api.upserts.isEmpty())
        assertTrue(api.deletes.isEmpty())
        // Une glycémie et un bolus de correction : le profil compte à part.
        assertEquals(2, report.wouldWrite)
        assertEquals(0, report.written)
        assertEquals(0L, state.lastSuccessMs)
        assertTrue(report.summary().startsWith("Simulation"))
    }

    @Test
    fun `un CSV illisible ne touche à rien`() {
        val api = FakeApi()
        val report = import(api, FakeState(), csv = "ceci n'est pas un export")

        assertFalse(report.ok)
        assertTrue(api.upserts.isEmpty())
        assertTrue(api.deletes.isEmpty())
        assertTrue(report.summary().contains("export Glooko"))
    }

    @Test
    fun `un refus d écriture arrete l import et se dit`() {
        val api = FakeApi()
        val state = FakeState()
        api.upsertBehaviour = { NightscoutException(NightscoutErrorKind.UNAUTHORIZED, "401") }

        val report = import(
            api,
            state,
            csv = GlookoCsv()
                .row(time = "17/10/2026 12:00", bg = "120")
                .row(time = "17/10/2026 12:30", bolus = "3", bolusType = "normal")
                .build(),
        )

        assertFalse(report.ok)
        // Rien n'a été accepté : le refus intervient avant l'écriture.
        assertEquals(0, api.upserts.size)
        assertTrue(report.summary().contains("refusé"))
        assertEquals(0L, state.lastSuccessMs)
    }

    @Test
    fun `une lecture impossible empêche toute écriture`() {
        val api = object : NightscoutApi by FakeApi() {
            override fun entries(credentials: NightscoutCredentials, fromMs: Long, toMs: Long):
                List<NightscoutEntryRef> = throw NightscoutException(NightscoutErrorKind.NETWORK, "réseau")
        }
        val report = import(api, FakeState())

        assertFalse(report.ok)
        assertTrue(report.failures.single().startsWith("Lecture de Nightscout impossible"))
    }

    @Test
    fun `une panne passagère est réessayée et ne coûte aucun document`() {
        val api = FakeApi()
        var alreadyFailed = false
        api.upsertBehaviour = {
            if (alreadyFailed) {
                null
            } else {
                // Une coupure d'une seconde : la deuxième tentative doit passer.
                alreadyFailed = true
                NightscoutException(NightscoutErrorKind.NETWORK, "réseau")
            }
        }

        val report = import(
            api,
            FakeState(),
            csv = GlookoCsv()
                .row(time = "17/10/2026 12:00", bg = "120")
                .row(time = "17/10/2026 12:30", bolus = "3", bolusType = "normal")
                .build(),
        )

        assertTrue(report.failures.toString(), report.failures.isEmpty())
        assertEquals(2, report.written)
        // Une tentative perdue, puis deux écritures : la reprise a bien eu lieu.
        assertEquals(3, api.attempts)
    }

    @Test
    fun `un échec qui persiste dit combien de documents sont déjà écrits`() {
        val api = FakeApi()
        // La première écriture passe ; toutes les suivantes échouent, reprises comprises.
        api.upsertBehaviour = { attempt ->
            if (attempt >= 2) NightscoutException(NightscoutErrorKind.NETWORK, "réseau") else null
        }

        val report = import(
            api,
            FakeState(),
            csv = GlookoCsv()
                .row(time = "17/10/2026 12:00", bg = "120")
                .row(time = "17/10/2026 12:30", bolus = "3", bolusType = "normal")
                .row(time = "17/10/2026 13:00", bg = "140")
                .build(),
        )

        assertFalse(report.ok)
        assertEquals(1, report.written)
        val summary = report.summary()
        // Le compte passe avant la cause : sans lui, on relance à l'aveugle.
        assertTrue(summary, summary.startsWith("1 document(s) écrit(s), puis : "))
        assertTrue(summary, summary.contains("relancez, rien ne sera doublé"))
    }

    @Test
    fun `un refus définitif n est pas réessayé`() {
        val api = FakeApi()
        api.upsertBehaviour = { NightscoutException(NightscoutErrorKind.UNAUTHORIZED, "401") }

        val report = import(
            api,
            FakeState(),
            csv = GlookoCsv().row(time = "17/10/2026 12:00", bg = "120").build(),
        )

        assertFalse(report.ok)
        assertEquals(0, report.written)
        // Une clé refusée le sera encore dans deux secondes : une seule tentative.
        assertEquals(1, api.attempts)
    }
}
