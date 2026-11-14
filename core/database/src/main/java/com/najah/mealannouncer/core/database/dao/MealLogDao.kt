package com.najah.mealannouncer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.najah.mealannouncer.core.database.entity.MealItemEntity
import com.najah.mealannouncer.core.database.entity.MealLogEntity
import com.najah.mealannouncer.core.model.CamapsStatus
import com.najah.mealannouncer.core.model.GlookoStatus
import com.najah.mealannouncer.core.model.NightscoutStatus
import kotlinx.coroutines.flow.Flow

/**
 * Journal des repas.
 *
 * Classe abstraite et non interface : Room ne sait pas exécuter une méthode
 * `@Transaction` par défaut sur une interface, or l'insertion d'un repas et de ses
 * lignes doit être atomique.
 */
@Dao
abstract class MealLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(log: MealLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertItems(items: List<MealItemEntity>)

    /** Enregistre un repas et toutes ses lignes, ou rien du tout. */
    @Transaction
    open suspend fun insertMealWithItems(log: MealLogEntity, items: List<MealItemEntity>) {
        insert(log)
        if (items.isNotEmpty()) insertItems(items)
    }

    @Query("SELECT * FROM meal_logs ORDER BY createdAt DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int = 50): Flow<List<MealLogEntity>>

    @Query("SELECT * FROM meal_logs ORDER BY createdAt DESC")
    abstract fun observeAll(): Flow<List<MealLogEntity>>

    @Query("SELECT * FROM meal_logs WHERE id = :id")
    abstract suspend fun findById(id: String): MealLogEntity?

    @Query("SELECT * FROM meal_logs WHERE nightscoutStatus = :status ORDER BY createdAt ASC")
    abstract suspend fun findByNightscoutStatus(status: NightscoutStatus): List<MealLogEntity>

    /**
     * Compte les repas identiques dans une fenêtre de temps : sert à la détection de
     * doublon avant d'annoncer deux fois le même repas.
     */
    @Query(
        """
        SELECT COUNT(*) FROM meal_logs
        WHERE totalCarbs = :carbs AND notes = :notes
          AND createdAt BETWEEN :from AND :to
        """,
    )
    abstract suspend fun countSimilar(carbs: Double, notes: String, from: Long, to: Long): Int

    @Query("UPDATE meal_logs SET camapsStatus = :status WHERE id = :id")
    abstract suspend fun updateCamapsStatus(id: String, status: CamapsStatus)

    @Query("UPDATE meal_logs SET nightscoutStatus = :status WHERE id = :id")
    abstract suspend fun updateNightscoutStatus(id: String, status: NightscoutStatus)

    @Query("UPDATE meal_logs SET glookoStatus = :status WHERE id = :id")
    abstract suspend fun updateGlookoStatus(id: String, status: GlookoStatus)

    @Query("UPDATE meal_logs SET notes = :notes WHERE id = :id")
    abstract suspend fun updateNotes(id: String, notes: String)

    @Query("DELETE FROM meal_logs WHERE id = :id")
    abstract suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM meal_logs")
    abstract suspend fun count(): Int
}
