package com.najah.mealannouncer.core.datastore

/**
 * Le coffre de la clé Mistral.
 *
 * Depuis le 10/11/2026, elle ne sert plus qu'à la voix Voxtral, facultative : les IA du repas
 * passent par OpenRouter ([AiSecretStore]). `VoxtralSecretStore` en est l'implémentation,
 * avec son alias et ses entrées propres dans le Keystore.
 */
interface MistralSecretStore : SecretStore
