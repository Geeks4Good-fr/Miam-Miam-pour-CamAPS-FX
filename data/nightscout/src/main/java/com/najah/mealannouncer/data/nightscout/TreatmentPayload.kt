package com.najah.mealannouncer.data.nightscout

import com.najah.mealannouncer.core.model.MealConfirmation
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Construit le traitement Nightscout d'un repas.
 *
 * Un « traitement » de type `Carbs` dans la section Soins : c'est exactement ce que
 * l'utilisateur saisirait à la main dans Nightscout, et ce que CamAPS FX et les rapports
 * savent relire. Aucune donnée de glycémie n'est inventée ni envoyée.
 */
object TreatmentPayload {

    const val EVENT_TYPE = "Carbs"
    const val ENTERED_BY = "Meal Announcer"

    private val CREATED_AT_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** Traitement d'un repas confirmé, daté du moment où il a été confirmé. */
    fun build(meal: MealConfirmation): JSONObject = build(
        // Même arrondi que CamAPS FX : les deux destinations doivent annoncer le même chiffre.
        carbs = meal.totalCarbs.toCamapsGrams(),
        notes = meal.noteOrSummary(),
        createdAt = meal.createdAt,
    )

    fun build(carbs: Int, notes: String, createdAt: Instant): JSONObject = JSONObject().apply {
        put("eventType", EVENT_TYPE)
        put("carbs", carbs)
        put("notes", notes)
        put("created_at", CREATED_AT_FORMAT.format(createdAt))
        put("enteredBy", ENTERED_BY)
    }

    fun toJson(meal: MealConfirmation): String = build(meal).toString()
}
