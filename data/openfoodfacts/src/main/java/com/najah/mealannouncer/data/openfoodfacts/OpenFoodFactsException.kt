package com.najah.mealannouncer.data.openfoodfacts

/**
 * Panne de communication avec Open Food Facts.
 *
 * Distincte d'un « produit inconnu » : une panne réseau ne doit pas faire croire que le
 * produit n'existe pas, et l'utilisateur doit pouvoir saisir les glucides à la main.
 */
class OpenFoodFactsException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
