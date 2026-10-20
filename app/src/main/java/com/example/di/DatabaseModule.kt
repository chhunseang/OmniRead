package com.example.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.example.data.local.OmniReadDatabase
import com.example.data.local.dao.AudioTrackDao
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.HighlightDao
import com.example.data.local.dao.ReadingSessionDao
import com.example.data.local.dao.TtsSyncCueDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideOmniReadDatabase(
        @ApplicationContext context: Context
    ): OmniReadDatabase {
        return Room.databaseBuilder(
            context,
            OmniReadDatabase::class.java,
            OmniReadDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideBookDao(database: OmniReadDatabase): BookDao = database.bookDao()

    @Provides
    fun provideChapterDao(database: OmniReadDatabase): ChapterDao = database.chapterDao()

    @Provides
    fun provideBookmarkDao(database: OmniReadDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun provideHighlightDao(database: OmniReadDatabase): HighlightDao = database.highlightDao()

    @Provides
    fun provideAudioTrackDao(database: OmniReadDatabase): AudioTrackDao = database.audioTrackDao()

    @Provides
    fun provideTtsSyncCueDao(database: OmniReadDatabase): TtsSyncCueDao = database.ttsSyncCueDao()

    @Provides
    fun provideReadingSessionDao(database: OmniReadDatabase): ReadingSessionDao = database.readingSessionDao()

    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("omniread_settings") }
        )
    }

    @Provides
    @Singleton
    fun provideJson(): Json {
        return Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }
}
