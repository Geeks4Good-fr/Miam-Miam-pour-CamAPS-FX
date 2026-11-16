package com.najah.mealannouncer.core.database.repository

import com.najah.mealannouncer.core.database.dao.FavoriteMealDao
import com.najah.mealannouncer.core.database.entity.FavoriteMealEntity
import com.najah.mealannouncer.core.model.FavoriteMeal
import com.najah.mealannouncer.core.model.FavoriteMealLine
import com.najah.mealannouncer.core.model.MealUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Les repas favoris : le même petit-déjeuner, le même goûter, annoncés en un geste
 * (point 4 de la demande du 14/11/2026).
 */
interface FavoriteMealRepository {

    /** Les favoris, les plus utilisés d'abord. */
    fun observeFavorites(): Flow<List<FavoriteMeal>>

    /** Enregistre un favori (remplace celui de même identifiant) et rend son identifiant. */
    suspend fun save(favorite: FavoriteMeal): String

    /** Crée un favori à partir de lignes et d'un nom, sans se soucier de son identifiant. */
    suspend fun create(name: String, lines: List<FavoriteMealLine>): FavoriteMeal

    /** Note qu'un favori vient d'être utilisé : il remonte en tête de liste. */
    suspend fun markUsed(id: String)

    suspend fun delete(id: String)

    suspend fun count(): Int
}

@Singleton
class DefaultFavoriteMealRepository @Inject constructor(
    private val dao: FavoriteMealDao,
) : FavoriteMealRepository {

    override fun observeFavorites(): Flow<List<FavoriteMeal>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    override suspend fun save(favorite: FavoriteMeal): String {
        val id = favorite.id.ifBlank { UUID.randomUUID().toString() }
        dao.upsert(favorite.copy(id = id).toEntity())
        return id
    }

    override suspend fun create(name: String, lines: List<FavoriteMealLine>): FavoriteMeal {
        val favorite = FavoriteMeal(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Repas favori" }.take(MAX_NAME),
            lines = lines,
        )
        dao.upsert(favorite.toEntity())
        return favorite
    }

    override suspend fun markUsed(id: String) =
        dao.markUsed(id, System.currentTimeMillis())

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun count(): Int = dao.count()

    private fun FavoriteMealEntity.toDomainOrNull(): FavoriteMeal? {
        val lines = FavoriteMealJson.decodeLines(linesJson)
        // Un favori illisible est écarté : mieux vaut une liste courte qu'un écran qui plante.
        if (lines.isEmpty()) return null
        return FavoriteMeal(
            id = id,
            name = name,
            lines = lines,
            createdAt = Instant.ofEpochMilli(createdAt),
            lastUsedAt = lastUsedAt?.let(Instant::ofEpochMilli),
            uses = uses,
        )
    }

    private fun FavoriteMeal.toEntity(): FavoriteMealEntity = FavoriteMealEntity(
        id = id,
        name = name,
        linesJson = FavoriteMealJson.encodeLines(lines),
        createdAt = createdAt.toEpochMilli(),
        lastUsedAt = lastUsedAt?.toEpochMilli(),
        uses = uses,
    )

    private companion object {
        const val MAX_NAME = 80
    }
}

/**
 * Les lignes d'un favori en JSON.
 *
 * Chaque valeur nutritionnelle est recopiée : un favori doit rester annonçable des mois plus
 * tard, même si le produit a quitté le cache Open Food Facts ou si l'appli est hors ligne.
 */
object FavoriteMealJson {

    fun encodeLines(lines: List<FavoriteMealLine>): String {
        val array = JSONArray()
        for (line in lines) {
            array.put(
                JSONObject().apply {
                    put("name", line.name)
                    put("brand", line.brand ?: JSONObject.NULL)
                    put("barcode", line.barcode ?: JSONObject.NULL)
                    put("quantity", line.quantity)
                    put("unit", line.unit.name)
                    put("carbsPer100g", line.carbsPer100g ?: JSONObject.NULL)
                    put("carbsPer100ml", line.carbsPer100ml ?: JSONObject.NULL)
                    put("carbsPerUnit", line.carbsPerUnit ?: JSONObject.NULL)
                    put("portionGrams", line.portionGrams ?: JSONObject.NULL)
                    put("portionMl", line.portionMl ?: JSONObject.NULL)
                    put("servingLabel", line.servingLabel ?: JSONObject.NULL)
                    put("packageGrams", line.packageGrams ?: JSONObject.NULL)
                },
            )
        }
        return array.toString()
    }

    fun decodeLines(json: String): List<FavoriteMealLine> = runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                add(
                    FavoriteMealLine(
                        name = name,
                        brand = item.textOrNull("brand"),
                        barcode = item.textOrNull("barcode"),
                        quantity = item.optDouble("quantity", 1.0),
                        unit = MealUnit.entries.firstOrNull { it.name == item.optString("unit") }
                            ?: MealUnit.UNIT,
                        carbsPer100g = item.numberOrNull("carbsPer100g"),
                        carbsPer100ml = item.numberOrNull("carbsPer100ml"),
                        carbsPerUnit = item.numberOrNull("carbsPerUnit"),
                        portionGrams = item.numberOrNull("portionGrams"),
                        portionMl = item.numberOrNull("portionMl"),
                        servingLabel = item.textOrNull("servingLabel"),
                        packageGrams = item.numberOrNull("packageGrams"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.numberOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() }
}
