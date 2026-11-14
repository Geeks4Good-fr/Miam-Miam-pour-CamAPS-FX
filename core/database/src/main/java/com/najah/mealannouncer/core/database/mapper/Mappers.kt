package com.najah.mealannouncer.core.database.mapper

import com.najah.mealannouncer.core.database.entity.FoodProductEntity
import com.najah.mealannouncer.core.database.entity.MealItemEntity
import com.najah.mealannouncer.core.database.entity.MealLogEntity
import com.najah.mealannouncer.core.model.CamapsStatus
import com.najah.mealannouncer.core.model.CarbBasis
import com.najah.mealannouncer.core.model.Carbs
import com.najah.mealannouncer.core.model.FoodProduct
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.MealConfirmation
import com.najah.mealannouncer.core.model.NightscoutStatus
import com.najah.mealannouncer.core.model.ResolvedMealItem
import java.time.Instant

/**
 * Conversions entre les entités Room et le modèle de domaine.
 *
 * Les fonctions sont pures (aucun accès Android) : elles sont donc testables sur la JVM.
 */

fun FoodProductEntity.toDomain(): FoodProduct = FoodProduct(
    id = id,
    barcode = barcode,
    name = name,
    brand = brand,
    carbsPer100g = carbsPer100g,
    carbsPer100ml = carbsPer100ml,
    carbsPerUnit = carbsPerUnit,
    defaultPortionGrams = defaultPortionGrams,
    defaultPortionMl = defaultPortionMl,
    source = source,
    verifiedByUser = verifiedByUser,
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

fun FoodProduct.toEntity(): FoodProductEntity = FoodProductEntity(
    id = id,
    barcode = barcode,
    name = name,
    brand = brand,
    carbsPer100g = carbsPer100g,
    carbsPer100ml = carbsPer100ml,
    carbsPerUnit = carbsPerUnit,
    defaultPortionGrams = defaultPortionGrams,
    defaultPortionMl = defaultPortionMl,
    source = source,
    verifiedByUser = verifiedByUser,
    updatedAt = updatedAt.toEpochMilli(),
)

/**
 * Reconstruit un repas confirmé à partir du journal et de ses lignes.
 *
 * La source et la base de calcul ne sont pas stockées par ligne (le schéma ne les porte
 * pas) : elles sont déduites, `PER_UNIT` étant la base la plus prudente. Le détail exact
 * reste lisible dans le `payloadJson` du journal.
 */
fun MealLogEntity.toDomain(items: List<MealItemEntity>): MealConfirmation = MealConfirmation(
    id = id,
    totalCarbs = Carbs.of(totalCarbs),
    items = items.map { it.toResolvedItem() },
    notes = notes,
    rawTranscript = rawTranscript,
    createdAt = Instant.ofEpochMilli(createdAt),
    camapsAnnounced = camapsStatus == CamapsStatus.UI_AUTOMATION_DONE,
    nightscoutSaved = nightscoutStatus == NightscoutStatus.SENT,
    glookoStatus = glookoStatus,
    manualTotalEdited = false,
)

fun MealItemEntity.toResolvedItem(): ResolvedMealItem {
    val quantitySafe = if (quantity > 0.0) quantity else 1.0
    val carbsValue = carbs ?: 0.0
    return ResolvedMealItem(
        productName = productName,
        brand = brand,
        quantity = quantity,
        unit = unit,
        carbsPerUnit = carbsValue / quantitySafe,
        totalCarbs = Carbs.of(carbsValue),
        source = FoodSource.MANUAL,
        confidence = confidence,
        basis = CarbBasis.PER_UNIT,
    )
}

/**
 * Ligne de détail d'un repas confirmé.
 *
 * L'identifiant est l'index de la ligne dans le repas, et non le nom du produit : deux
 * lignes identiques (« Prince ×2 » ajouté deux fois) doivent coexister, alors que le nom
 * dupliqué les aurait fait s'écraser l'une l'autre.
 */
fun ResolvedMealItem.toEntity(mealLogId: String, index: Int): MealItemEntity = MealItemEntity(
    id = "$mealLogId-$index",
    mealLogId = mealLogId,
    productName = productName,
    brand = brand,
    quantity = quantity,
    unit = unit,
    carbs = totalCarbs.grams,
    confidence = confidence,
)

/** Journal d'un repas confirmé ; [payloadJson] est la photo complète du repas. */
fun MealConfirmation.toLogEntity(
    payloadJson: String,
    camapsStatus: CamapsStatus,
    nightscoutStatus: NightscoutStatus,
): MealLogEntity = MealLogEntity(
    id = id,
    rawTranscript = rawTranscript,
    totalCarbs = totalCarbs.grams,
    notes = notes,
    createdAt = createdAt.toEpochMilli(),
    camapsStatus = camapsStatus,
    nightscoutStatus = nightscoutStatus,
    glookoStatus = glookoStatus,
    payloadJson = payloadJson,
)
