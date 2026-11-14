package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.datastore.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** API v3 : c'est elle qui rend l'import rejouable sans créer de doublon. */
class NightscoutV3ClientTest {

    private fun credentials() = NightscoutCredentials(
        baseUrl = "https://ns.example.org",
        credentialType = CredentialType.API_SECRET,
        secret = "abc",
    )

    private fun api(response: HttpResponse) = HttpNightscoutApi(FakeTransport(response = response), NightscoutApiStyle.V3)

    @Test
    fun `ecrit un document v3 et rend le code de creation`() {
        val transport = FakeTransport(response = HttpResponse(201, """{"status":201}"""))
        val status = HttpNightscoutApi(transport, NightscoutApiStyle.V3)
            .upsert(credentials(), NightscoutDocument.TREATMENTS, """{"identifier":"x"}""")

        assertEquals(201, status)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://ns.example.org/api/v3/treatments", request.url)
        assertEquals("application/json", request.contentType)
        assertEquals("""{"identifier":"x"}""", request.body)
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            request.headers["api-secret"],
        )
    }

    @Test
    fun `rend le code de mise a jour quand le document existait deja`() {
        assertEquals(
            200,
            api(HttpResponse(200, "{}"))
                .upsert(credentials(), NightscoutDocument.ENTRIES, """{"identifier":"x"}"""),
        )
    }

    @Test
    fun `interroge une plage en ISO echappe`() {
        val transport = FakeTransport(response = HttpResponse(200, """{"status":200,"result":[]}"""))
        HttpNightscoutApi(transport, NightscoutApiStyle.V3).entries(credentials(), fromMs = 0L, toMs = 1_000L)

        assertEquals(
            "https://ns.example.org/api/v3/entries" +
                "?from=1970-01-01T00%3A00%3A00.000Z&to=1970-01-01T00%3A00%3A01.000Z",
            transport.requests.single().url,
        )
    }

    @Test
    fun `lit les glycemies existantes avec leur device`() {
        val body = """
            {"status":200,"result":[
              {"date":1700000000000,"device":"xDrip","identifier":"i1"},
              {"dateString":"2026-10-24T12:34:56.000Z","device":"Glooko-CamAPS"}
            ]}
        """.trimIndent()

        val refs = api(HttpResponse(200, body)).entries(credentials(), 0L, 1L)

        assertEquals(2, refs.size)
        assertEquals(1700000000000L, refs[0].dateMs)
        assertEquals("xDrip", refs[0].device)
        assertEquals("i1", refs[0].identifier)
        assertEquals(1_792_845_296_000L, refs[1].dateMs)
        assertEquals("Glooko-CamAPS", refs[1].device)
        assertNull(refs[1].identifier)
    }

    @Test
    fun `lit les traitements existants avec leur auteur`() {
        val body = """
            {"status":200,"result":[
              {"created_at":"2026-10-24T12:34:56.000Z","eventType":"Meal Bolus",
               "enteredBy":"Meal Announcer","identifier":"abc"}
            ]}
        """.trimIndent()

        val refs = api(HttpResponse(200, body)).treatments(credentials(), 0L, 1L)

        assertEquals(1, refs.size)
        assertEquals(1_792_845_296_000L, refs[0].createdAtMs)
        assertEquals("Meal Bolus", refs[0].eventType)
        assertEquals("Meal Announcer", refs[0].enteredBy)
        assertEquals("abc", refs[0].identifier)
    }

    @Test
    fun `accepte la reponse tableau de la v1`() {
        val body = """[{"date":1700000000000,"device":"xDrip"}]"""
        assertEquals(1, api(HttpResponse(200, body)).entries(credentials(), 0L, 1L).size)
    }

    @Test
    fun `une lecture vide ne fait pas echouer`() {
        assertTrue(api(HttpResponse(200, "")).entries(credentials(), 0L, 1L).isEmpty())
    }

    @Test
    fun `supprime par identifiant et tolere un document deja absent`() {
        val transport = FakeTransport(response = HttpResponse(404, "{}"))
        assertEquals(
            404,
            HttpNightscoutApi(transport, NightscoutApiStyle.V3).delete(credentials(), NightscoutDocument.ENTRIES, "abc"),
        )
        val request = transport.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("https://ns.example.org/api/v3/entries/abc", request.url)
    }

    @Test
    fun `une suppression qui echoue vraiment est signalee`() {
        val error = assertThrows(NightscoutException::class.java) {
            api(HttpResponse(503, "nope")).delete(credentials(), NightscoutDocument.ENTRIES, "abc")
        }
        assertEquals(NightscoutErrorKind.SERVER, error.kind)
    }

    @Test
    fun `une lecture refusee est classee`() {
        val error = assertThrows(NightscoutException::class.java) {
            api(HttpResponse(401, "{}")).entries(credentials(), 0L, 1L)
        }
        assertEquals(NightscoutErrorKind.UNAUTHORIZED, error.kind)
    }

    @Test
    fun `une reponse de lecture illisible est classee`() {
        val error = assertThrows(NightscoutException::class.java) {
            api(HttpResponse(200, "<html>pas du json</html>")).treatments(credentials(), 0L, 1L)
        }
        assertEquals(NightscoutErrorKind.INVALID_RESPONSE, error.kind)
    }

    @Test
    fun `une date illisible est ignoree plutot que devinee`() {
        assertNull(isoToEpochMs("2026-13-40T99:99:99Z"))
        assertNull(isoToEpochMs(""))
        assertEquals(
            1_792_845_296_000L,
            isoToEpochMs("2026-10-24T14:34:56+02:00"),
        )
    }
}
