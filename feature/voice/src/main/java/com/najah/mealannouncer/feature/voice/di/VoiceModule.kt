package com.najah.mealannouncer.feature.voice.di

import com.najah.mealannouncer.feature.voice.AndroidEngine
import com.najah.mealannouncer.feature.voice.AndroidTts
import com.najah.mealannouncer.feature.voice.VoiceEngineTts
import com.najah.mealannouncer.feature.voice.VoxtralEngine
import com.najah.mealannouncer.feature.voice.VoxtralTts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Les deux moteurs de voix, distingués par leur qualifier.
 *
 * `VoiceEngineTts` a deux implémentations : sans [AndroidEngine] / [VoxtralEngine], Hilt ne
 * saurait pas laquelle injecter. Les qualifiers rendent le choix explicite jusque dans la
 * signature de [com.najah.mealannouncer.feature.voice.VoiceAnnouncer], où l'on voit d'un coup
 * d'œil quel moteur sert de repli.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceModule {

    @Binds
    @Singleton
    @AndroidEngine
    abstract fun bindAndroidEngine(impl: AndroidTts): VoiceEngineTts

    @Binds
    @Singleton
    @VoxtralEngine
    abstract fun bindVoxtralEngine(impl: VoxtralTts): VoiceEngineTts
}
