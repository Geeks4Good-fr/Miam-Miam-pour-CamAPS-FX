package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.model.NightscoutStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightscoutRepositoryTest {

    @Test
    fun `un envoi reussi marque le repas comme envoye`() = runTest {
        val meals = FakeMealRepository(sampleMeal)
        val api = FakeApi()
        val repository = NightscoutRepository(FakeSettings(), api, meals)

        assertEquals(NightscoutSendOutcome.Sent, repository.sendNow("meal-1"))
        assertEquals(NightscoutStatus.SENT, meals.statusOf("meal-1"))
        assertEquals(1, api.postedPayloads.size)
        assertTrue(api.postedPayloads.single().contains("\"carbs\":40"))
    }

    @Test
    fun `un refus d identifiant ne marque pas le repas envoye`() = runTest {
        val meals = FakeMealRepository(sampleMeal)
        val api = FakeApi(failure = NightscoutException(NightscoutErrorKind.UNAUTHORIZED, "401"))
        val repository = NightscoutRepository(FakeSettings(), api, meals)

        val outcome = repository.sendNow("meal-1")

        assertEquals(NightscoutSendOutcome.Failed(NightscoutErrorKind.UNAUTHORIZED), outcome)
        assertNull(meals.statusOf("meal-1"))
    }

    @Test
    fun `la simulation n envoie rien et ne touche pas au journal`() = runTest {
        val meals = FakeMealRepository(sampleMeal)
        val api = FakeApi()
        val repository = NightscoutRepository(
            FakeSettings(config = configuredAndEnabled.copy(simulation = true)),
            api,
            meals,
        )

        assertEquals(NightscoutSendOutcome.Simulated, repository.sendNow("meal-1"))
        assertTrue(api.postedPayloads.isEmpty())
        assertNull(meals.statusOf("meal-1"))
    }

    @Test
    fun `sans activation, aucun envoi n est tenté`() = runTest {
        val meals = FakeMealRepository(sampleMeal)
        val api = FakeApi()
        val repository = NightscoutRepository(
            FakeSettings(config = configuredAndEnabled.copy(enabled = false)),
            api,
            meals,
        )

        assertEquals(NightscoutSendOutcome.Disabled, repository.sendNow("meal-1"))
        assertTrue(api.postedPayloads.isEmpty())
    }

    @Test
    fun `sans secret, aucun envoi n est tenté`() = runTest {
        val meals = FakeMealRepository(sampleMeal)
        val api = FakeApi()
        val repository = NightscoutRepository(FakeSettings(secret = null), api, meals)

        assertEquals(NightscoutSendOutcome.Disabled, repository.sendNow("meal-1"))
        assertTrue(api.postedPayloads.isEmpty())
    }

    @Test
    fun `un repas deja envoye n est pas renvoye`() = runTest {
        val meals = FakeMealRepository(sampleMeal)
        meals.updateNightscoutStatus("meal-1", NightscoutStatus.SENT)
        val api = FakeApi()
        val repository = NightscoutRepository(FakeSettings(), api, meals)

        assertEquals(NightscoutSendOutcome.AlreadySent, repository.sendNow("meal-1"))
        assertTrue(api.postedPayloads.isEmpty())
    }

    @Test
    fun `un repas supprime n est pas envoye`() = runTest {
        val meals = FakeMealRepository()
        val api = FakeApi()
        val repository = NightscoutRepository(FakeSettings(), api, meals)

        assertEquals(NightscoutSendOutcome.NoMeal, repository.sendNow("meal-1"))
        assertTrue(api.postedPayloads.isEmpty())
    }

    @Test
    fun `le test de connexion renvoie les renseignements du serveur`() = runTest {
        val repository = NightscoutRepository(FakeSettings(), FakeApi(), FakeMealRepository())

        val result = repository.testConnection()

        val success = result as NightscoutTestResult.Success
        assertEquals("15.0.8", success.server.version)
    }

    @Test
    fun `le test de connexion signale un refus d identifiant`() = runTest {
        val api = FakeApi(failure = NightscoutException(NightscoutErrorKind.UNAUTHORIZED, "401"))
        val repository = NightscoutRepository(FakeSettings(), api, FakeMealRepository())

        val result = repository.testConnection()

        assertEquals(
            NightscoutTestResult.Failure(NightscoutErrorKind.UNAUTHORIZED),
            result,
        )
    }

    @Test
    fun `le test de connexion refuse de partir sans adresse`() = runTest {
        val repository = NightscoutRepository(
            FakeSettings(config = configuredAndEnabled.copy(baseUrl = "")),
            FakeApi(),
            FakeMealRepository(),
        )

        assertEquals(
            NightscoutTestResult.Failure(NightscoutErrorKind.NOT_CONFIGURED),
            repository.testConnection(),
        )
    }
}
