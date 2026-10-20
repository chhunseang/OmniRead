package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "started_at"]),
        Index(value = ["session_date_epoch_day"])
    ]
)
data class ReadingSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "media_type")
    val mediaType: MediaType,

    @ColumnInfo(name = "session_date_epoch_day")
    val sessionDateEpochDay: Long,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    @ColumnInfo(name = "ended_at")
    val endedAt: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "words_read")
    val wordsRead: Int = 0,

    @ColumnInfo(name = "pages_read")
    val pagesRead: Int = 0,

    @ColumnInfo(name = "average_wpm")
    val averageWpm: Int = 0,

    @ColumnInfo(name = "start_progression")
    val startProgression: Float = 0f,

    @ColumnInfo(name = "end_progression")
    val endProgression: Float = 0f,

    @ColumnInfo(name = "used_tts_read_aloud")
    val usedTtsReadAloud: Boolean = false
)
