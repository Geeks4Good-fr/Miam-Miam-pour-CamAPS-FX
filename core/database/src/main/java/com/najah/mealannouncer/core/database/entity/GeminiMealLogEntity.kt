package com.najah.mealannouncer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un repas mené en mode « Gemini direct », avec toute sa conversation (version 3 de la base).
 *
 * La conversation et les destinations sont gardées en JSON : le texte de Gemini (Markdown,
 * tableaux) n'a pas de forme fixe, et seul l'écran du journal le relit.
 */
@Entity(
    tableName = "gemini_meal_log",
    indices = [Index(value = ["startedAt"])],
)
data class GeminiMealLogEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val updatedAt: Long,
    val totalCarbs: Double?,
    val minCarbs: Double?,
    val maxCarbs: Double?,
    val meal: String,
    val detail: String,
    val model: String,
    val turnsJson: String,
    val destinationsJson: String,
)
