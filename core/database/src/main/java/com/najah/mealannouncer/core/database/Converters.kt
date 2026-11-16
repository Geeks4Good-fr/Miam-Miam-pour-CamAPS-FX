package com.najah.mealannouncer.core.database

import androidx.room.TypeConverter
import com.najah.mealannouncer.core.model.AutomationOutcome
import com.najah.mealannouncer.core.model.AutomationSource
import com.najah.mealannouncer.core.model.CamapsStatus
import com.najah.mealannouncer.core.model.FoodSource
import com.najah.mealannouncer.core.model.GlookoStatus
import com.najah.mealannouncer.core.model.MealUnit
import com.najah.mealannouncer.core.model.NightscoutStatus

/**
 * Conversions entre les énumérations du domaine et leur nom, stocké en TEXT.
 *
 * Stocker le **nom** et non l'ordinal est délibéré : réordonner une énumération ne doit
 * jamais transformer un repas « envoyé » en « en attente ». La lecture est tolérante :
 * une valeur inconnue (base modifiée à la main, version future) retombe sur une valeur
 * sûre au lieu de faire planter l'ouverture de l'application.
 */
class Converters {

    @TypeConverter
    fun fromFoodSource(value: FoodSource): String = value.name

    @TypeConverter
    fun toFoodSource(value: String): FoodSource =
        FoodSource.entries.firstOrNull { it.name == value } ?: FoodSource.MANUAL

    @TypeConverter
    fun fromCamapsStatus(value: CamapsStatus): String = value.name

    @TypeConverter
    fun toCamapsStatus(value: String): CamapsStatus =
        CamapsStatus.entries.firstOrNull { it.name == value } ?: CamapsStatus.NOT_REQUESTED

    @TypeConverter
    fun fromNightscoutStatus(value: NightscoutStatus): String = value.name

    @TypeConverter
    fun toNightscoutStatus(value: String): NightscoutStatus =
        NightscoutStatus.entries.firstOrNull { it.name == value } ?: NightscoutStatus.NOT_REQUESTED

    @TypeConverter
    fun fromGlookoStatus(value: GlookoStatus): String = value.name

    @TypeConverter
    fun toGlookoStatus(value: String): GlookoStatus =
        GlookoStatus.entries.firstOrNull { it.name == value } ?: GlookoStatus.NOT_REQUIRED

    @TypeConverter
    fun fromMealUnit(value: MealUnit): String = value.name

    @TypeConverter
    fun toMealUnit(value: String): MealUnit =
        MealUnit.entries.firstOrNull { it.name == value } ?: MealUnit.UNKNOWN

    @TypeConverter
    fun fromAutomationOutcome(value: AutomationOutcome): String = value.name

    /** Une valeur inconnue retombe sur `FAILED` : mieux vaut sous-estimer une réussite. */
    @TypeConverter
    fun toAutomationOutcome(value: String): AutomationOutcome =
        AutomationOutcome.entries.firstOrNull { it.name == value } ?: AutomationOutcome.FAILED

    @TypeConverter
    fun fromAutomationSource(value: AutomationSource): String = value.name

    @TypeConverter
    fun toAutomationSource(value: String): AutomationSource =
        AutomationSource.entries.firstOrNull { it.name == value } ?: AutomationSource.ASSISTANT
}
