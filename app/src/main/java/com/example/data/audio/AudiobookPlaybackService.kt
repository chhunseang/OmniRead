package com.example.data.audio

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.documentfile.provider.DocumentFile
import com.example.MainActivity
import com.example.data.local.dao.AudioTrackDao
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.AudioTrackEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ReadingStatus
import com.example.domain.audio.AudiobookPlaybackState
import com.example.domain.audio.AudiobookPlayerController
import com.example.domain.audio.SleepTimerMode
import com.example.domain.audio.SleepTimerState
import com.example.domain.audio.VoiceBoostEqConfig
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AudiobookPlaybackService : MediaSessionService() {

    @Inject
    lateinit var bookDao: BookDao

    @Inject
    lateinit var audioTrackDao: AudioTrackDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var silenceProcessor: SilenceSkippingAudioProcessor? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null

    private var positionPersistJob: Job? = null
    private var sleepTimerCountdownJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        val silenceAudioProcessor = SilenceSkippingAudioProcessor()
        silenceProcessor = silenceAudioProcessor

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(silenceAudioProcessor))
                    .build()
            }
        }

        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                    configureAudioEffectsForSession(audioSessionId)
                }
            }
        })

        exoPlayer = player

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val skipBackButton = CommandButton.Builder()
            .setDisplayName("Rewind")
            .setSessionCommand(SessionCommand(CMD_SKIP_BACKWARD, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_rew)
            .build()

        val skipForwardButton = CommandButton.Builder()
            .setDisplayName("Fast Forward")
            .setSessionCommand(SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_ff)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityIntent)
            .setCustomLayout(listOf(skipBackButton, skipForwardButton))
            .setCallback(AudiobookMediaSessionCallback())
            .build()

        startPeriodicPositionPersistence()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        positionPersistJob?.cancel()
        sleepTimerCountdownJob?.cancel()
        releaseAudioEffects()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPeriodicPositionPersistence() {
        positionPersistJob?.cancel()
        positionPersistJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                val player = exoPlayer ?: continue
                if (player.isPlaying) {
                    val currentItem = player.currentMediaItem ?: continue
                    val bookId = currentItem.mediaMetadata.extras?.getString(EXTRA_BOOK_ID) ?: continue
                    val trackIdx = player.currentMediaItemIndex
                    val posMs = player.currentPosition.coerceAtLeast(0L)
                    val durMs = player.duration.takeIf { it > 0L } ?: 1L
                    val prog = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f)

                    withContext(Dispatchers.IO) {
                        bookDao.updateAudioPosition(
                            bookId = bookId,
                            trackIndex = trackIdx,
                            positionMs = posMs,
                            progression = prog,
                            status = if (prog >= 0.98f) ReadingStatus.COMPLETED else ReadingStatus.READING
                        )
                    }
                }
            }
        }
    }

    private fun configureAudioEffectsForSession(audioSessionId: Int) {
        releaseAudioEffects()
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                enabled = false
            }
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = false
            }
        }.onFailure { err ->
            Log.w(TAG, "Audio effects unavailable on this device", err)
        }
    }

    private fun applyVoiceBoostDsp(enabled: Boolean, gainMb: Int) {
        runCatching {
            loudnessEnhancer?.apply {
                setTargetGain(if (enabled) gainMb.coerceIn(100, 1500) else 0)
                this.enabled = enabled
            }
            equalizer?.apply {
                this.enabled = enabled
                if (enabled && numberOfBands >= 3) {
                    // Attenuate low rumble (<120Hz) and boost vocal presence (1kHz-3.5kHz)
                    val minLevel = bandLevelRange[0]
                    val maxLevel = bandLevelRange[1]
                    setBandLevel(0, (minLevel / 3).toShort())
                    val midBand = (numberOfBands / 2).toShort()
                    setBandLevel(midBand, (maxLevel / 2).toShort())
                }
            }
        }
    }

    private fun releaseAudioEffects() {
        runCatching {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            equalizer?.release()
            equalizer = null
        }
    }

    private fun startFadeOutSleepTimer(durationMs: Long) {
        sleepTimerCountdownJob?.cancel()
        val player = exoPlayer ?: return
        player.volume = 1.0f
        if (durationMs <= 0L) return

        sleepTimerCountdownJob = serviceScope.launch {
            val fadeWindowMs = min(10_000L, durationMs / 3)
            val normalWaitMs = (durationMs - fadeWindowMs).coerceAtLeast(0L)
            delay(normalWaitMs)

            val steps = 20
            val stepDelay = (fadeWindowMs / steps).coerceAtLeast(50L)
            for (step in steps downTo 1) {
                if (!isActive) return@launch
                player.volume = (step.toFloat() / steps.toFloat()).coerceIn(0.05f, 1.0f)
                delay(stepDelay)
            }
            player.pause()
            player.volume = 1.0f
        }
    }

    private inner class AudiobookMediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_SET_SPEED_AND_PITCH, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_SKIP_SILENCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_VOICE_BOOST, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_FORWARD, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_BACKWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            val player = exoPlayer ?: return Futures.immediateFuture(
                SessionResult(androidx.media3.session.SessionError.ERROR_BAD_VALUE)
            )
            when (customCommand.customAction) {
                CMD_SET_SPEED_AND_PITCH -> {
                    val speed = args.getFloat(ARG_SPEED, 1.0f).coerceIn(0.5f, 3.5f)
                    val pitch = args.getFloat(ARG_PITCH, 1.0f).coerceIn(0.5f, 2.0f)
                    player.playbackParameters = PlaybackParameters(speed, pitch)
                }
                CMD_SET_SKIP_SILENCE -> {
                    val enabled = args.getBoolean(ARG_ENABLED, false)
                    silenceProcessor?.setEnabled(enabled)
                    player.skipSilenceEnabled = enabled
                }
                CMD_SET_VOICE_BOOST -> {
                    val enabled = args.getBoolean(ARG_ENABLED, false)
                    val gainMb = args.getInt(ARG_GAIN_MB, 600)
                    applyVoiceBoostDsp(enabled, gainMb)
                }
                CMD_SET_SLEEP_TIMER -> {
                    val durationMs = args.getLong(ARG_DURATION_MS, 0L)
                    if (durationMs > 0L) {
                        startFadeOutSleepTimer(durationMs)
                    } else {
                        sleepTimerCountdownJob?.cancel()
                        player.volume = 1.0f
                    }
                }
                CMD_SKIP_FORWARD -> {
                    val deltaMs = args.getLong(ARG_SKIP_DELTA_MS, 30_000L)
                    player.seekTo((player.currentPosition + deltaMs).coerceAtMost(max(0L, player.duration)))
                }
                CMD_SKIP_BACKWARD -> {
                    val deltaMs = args.getLong(ARG_SKIP_DELTA_MS, 10_000L)
                    player.seekTo((player.currentPosition - deltaMs).coerceAtLeast(0L))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        private const val TAG = "AudiobookPlaybackSvc"
        const val EXTRA_BOOK_ID = "extra_omniread_book_id"

        const val CMD_SET_SPEED_AND_PITCH = "omniread.cmd.SET_SPEED_AND_PITCH"
        const val CMD_SET_SKIP_SILENCE = "omniread.cmd.SET_SKIP_SILENCE"
        const val CMD_SET_VOICE_BOOST = "omniread.cmd.SET_VOICE_BOOST"
        const val CMD_SET_SLEEP_TIMER = "omniread.cmd.SET_SLEEP_TIMER"
        const val CMD_SKIP_FORWARD = "omniread.cmd.SKIP_FORWARD"
        const val CMD_SKIP_BACKWARD = "omniread.cmd.SKIP_BACKWARD"

        const val ARG_SPEED = "arg_speed"
        const val ARG_PITCH = "arg_pitch"
        const val ARG_ENABLED = "arg_enabled"
        const val ARG_GAIN_MB = "arg_gain_mb"
        const val ARG_DURATION_MS = "arg_duration_ms"
        const val ARG_SKIP_DELTA_MS = "arg_skip_delta_ms"
    }
}

