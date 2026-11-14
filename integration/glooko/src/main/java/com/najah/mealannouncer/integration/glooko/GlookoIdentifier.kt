package com.najah.mealannouncer.integration.glooko

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Identifiants déterministes des documents importés.
 *
 * Un UUIDv5 est un hachage : mêmes entrées, même identifiant, sur ce téléphone comme
 * ailleurs. C'est lui qui rend l'import rejouable — la v3 de Nightscout voit un identifiant
 * connu et met à jour au lieu de créer. Sans cela, un import toutes les quinze minutes sur
 * un CSV de sept jours remplirait la base de doublons.
 */
object GlookoIdentifier {

    /** Espace de noms DNS de la RFC 4122, racine de notre propre espace. */
    private val DNS_NAMESPACE = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

    /** Espace de noms propre à cet import. */
    val NAMESPACE: UUID = uuidV5(DNS_NAMESPACE, "glooko.camaps.nightscout")

    /**
     * Identifiant d'un document.
     *
     * [businessKey] distingue ce qui partage un horodatage : les colonnes du CSV sont à la
     * minute, donc deux évènements de familles différentes tombent souvent sur le même
     * instant. La clé inclut le libellé et la durée, ce qui évite qu'un mode de 26 secondes
     * et un mode de 1 h 44 à la même minute s'écrasent l'un l'autre.
     */
    fun of(kind: String, timestampMs: Long, businessKey: String): String =
        uuidV5(NAMESPACE, "$kind|$timestampMs|$businessKey").toString()

    /** UUIDv5 (RFC 4122) : SHA-1 de l'espace de noms suivi du nom. */
    internal fun uuidV5(namespace: UUID, name: String): UUID {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespace.toBytes())
        digest.update(name.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest()

        // Version 5 dans les quatre bits de poids fort de l'octet 6, variante RFC 4122
        // dans les deux bits de poids fort de l'octet 8.
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        val buffer = ByteBuffer.wrap(bytes, 0, 16)
        return UUID(buffer.long, buffer.long)
    }

    private fun UUID.toBytes(): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(mostSignificantBits)
            .putLong(leastSignificantBits)
            .array()
}
