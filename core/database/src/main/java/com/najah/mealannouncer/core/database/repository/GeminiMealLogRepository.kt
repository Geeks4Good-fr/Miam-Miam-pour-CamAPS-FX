package com.najah.mealannouncer.core.database.repository

import com.najah.mealannouncer.core.database.dao.GeminiMealLogDao
import com.najah.mealannouncer.core.database.entity.GeminiMealLogEntity
import com.najah.mealannouncer.core.model.GeminiMealLog
import com.najah.mealannouncer.core.model.GeminiTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Le journal complet des repas menés avec Gemini : la conversation entière, gardée sur le
 * téléphone. Aucune méthode ne lève : un journal en panne ne doit jamais empêcher un repas.
 */
interface GeminiMealLogRepository {

    fun observeRecent(limit: Int = 100): Flow<List<GeminiMealLog>>

    /** Écrit le repas tel qu'il est (création ou mise à jour après un échange). */
    suspend fun save(log: GeminiMealLog)

    /** Ajoute une ligne aux destinations (« Carnet Glooko : noté », « Nightscout : envoyé »…). */
    suspend fun addDestination(id: String, line: String)

    suspend fun delete(id: String)

    suspend fun clear()
}

@Singleton
class DefaultGeminiMealLogRepository @Inject constructor(
    private val dao: GeminiMealLogDao,
) : GeminiMealLogRepository {

    override fun observeRecent(limit: Int): Flow<List<GeminiMealLog>> =
        dao.observeRecent(limit).map { rows -> rows.mapNotNull { row -> runCatching { GeminiMealLogJson.toDomain(row) }.getOrNull() } }

    override suspend fun save(log: GeminiMealLog) {
        runCatching { dao.upsert(GeminiMealLogJson.toEntity(log)) }
    }

    override suspend fun addDestination(id: String, line: String) {
        runCatching {
            val row = dao.findById(id) ?: return
            val current = GeminiMealLogJson.decodeLines(row.destinationsJson)
            dao.upsert(
                row.copy(
                    destinationsJson = GeminiMealLogJson.encodeLines(current + line),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }
    }

    override suspend fun delete(id: String) {
        runCatching { dao.deleteById(id) }
    }

    override suspend fun clear() {
        runCatching { dao.clear() }
    }
}

/** Passage entre le journal et sa forme en base (conversation et destinations en JSON). */
object GeminiMealLogJson {

    fun toEntity(log: GeminiMealLog): GeminiMealLogEntity = GeminiMealLogEntity(
        id = log.id,
        startedAt = log.startedAt.toEpochMilli(),
        updatedAt = log.updatedAt.toEpochMilli(),
        totalCarbs = log.totalCarbs,
        minCarbs = log.minCarbs,
        maxCarbs = log.maxCarbs,
        meal = log.meal,
        detail = log.detail,
        model = log.model,
        turnsJson = encodeTurns(log.turns),
        destinationsJson = encodeLines(log.destinations),
    )

    fun toDomain(row: GeminiMealLogEntity): GeminiMealLog = GeminiMealLog(
        id = row.id,
        startedAt = Instant.ofEpochMilli(row.startedAt),
        updatedAt = Instant.ofEpochMilli(row.updatedAt),
        totalCarbs = row.totalCarbs,
        minCarbs = row.minCarbs,
        maxCarbs = row.maxCarbs,
        meal = row.meal,
        detail = row.detail,
        model = row.model,
        turns = decodeTurns(row.turnsJson),
        destinations = decodeLines(row.destinationsJson),
    )

    fun encodeTurns(turns: List<GeminiTurn>): String {
        val array = JSONArray()
        turns.forEach { turn ->
            array.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "assistant")
                    .put("text", turn.text)
                    .put("photo", turn.withPhoto)
                    .put("total_g", turn.totalCarbs ?: JSONObject.NULL),
            )
        }
        return array.toString()
    }

    fun decodeTurns(json: String): List<GeminiTurn> {
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            GeminiTurn(
                fromUser = item.optString("role") == "user",
                text = item.optString("text"),
                withPhoto = item.optBoolean("photo", false),
                totalCarbs = if (item.isNull("total_g")) null else item.optDouble("total_g").takeIf { it.isFinite() },
            )
        }
    }

    fun encodeLines(lines: List<String>): String = JSONArray(lines).toString()

    fun decodeLines(json: String): List<String> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.optString(it) }
    }
}
