package com.najah.mealannouncer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najah.mealannouncer.core.database.entity.AutomationLogEntity
import com.najah.mealannouncer.core.model.AutomationOutcome
import kotlinx.coroutines.flow.Flow

/**
 * Accès au journal d'automatisation.
 *
 * Le résumé (complets / arrêtés à CamAPS / annulés / échoués) est compté par la base : c'est
 * une agrégation, pas une liste à parcourir à l'écran.
 */
@Dao
interface AutomationLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AutomationLogEntity)

    @Query("SELECT * FROM automation_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AutomationLogEntity>>

    @Query("SELECT * FROM automation_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AutomationLogEntity>

    @Query("SELECT COUNT(*) FROM automation_log WHERE outcome = :outcome")
    fun observeCount(outcome: AutomationOutcome): Flow<Int>

    @Query("SELECT COUNT(*) FROM automation_log")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COUNT(*) FROM automation_log")
    suspend fun count(): Int

    @Query("DELETE FROM automation_log WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM automation_log")
    suspend fun clear()
}
