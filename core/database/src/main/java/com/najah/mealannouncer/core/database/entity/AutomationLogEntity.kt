package com.najah.mealannouncer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.najah.mealannouncer.core.model.AutomationOutcome
import com.najah.mealannouncer.core.model.AutomationSource

/**
 * Une ligne du journal d'automatisation (demande du 14/11/2026, point 3).
 *
 * L'index sur `at` sert la seule requête de la liste : les annonces les plus récentes d'abord.
 * L'énumération du résultat est stockée en TEXT (voir `Converters`) : renommer ou réordonner
 * l'énumération ne doit jamais transformer un « arrêté à CamAPS » en « réussi ».
 */
@Entity(
    tableName = "automation_log",
    indices = [Index(value = ["at"]), Index(value = ["outcome"])],
)
data class AutomationLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val carbs: Int,
    val description: String,
    /** Stocké en TEXT par [com.najah.mealannouncer.core.database.Converters]. */
    val source: AutomationSource,
    /** Stocké en TEXT : `COMPLETE`, `CAMAPS_ONLY`, `CANCELLED`, `FAILED`. */
    val outcome: AutomationOutcome,
    val detail: String,
)
