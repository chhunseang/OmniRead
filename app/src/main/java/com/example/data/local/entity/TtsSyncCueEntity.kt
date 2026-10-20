package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "tts_sync_cues",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "chapter_index", "sentence_index"], unique = true),
        Index(value = ["book_id", "audio_track_id", "start_time_ms"]),
        Index(value = ["book_id", "chapter_index", "start_char_offset"])
    ]
)
data class TtsSyncCueEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,

    @ColumnInfo(name = "sentence_index")
    val sentenceIndex: Int,

    @ColumnInfo(name = "audio_track_id")
    val audioTrackId: String? = null,

    @ColumnInfo(name = "text_content")
    val textContent: String,

    @ColumnInfo(name = "start_char_offset")
    val startCharOffset: Int,

    @ColumnInfo(name = "end_char_offset")
    val endCharOffset: Int,

    @ColumnInfo(name = "dom_selector")
    val domSelector: String? = null,

    @ColumnInfo(name = "locator_json")
    val locatorJson: String? = null,

    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long,

    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long,

    @ColumnInfo(name = "cached_pcm_or_audio_path")
    val cachedPcmOrAudioPath: String? = null,

    @ColumnInfo(name = "tts_engine")
    val ttsEngine: TtsEngineType = TtsEngineType.LOCAL_KOKORO,

    @ColumnInfo(name = "voice_id")
    val voiceId: String = "af_heart"
)
