package com.najah.mealannouncer.core.datastore.di

import com.najah.mealannouncer.core.datastore.HomeVoiceConfigStore
import com.najah.mealannouncer.core.datastore.HomeVoiceSettings
import com.najah.mealannouncer.core.datastore.DebugConfigStore
import com.najah.mealannouncer.core.datastore.DebugSettings
import com.najah.mealannouncer.core.datastore.KeystoreSecretStore
import com.najah.mealannouncer.core.datastore.MealRecordConfigStore
import com.najah.mealannouncer.core.datastore.MealRecordSettings
import com.najah.mealannouncer.core.datastore.AiSecretStore
import com.najah.mealannouncer.core.datastore.MistralSecretStore
import com.najah.mealannouncer.core.datastore.OpenRouterSecretStore
import com.najah.mealannouncer.core.datastore.NightscoutConfigStore
import com.najah.mealannouncer.core.datastore.NightscoutSettings
import com.najah.mealannouncer.core.datastore.SecretStore
import com.najah.mealannouncer.core.datastore.ShoppingConfigStore
import com.najah.mealannouncer.core.datastore.ShoppingSettings
import com.najah.mealannouncer.core.datastore.VoiceConfigStore
import com.najah.mealannouncer.core.datastore.VoiceSettings
import com.najah.mealannouncer.core.datastore.VoxtralSecretStore
import com.najah.mealannouncer.core.datastore.WakeWordConfigStore
import com.najah.mealannouncer.core.datastore.WakeWordSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Relie le coffre du secret et les réglages Nightscout à leurs implémentations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataStoreModule {

    @Binds
    @Singleton
    abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    @Singleton
    abstract fun bindNightscoutSettings(impl: NightscoutConfigStore): NightscoutSettings

    @Binds
    @Singleton
    abstract fun bindVoiceSettings(impl: VoiceConfigStore): VoiceSettings

    @Binds
    @Singleton
    abstract fun bindMealRecordSettings(impl: MealRecordConfigStore): MealRecordSettings

    @Binds
    @Singleton
    abstract fun bindHomeVoiceSettings(impl: HomeVoiceConfigStore): HomeVoiceSettings

    @Binds
    @Singleton
    abstract fun bindWakeWordSettings(impl: WakeWordConfigStore): WakeWordSettings

    @Binds
    @Singleton
    abstract fun bindShoppingSettings(impl: ShoppingConfigStore): ShoppingSettings

    /** Mode debug et mises à jour (page « À propos »). */
    @Binds
    @Singleton
    abstract fun bindDebugSettings(impl: DebugConfigStore): DebugSettings

    /**
     * La même instance que [VoxtralSecretStore] : une seule clé Mistral, un seul coffre.
     * Deux alias distincts effaceraient l'un en oubliant l'autre.
     */
    @Binds
    @Singleton
    abstract fun bindMistralSecretStore(impl: VoxtralSecretStore): MistralSecretStore

    /** La clé OpenRouter : la seule nécessaire aux IA de l'appli (décision 48). */
    @Binds
    @Singleton
    abstract fun bindAiSecretStore(impl: OpenRouterSecretStore): AiSecretStore
}
