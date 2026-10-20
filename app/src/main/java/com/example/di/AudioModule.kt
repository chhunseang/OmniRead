package com.example.di

import com.example.data.audio.AudiobookPlayerControllerImpl
import com.example.domain.audio.AudiobookPlayerController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindAudiobookPlayerController(
        impl: AudiobookPlayerControllerImpl
    ): AudiobookPlayerController
}
