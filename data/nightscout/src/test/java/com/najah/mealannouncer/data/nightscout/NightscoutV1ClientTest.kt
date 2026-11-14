package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.datastore.CredentialType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API v1 : la seule que beaucoup de serveurs ouvrent à une clé d'API.
 *
 * Elle ajoute des documents mais ne les met pas à jour, et elle n'écrit pas de profil.
 * L'import doit s'en accommoder sans jamais perdre un document : c'est ce que ces tests
 * tiennent.
 */
class NightscoutV1ClientTest {

    private fun credentials() = NightscoutCredentials(
        baseUrl = "https://ns.example.org",
        credentialType = CredentialType.API_SECRET,
        secret = "abc",
    )

    private fun api(vararg responses: HttpResponse) = HttpNightscoutApi(
        FakeTransport(response = responses.first(), queued = responses.drop(1)),
        NightscoutApiStyle.V1,
    )

    @Test
    fun `ajoute le traitement a la collection v1`() {
        val transport = FakeTransport(response = HttpResponse(201, """{"_id":"1"}"""))
        val status = HttpNightscoutApi(transport, NightscoutApiStyle.V1)
            .upsert(credentials(), NightscoutDocument.TREATMENTS, """{"identifier":"x"}""")

        assertEquals(201, status)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://ns.example.org/api/v1/treatments", request.url)
        assertEquals("application/json", request.contentType)
        assertEquals("""{"identifier":"x"}""", request.body)
    }

    /** La v1 n'expose le profil qu'en lecture : le dire vaut mieux que le perdre en silence. */
    @Test
    fun `le profil est signale impossible plutot que perdu`() {
        val error = assertThrows(NightscoutException::class.java) {
            api(HttpResponse(201, "{}"))
                .upsert(credentials(), NightscoutDocument.PROFILE, """{"identifier":"p"}""")
        }

        assertEquals(NightscoutErrorKind.PROFILE_UNSUPPORTED, error.kind)
    }

    @Test
    fun `lit une plage avec les filtres mongo de la v1`() {
        val transport = FakeTransport(
            response = HttpResponse(
                200,
                """[{"_id":"abc","device":"xDrip","date":1500,"identifier":"i1"}]""",
            ),
        )

        val refs = HttpNightscoutApi(transport, NightscoutApiStyle.V1)
            .entries(credentials(), fromMs = 0L, toMs = 2_000L)

        assertEquals(1, refs.size)
        assertEquals(1_500L, refs[0].dateMs)
        assertEquals("xDrip", refs[0].device)
        assertEquals("i1", refs[0].identifier)
        assertEquals("abc", refs[0].id)
        assertEquals(
            "https://ns.example.org/api/v1/entries" +
                "?find%5Bdate%5D%5B%24gte%5D=0&find%5Bdate%5D%5B%24lte%5D=2000&count=500",
            transport.requests.single().url,
        )
    }

    @Test
    fun `lit les traitements par created_at`() {
        val transport = FakeTransport(
            response = HttpResponse(
                200,
                """[{"_id":"t1","created_at":"2026-10-24T12:34:56.000Z","eventType":"Meal Bolus",
                     "enteredBy":"Glooko-CamAPS","identifier":"abc"}]""",
            ),
        )

        val refs = HttpNightscoutApi(transport, NightscoutApiStyle.V1)
            .treatments(credentials(), fromMs = 0L, toMs = 2_000L)

        assertEquals(1, refs.size)
        assertEquals("Meal Bolus", refs[0].eventType)
        assertEquals("Glooko-CamAPS", refs[0].enteredBy)
        assertEquals("t1", refs[0].id)
        assertTrue(
            transport.requests.single().url.startsWith(
                "https://ns.example.org/api/v1/treatments?find%5Bcreated_at%5D",
            ),
        )
    }

