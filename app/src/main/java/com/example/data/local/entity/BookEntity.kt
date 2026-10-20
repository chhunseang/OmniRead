package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["file_uri"], unique = true),
        Index(value = ["series_name", "series_index"]),
        Index(value = ["last_opened_at"]),
        Index(value = ["media_type"]),
        Index(value = ["reading_status"])
    ]
)
data class BookEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "subtitle")
    val subtitle: String? = null,

    @ColumnInfo(name = "author")
    val author: String = "Unknown Author",

    @ColumnInfo(name = "narrator")
    val narrator: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "publisher")
    val publisher: String? = null,

    @ColumnInfo(name = "published_date")
    val publishedDate: String? = null,

    @ColumnInfo(name = "language")
    val language: String = "en",

    @ColumnInfo(name = "isbn")
    val isbn: String? = null,

    @ColumnInfo(name = "format")
    val format: BookFormat,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    @ColumnInfo(name = "file_uri")
    val fileUri: String,

    @ColumnInfo(name = "parent_folder_uri")
    val parentFolderUri: String? = null,

    @ColumnInfo(name = "cover_image_path")
    val coverImagePath: String? = null,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0L,

    @ColumnInfo(name = "file_hash")
    val fileHash: String? = null,

    @ColumnInfo(name = "series_name")
    val seriesName: String? = null,

    @ColumnInfo(name = "series_index")
    val seriesIndex: Float? = null,

    @ColumnInfo(name = "tags")
    val tags: List<String> = emptyList(),

    @ColumnInfo(name = "collections")
    val collections: List<String> = emptyList(),

    @ColumnInfo(name = "reading_status")
    val readingStatus: ReadingStatus = ReadingStatus.UNREAD,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "total_chapters")
    val totalChapters: Int = 0,

    @ColumnInfo(name = "total_pages")
    val totalPages: Int = 0,

    @ColumnInfo(name = "total_words")
    val totalWords: Int = 0,

    @ColumnInfo(name = "total_duration_ms")
    val totalDurationMs: Long = 0L,

    @ColumnInfo(name = "current_chapter_index")
    val currentChapterIndex: Int = 0,

    @ColumnInfo(name = "current_locator_json")
    val currentLocatorJson: String? = null,

    @ColumnInfo(name = "current_char_offset")
    val currentCharOffset: Int = 0,

    @ColumnInfo(name = "current_audio_track_index")
    val currentAudioTrackIndex: Int = 0,

    @ColumnInfo(name = "current_audio_position_ms")
    val currentAudioPositionMs: Long = 0L,

    @ColumnInfo(name = "progression_percentage")
    val progressionPercentage: Float = 0f,

    @ColumnInfo(name = "playback_speed")
    val playbackSpeed: Float = 1.0f,

    @ColumnInfo(name = "playback_pitch")
    val playbackPitch: Float = 1.0f,

    @ColumnInfo(name = "skip_silence_enabled")
    val skipSilenceEnabled: Boolean = false,

    @ColumnInfo(name = "voice_boost_enabled")
    val voiceBoostEnabled: Boolean = false,

    @ColumnInfo(name = "synced_audiobook_uri")
    val syncedAudiobookUri: String? = null,

    @ColumnInfo(name = "synced_sidecar_path")
    val syncedSidecarPath: String? = null,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_opened_at")
    val lastOpenedAt: Long? = null,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
