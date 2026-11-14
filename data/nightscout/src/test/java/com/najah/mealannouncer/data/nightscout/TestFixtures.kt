package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.database.repository.MealRepository
import com.najah.mealannouncer.core.datastore.NightscoutConfig
import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.core.model.CarbBasis
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.NightscoutStatus
import com.najah.mealannouncer.core.model.ResolvedMealItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

/** Repas de référence : le cas validé sur le téléphone, Prince ×2 + Candy Up 200 ml = 40 g. */
internal val sampleMeal = MealConfirmation(
    id = "meal-1",
    totalCarbs = Carbs(40.0),
    items = listOf(
        ResolvedMealItem(
            productName = "Prince",
            brand = "LU",
            quantity = 2.0,
            unit = MealUnit.UNIT,
            carbsPerUnit = 11.0,
            totalCarbs = Carbs(22.0),
            source = FoodSource.USER_FAVORITE,
            confidence = 0.9f,
            basis = CarbBasis.PER_UNIT,
        ),
        ResolvedMealItem(
            productName = "Candy Up",
            brand = null,
            quantity = 200.0,
            unit = MealUnit.MILLILITER,
            carbsPerUnit = 9.0,
            totalCarbs = Carbs(18.0),
            source = FoodSource.OPEN_FOOD_FACTS_CACHE,
            confidence = 0.8f,
            basis = CarbBasis.PER_100_ML,
        ),
    ),
    notes = "Prince ×2 + Candy Up 200 ml",
    createdAt = Instant.parse("2026-10-24T12:34:56Z"),
)

internal val configuredAndEnabled = NightscoutConfig(
    baseUrl = "https://ns.example.org",
    enabled = true,
    hasSecret = true,
)

internal class FakeSettings(
    config: NightscoutConfig = configuredAndEnabled,
    private val secret: String? = "abc",
) : NightscoutSettings {

    private val state = MutableStateFlow(config)

    override val config: StateFlow<NightscoutConfig> = state

    override fun readSecret(): String? = secret

    fun update(config: NightscoutConfig) {
        state.value = config
    }
}

internal class FakeApi(
    private val info: NightscoutServerInfo = NightscoutServerInfo(
        name = "Nightscout",
        version = "15.0.8",
        apiEnabled = true,
        careportalEnabled = true,
        units = "mg/dl",
    ),
    private val failure: NightscoutException? = null,
) : NightscoutApi {

    val postedPayloads = mutableListOf<String>()

    var lastCredentials: NightscoutCredentials? = null

    /** Documents v3 écrits : collection, corps et statut rendu. */
    val upserted = mutableListOf<Upsert>()

    val deleted = mutableListOf<Pair<NightscoutDocument, String>>()

    /** `_id` transmis avec chaque suppression : la v1 en a besoin, la v3 l'ignore. */
    val deletedItemIds = mutableListOf<String?>()

    /** Ce que la lecture d'une plage doit rendre. */
    var existingEntries: List<NightscoutEntryRef> = emptyList()

    var existingTreatments: List<NightscoutTreatmentRef> = emptyList()

    /** Statut rendu par un upsert : 201 à la création, 200 à la mise à jour. */
    var upsertStatus: Int = 201

    /** Statut rendu par une suppression ; 404 pour simuler un document déjà absent. */
    var deleteStatus: Int = 200

    /** Plages interrogées, pour vérifier que la fenêtre de travail est respectée. */
    val rangeQueries = mutableListOf<LongRange>()

    var failAfterUpserts: Int? = null

    data class Upsert(val document: NightscoutDocument, val payload: String, val status: Int)

    private fun guard() {
        failure?.let { throw it }
    }

    override fun status(credentials: NightscoutCredentials): NightscoutServerInfo {
        lastCredentials = credentials
        guard()
        return info
    }

    override fun postTreatment(credentials: NightscoutCredentials, payload: String) {
        lastCredentials = credentials
        guard()
        postedPayloads += payload
    }

    override fun upsert(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        payload: String,
    ): Int {
        lastCredentials = credentials
        guard()
        failAfterUpserts?.let { limit ->
            if (upserted.size >= limit) {
                throw NightscoutException(NightscoutErrorKind.NETWORK, "réseau coupé")
            }
        }
        upserted += Upsert(document, payload, upsertStatus)
        return upsertStatus
    }

    override fun delete(
        credentials: NightscoutCredentials,
        document: NightscoutDocument,
        identifier: String,
        itemId: String?,
    ): Int {
        lastCredentials = credentials
        guard()
        deleted += document to identifier
        deletedItemIds += itemId
        return deleteStatus
    }

    override fun entries(
        credentials: NightscoutCredentials,
        fromMs: Long,
        toMs: Long,
    ): List<NightscoutEntryRef> {
        lastCredentials = credentials
        guard()
        rangeQueries += fromMs..toMs
        return existingEntries
    }

    override fun treatments(
        credentials: NightscoutCredentials,
        fromMs: Long,
        toMs: Long,
    ): List<NightscoutTreatmentRef> {
        lastCredentials = credentials
        guard()
        rangeQueries += fromMs..toMs
        return existingTreatments
    }
}

internal class FakeMealRepository(vararg meals: MealConfirmation) : MealRepository {

    private val byId = meals.associateBy { it.id }.toMutableMap()

    private val statuses = mutableMapOf<String, NightscoutStatus>()

    override fun observeRecentMeals(limit: Int): Flow<List<MealConfirmation>> =
        MutableStateFlow(byId.values.toList())

    override fun observeNightscoutStatuses(limit: Int): Flow<Map<String, NightscoutStatus>> =
        MutableStateFlow(statuses.toMap())

    override suspend fun save(meal: MealConfirmation): String {
        byId[meal.id] = meal
        return meal.id
    }

    override suspend fun findById(id: String): MealConfirmation? = byId[id]

    override suspend fun delete(id: String) {
        byId.remove(id)
        statuses.remove(id)
    }

    override suspend fun count(): Int = byId.size

    override suspend fun nightscoutStatusOf(id: String): NightscoutStatus? =
        statuses[id] ?: if (byId.containsKey(id)) NightscoutStatus.NOT_REQUESTED else null

    override suspend fun updateNightscoutStatus(id: String, status: NightscoutStatus) {
        statuses[id] = status
    }

    fun statusOf(id: String): NightscoutStatus? = statuses[id]
}

/**
 * Transport piloté par le test : il note les requêtes et rend les réponses prévues.
 *
 * Les réponses sont servies dans l'ordre, la dernière se répétant : c'est ce qu'il faut
 * pour une sonde de style (un 401) suivie d'une lecture (un tableau).
 */
internal class FakeTransport(
    response: HttpResponse = HttpResponse(200, "{}"),
    private var failure: Exception? = null,
    queued: List<HttpResponse> = emptyList(),
) : HttpTransport {

    private val queue = ArrayDeque(listOf(response) + queued)

    val requests = mutableListOf<HttpRequest>()

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        failure?.let { throw it }
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    fun respondWith(code: Int, body: String) {
        queue.clear()
        queue.add(HttpResponse(code, body))
    }

    fun failWith(error: Exception) {
        failure = error
    }
}
