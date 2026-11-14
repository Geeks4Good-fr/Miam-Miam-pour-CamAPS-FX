package com.najah.mealannouncer.integration.glooko

import com.najah.mealannouncer.core.datastore.CredentialType
import com.najah.mealannouncer.data.nightscout.CamapsCommit
import com.najah.mealannouncer.data.nightscout.CamapsImportApi
import com.najah.mealannouncer.data.nightscout.CamapsImportErrorKind
import com.najah.mealannouncer.data.nightscout.CamapsImportException
import com.najah.mealannouncer.data.nightscout.CamapsImportSummary
import com.najah.mealannouncer.data.nightscout.CamapsPreview
import com.najah.mealannouncer.data.nightscout.NightscoutCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La conduite de reprise du § 3 de P7, sans réseau. */
class CamapsGlookoUploaderTest {

    private val credentials = NightscoutCredentials("https://ns.exemple.fr", CredentialType.ACCESS_TOKEN, "jeton")
    private val csv = "export".toByteArray()
    private val summary = CamapsImportSummary(1, 10, 10, 0, 0, 0, 0, 0, 0, 0)

    /** Faux serveur : rend les réponses ou les refus prévus, dans l'ordre. */
    private class FakeApi(
        val previews: MutableList<Any>,
        val commits: MutableList<Any>,
    ) : CamapsImportApi {
        var previewCalls = 0
        val committed = mutableListOf<CamapsPreview>()

        override fun preview(credentials: NightscoutCredentials, csv: ByteArray): CamapsPreview {
            previewCalls++
            return when (val next = previews.removeAt(0)) {
                is CamapsImportException -> throw next
                else -> next as CamapsPreview
            }
        }

        override fun commit(credentials: NightscoutCredentials, preview: CamapsPreview): CamapsCommit {
            committed += preview
            return when (val next = commits.removeAt(0)) {
                is CamapsImportException -> throw next
                else -> next as CamapsCommit
            }
        }
    }

    private fun preview(id: String) = CamapsPreview(id, "d-$id", null, summary)
    private fun error(kind: CamapsImportErrorKind) = CamapsImportException(kind, null, "refus $kind")

    @Test
    fun `simulation — apercu seul, aucune confirmation`() {
        val api = FakeApi(mutableListOf(preview("1")), mutableListOf())
        val report = CamapsGlookoUploader(api).upload(credentials, csv, dryRun = true)
        assertTrue(report.ok)
        assertTrue(report.dryRun)
        assertTrue(report.summary.startsWith("Vérifié sans importer"))
        assertTrue("Rien ne doit être confirmé", api.committed.isEmpty())
    }

    @Test
    fun `import normal — apercu puis confirmation du meme apercu`() {
        val api = FakeApi(mutableListOf(preview("1")), mutableListOf(CamapsCommit("b", false)))
        val report = CamapsGlookoUploader(api).upload(credentials, csv, dryRun = false)
        assertTrue(report.ok)
        assertEquals("1", api.committed.single().previewId)
        assertTrue(report.summary.startsWith("Importé dans Nightscout for CamAPS FX"))
    }

    @Test
    fun `confirmation perdue — rejouee telle quelle, sans nouvel apercu`() {
        val api = FakeApi(
            mutableListOf(preview("1")),
            mutableListOf(error(CamapsImportErrorKind.RETRY), CamapsCommit("b", true)),
        )
        val report = CamapsGlookoUploader(api).upload(credentials, csv, dryRun = false)
        assertTrue(report.ok)
        assertEquals(1, api.previewCalls)
        assertEquals(listOf("1", "1"), api.committed.map { it.previewId })
        assertTrue(report.summary.contains("rien en double"))
    }

    @Test
    fun `apercu perime — refait une fois puis confirme`() {
        val api = FakeApi(
            mutableListOf(preview("1"), preview("2")),
            mutableListOf(error(CamapsImportErrorKind.REDO_PREVIEW), CamapsCommit("b", false)),
        )
        val report = CamapsGlookoUploader(api).upload(credentials, csv, dryRun = false)
        assertTrue(report.ok)
        assertEquals(listOf("1", "2"), api.committed.map { it.previewId })
    }

    @Test
    fun `jeton refuse ou serveur classique — echec sans nouvel essai`() {
        for (kind in listOf(CamapsImportErrorKind.UNAUTHORIZED, CamapsImportErrorKind.NOT_ENABLED)) {
            val api = FakeApi(mutableListOf(error(kind)), mutableListOf())
            val report = CamapsGlookoUploader(api).upload(credentials, csv, dryRun = false)
            assertFalse(report.ok)
            assertEquals(1, api.previewCalls)
        }
    }

    @Test
    fun `serveur en panne a chaque confirmation — on s'arrete apres deux rejeux`() {
        val api = FakeApi(mutableListOf(preview("1")), MutableList(3) { error(CamapsImportErrorKind.RETRY) })
        val report = CamapsGlookoUploader(api).upload(credentials, csv, dryRun = false)
        assertFalse(report.ok)
        assertEquals(3, api.committed.size)
    }
}
