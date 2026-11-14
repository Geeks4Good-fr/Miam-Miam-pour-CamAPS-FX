package com.najah.mealannouncer.core.datastore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutConfigTest {

    private val complete = NightscoutConfig(
        baseUrl = "https://ns.example.org",
        enabled = true,
        hasSecret = true,
    )

    @Test
    fun `une adresse sans secret ne suffit pas`() {
        assertFalse(complete.copy(hasSecret = false).isComplete())
    }

    @Test
    fun `un secret sans adresse ne suffit pas`() {
        assertFalse(complete.copy(baseUrl = "  ").isComplete())
    }

    @Test
    fun `l envoi demande activation, adresse et secret`() {
        assertTrue(complete.canSend())
        assertFalse(complete.copy(enabled = false).canSend())
        assertFalse(complete.copy(hasSecret = false).canSend())
    }

    @Test
    fun `la simulation n envoie jamais rien`() {
        assertFalse(complete.copy(simulation = true).canSend())
    }
}