    /**
     * Une page pleine peut avoir été tronquée par le serveur : elle n'est pas retenue,
     * l'intervalle est recoupé en deux et relu. Perdre une ligne ici ferait réécrire un
     * repas déjà présent.
     */
    @Test
    fun `une page pleine est recoupee pour ne rien perdre`() {
        fun page(vararg dates: IntRange) = dates
            .flatMap { it.toList() }
            .joinToString(",", "[", "]") { """{"date":$it,"device":"xDrip"}""" }

        val transport = FakeTransport(
            // Page pleine (donc peut-être tronquée) : son contenu ne doit pas être retenu.
            response = HttpResponse(200, page(1..500)),
            queued = listOf(
                HttpResponse(200, page(1..250)),
                HttpResponse(200, page(251..500)),
            ),
        )

        val refs = HttpNightscoutApi(transport, NightscoutApiStyle.V1)
            .entries(credentials(), fromMs = 0L, toMs = 2_000L)

        assertEquals(500, refs.size)
        assertEquals(1L, refs.first().dateMs)
        assertEquals(500L, refs.last().dateMs)
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("lte%5D=2000"))
        assertTrue(transport.requests[1].url.contains("lte%5D=1000"))
        assertTrue(transport.requests[2].url.contains("gte%5D=1001"))
    }

    @Test
    fun `decoupe une fenetre de plusieurs jours en tranches`() {
        val transport = FakeTransport(response = HttpResponse(200, "[]"))
        val dayMs = 24L * 60 * 60 * 1000

        HttpNightscoutApi(transport, NightscoutApiStyle.V1)
            .treatments(credentials(), fromMs = 0L, toMs = 3 * dayMs)

        // 72 h en tranches de 12 h : six demandes, sans recouvrement.
        assertEquals(6, transport.requests.size)
        assertTrue(transport.requests[1].url.contains("gte%5D="))
    }

    @Test
    fun `supprime par l id du serveur`() {
        val transport = FakeTransport(response = HttpResponse(200, """{"deletedCount":1}"""))

        val status = HttpNightscoutApi(transport, NightscoutApiStyle.V1).delete(
            credentials = credentials(),
            document = NightscoutDocument.TREATMENTS,
            identifier = "uuid-metier",
            itemId = "6a16bbf96cdda53deb6fb7e0",
        )

        assertEquals(200, status)
        assertEquals(
            "https://ns.example.org/api/v1/treatments/6a16bbf96cdda53deb6fb7e0",
            transport.requests.single().url,
        )
    }

    @Test
    fun `sans id de serveur il n y a rien a supprimer`() {
        val transport = FakeTransport(response = HttpResponse(200, "{}"))

        val status = HttpNightscoutApi(transport, NightscoutApiStyle.V1)
            .delete(credentials(), NightscoutDocument.ENTRIES, "uuid-metier")

        assertEquals(204, status)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `une suppression refusee est classee`() {
        val error = assertThrows(NightscoutException::class.java) {
            HttpNightscoutApi(FakeTransport(response = HttpResponse(401, "{}")), NightscoutApiStyle.V1)
                .delete(credentials(), NightscoutDocument.ENTRIES, "uuid", "abc")
        }

        assertEquals(NightscoutErrorKind.UNAUTHORIZED, error.kind)
    }

    // ---- Négociation du style -------------------------------------------------------

    /**
     * Le serveur de l'utilisateur refuse la v3 aux clés d'API (401) : c'est la v1 qui prend le
     * relais, sans que l'appelant ait à le savoir.
     */
    @Test
    fun `une v3 refusee fait passer en v1`() {
        val transport = FakeTransport(
            response = HttpResponse(401, "{}"),
            queued = listOf(HttpResponse(200, "[]")),
        )

        val refs = HttpNightscoutApi(transport).entries(credentials(), fromMs = 0L, toMs = 2_000L)

        assertTrue(refs.isEmpty())
        assertEquals(2, transport.requests.size)
        assertEquals("https://ns.example.org/api/v3/entries?limit=1", transport.requests[0].url)
        assertTrue(
            transport.requests[1].url.startsWith("https://ns.example.org/api/v1/entries?"),
        )
    }

    @Test
    fun `une v3 ouverte est utilisee`() {
        val transport = FakeTransport(
            response = HttpResponse(200, """{"status":200,"result":[]}"""),
            queued = listOf(HttpResponse(200, """{"status":200,"result":[]}""")),
        )

        HttpNightscoutApi(transport).entries(credentials(), fromMs = 0L, toMs = 2_000L)

        assertEquals(2, transport.requests.size)
        assertEquals("https://ns.example.org/api/v3/entries?limit=1", transport.requests[0].url)
        assertTrue(transport.requests[1].url.startsWith("https://ns.example.org/api/v3/entries?"))
    }

    /** Le style trouvé est gardé : deux lectures ne sondent qu'une fois. */
    @Test
    fun `le style est demande une seule fois`() {
        val transport = FakeTransport(
            response = HttpResponse(401, "{}"),
            queued = listOf(HttpResponse(200, "[]"), HttpResponse(200, "[]")),
        )
        val api = HttpNightscoutApi(transport)

        api.entries(credentials(), fromMs = 0L, toMs = 1_000L)
        api.treatments(credentials(), fromMs = 0L, toMs = 1_000L)

        assertEquals(3, transport.requests.size)
        assertEquals(
            1,
            transport.requests.count { it.url.endsWith("/api/v3/entries?limit=1") },
        )
    }

    @Test
    fun `une sonde en panne serveur est classee`() {
        val error = assertThrows(NightscoutException::class.java) {
            HttpNightscoutApi(FakeTransport(response = HttpResponse(503, "nope")))
                .entries(credentials(), fromMs = 0L, toMs = 1_000L)
        }

        assertEquals(NightscoutErrorKind.SERVER, error.kind)
    }

    @Test
    fun `un identifiant absent de la reponse reste null`() {
        val refs = api(HttpResponse(200, """[{"date":1500,"device":"xDrip"}]"""))
            .entries(credentials(), fromMs = 0L, toMs = 2_000L)

        assertNull(refs[0].identifier)
        assertNull(refs[0].id)
    }
}