@Singleton
class AudiobookPlayerControllerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val audioTrackDao: AudioTrackDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val ttsSyncCueDao: TtsSyncCueDao
) : AudiobookPlayerController, SensorEventListener {

    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _playbackState = MutableStateFlow(AudiobookPlaybackState())
    override val playbackState: StateFlow<AudiobookPlaybackState> = _playbackState.asStateFlow()

    private var mediaControllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var directFallbackPlayer: ExoPlayer? = null

    private var progressPollingJob: Job? = null
    private var sleepTimerUiJob: Job? = null
    private var bookmarksObserverJob: Job? = null
    private var lastShakeTimestampMs: Long = 0L

    init {
        connectToMediaSessionService()
        startProgressAndCueSyncPolling()
    }

    private fun connectToMediaSessionService() {
        runCatching {
            val sessionToken = androidx.media3.session.SessionToken(
                context,
                ComponentName(context, AudiobookPlaybackService::class.java)
            )
            val future = MediaController.Builder(context, sessionToken).buildAsync()
            mediaControllerFuture = future
            future.addListener(
                {
                    runCatching {
                        val controller = future.get()
                        mediaController = controller
                        attachPlayerListener(controller)
                    }
                },
                androidx.core.content.ContextCompat.getMainExecutor(context)
            )
        }.onFailure {
            Log.w(TAG, "MediaSessionService connection deferred, initializing local ExoPlayer", it)
        }
    }

    private fun getActivePlayer(): Player {
        mediaController?.let { return it }
        directFallbackPlayer?.let { return it }
        val localPlayer = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        attachPlayerListener(localPlayer)
        directFallbackPlayer = localPlayer
        return localPlayer
    }

    private fun attachPlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _playbackState.update {
                    it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newTrackIdx = player.currentMediaItemIndex
                _playbackState.update { state ->
                    val track = state.tracks.getOrNull(newTrackIdx)
                    state.copy(
                        currentTrackIndex = newTrackIdx,
                        currentTrackDurationMs = track?.durationMs ?: player.duration.coerceAtLeast(0L),
                        waveformAmplitudes = track?.waveformPeaks?.ifEmpty { defaultWaveform() }
                            ?: defaultWaveform()
                    )
                }
            }
        })
    }

    override suspend fun loadAudiobook(
        bookId: String,
        autoPlay: Boolean,
        startTrackIndex: Int?,
        startPositionMs: Long?
    ): Result<Unit> = withContext(Dispatchers.Main) {
        runCatching {
            val book = withContext(Dispatchers.IO) { bookDao.getBookById(bookId) }
                ?: throw IllegalArgumentException("Audiobook not found: $bookId")

            var tracks = withContext(Dispatchers.IO) { audioTrackDao.getTracksForBook(bookId) }
            if (tracks.isEmpty()) {
                tracks = withContext(Dispatchers.IO) { buildOrDiscoverAudioTracksForBook(book) }
            }

            var chapters = withContext(Dispatchers.IO) { chapterDao.getChaptersForBook(bookId) }
            if (chapters.isEmpty()) {
                chapters = tracks.mapIndexed { idx, track ->
                    ChapterEntity(
                        id = "${book.id}_ch_$idx",
                        bookId = book.id,
                        chapterIndex = idx,
                        title = track.title,
                        href = track.fileUri,
                        audioTrackId = track.id,
                        startTimeMs = track.startOffsetInBookMs,
                        endTimeMs = track.startOffsetInBookMs + track.durationMs
                    )
                }
                withContext(Dispatchers.IO) { chapterDao.upsertChapters(chapters) }
            }

            observeBookmarksForBook(book.id)

            val initialTrackIdx = (startTrackIndex ?: book.currentAudioTrackIndex)
                .coerceIn(0, max(0, tracks.size - 1))
            val initialPosMs = (startPositionMs ?: book.currentAudioPositionMs).coerceAtLeast(0L)
            val totalDuration = tracks.sumOf { it.durationMs }.coerceAtLeast(1L)

            val mediaItems = tracks.map { track ->
                val extras = Bundle().apply {
                    putString(AudiobookPlaybackService.EXTRA_BOOK_ID, book.id)
                }
                MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(Uri.parse(track.fileUri))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(book.author)
                            .setAlbumTitle(book.title)
                            .setExtras(extras)
                            .build()
                    )
                    .build()
            }

            val player = getActivePlayer()
            player.setMediaItems(mediaItems, initialTrackIdx, initialPosMs)
            player.prepare()
            setPlaybackSpeed(book.playbackSpeed)
            setPlaybackPitch(book.playbackPitch)
            setSkipSilenceEnabled(book.skipSilenceEnabled)
            setVoiceBoostEq(VoiceBoostEqConfig(enabled = book.voiceBoostEnabled))

            if (autoPlay) {
                player.play()
            }

            val activeTrack = tracks.getOrNull(initialTrackIdx)
            _playbackState.update {
                it.copy(
                    activeBook = book,
                    tracks = tracks,
                    chapters = chapters,
                    currentTrackIndex = initialTrackIdx,
                    currentTrackPositionMs = initialPosMs,
                    currentTrackDurationMs = activeTrack?.durationMs ?: totalDuration,
                    totalBookPositionMs = (activeTrack?.startOffsetInBookMs ?: 0L) + initialPosMs,
                    totalBookDurationMs = totalDuration,
                    playbackSpeed = book.playbackSpeed,
                    playbackPitch = book.playbackPitch,
                    skipSilenceEnabled = book.skipSilenceEnabled,
                    voiceBoostConfig = VoiceBoostEqConfig(enabled = book.voiceBoostEnabled),
                    waveformAmplitudes = activeTrack?.waveformPeaks?.ifEmpty { defaultWaveform() }
                        ?: defaultWaveform(),
                    errorMessage = null
                )
            }
        }
    }

    override fun play() {
        getActivePlayer().play()
    }

    override fun pause() {
        getActivePlayer().pause()
    }

    override fun togglePlayPause() {
        val player = getActivePlayer()
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun stop() {
        val player = getActivePlayer()
        player.pause()
        player.seekTo(0L)
    }

    override fun seekToTrackPosition(positionMs: Long) {
        val player = getActivePlayer()
        val clamped = positionMs.coerceAtLeast(0L)
        player.seekTo(clamped)
        updateImmediatePositionState(player.currentMediaItemIndex, clamped)
    }

    override fun seekToBookPosition(bookPositionMs: Long) {
        val tracks = _playbackState.value.tracks
        if (tracks.isEmpty()) {
            seekToTrackPosition(bookPositionMs)
            return
        }
        val targetTrack = tracks.lastOrNull { it.startOffsetInBookMs <= bookPositionMs }
            ?: tracks.first()
        val intraTrackOffset = (bookPositionMs - targetTrack.startOffsetInBookMs)
            .coerceIn(0L, max(0L, targetTrack.durationMs))
        seekToTrack(targetTrack.trackIndex, intraTrackOffset)
    }

    override fun seekToTrack(trackIndex: Int, positionMs: Long) {
        val tracks = _playbackState.value.tracks
        if (tracks.isEmpty()) return
        val clampedIdx = trackIndex.coerceIn(0, tracks.size - 1)
        val player = getActivePlayer()
        player.seekTo(clampedIdx, positionMs.coerceAtLeast(0L))
        updateImmediatePositionState(clampedIdx, positionMs.coerceAtLeast(0L))
    }

    override fun seekToChapter(chapterIndex: Int) {
        val chapter = _playbackState.value.chapters.firstOrNull { it.chapterIndex == chapterIndex }
            ?: return
        val startMs = chapter.startTimeMs ?: 0L
        seekToBookPosition(startMs)
        _playbackState.update { it.copy(currentChapterIndex = chapterIndex) }
    }

    override fun skipForward() {
        val delta = _playbackState.value.skipForwardIntervalMs
        val player = getActivePlayer()
        val nextPos = (player.currentPosition + delta).coerceAtMost(
            max(player.duration.coerceAtLeast(0L), _playbackState.value.currentTrackDurationMs)
        )
        seekToTrackPosition(nextPos)
    }

    override fun skipBackward() {
        val delta = _playbackState.value.skipBackwardIntervalMs
        val player = getActivePlayer()
        val prevPos = (player.currentPosition - delta).coerceAtLeast(0L)
        seekToTrackPosition(prevPos)
    }

    override fun setCustomSkipIntervals(forwardMs: Long, backwardMs: Long) {
        _playbackState.update {
            it.copy(
                skipForwardIntervalMs = forwardMs.coerceIn(5_000L, 120_000L),
                skipBackwardIntervalMs = backwardMs.coerceIn(5_000L, 120_000L)
            )
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.5f)
        val pitch = _playbackState.value.playbackPitch
        _playbackState.update { it.copy(playbackSpeed = clamped) }
        getActivePlayer().playbackParameters = PlaybackParameters(clamped, pitch)

        mediaController?.sendCustomCommand(
            SessionCommand(AudiobookPlaybackService.CMD_SET_SPEED_AND_PITCH, Bundle.EMPTY),
            Bundle().apply {
                putFloat(AudiobookPlaybackService.ARG_SPEED, clamped)
                putFloat(AudiobookPlaybackService.ARG_PITCH, pitch)
            }
        )
        persistAudioDspSettings()
    }

    override fun setPlaybackPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.5f, 2.0f)
        val speed = _playbackState.value.playbackSpeed
        _playbackState.update { it.copy(playbackPitch = clamped) }
        getActivePlayer().playbackParameters = PlaybackParameters(speed, clamped)

        mediaController?.sendCustomCommand(
            SessionCommand(AudiobookPlaybackService.CMD_SET_SPEED_AND_PITCH, Bundle.EMPTY),
            Bundle().apply {
                putFloat(AudiobookPlaybackService.ARG_SPEED, speed)
                putFloat(AudiobookPlaybackService.ARG_PITCH, clamped)
            }
        )
        persistAudioDspSettings()
    }

    override fun setSkipSilenceEnabled(enabled: Boolean) {
        _playbackState.update { it.copy(skipSilenceEnabled = enabled) }
        mediaController?.sendCustomCommand(
            SessionCommand(AudiobookPlaybackService.CMD_SET_SKIP_SILENCE, Bundle.EMPTY),
            Bundle().apply { putBoolean(AudiobookPlaybackService.ARG_ENABLED, enabled) }
        )
        persistAudioDspSettings()
    }

    override fun setVoiceBoostEq(config: VoiceBoostEqConfig) {
        _playbackState.update { it.copy(voiceBoostConfig = config) }
        mediaController?.sendCustomCommand(
            SessionCommand(AudiobookPlaybackService.CMD_SET_VOICE_BOOST, Bundle.EMPTY),
            Bundle().apply {
                putBoolean(AudiobookPlaybackService.ARG_ENABLED, config.enabled)
                putInt(AudiobookPlaybackService.ARG_GAIN_MB, config.loudnessGainMb)
            }
        )
        persistAudioDspSettings()
    }

    override fun startSleepTimer(
        mode: SleepTimerMode,
        durationMinutes: Int,
        shakeToReset: Boolean
    ) {
        sleepTimerUiJob?.cancel()
        if (mode == SleepTimerMode.OFF) {
            cancelSleepTimer()
            return
        }

        val state = _playbackState.value
        val configuredDurationMs = when (mode) {
            SleepTimerMode.DURATION_MINUTES -> durationMinutes.coerceIn(1, 240) * 60_000L
            SleepTimerMode.END_OF_CHAPTER -> {
                val activeCh = state.chapters.getOrNull(state.currentChapterIndex)
                val chEnd = activeCh?.endTimeMs ?: state.currentTrackDurationMs
                (chEnd - state.totalBookPositionMs).coerceAtLeast(30_000L)
            }
            SleepTimerMode.END_OF_TRACK -> {
                (state.currentTrackDurationMs - state.currentTrackPositionMs).coerceAtLeast(30_000L)
            }
            SleepTimerMode.OFF -> 0L
        }

        if (shakeToReset) {
            registerShakeSensor()
        } else {
            unregisterShakeSensor()
        }

        _playbackState.update {
            it.copy(
                sleepTimer = SleepTimerState(
                    mode = mode,
                    remainingMs = configuredDurationMs,
                    totalConfiguredMs = configuredDurationMs,
                    shakeToResetEnabled = shakeToReset
                )
            )
        }

        mediaController?.sendCustomCommand(
            SessionCommand(AudiobookPlaybackService.CMD_SET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putLong(AudiobookPlaybackService.ARG_DURATION_MS, configuredDurationMs) }
        )

        sleepTimerUiJob = controllerScope.launch {
            var remaining = configuredDurationMs
            while (remaining > 0L && isActive) {
                delay(1_000L)
                remaining = (remaining - 1_000L).coerceAtLeast(0L)
                _playbackState.update { st ->
                    st.copy(sleepTimer = st.sleepTimer.copy(remainingMs = remaining))
                }
            }
            if (isActive) {
                pause()
                _playbackState.update { st ->
                    st.copy(sleepTimer = SleepTimerState(mode = SleepTimerMode.OFF))
                }
                unregisterShakeSensor()
            }
        }
    }

    override fun resetSleepTimerFromShake() {
        val timer = _playbackState.value.sleepTimer
        if (timer.mode != SleepTimerMode.OFF && timer.totalConfiguredMs > 0L) {
            val mins = (timer.totalConfiguredMs / 60_000L).toInt().coerceAtLeast(1)
            startSleepTimer(timer.mode, mins, timer.shakeToResetEnabled)
            play()
        }
    }

    override fun cancelSleepTimer() {
        sleepTimerUiJob?.cancel()
        unregisterShakeSensor()
        _playbackState.update {
            it.copy(sleepTimer = SleepTimerState(mode = SleepTimerMode.OFF))
        }
        mediaController?.sendCustomCommand(
            SessionCommand(AudiobookPlaybackService.CMD_SET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply { putLong(AudiobookPlaybackService.ARG_DURATION_MS, 0L) }
        )
    }

    override suspend fun createAudioBookmark(
        noteMarkdown: String?,
        label: String?
    ): Result<BookmarkEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val state = _playbackState.value
            val book = state.activeBook ?: throw IllegalStateException("No active audiobook")
            val activeTrack = state.tracks.getOrNull(state.currentTrackIndex)
            val activeChapter = state.chapters.getOrNull(state.currentChapterIndex)
            val cueSnippet = state.activeSyncedCue?.textContent
                ?: "Audio position ${formatTimestampMs(state.totalBookPositionMs)}"

            val prog = (state.totalBookPositionMs.toFloat() / state.totalBookDurationMs.coerceAtLeast(1L).toFloat())
                .coerceIn(0f, 1f)

            val bookmark = BookmarkEntity(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                chapterIndex = state.currentChapterIndex,
                chapterTitle = activeChapter?.title ?: activeTrack?.title ?: "Chapter ${state.currentChapterIndex + 1}",
                locatorJson = """{"trackIndex":${state.currentTrackIndex},"positionMs":${state.currentTrackPositionMs}}""",
                charOffset = state.activeSyncedCue?.startCharOffset ?: 0,
                progressionPercentage = prog,
                audioTrackId = activeTrack?.id,
                audioTimestampMs = state.totalBookPositionMs,
                snippetText = cueSnippet,
                noteMarkdown = noteMarkdown?.takeIf { it.isNotBlank() },
                label = label ?: "Audio @ ${formatTimestampMs(state.totalBookPositionMs)}"
            )
            bookmarkDao.upsertBookmark(bookmark)
            bookmark
        }
    }

    override suspend fun jumpToAudioBookmark(bookmark: BookmarkEntity) {
        val targetMs = bookmark.audioTimestampMs ?: return
        seekToBookPosition(targetMs)
    }

    // --- Shake-to-Reset Accelerometer Listener ---

    private fun registerShakeSensor() {
        runCatching {
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun unregisterShakeSensor() {
        runCatching {
            sensorManager.unregisterListener(this)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val gForce = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        if (gForce > 2.35) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTimestampMs > 1_500L) {
                lastShakeTimestampMs = now
                resetSleepTimerFromShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // --- Progress Polling, 5-Second Room Persistence & Active TtsSyncCue Lookup ---

    private fun startProgressAndCueSyncPolling() {
        progressPollingJob?.cancel()
        progressPollingJob = controllerScope.launch {
            var tickCount = 0
            while (isActive) {
                delay(250L)
                val player = mediaController ?: directFallbackPlayer ?: continue
                val state = _playbackState.value
                val book = state.activeBook ?: continue

                val trackIdx = player.currentMediaItemIndex.coerceIn(0, max(0, state.tracks.size - 1))
                val trackPosMs = player.currentPosition.coerceAtLeast(0L)
                val activeTrack = state.tracks.getOrNull(trackIdx)
                val trackDurMs = max(
                    activeTrack?.durationMs ?: 0L,
                    player.duration.takeIf { it > 0L } ?: 0L
                )
                val bookPosMs = (activeTrack?.startOffsetInBookMs ?: 0L) + trackPosMs
                val totalBookDurMs = state.totalBookDurationMs.coerceAtLeast(trackDurMs).coerceAtLeast(1L)

                val matchedChapterIdx = state.chapters.indexOfLast { ch ->
                    (ch.startTimeMs ?: 0L) <= bookPosMs
                }.takeIf { it >= 0 } ?: 0

                val syncedCue = withContext(Dispatchers.IO) {
                    ttsSyncCueDao.getActiveCueAtTime(
                        bookId = book.id,
                        audioTrackId = activeTrack?.id,
                        positionMs = bookPosMs
                    )
                }

                _playbackState.update { st ->
                    st.copy(
                        currentTrackIndex = trackIdx,
                        currentChapterIndex = matchedChapterIdx,
                        currentTrackPositionMs = trackPosMs,
                        currentTrackDurationMs = trackDurMs,
                        totalBookPositionMs = bookPosMs,
                        totalBookDurationMs = totalBookDurMs,
                        activeSyncedCue = syncedCue
                    )
                }

                tickCount++
                // Save position to Room every 5 seconds (20 ticks * 250ms = 5000ms)
                if (tickCount % 20 == 0 && player.isPlaying) {
                    val prog = (bookPosMs.toFloat() / totalBookDurMs.toFloat()).coerceIn(0f, 1f)
                    withContext(Dispatchers.IO) {
                        bookDao.updateAudioPosition(
                            bookId = book.id,
                            trackIndex = trackIdx,
                            positionMs = trackPosMs,
                            progression = prog,
                            status = if (prog >= 0.98f) ReadingStatus.COMPLETED else ReadingStatus.READING
                        )
                    }
                }
            }
        }
    }

    private fun updateImmediatePositionState(trackIdx: Int, positionMs: Long) {
        _playbackState.update { state ->
            val track = state.tracks.getOrNull(trackIdx)
            val bookPos = (track?.startOffsetInBookMs ?: 0L) + positionMs
            val chapterIdx = state.chapters.indexOfLast { (it.startTimeMs ?: 0L) <= bookPos }
                .takeIf { it >= 0 } ?: state.currentChapterIndex
            state.copy(
                currentTrackIndex = trackIdx,
                currentChapterIndex = chapterIdx,
                currentTrackPositionMs = positionMs,
                totalBookPositionMs = bookPos
            )
        }
    }

    private fun observeBookmarksForBook(bookId: String) {
        bookmarksObserverJob?.cancel()
        bookmarksObserverJob = controllerScope.launch {
            bookmarkDao.observeBookmarksForBook(bookId).collectLatest { list ->
                _playbackState.update { it.copy(bookmarks = list) }
            }
        }
    }

    private fun persistAudioDspSettings() {
        val state = _playbackState.value
        val bookId = state.activeBook?.id ?: return
        controllerScope.launch(Dispatchers.IO) {
            bookDao.updateAudioPlaybackSettings(
                bookId = bookId,
                speed = state.playbackSpeed,
                pitch = state.playbackPitch,
                skipSilence = state.skipSilenceEnabled,
                voiceBoost = state.voiceBoostConfig.enabled
            )
        }
    }

    /**
     * Discovers multi-file audiobook folders via SAF `DocumentFile` or generates a playable
     * `.wav` starter audiobook with synchronized `TtsSyncCueEntity` cues if none exists yet.
     */
    private suspend fun buildOrDiscoverAudioTracksForBook(book: BookEntity): List<AudioTrackEntity> {
        val uri = Uri.parse(book.fileUri)
        if (uri.scheme == "content") {
            val docDir = DocumentFile.fromTreeUri(context, uri)
            if (docDir != null && docDir.isDirectory) {
                val audioFiles = docDir.listFiles()
                    .filter { file ->
                        val name = file.name?.lowercase().orEmpty()
                        name.endsWith(".m4b") || name.endsWith(".mp3") ||
                            name.endsWith(".m4a") || name.endsWith(".flac") ||
                            name.endsWith(".ogg") || name.endsWith(".wav")
                    }
                    .sortedBy { it.name.orEmpty() }

                if (audioFiles.isNotEmpty()) {
                    var cumulativeMs = 0L
                    val discovered = audioFiles.mapIndexed { idx, file ->
                        val estDurationMs = max(60_000L, (file.length() / (16 * 1024L)) * 1000L)
                        val track = AudioTrackEntity(
                            id = "${book.id}_track_$idx",
                            bookId = book.id,
                            trackIndex = idx,
                            title = file.name?.substringBeforeLast('.') ?: "Track ${idx + 1}",
                            fileUri = file.uri.toString(),
                            mimeType = file.type ?: "audio/mpeg",
                            durationMs = estDurationMs,
                            startOffsetInBookMs = cumulativeMs,
                            fileSizeBytes = file.length(),
                            waveformPeaks = defaultWaveform(seed = idx + 1)
                        )
                        cumulativeMs += estDurationMs
                        track
                    }
                    audioTrackDao.upsertTracks(discovered)
                    return discovered
                }
            }
        }

        // Generate a real playable 24kHz WAV audiobook demo track & synced cues if not yet synthesized
        val audioDir = File(context.filesDir, "audiobooks/${book.id}").apply { mkdirs() }
        val demoWavFile = File(audioDir, "starter_audiobook_24k.wav")
        val durationMs = 48_000L // 48 seconds across 4 chapters
        if (!demoWavFile.exists() || demoWavFile.length() == 0L) {
            generatePlayableStarterWavFile(demoWavFile, sampleRateHz = 24000, durationSeconds = 48)
        }

        val trackId = "${book.id}_track_0"
        val singleTrack = AudioTrackEntity(
            id = trackId,
            bookId = book.id,
            trackIndex = 0,
            title = book.title,
            fileUri = Uri.fromFile(demoWavFile).toString(),
            mimeType = "audio/wav",
            durationMs = durationMs,
            startOffsetInBookMs = 0L,
            bitrateKbps = 384,
            sampleRateHz = 24000,
            channels = 1,
            fileSizeBytes = demoWavFile.length(),
            waveformPeaks = defaultWaveform(seed = 7),
            isTtsGenerated = true
        )
        audioTrackDao.upsertTrack(singleTrack)

        val starterChapters = listOf(
            ChapterEntity("${book.id}_ch_0", book.id, 0, title = "Chapter 1: The Architecture of Deep Reading", href = "ch1.xhtml", audioTrackId = trackId, startTimeMs = 0L, endTimeMs = 12_000L, isTtsConverted = true),
            ChapterEntity("${book.id}_ch_1", book.id, 1, title = "Chapter 2: Acoustic Prosody & Dual-Coding Memory", href = "ch2.xhtml", audioTrackId = trackId, startTimeMs = 12_000L, endTimeMs = 24_000L, isTtsConverted = true),
            ChapterEntity("${book.id}_ch_2", book.id, 2, title = "Chapter 3: On-Device Neural Synthesis with Kokoro-82M", href = "ch3.xhtml", audioTrackId = trackId, startTimeMs = 24_000L, endTimeMs = 36_000L, isTtsConverted = true),
            ChapterEntity("${book.id}_ch_3", book.id, 3, title = "Chapter 4: Sentence Locators & Real-Time DOM Highlighting", href = "ch4.xhtml", audioTrackId = trackId, startTimeMs = 36_000L, endTimeMs = 48_000L, isTtsConverted = true)
        )
        chapterDao.upsertChapters(starterChapters)

        val existingCues = ttsSyncCueDao.getCuesForChapter(book.id, 0)
        if (existingCues.isEmpty()) {
            val sampleSentences = listOf(
                0 to "Deep reading is an active, contemplative cognitive process in which the reader constructs vivid mental models.",
                0 to "Sustained immersion in long-form literature links visual word-form recognition with semantic memory.",
                0 to "Every typographic parameter in OmniRead is engineered to preserve effortless reading flow.",
                1 to "Dual-coding theory demonstrates that comprehension improves when visual typography is paired with spoken audio.",
                1 to "When your eyes track a highlighted sentence while your ears process cadence, working memory load is balanced.",
                1 to "OmniRead pre-buffers three sentences ahead of playback for zero-latency transitions.",
                2 to "By integrating Kokoro-82M via Sherpa-ONNX, OmniRead delivers 24kHz float32 neural speech offline.",
                2 to "Fifty-four distinct speaker embeddings and voice blending let you cast narrators for every book.",
                2 to "Cloud TTS streams are cached on disk keyed by book, chapter, sentence hash, and voice ID.",
                3 to "Every chapter is tokenized into SentenceLocator objects with exact character offsets and audio timestamps.",
                3 to "Playing a converted M4B audiobook illuminates each spoken sentence in real time inside the reader.",
                3 to "Tap 'Switch to Synced Text View' at any moment to jump straight into the highlighted page."
            )
            val cues = sampleSentences.mapIndexed { idx, (chIdx, text) ->
                val startMs = idx * 4_000L
                val endMs = startMs + 4_000L
                com.example.data.local.entity.TtsSyncCueEntity(
                    id = "${book.id}_cue_$idx",
                    bookId = book.id,
                    chapterIndex = chIdx,
                    sentenceIndex = idx,
                    audioTrackId = trackId,
                    textContent = text,
                    startCharOffset = (idx % 3) * 110,
                    endCharOffset = ((idx % 3) + 1) * 110,
                    startTimeMs = startMs,
                    endTimeMs = endMs
                )
            }
            ttsSyncCueDao.upsertCues(cues)
        }

        return listOf(singleTrack)
    }

    private fun generatePlayableStarterWavFile(
        targetFile: File,
        sampleRateHz: Int,
        durationSeconds: Int
    ) {
        val totalSamples = sampleRateHz * durationSeconds
        val dataBytesSize = totalSamples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            putInt(36 + dataBytesSize)
            put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(sampleRateHz)
            putInt(sampleRateHz * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(StandardCharsets.US_ASCII))
            putInt(dataBytesSize)
        }

        targetFile.outputStream().buffered().use { out ->
            out.write(header.array())
            val chunkSamples = 4800
            val byteChunk = ByteBuffer.allocate(chunkSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
            var sampleIdx = 0
            while (sampleIdx < totalSamples) {
                byteChunk.clear()
                val limit = min(chunkSamples, totalSamples - sampleIdx)
                for (i in 0 until limit) {
                    val globalIdx = sampleIdx + i
                    val t = globalIdx.toDouble() / sampleRateHz
                    val cadenceEnv = (0.5 + 0.5 * sin(2.0 * PI * 3.6 * t)).coerceIn(0.05, 1.0)
                    val carrier = 0.6 * sin(2.0 * PI * 175.0 * t) + 0.3 * sin(2.0 * PI * 520.0 * t)
                    val pcmShort = (carrier * cadenceEnv * 6500.0).toInt().coerceIn(-32767, 32767).toShort()
                    byteChunk.putShort(pcmShort)
                }
                out.write(byteChunk.array(), 0, limit * 2)
                sampleIdx += limit
            }
        }
    }

    private fun defaultWaveform(seed: Int = 1): List<Float> {
        return List(96) { idx ->
            val v = 0.25 + 0.55 * kotlin.math.abs(sin((idx + seed) * 0.37))
            v.toFloat().coerceIn(0.12f, 0.98f)
        }
    }

    private fun formatTimestampMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hrs = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hrs > 0L) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    companion object {
        private const val TAG = "AudiobookPlayerCtrl"
    }
}
