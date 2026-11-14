package com.najah.mealannouncer.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.najah.mealannouncer.core.database.entity.FoodProductEntity
import com.najah.mealannouncer.core.model.FoodSource
import kotlinx.coroutines.flow.Flow

/**
 * Accès aux produits alimentaires locaux.
 *
 * La recherche est volontairement simple (LIKE sur le nom et la marque) : elle sert le
 * résolveur, pas un moteur de recherche. Un index sur `name`, `brand` et `barcode` suffit
 * pour un cache de quelques milliers de produits.
 */
@Dao
interface FoodProductDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(product: FoodProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(products: List<FoodProductEntity>)

    @Query("SELECT * FROM food_products WHERE id = :id")
    suspend fun findById(id: String): FoodProductEntity?

    @Query("SELECT * FROM food_products WHERE barcode = :barcode LIMIT 1")
    suspend fun findByBarcode(barcode: String): FoodProductEntity?

    /**
     * Recherche par nom ou marque : correspondances exactes d'abord, puis favoris
     * vérifiés, puis les correspondances partielles les plus récentes.
     */
    @Query(
        """
        SELECT * FROM food_products
        WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN lower(name) = lower(:query) THEN 0 ELSE 1 END,
            verifiedByUser DESC,
            updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int = 20): List<FoodProductEntity>

    /** Favoris validés par l'utilisateur, du plus récemment confirmé au plus ancien. */
    @Query("SELECT * FROM food_products WHERE verifiedByUser = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<FoodProductEntity>>

    @Query("SELECT * FROM food_products WHERE verifiedByUser = 1 ORDER BY updatedAt DESC")
    suspend fun favorites(): List<FoodProductEntity>

    @Query("SELECT COUNT(*) FROM food_products")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM food_products WHERE source = :source")
    suspend fun countBySource(source: FoodSource): Int

    @Delete
    suspend fun delete(product: FoodProductEntity)

    @Query("DELETE FROM food_products WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Vide le cache Open Food Facts sans toucher aux favoris de l'utilisateur. */
    @Query("DELETE FROM food_products WHERE verifiedByUser = 0 AND source NOT IN ('MANUAL')")
    suspend fun clearCache(): Int
}
