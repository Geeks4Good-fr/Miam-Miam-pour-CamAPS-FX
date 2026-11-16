package com.najah.mealannouncer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najah.mealannouncer.core.database.entity.FavoriteMealEntity
import kotlinx.coroutines.flow.Flow

/**
 * Accès aux repas favoris.
 *
 * L'ordre est celui qui rend service : les plus utilisés d'abord, puis les plus récemment
 * utilisés. Un favori jamais utilisé reste en bas, prêt pour la première fois.
 */
@Dao
interface FavoriteMealDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteMealEntity)

    @Query("SELECT * FROM favorite_meals ORDER BY uses DESC, lastUsedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<FavoriteMealEntity>>

    @Query("SELECT * FROM favorite_meals ORDER BY uses DESC, lastUsedAt DESC, createdAt DESC")
    suspend fun all(): List<FavoriteMealEntity>

    @Query("SELECT * FROM favorite_meals WHERE id = :id")
    suspend fun findById(id: String): FavoriteMealEntity?

    @Query("UPDATE favorite_meals SET uses = uses + 1, lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: String, at: Long)

    @Query("DELETE FROM favorite_meals WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM favorite_meals")
    suspend fun count(): Int
}
