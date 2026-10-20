package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.data.local.dao.AudioTrackDao
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.HighlightDao
import com.example.data.local.dao.ReadingSessionDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.AudioTrackEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.HighlightEntity
import com.example.data.local.entity.ReadingSessionEntity
import com.example.data.local.entity.TtsSyncCueEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        AudioTrackEntity::class,
        TtsSyncCueEntity::class,
        ReadingSessionEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(OmniReadTypeConverters::class)
abstract class OmniReadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun audioTrackDao(): AudioTrackDao
    abstract fun ttsSyncCueDao(): TtsSyncCueDao
    abstract fun readingSessionDao(): ReadingSessionDao

    companion object {
        const val DATABASE_NAME = "omniread_library.db"
    }
}
