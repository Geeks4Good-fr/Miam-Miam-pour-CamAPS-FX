package com.najah.mealannouncer.core.database.repository

import com.najah.mealannouncer.core.database.dao.AutomationLogDao
import com.najah.mealannouncer.core.database.entity.AutomationLogEntity
import com.najah.mealannouncer.core.model.AutomationEntry
import com.najah.mealannouncer.core.model.AutomationOutcome
import com.najah.mealannouncer.core.model.AutomationSource
import com.najah.mealannouncer.core.model.AutomationSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le journal d'automatisation (point 3 de la demande du 14/11/2026).
 *
 * Il répond à une seule question, mais elle est quotidienne : « le repas de midi a-t-il été
 * mené jusqu'au bout, ou seulement déposé dans CamAPS ? » Le journal des repas garde ce qui a
 * été mangé ; celui-ci garde le sort technique de chaque annonce.
 */
interface JournalRepository {

    /** Les dernières annonces, de la plus récente à la plus ancienne. */
    fun observeRecent(limit: Int = 200): Flow<List<AutomationEntry>>

    /** Le décompte par issue, pour le bandeau du journal. */
    fun observeSummary(): Flow<AutomationSummary>

    /**
     * Note une annonce et son issue.
     *
     * Ne lève jamais : un journal en échec ne doit pas empêcher un repas d'être annoncé.
     */
    suspend fun record(
        carbs: Int,
        description: String,
        source: AutomationSource,
        outcome: AutomationOutcome,
        detail: String = "",
        at: Instant = Instant.now(),
    ): AutomationEntry?

    suspend fun delete(id: String)

    suspend fun clear()

    suspend fun count(): Int
}

@Singleton
class DefaultJournalRepository @Inject constructor(
    private val dao: AutomationLogDao,
) : JournalRepository {

    override fun observeRecent(limit: Int): Flow<List<AutomationEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeSummary(): Flow<AutomationSummary> = combine(
        dao.observeCount(AutomationOutcome.COMPLETE),
        dao.observeCount(AutomationOutcome.CAMAPS_ONLY),
        dao.observeCount(AutomationOutcome.CANCELLED),
        dao.observeCount(AutomationOutcome.FAILED),
    ) { complete, camapsOnly, cancelled, failed ->
        AutomationSummary(
            complete = complete,
            camapsOnly = camapsOnly,
            cancelled = cancelled,
            failed = failed,
        )
    }

    override suspend fun record(
        carbs: Int,
        description: String,
        source: AutomationSource,
        outcome: AutomationOutcome,
        detail: String,
        at: Instant,
    ): AutomationEntry? = runCatching {
        val entry = AutomationEntry(
            id = UUID.randomUUID().toString(),
            at = at,
            carbs = carbs,
            description = description.take(MAX_DESCRIPTION),
            source = source,
            outcome = outcome,
            detail = detail.take(MAX_DETAIL),
        )
        dao.insert(entry.toEntity())
        entry
    }.getOrNull()

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun clear() = dao.clear()

    override suspend fun count(): Int = dao.count()

    private fun AutomationLogEntity.toDomain(): AutomationEntry = AutomationEntry(
        id = id,
        at = Instant.ofEpochMilli(at),
        carbs = carbs,
        description = description,
        source = source,
        outcome = outcome,
        detail = detail,
    )

    private fun AutomationEntry.toEntity(): AutomationLogEntity = AutomationLogEntity(
        id = id,
        at = at.toEpochMilli(),
        carbs = carbs,
        description = description,
        source = source,
        outcome = outcome,
        detail = detail,
    )

    private companion object {
        const val MAX_DESCRIPTION = 500
        const val MAX_DETAIL = 500
    }
}
