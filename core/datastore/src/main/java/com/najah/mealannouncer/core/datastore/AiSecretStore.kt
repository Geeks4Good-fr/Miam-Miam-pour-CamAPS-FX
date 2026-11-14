package com.najah.mealannouncer.core.datastore

/**
 * Le coffre de la clé des IA (OpenRouter) : dialogue du repas, photos, relecture, estimations,
 * dictée de secours. [OpenRouterSecretStore] en est l'implémentation.
 *
 * La clé Mistral ([MistralSecretStore]) ne sert plus qu'à la voix Voxtral, facultative.
 */
interface AiSecretStore : SecretStore
