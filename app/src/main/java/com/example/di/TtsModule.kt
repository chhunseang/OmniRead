package com.example.di

import com.example.data.tts.BatchAudiobookExportManagerImpl
import com.example.data.tts.CloudApiTtsProviderImpl
import com.example.data.tts.LocalKokoroTtsProviderImpl
import com.example.data.tts.ReadAloudCoordinator
import com.example.domain.tts.BatchAudiobookExportManager
import com.example.domain.tts.CloudApiTtsProvider
import com.example.domain.tts.LiveTtsSyncCoordinator
import com.example.domain.tts.LocalKokoroTtsProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TtsModule {

    @Binds
    @Singleton
    abstract fun bindLocalKokoroTtsProvider(
        impl: LocalKokoroTtsProviderImpl
    ): LocalKokoroTtsProvider

    @Binds
    @Singleton
    abstract fun bindCloudApiTtsProvider(
        impl: CloudApiTtsProviderImpl
    ): CloudApiTtsProvider

    @Binds
    @Singleton
    abstract fun bindLiveTtsSyncCoordinator(
        impl: ReadAloudCoordinator
    ): LiveTtsSyncCoordinator

    @Binds
    @Singleton
    abstract fun bindBatchAudiobookExportManager(
        impl: BatchAudiobookExportManagerImpl
    ): BatchAudiobookExportManager
}
