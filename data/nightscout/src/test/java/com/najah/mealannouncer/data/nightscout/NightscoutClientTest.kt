package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.datastore.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class NightscoutClientTest {

    private val statusBody = """
        {"status":"ok","name":"Nightscout","version":"15.0.8","apiEnabled":true,
         "careportalEnabled":true,"settings":{"units":"mg/dl"}}
    """.trimIndent()

    private fun credentials(
        type: CredentialType = CredentialType.API_SECRET,
        secret: String = "abc",
        baseUrl: String = "https://ns.example.org",
    ) = NightscoutCredentials(baseUrl, type, secret)

    /** Rend l'exception levée par [block], en échouant si rien n'est levé. */
    private fun failure(block: () -> Unit): NightscoutException =
        assertThrows(NightscoutException::class.java) { block() }

    @Test
    fun `envoie le SHA-1 du secret pour une cle d API`() {
        val transport = FakeTransport(response = HttpResponse(200, statusBody))
        HttpNightscoutApi(transport).status(credentials())
        // Vecteur de référence SHA-1 : sha1("abc").
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            transport.requests.single().headers["api-secret"],
        )
    }

    @Test
    fun `token d'acces — token en api-secret et JWT en Bearer`() {
        // Un en-tete jamais vide (JSON renvoye par /api/v2/authorization/request).
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjk5OTk5OTk5OTl9.sig"
        val transport = FakeTransport(
            response = HttpResponse(200, """{"token":"$jwt"}"""),
            queued = listOf(HttpResponse(200, statusBody)),
        )
        HttpNightscoutApi(transport).status(credentials(type = CredentialType.ACCESS_TOKEN, secret = "tok-123"))
        // 1re requete : l'echange du token contre un JWT.
        assertTrue(transport.requests[0].url.endsWith("/api/v2/authorization/request/tok-123"))
        // 2e requete : le token ouvre la v1 par `api-secret`, le JWT ouvre la v3 par `Authorization`.
        val call = transport.requests[1]
        assertEquals("tok-123", call.headers["api-secret"])
        assertEquals("Bearer $jwt", call.headers["Authorization"])
    }

    @Test
    fun `interroge status json a la racine du serveur`() {
        val transport = FakeTransport(response = HttpResponse(200, statusBody))
        HttpNightscoutApi(transport).status(credentials(baseUrl = "https://ns.example.org/api/v1/"))
        val request = transport.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://ns.example.org/api/v1/status.json", request.url)
    }

    @Test
    fun `lit les renseignements du serveur`() {
        val transport = FakeTransport(response = HttpResponse(200, statusBody))
        val info = HttpNightscoutApi(transport).status(credentials())
        assertEquals("Nightscout", info.name)
        assertEquals("15.0.8", info.version)
        assertTrue(info.apiEnabled)
        assertTrue(info.careportalEnabled)
        assertEquals("mg/dl", info.units)
    }

    @Test
    fun `poste le traitement en JSON`() {
        val transport = FakeTransport(response = HttpResponse(200, "{}"))
        val payload = TreatmentPayload.toJson(sampleMeal)
        HttpNightscoutApi(transport).postTreatment(credentials(), payload)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://ns.example.org/api/v1/treatments", request.url)
        assertEquals("application/json", request.contentType)
        assertEquals(payload, request.body)
    }

    @Test
    fun `un 401 est un refus d identifiant`() {
        val transport = FakeTransport(response = HttpResponse(401, """{"status":401}"""))
        val error = failure { HttpNightscoutApi(transport).status(credentials()) }
        assertEquals(NightscoutErrorKind.UNAUTHORIZED, error.kind)
    }

    @Test
    fun `un 404 est une adresse introuvable`() {
        val transport = FakeTransport(response = HttpResponse(404, "Not found"))
        val error = failure { HttpNightscoutApi(transport).status(credentials()) }
        assertEquals(NightscoutErrorKind.NOT_FOUND, error.kind)
    }

    @Test
    fun `un 500 est une panne du serveur, reessayable`() {
        val transport = FakeTransport(response = HttpResponse(503, "nope"))
        val error = failure { HttpNightscoutApi(transport).status(credentials()) }
        assertEquals(NightscoutErrorKind.SERVER, error.kind)
        assertTrue(error.kind.isRetryable)
    }

    @Test
    fun `un delai depasse est reessayable`() {
        val transport = FakeTransport().apply { failWith(SocketTimeoutException("trop long")) }
        val error = failure { HttpNightscoutApi(transport).status(credentials()) }
        assertEquals(NightscoutErrorKind.TIMEOUT, error.kind)
        assertTrue(error.kind.isRetryable)
    }

    @Test
    fun `une panne reseau est reessayable`() {
        val transport = FakeTransport().apply { failWith(IOException("réseau coupé")) }
        val error = failure { HttpNightscoutApi(transport).status(credentials()) }
        assertEquals(NightscoutErrorKind.NETWORK, error.kind)
        assertTrue(error.kind.isRetryable)
    }

    @Test
    fun `une reponse illisible est signalee`() {
        val transport = FakeTransport(response = HttpResponse(200, "<html>pas du json</html>"))
        val error = failure { HttpNightscoutApi(transport).status(credentials()) }
        assertEquals(NightscoutErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `sans secret, rien n est envoye`() {
        val transport = FakeTransport()
        val error = failure { HttpNightscoutApi(transport).status(credentials(secret = "")) }
        assertEquals(NightscoutErrorKind.NOT_CONFIGURED, error.kind)
        assertTrue(transport.requests.isEmpty())
    }
}
