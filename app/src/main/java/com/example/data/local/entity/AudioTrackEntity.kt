package com.example.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "audio_tracks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["book_id", "track_index"], unique = true)
    ]
)
data class AudioTrackEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "book_id")
    val bookId: String,

    @ColumnInfo(name = "track_index")
    val trackIndex: Int,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "file_uri")
    val fileUri: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String = "audio/mp4",

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,

    @ColumnInfo(name = "start_offset_in_book_ms")
    val startOffsetInBookMs: Long = 0L,

    @ColumnInfo(name = "bitrate_kbps")
    val bitrateKbps: Int = 128,

    @ColumnInfo(name = "sample_rate_hz")
    val sampleRateHz: Int = 44100,

    @ColumnInfo(name = "channels")
    val channels: Int = 2,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0L,

    @ColumnInfo(name = "waveform_peaks")
    val waveformPeaks: List<Float> = emptyList(),

    @ColumnInfo(name = "is_tts_generated")
    val isTtsGenerated: Boolean = false
)
