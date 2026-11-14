package com.najah.mealannouncer.data.nightscout

/**
 * Collections de documents visées par l'import Glooko.
 *
 * Les deux versions de l'API les exposent, avec deux façons de désigner un document :
 * la v3 par l'`identifier` qu'on lui a donné, la v1 par l'`_id` qu'elle a fabriqué.
 * Les références ci-dessous portent les deux, pour que l'appelant n'ait pas à savoir
 * laquelle il parle.
 */
enum class NightscoutDocument(val path: String) {
    ENTRIES("entries"),
    TREATMENTS("treatments"),
    PROFILE("profile"),
}

/** Une glycémie déjà présente, réduite à ce qu'il faut pour décider d'un backfill. */
data class NightscoutEntryRef(
    /** Horodatage en millisecondes, tel que Nightscout le renvoie. */
    val dateMs: Long,
    /** `device` du document : c'est lui qui dit si la mesure vient de xDrip+ ou de nous. */
    val device: String?,
    val identifier: String?,
    /** `_id` attribué par le serveur ; absent d'une réponse v3 qui ne le renvoie pas. */
    val id: String? = null,
)

/** Un traitement déjà présent, réduit à ce qu'il faut pour éviter un double comptage. */
data class NightscoutTreatmentRef(
    val createdAtMs: Long,
    val eventType: String?,
    /** `enteredBy` du document : « Glooko-CamAPS » signale un import, le reste est temps réel. */
    val enteredBy: String?,
    val identifier: String?,
    /** `_id` attribué par le serveur ; c'est lui que la v1 sait supprimer. */
    val id: String? = null,
)
