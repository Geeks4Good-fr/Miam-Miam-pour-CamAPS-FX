package com.najah.mealannouncer.data.mistral.di

import com.najah.mealannouncer.data.mistral.HttpMistralChat
import com.najah.mealannouncer.data.mistral.HttpMistralTranscriber
import com.najah.mealannouncer.data.mistral.HttpMistralWebSearch
import com.najah.mealannouncer.data.mistral.HttpPackagePhotoReader
import com.najah.mealannouncer.data.mistral.HttpPlateReader
import com.najah.mealannouncer.data.mistral.HttpReceiptReader
import com.najah.mealannouncer.data.mistral.PackagePhotoReader
import com.najah.mealannouncer.data.mistral.HttpPortionInterpreter
import com.najah.mealannouncer.data.mistral.HttpTransport
import com.najah.mealannouncer.data.mistral.MistralChat
import com.najah.mealannouncer.data.mistral.MistralTranscriber
import com.najah.mealannouncer.data.mistral.MistralWebSearch
import com.najah.mealannouncer.data.mistral.PlateReader
import com.najah.mealannouncer.data.mistral.PortionInterpreter
import com.najah.mealannouncer.data.mistral.ReceiptReader
import com.najah.mealannouncer.data.mistral.UrlConnectionTransport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Câblage du module Mistral, calqué sur celui de Voxtral et de Nightscout. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MistralDataModule {

    @Binds
    @Singleton
    abstract fun bindTransport(impl: UrlConnectionTransport): HttpTransport

    @Binds
    @Singleton
    abstract fun bindChat(impl: HttpMistralChat): MistralChat

    @Binds
    @Singleton
    abstract fun bindTranscriber(impl: HttpMistralTranscriber): MistralTranscriber

    @Binds
    @Singleton
    abstract fun bindWebSearch(impl: HttpMistralWebSearch): MistralWebSearch

    @Binds
    @Singleton
    abstract fun bindPortionInterpreter(impl: HttpPortionInterpreter): PortionInterpreter

    @Binds
    @Singleton
    abstract fun bindPlateReader(impl: HttpPlateReader): PlateReader

    @Binds
    @Singleton
    abstract fun bindPackagePhotoReader(impl: HttpPackagePhotoReader): PackagePhotoReader

    @Binds
    @Singleton
    abstract fun bindReceiptReader(impl: HttpReceiptReader): ReceiptReader
}
