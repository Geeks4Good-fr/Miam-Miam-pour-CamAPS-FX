package com.najah.mealannouncer.integration.glooko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Les identifiants : stables, prévisibles, et conformes à la RFC 4122. */
class GlookoIdentifierTest {

    private val dns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    @Test
    fun `uuidv5 suit le vecteur de la RFC 4122`() {
        // Vecteur public de référence : UUIDv5(namespace DNS, "python.org").
        assertEquals(
            "886313e1-3b8a-5372-9b90-0c9aee199e5d",
            GlookoIdentifier.uuidV5(dns, "python.org").toString(),
        )
    }

    @Test
    fun `meme entree meme identifiant`() {
        val first = GlookoIdentifier.of("sgv", 1_792_845_296_000L, "sgv")
        val second = GlookoIdentifier.of("sgv", 1_792_845_296_000L, "sgv")
        assertEquals(first, second)
    }

    @Test
    fun `la cle metier distingue deux documents de la meme minute`() {
        val attemptShort = GlookoIdentifier.of("pumpmode", 1_792_845_296_000L, "pumpMode - Attempting|26000")
        val attemptLong = GlookoIdentifier.of("pumpmode", 1_792_845_296_000L, "pumpMode - Attempting|6232000")
        assertNotEquals(attemptShort, attemptLong)
    }

    @Test
    fun `l identifiant est un uuid de version 5`() {
        val uuid = UUID.fromString(GlookoIdentifier.of("sgv", 0L, "x"))
        assertEquals(5, uuid.version())
        assertEquals(2, uuid.variant())
    }

    @Test
    fun `l espace de noms est derive du DNS et non du hasard`() {
        // Valeur figée, calculée une fois : si elle change, tous les documents déjà envoyés
        // seraient recréés au prochain import.
        assertEquals(
            "3e7e8a54-a57a-5323-aed5-bb4095dc20eb",
            GlookoIdentifier.NAMESPACE.toString(),
        )
        assertFalse(GlookoIdentifier.NAMESPACE == dns)
    }

    @Test
    fun `un identifiant reste stable dun telephone a lautre`() {
        assertEquals(
            "3251e440-6910-57ad-be18-b29408f30fd9",
            GlookoIdentifier.of("sgv", 1_792_845_296_000L, "sgv"),
        )
    }
}
