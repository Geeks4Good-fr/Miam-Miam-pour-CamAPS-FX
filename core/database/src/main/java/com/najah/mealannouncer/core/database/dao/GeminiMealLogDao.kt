package com.najah.mealannouncer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najah.mealannouncer.core.database.entity.GeminiMealLogEntity
import kotlinx.coroutines.flow.Flow

/** Accès au journal complet des repas menés avec Gemini. */
@Dao
interface GeminiMealLogDao {

    /** Crée ou remplace : un repas est réécrit après chaque échange. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: GeminiMealLogEntity)

    @Query("SELECT * FROM gemini_meal_log ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<GeminiMealLogEntity>>

    @Query("SELECT * FROM gemini_meal_log WHERE id = :id")
    suspend fun findById(id: String): GeminiMealLogEntity?

    @Query("DELETE FROM gemini_meal_log WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM gemini_meal_log")
    suspend fun clear()
}
