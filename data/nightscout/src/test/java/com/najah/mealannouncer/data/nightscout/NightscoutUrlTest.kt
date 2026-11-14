package com.najah.mealannouncer.data.nightscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightscoutUrlTest {

    @Test
    fun `retire l identifiant devant l hote et le suffixe d api`() {
        assertEquals(
            "https://ns.exemple.fr",
            NightscoutUrl.normalize("https://MON-SECRET@ns.exemple.fr/api/v1/"),
        )
    }

    @Test
    fun `ajoute https quand le schema manque`() {
        assertEquals("https://ns.example.org", NightscoutUrl.normalize("ns.example.org"))
    }

    @Test
    fun `enleve les barres finales`() {
        assertEquals("https://ns.example.org", NightscoutUrl.normalize("https://ns.example.org///"))
    }

    @Test
    fun `garde un chemin de deploiement`() {
        assertEquals(
            "https://example.org/nightscout",
            NightscoutUrl.normalize("https://example.org/nightscout/api/v1"),
        )
    }

    @Test
    fun `accepte http et un port local`() {
        assertEquals("http://192.168.1.10:1337", NightscoutUrl.normalize("http://192.168.1.10:1337/api/v1"))
    }

    @Test
    fun `met le schema et l hote en minuscules`() {
        assertEquals("https://ns.example.org", NightscoutUrl.normalize("HTTPS://NS.Example.ORG/API/V1"))
    }

    @Test
    fun `refuse un schema qui n est pas http`() {
        assertNull(NightscoutUrl.normalize("ftp://ns.example.org"))
    }

    @Test
    fun `refuse une adresse vide`() {
        assertNull(NightscoutUrl.normalize(""))
        assertNull(NightscoutUrl.normalize("   "))
    }

    @Test
    fun `refuse une adresse sans hote`() {
        assertNull(NightscoutUrl.normalize("https://"))
        assertNull(NightscoutUrl.normalize("https:///api/v1"))
    }

    @Test
    fun `compose les points d entree de l api`() {
        assertEquals(
            "https://ns.example.org/api/v1/status.json",
            NightscoutUrl.statusUrl("https://ns.example.org"),
        )
        assertEquals(
            "https://ns.example.org/api/v1/treatments",
            NightscoutUrl.treatmentsUrl("https://ns.example.org/"),
        )
    }

    /** La v1 ne prend pas de bornes `from`/`to` : elle parle en filtres Mongo. */
    @Test
    fun `compose une plage v1 pour les glycemies et les traitements`() {
        assertEquals(
            "https://ns.example.org/api/v1/entries" +
                "?find%5Bdate%5D%5B%24gte%5D=1000&find%5Bdate%5D%5B%24lte%5D=2000&count=500",
            NightscoutUrl.v1RangeUrl(
                baseUrl = "https://ns.example.org",
                document = NightscoutDocument.ENTRIES,
                fromMs = 1_000L,
                toMs = 2_000L,
                count = 500,
            ),
        )
        assertEquals(
            "https://ns.example.org/api/v1/treatments" +
                "?find%5Bcreated_at%5D%5B%24gte%5D=2026-10-24T12%3A34%3A56.000Z" +
                "&find%5Bcreated_at%5D%5B%24lte%5D=2026-10-24T12%3A35%3A56.000Z&count=500",
            NightscoutUrl.v1RangeUrl(
                baseUrl = "https://ns.example.org/",
                document = NightscoutDocument.TREATMENTS,
                fromMs = 1_792_845_296_000L,
                toMs = 1_792_845_356_000L,
                count = 500,
            ),
        )
    }

    @Test
    fun `compose les points d entree v1 et la sonde v3`() {
        assertEquals(
            "https://ns.example.org/api/v1/treatments",
            NightscoutUrl.v1CollectionUrl("https://ns.example.org", NightscoutDocument.TREATMENTS),
        )
        assertEquals(
            "https://ns.example.org/api/v1/entries/abc123",
            NightscoutUrl.v1ItemUrl("https://ns.example.org", NightscoutDocument.ENTRIES, "abc123"),
        )
        assertEquals(
            "https://ns.example.org/api/v3/entries?limit=1",
            NightscoutUrl.v3ProbeUrl("https://ns.example.org"),
        )
    }
}
