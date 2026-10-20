package com.example.di

import com.example.data.reader.EbookParserService
import com.example.domain.reader.UnifiedEbookReaderEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReaderModule {

    @Binds
    @Singleton
    abstract fun bindUnifiedEbookReaderEngine(
        impl: EbookParserService
    ): UnifiedEbookReaderEngine
}
