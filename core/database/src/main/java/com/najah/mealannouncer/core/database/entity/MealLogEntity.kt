package com.najah.mealannouncer.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.najah.mealannouncer.core.model.CamapsStatus
import com.najah.mealannouncer.core.model.GlookoStatus
import com.najah.mealannouncer.core.model.NightscoutStatus

/**
 * Journal local d'un repas annoncé, avec l'état de chacune des trois destinations.
 *
 * [payloadJson] conserve la photo complète du repas (produits, quantités, valeurs) au
 * moment de la confirmation : c'est la trace vérifiable, même si les lignes de détail
 * évoluent plus tard.
 */
@Entity(tableName = "meal_logs")
data class MealLogEntity(
    @PrimaryKey val id: String,
    val rawTranscript: String,
    val totalCarbs: Double,
    val notes: String,
    val createdAt: Long,
    val camapsStatus: CamapsStatus,
    val nightscoutStatus: NightscoutStatus,
    val glookoStatus: GlookoStatus,
    val payloadJson: String,
)
