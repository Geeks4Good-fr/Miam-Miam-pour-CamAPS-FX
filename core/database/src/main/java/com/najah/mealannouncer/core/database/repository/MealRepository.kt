package com.najah.mealannouncer.core.database.repository

import com.najah.mealannouncer.core.database.dao.MealLogDao
import com.najah.mealannouncer.core.database.mapper.toDomain
import com.najah.mealannouncer.core.database.mapper.toEntity
import com.najah.mealannouncer.core.database.mapper.toLogEntity
import com.najah.mealannouncer.core.model.CamapsStatus
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.NightscoutStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Journal des repas : enregistrer, relire, supprimer.
 *
 * Le journal est la mémoire de l'application. Il doit rester lisible des mois plus tard,
 * y compris quand le schéma aura évolué : c'est pourquoi le détail complet de chaque repas
 * est aussi conservé en JSON dans le journal.
 */
interface MealRepository {

    /** Les derniers repas, du plus récent au plus ancien. */
    fun observeRecentMeals(limit: Int = 50): Flow<List<MealConfirmation>>

    /**
     * État d'envoi Nightscout des derniers repas, par identifiant.
     *
     * L'historique en a besoin pour dire ce qui est réellement parti, mais la liste des
     * repas n'a pas à porter un détail de transport : d'où cette seconde vue, distincte.
     */
    fun observeNightscoutStatuses(limit: Int = 50): Flow<Map<String, NightscoutStatus>>

    /** Enregistre un repas et ses lignes en une seule transaction. */
    suspend fun save(meal: MealConfirmation): String

    suspend fun findById(id: String): MealConfirmation?

    suspend fun delete(id: String)

    suspend fun count(): Int

    /** État de l'envoi vers Nightscout, ou `null` si le repas n'existe pas. */
    suspend fun nightscoutStatusOf(id: String): NightscoutStatus?

    suspend fun updateNightscoutStatus(id: String, status: NightscoutStatus)
}

@Singleton
class DefaultMealRepository @Inject constructor(
    private val dao: MealLogDao,
) : MealRepository {

    override fun observeRecentMeals(limit: Int): Flow<List<MealConfirmation>> =
        // Les lignes ne sont pas chargées pour la liste : le total et la note suffisent à
        // la reconnaître, et charger mille lignes pour un écran de liste serait du gâchis.
        dao.observeRecent(limit).map { logs -> logs.map { it.toDomain(emptyList()) } }

    override fun observeNightscoutStatuses(limit: Int): Flow<Map<String, NightscoutStatus>> =
        dao.observeRecent(limit).map { logs -> logs.associate { it.id to it.nightscoutStatus } }

    override suspend fun save(meal: MealConfirmation): String {        // La note est remplie si elle est vide : sans elle, la ligne d'historique serait
        // anonyme (« 40 g » sans dire de quoi).
        val withNote = if (meal.notes.isBlank()) {
            meal.copy(notes = meal.items.joinToString(" + ") { it.shortLabel() })
        } else {
            meal
        }
        val log = withNote.toLogEntity(
            payloadJson = MealPayloadJson.encode(withNote),
            camapsStatus = CamapsStatus.NOT_REQUESTED,
            nightscoutStatus = NightscoutStatus.NOT_REQUESTED,
        )
        dao.insertMealWithItems(
            log = log,
            items = withNote.items.mapIndexed { index, item -> item.toEntity(withNote.id, index) },
        )
        return withNote.id
    }

    override suspend fun findById(id: String): MealConfirmation? =
        dao.findById(id)?.toDomain(emptyList())

    override suspend fun delete(id: String) = dao.deleteById(id)

    override suspend fun count(): Int = dao.count()

    override suspend fun nightscoutStatusOf(id: String): NightscoutStatus? =
        dao.findById(id)?.nightscoutStatus

    override suspend fun updateNightscoutStatus(id: String, status: NightscoutStatus) =
        dao.updateNightscoutStatus(id, status)
}

/**
 * Photo complète d'un repas, conservée à côté des colonnes interrogeables.
 *
 * Les colonnes de `meal_items` ne portent que ce qui sert à recalculer et à filtrer. Le
 * nom du produit, sa marque, la source des valeurs et la base de calcul (pour 100 g, pour
 * une unité…) vivent ici : l'historique doit rester relisible, pas seulement récapitulable.
 */
object MealPayloadJson {

    fun encode(meal: MealConfirmation): String {
        val root = JSONObject()
        root.put("id", meal.id)
        root.put("createdAt", meal.createdAt.toString())
        root.put("totalCarbs", meal.totalCarbs.grams)
        root.put("notes", meal.notes)
        root.put("rawTranscript", meal.rawTranscript)
        root.put("manualTotalEdited", meal.manualTotalEdited)

        val items = JSONArray()
        for (item in meal.items) {
            items.put(
                JSONObject().apply {
                    put("productName", item.productName)
                    put("brand", item.brand ?: JSONObject.NULL)
                    put("quantity", item.quantity)
                    put("unit", item.unit.name)
                    put("carbsPerUnit", item.carbsPerUnit)
                    put("totalCarbs", item.totalCarbs.grams)
                    put("source", item.source.name)
                    put("confidence", item.confidence.toDouble())
                    put("basis", item.basis.name)
                },
            )
        }
        root.put("items", items)
        return root.toString()
    }
}
