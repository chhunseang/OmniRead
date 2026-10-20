package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "chapter_index"]),
        Index(value = ["book_id", "created_at"])
    ]
)
data class BookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,

    @ColumnInfo(name = "chapter_title")
    val chapterTitle: String,

    @ColumnInfo(name = "locator_json")
    val locatorJson: String,

    @ColumnInfo(name = "char_offset")
    val charOffset: Int = 0,

    @ColumnInfo(name = "progression_percentage")
    val progressionPercentage: Float = 0f,

    @ColumnInfo(name = "audio_track_id")
    val audioTrackId: String? = null,

    @ColumnInfo(name = "audio_timestamp_ms")
    val audioTimestampMs: Long? = null,

    @ColumnInfo(name = "snippet_text")
    val snippetText: String = "",

    @ColumnInfo(name = "note_markdown")
    val noteMarkdown: String? = null,

    @ColumnInfo(name = "label")
    val label: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
