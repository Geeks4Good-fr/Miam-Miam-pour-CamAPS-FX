package com.najah.mealannouncer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najah.mealannouncer.core.database.entity.MealItemEntity
import kotlinx.coroutines.flow.Flow

/** Lignes de détail d'un repas. */
@Dao
interface MealItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MealItemEntity>)

    @Query("SELECT * FROM meal_items WHERE mealLogId = :mealLogId ORDER BY productName ASC")
    suspend fun forMeal(mealLogId: String): List<MealItemEntity>

    @Query("SELECT * FROM meal_items WHERE mealLogId = :mealLogId ORDER BY productName ASC")
    fun observeForMeal(mealLogId: String): Flow<List<MealItemEntity>>

    @Query("DELETE FROM meal_items WHERE mealLogId = :mealLogId")
    suspend fun deleteForMeal(mealLogId: String)

    @Query("SELECT COUNT(*) FROM meal_items")
    suspend fun count(): Int
}
