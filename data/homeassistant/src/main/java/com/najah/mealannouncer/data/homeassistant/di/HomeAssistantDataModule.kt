package com.najah.mealannouncer.data.homeassistant.di

import com.najah.mealannouncer.data.homeassistant.OkHttpWebSocketTransport
import com.najah.mealannouncer.data.homeassistant.WebSocketTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Câblage du lien avec Home Assistant, calqué sur celui de Voxtral. */
@Module
@InstallIn(SingletonComponent::class)
abstract class HomeAssistantDataModule {

    @Binds
    @Singleton
    abstract fun bindTransport(impl: OkHttpWebSocketTransport): WebSocketTransport
}
