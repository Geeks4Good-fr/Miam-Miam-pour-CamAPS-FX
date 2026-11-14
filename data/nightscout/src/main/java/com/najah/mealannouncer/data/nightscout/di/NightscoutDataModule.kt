package com.najah.mealannouncer.data.nightscout.di

import com.najah.mealannouncer.data.nightscout.HttpNightscoutApi
import com.najah.mealannouncer.data.nightscout.HttpTransport
import com.najah.mealannouncer.data.nightscout.NightscoutApi
import com.najah.mealannouncer.data.nightscout.UrlConnectionTransport
import com.najah.mealannouncer.data.nightscout.work.NightscoutScheduler
import com.najah.mealannouncer.data.nightscout.work.WorkManagerNightscoutScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Câblage du module Nightscout. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NightscoutDataModule {

    @Binds
    @Singleton
    abstract fun bindTransport(impl: UrlConnectionTransport): HttpTransport

    @Binds
    @Singleton
    abstract fun bindApi(impl: HttpNightscoutApi): NightscoutApi

    @Binds
    @Singleton
    abstract fun bindCamapsImport(impl: com.najah.mealannouncer.data.nightscout.HttpCamapsImportApi):
        com.najah.mealannouncer.data.nightscout.CamapsImportApi

    @Binds
    @Singleton
    abstract fun bindScheduler(impl: WorkManagerNightscoutScheduler): NightscoutScheduler
}
