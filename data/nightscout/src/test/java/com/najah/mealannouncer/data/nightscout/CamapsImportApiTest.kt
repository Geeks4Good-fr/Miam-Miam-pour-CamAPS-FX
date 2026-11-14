package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.datastore.CredentialType
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le client d'un Nightscout for CamAPS FX : contrat P7 (aperçu, confirmation, codes d'erreur). */
class CamapsImportApiTest {

    private val token = NightscoutCredentials("https://ns.exemple.fr/", CredentialType.ACCESS_TOKEN, "camapsuploa-123")

    /** Un export Glooko avec BOM et fins de ligne Windows : il doit partir tel quel. */
    private val csv = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "Nom:;Exemple\r\nTimestamp;Type\r\n2026-10-28 08:00;Bolus\r\n".toByteArray(Charsets.UTF_8)

    private val previewBody = """{"previewId":"p-1","previewDigest":"sha256:abc","policyVersion":"1",
        "expiresAt":"2026-10-29T08:00:00Z","summary":{"days":2,"total":40,"new":38,"alreadyKnown":2,
        "conflicts":1,"possibleCorrections":0,"ambiguous":0,"unsupported":0,"unmapped":3,"rejectedRows":1}}"""

    private fun failure(block: () -> Unit): CamapsImportException =
        assertThrows(CamapsImportException::class.java) { block() }

    @Test
    fun `l'apercu envoie le CSV octet pour octet, BOM et CRLF compris`() {
        val transport = FakeTransport(HttpResponse(200, previewBody))
        val preview = HttpCamapsImportApi(transport).preview(token, csv)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://ns.exemple.fr/api/v1/camaps/imports/preview", request.url)
        assertEquals("text/csv", request.contentType)
        assertArrayEquals("Le fichier ne doit subir aucune transformation", csv, request.bodyBytes)
        assertNull("Pas de corps texte : il recoderait le fichier", request.body)
        assertEquals("Le jeton part tel quel dans api-secret", "camapsuploa-123", request.headers["api-secret"])

        assertEquals("p-1", preview.previewId)
        assertEquals("sha256:abc", preview.previewDigest)
        assertEquals(38, preview.summary.new)
        assertTrue(preview.summary.text().contains("40 événement(s) sur 2 jour(s)"))
        assertTrue(preview.summary.text().contains("1 conflit(s)"))
    }

    @Test
    fun `la confirmation cite l'apercu et lit le lot`() {
        val transport = FakeTransport(HttpResponse(200, """{"batchId":"b-9","status":"committed","alreadyCommitted":true}"""))
        val preview = HttpCamapsImportApi(FakeTransport(HttpResponse(200, previewBody))).preview(token, csv)

        val commit = HttpCamapsImportApi(transport).commit(token, preview)

        val request = transport.requests.single()
        assertEquals("https://ns.exemple.fr/api/v1/camaps/imports/commit", request.url)
        assertEquals("application/json", request.contentType)
        val body = JSONObject(request.body!!)
        assertEquals("p-1", body.getString("previewId"))
        assertEquals("sha256:abc", body.getString("previewDigest"))
        assertEquals("b-9", commit.batchId)
        assertTrue(commit.alreadyCommitted)
    }

    @Test
    fun `codes d'erreur du contrat — la conduite a tenir`() {
        fun kindFor(code: Int, body: String = """{"code":"X"}"""): CamapsImportErrorKind =
            failure { HttpCamapsImportApi(FakeTransport(HttpResponse(code, body))).preview(token, csv) }.kind

        assertEquals(CamapsImportErrorKind.REJECTED_FILE, kindFor(400))
        assertEquals(CamapsImportErrorKind.UNAUTHORIZED, kindFor(401))
        assertEquals(CamapsImportErrorKind.UNAUTHORIZED, kindFor(403))
        assertEquals("Nightscout classique, sans le module", CamapsImportErrorKind.NOT_ENABLED, kindFor(404, "Not found"))
        assertEquals(CamapsImportErrorKind.REDO_PREVIEW, kindFor(409))
        assertEquals(CamapsImportErrorKind.REDO_PREVIEW, kindFor(410))
        assertEquals(CamapsImportErrorKind.TOO_LARGE, kindFor(413))
        assertEquals(CamapsImportErrorKind.PROGRAMMING, kindFor(415))
        assertEquals(CamapsImportErrorKind.RETRY, kindFor(500))
    }

    @Test
    fun `le code du serveur est garde`() {
        val error = failure {
            HttpCamapsImportApi(FakeTransport(HttpResponse(410, """{"code":"PREVIEW_EXPIRED_OR_UNKNOWN"}""")))
                .preview(token, csv)
        }
        assertEquals("PREVIEW_EXPIRED_OR_UNKNOWN", error.code)
    }

    @Test
    fun `panne reseau — RETRY`() {
        val transport = FakeTransport()
        transport.failWith(java.io.IOException("Wi-Fi perdu"))
        assertEquals(CamapsImportErrorKind.RETRY, failure { HttpCamapsImportApi(transport).preview(token, csv) }.kind)
    }

    @Test
    fun `sans jeton ou au-dela de 10 Mo — rien ne part`() {
        val transport = FakeTransport(HttpResponse(200, previewBody))
        val noToken = token.copy(secret = "")
        assertEquals(CamapsImportErrorKind.NOT_CONFIGURED, failure { HttpCamapsImportApi(transport).preview(noToken, csv) }.kind)
        assertEquals(
            CamapsImportErrorKind.TOO_LARGE,
            failure { HttpCamapsImportApi(transport).preview(token, ByteArray(10_000_001)) }.kind,
        )
        assertFalse(transport.requests.any())
    }

    @Test
    fun `une cle API_SECRET part hachee en SHA-1, comme pour le reste de Nightscout`() {
        val transport = FakeTransport(HttpResponse(200, previewBody))
        HttpCamapsImportApi(transport).preview(token.copy(credentialType = CredentialType.API_SECRET, secret = "secret"), csv)
        assertEquals(sha1Hex("secret"), transport.requests.single().headers["api-secret"])
    }
}
