package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "chapter_index"], unique = true),
        Index(value = ["book_id", "href"])
    ]
)
data class ChapterEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,

    @ColumnInfo(name = "parent_chapter_id")
    val parentChapterId: String? = null,

    @ColumnInfo(name = "depth_level")
    val depthLevel: Int = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "href")
    val href: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String = "application/xhtml+xml",

    @ColumnInfo(name = "word_count")
    val wordCount: Int = 0,

    @ColumnInfo(name = "char_count")
    val charCount: Int = 0,

    @ColumnInfo(name = "start_page")
    val startPage: Int = 0,

    @ColumnInfo(name = "end_page")
    val endPage: Int = 0,

    @ColumnInfo(name = "audio_track_id")
    val audioTrackId: String? = null,

    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long? = null,

    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long? = null,

    @ColumnInfo(name = "is_tts_converted")
    val isTtsConverted: Boolean = false,

    @ColumnInfo(name = "converted_audio_path")
    val convertedAudioPath: String? = null
)
