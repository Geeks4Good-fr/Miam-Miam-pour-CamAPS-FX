package com.najah.mealannouncer.core.database.mapper

import com.najah.mealannouncer.core.database.entity.MealLogEntity
import com.najah.mealannouncer.core.model.CamapsStatus
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.GlookoStatus
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.NightscoutStatus
import com.najah.mealannouncer.core.model.ResolvedMealItem
import com.najah.mealannouncer.core.model.CarbBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Les allers-retours entité ↔ domaine ne doivent rien perdre d'important. */
class MappersTest {

    private val instant = Instant.parse("2026-10-24T02:10:00Z")

    @Test
    fun `produit aller et retour`() {
        val produit = FoodProduct(
            id = "prince",
            barcode = "3229820129488",
            name = "Biscuit Prince",
            brand = "LU",
            carbsPer100g = 68.0,
            carbsPerUnit = 11.0,
            defaultPortionGrams = 16.5,
            source = FoodSource.USER_FAVORITE,
            verifiedByUser = true,
            updatedAt = instant,
        )

        val retour = produit.toEntity().toDomain()

        assertEquals(produit, retour)
    }

    @Test
    fun `journal et lignes reconstituent le repas`() {
        val log = MealLogEntity(
            id = "repas-1",
            rawTranscript = "deux biscuits Prince",
            totalCarbs = 22.0,
            notes = "2 biscuits Prince",
            createdAt = instant.toEpochMilli(),
            camapsStatus = CamapsStatus.UI_AUTOMATION_DONE,
            nightscoutStatus = NightscoutStatus.SENT,
            glookoStatus = GlookoStatus.QUEUED,
            payloadJson = "{}",
        )

        val ligne = ResolvedMealItem(
            productName = "Biscuit Prince",
            brand = "LU",
            quantity = 2.0,
            unit = MealUnit.UNIT,
            carbsPerUnit = 11.0,
            totalCarbs = Carbs(22.0),
            source = FoodSource.USER_FAVORITE,
            confidence = 0.9f,
            basis = CarbBasis.PER_UNIT,
        ).toEntity(log.id, 0)

        val repas = log.toDomain(listOf(ligne))

        assertEquals("repas-1", repas.id)
        assertEquals(22.0, repas.totalCarbs.grams, 0.0001)
        assertEquals(1, repas.items.size)
        assertEquals("Biscuit Prince", repas.items.first().productName)
        assertEquals(MealUnit.UNIT, repas.items.first().unit)
        assertTrue(repas.camapsAnnounced)
        assertTrue(repas.nightscoutSaved)
        assertEquals(GlookoStatus.QUEUED, repas.glookoStatus)
        assertEquals(instant, repas.createdAt)
        assertEquals("deux biscuits Prince", repas.rawTranscript)
    }

    @Test
    fun `une ligne sans glucides retombe sur zero sans planter`() {
        val log = MealLogEntity(
            id = "repas-2",
            rawTranscript = "",
            totalCarbs = 0.0,
            notes = "",
            createdAt = instant.toEpochMilli(),
            camapsStatus = CamapsStatus.NOT_REQUESTED,
            nightscoutStatus = NightscoutStatus.NOT_REQUESTED,
            glookoStatus = GlookoStatus.NOT_REQUIRED,
            payloadJson = "{}",
        )
        val ligne = com.najah.mealannouncer.core.database.entity.MealItemEntity(
            id = "l1",
            mealLogId = "repas-2",
            productName = "Inconnu",
            brand = null,
            quantity = 0.0,
            unit = MealUnit.UNKNOWN,
            carbs = null,
            confidence = 0f,
        )

        val repas = log.toDomain(listOf(ligne))

        assertEquals(0.0, repas.totalCarbs.grams, 0.0)
        assertEquals(0.0, repas.items.first().totalCarbs.grams, 0.0)
        assertFalse(repas.camapsAnnounced)
    }
}
