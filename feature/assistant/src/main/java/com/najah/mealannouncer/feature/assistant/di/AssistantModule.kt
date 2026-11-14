package com.najah.mealannouncer.feature.assistant.di

import com.najah.mealannouncer.feature.assistant.AndroidAudioRecorder
import com.najah.mealannouncer.feature.assistant.AndroidSpeechAnswers
import com.najah.mealannouncer.feature.assistant.MealAudioRecorder
import com.najah.mealannouncer.feature.assistant.SpeechAnswers
import com.najah.mealannouncer.feature.assistant.portions.PortionBank
import com.najah.mealannouncer.feature.assistant.portions.PortionLookup
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Câblage de la dictée.
 *
 * L'enregistreur est unique pour toute l'application : deux enregistreurs ouverts en même
 * temps se disputeraient le micro, et le second perdrait sans le dire. Il en va de même de
 * la reconnaissance vocale : un seul moteur à la fois peut écouter.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantModule {

    @Binds
    @Singleton
    abstract fun bindMealAudioRecorder(impl: AndroidAudioRecorder): MealAudioRecorder

    @Binds
    @Singleton
    abstract fun bindSpeechAnswers(impl: AndroidSpeechAnswers): SpeechAnswers

    /** La banque de portions en images (`docs/PORTIONS.md`), consultée avant Open Food Facts. */
    @Binds
    @Singleton
    abstract fun bindPortionLookup(impl: PortionBank): PortionLookup
}
