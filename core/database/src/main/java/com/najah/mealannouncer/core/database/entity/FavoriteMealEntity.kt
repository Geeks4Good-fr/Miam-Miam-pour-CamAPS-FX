package com.najah.mealannouncer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un repas favori : ses lignes sont conservées en JSON (`FavoriteMealJson`), avec leurs valeurs
 * nutritionnelles recopiées.
 *
 * Pourquoi du JSON plutôt que des colonnes ? Un favori est une **photo** d'un repas composé,
 * qui doit rester annonçable même si le produit a disparu du cache Open Food Facts. Le détail
 * des lignes ne sert à aucune requête (on cherche un favori par son nom) : il n'a donc pas
 * besoin d'être éclaté en colonnes, et un futur champ n'imposera pas de migration.
 */
@Entity(
    tableName = "favorite_meals",
    indices = [Index(value = ["name"])],
)
data class FavoriteMealEntity(
    @PrimaryKey val id: String,
    val name: String,
    val linesJson: String,
    val createdAt: Long,
    val lastUsedAt: Long?,
    val uses: Int,
)
