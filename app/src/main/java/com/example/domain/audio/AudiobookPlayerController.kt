package com.example.domain.audio

import com.example.data.local.entity.AudioTrackEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.TtsSyncCueEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class SleepTimerMode {
    OFF,
    DURATION_MINUTES,
    END_OF_CHAPTER,
    END_OF_TRACK
}

@Serializable
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMs: Long = 0L,
    val totalConfiguredMs: Long = 0L,
    val shakeToResetEnabled: Boolean = true,
    val fadeOutAudioOnExpire: Boolean = true
)

@Serializable
data class VoiceBoostEqConfig(
    val enabled: Boolean = false,
    val highPassCutoffHz: Int = 95,
    val presenceBoostGainDb: Float = 4.5f,
    val loudnessGainMb: Int = 600,
    val dynamicCompressionEnabled: Boolean = true
)

data class AudiobookPlaybackState(
    val activeBook: BookEntity? = null,
    val tracks: List<AudioTrackEntity> = emptyList(),
    val chapters: List<ChapterEntity> = emptyList(),
    val currentTrackIndex: Int = 0,
    val currentChapterIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentTrackPositionMs: Long = 0L,
    val currentTrackDurationMs: Long = 0L,
    val totalBookPositionMs: Long = 0L,
    val totalBookDurationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val skipSilenceEnabled: Boolean = false,
    val voiceBoostConfig: VoiceBoostEqConfig = VoiceBoostEqConfig(),
    val skipForwardIntervalMs: Long = 30_000L,
    val skipBackwardIntervalMs: Long = 10_000L,
    val waveformAmplitudes: List<Float> = emptyList(),
    val activeSyncedCue: TtsSyncCueEntity? = null,
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val errorMessage: String? = null
)

interface AudiobookPlayerController {
    val playbackState: StateFlow<AudiobookPlaybackState>

    suspend fun loadAudiobook(
        bookId: String,
        autoPlay: Boolean = false,
        startTrackIndex: Int? = null,
        startPositionMs: Long? = null
    ): Result<Unit>

    fun play()
    fun pause()
    fun togglePlayPause()
    fun stop()

    fun seekToTrackPosition(positionMs: Long)
    fun seekToBookPosition(bookPositionMs: Long)
    fun seekToTrack(trackIndex: Int, positionMs: Long = 0L)
    fun seekToChapter(chapterIndex: Int)

    fun skipForward()
    fun skipBackward()
    fun setCustomSkipIntervals(forwardMs: Long, backwardMs: Long)

    fun setPlaybackSpeed(speed: Float)
    fun setPlaybackPitch(pitch: Float)
    fun setSkipSilenceEnabled(enabled: Boolean)
    fun setVoiceBoostEq(config: VoiceBoostEqConfig)

    fun startSleepTimer(mode: SleepTimerMode, durationMinutes: Int = 30, shakeToReset: Boolean = true)
    fun resetSleepTimerFromShake()
    fun cancelSleepTimer()

    suspend fun createAudioBookmark(noteMarkdown: String?, label: String?): Result<BookmarkEntity>
    suspend fun jumpToAudioBookmark(bookmark: BookmarkEntity)
}
