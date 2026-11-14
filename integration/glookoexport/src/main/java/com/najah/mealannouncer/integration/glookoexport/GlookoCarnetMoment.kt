package com.najah.mealannouncer.integration.glookoexport

/**
 * Le moment d'un repas dans le Carnet Glooko.
 *
 * Les libellés sont ceux relevés sur l'appareil (`docs/GLOOKO-CARNET.md`) : « Petit-déj »,
 * « Déjeuner », « Dîner », « En-cas ».
 */
enum class CarnetMoment(val label: String) {
    BREAKFAST("Petit-déj"),
    LUNCH("Déjeuner"),
    DINNER("Dîner"),
    SNACK("En-cas"),
}

/**
 * Déduit le moment du repas de l'heure locale (décision du 26/10/2026 : automatique, modifiable).
 *
 * Pur et testable : aucune dépendance Android. Les bornes sont nommées et ajustables ; hors des
 * plages de repas, c'est un « En-cas ».
 */
object GlookoCarnetMoment {

    fun forHour(hour: Int): CarnetMoment = when (hour) {
        in 5..10 -> CarnetMoment.BREAKFAST
        in 11..14 -> CarnetMoment.LUNCH
        in 18..22 -> CarnetMoment.DINNER
        else -> CarnetMoment.SNACK
    }
}
