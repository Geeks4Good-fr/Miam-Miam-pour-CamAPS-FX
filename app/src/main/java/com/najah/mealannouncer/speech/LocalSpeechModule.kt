package com.najah.mealannouncer.speech

import com.najah.mealannouncer.feature.assistant.LocalTranscriber
import com.najah.mealannouncer.feature.assistant.WakeWordVerifier
import com.najah.mealannouncer.feature.voice.PiperEngine
import com.najah.mealannouncer.feature.voice.VoiceEngineTts
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * La voix locale (sherpa-onnx) est branchée ici, dans `app`, seul module qui embarque la
 * bibliothèque native : les modules `feature` n'en voient que les contrats.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class LocalSpeechModule {

    @Binds
    @Singleton
    @PiperEngine
    abstract fun bindPiper(impl: PiperTts): VoiceEngineTts

    @Binds
    @Singleton
    abstract fun bindTranscriber(impl: KrokoSpeech): LocalTranscriber

    @Binds
    @Singleton
    abstract fun bindWakeWordVerifier(impl: KrokoSpeech): WakeWordVerifier
}
