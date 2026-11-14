package com.najah.mealannouncer.integration.glookoexport

import org.junit.Assert.assertEquals
import org.junit.Test

class GlookoCarnetMomentTest {

    @Test
    fun `les plages de repas`() {
        assertEquals(CarnetMoment.BREAKFAST, GlookoCarnetMoment.forHour(8))
        assertEquals(CarnetMoment.LUNCH, GlookoCarnetMoment.forHour(12))
        assertEquals(CarnetMoment.DINNER, GlookoCarnetMoment.forHour(20))
    }

    @Test
    fun `hors des repas, c'est un en-cas`() {
        assertEquals(CarnetMoment.SNACK, GlookoCarnetMoment.forHour(3))
        assertEquals(CarnetMoment.SNACK, GlookoCarnetMoment.forHour(16))
        assertEquals(CarnetMoment.SNACK, GlookoCarnetMoment.forHour(23))
    }

    @Test
    fun `les bornes exactes`() {
        assertEquals(CarnetMoment.BREAKFAST, GlookoCarnetMoment.forHour(5))
        assertEquals(CarnetMoment.BREAKFAST, GlookoCarnetMoment.forHour(10))
        assertEquals(CarnetMoment.LUNCH, GlookoCarnetMoment.forHour(11))
        assertEquals(CarnetMoment.LUNCH, GlookoCarnetMoment.forHour(14))
        assertEquals(CarnetMoment.SNACK, GlookoCarnetMoment.forHour(15))
        assertEquals(CarnetMoment.DINNER, GlookoCarnetMoment.forHour(18))
        assertEquals(CarnetMoment.DINNER, GlookoCarnetMoment.forHour(22))
    }
}
