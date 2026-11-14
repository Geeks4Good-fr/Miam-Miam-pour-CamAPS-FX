package com.najah.mealannouncer.data.voxtral.di

import com.najah.mealannouncer.data.voxtral.HttpTransport
import com.najah.mealannouncer.data.voxtral.HttpVoxtralApi
import com.najah.mealannouncer.data.voxtral.UrlConnectionTransport
import com.najah.mealannouncer.data.voxtral.VoxtralApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Câblage du module Voxtral, calqué sur celui de Nightscout. */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoxtralDataModule {

    @Binds
    @Singleton
    abstract fun bindTransport(impl: UrlConnectionTransport): HttpTransport

    @Binds
    @Singleton
    abstract fun bindApi(impl: HttpVoxtralApi): VoxtralApi
}
