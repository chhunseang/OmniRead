package com.example.data.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.TtsEngineType
import com.example.data.local.entity.TtsSyncCueEntity
import com.example.domain.reader.SentenceLocator
import com.example.domain.tts.CloudApiTtsProvider
import com.example.domain.tts.LiveTtsReadAloudStatus
import com.example.domain.tts.LiveTtsSessionState
import com.example.domain.tts.LiveTtsSyncCoordinator
import com.example.domain.tts.LocalKokoroTtsProvider
import com.example.domain.tts.SynthesizedAudioChunk
import com.example.domain.tts.TtsProvider
import com.example.domain.tts.TtsSynthesisRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

sealed interface ReadAloudUiEvent {
    data class HighlightSentenceChanged(
        val sentenceLocator: SentenceLocator,
        val shouldAutoAdvanceViewport: Boolean
    ) : ReadAloudUiEvent

    data class ChapterCompleted(
        val bookId: String,
        val completedChapterIndex: Int,
        val nextChapterIndex: Int
    ) : ReadAloudUiEvent
}

@Singleton
class ReadAloudCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localKokoroProvider: LocalKokoroTtsProvider,
    private val cloudApiProvider: CloudApiTtsProvider,
    private val ttsSyncCueDao: TtsSyncCueDao,
    private val bookDao: BookDao
) : LiveTtsSyncCoordinator {

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _sessionState = MutableStateFlow(LiveTtsSessionState())
    override val sessionState: StateFlow<LiveTtsSessionState> = _sessionState.asStateFlow()

    private val _activeSentenceFlow = MutableStateFlow<SentenceLocator?>(null)
    val activeSentenceFlow: StateFlow<SentenceLocator?> = _activeSentenceFlow.asStateFlow()

    private val _uiEvents = MutableSharedFlow<ReadAloudUiEvent>(extraBufferCapacity = 16)
    val uiEvents: SharedFlow<ReadAloudUiEvent> = _uiEvents.asSharedFlow()

    private var currentChapterSentences: List<SentenceLocator> = emptyList()
    private var viewportEndCharOffset: Int = Int.MAX_VALUE

    private var pipelineJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val isPausedFlag = AtomicBoolean(false)

    private data class SynthesizedSentencePacket(
        val sentenceIndex: Int,
        val locator: SentenceLocator,
        val chunks: List<SynthesizedAudioChunk>,
        val sampleRateHz: Int,
        val totalDurationMs: Long
    )

    /**
     * Tokenizes raw EPUB/MOBI HTML or plain text into sentence-level [SentenceLocator] tokens
     * with exact character offsets (`startCharOffset` / `endCharOffset`), paragraph indices,
     * and CSS DOM selectors for real-time Compose / WebView highlighting.
     */
    fun tokenizeChapterIntoSentences(
        bookId: String,
        chapterIndex: Int,
        chapterHref: String,
        rawHtmlOrText: String,
        locale: Locale = Locale.US
    ): List<SentenceLocator> {
        val isHtml = rawHtmlOrText.contains("<html", ignoreCase = true) ||
            rawHtmlOrText.contains("<body", ignoreCase = true) ||
            rawHtmlOrText.contains("<p", ignoreCase = true) ||
            rawHtmlOrText.contains("<div", ignoreCase = true)

        data class ParagraphBlock(
            val paragraphIndex: Int,
            val text: String,
            val domSelector: String
        )

        val blocks = ArrayList<ParagraphBlock>()
        if (isHtml) {
            val doc = Jsoup.parse(rawHtmlOrText)
            doc.select("script, style, nav, head").remove()
            val blockElements = doc.body().select("h1, h2, h3, h4, h5, h6, p, li, blockquote")
            if (blockElements.isNotEmpty()) {
                var pIdx = 0
                for (el in blockElements) {
                    // Skip nested block containers that duplicate child <p> text
                    if (el.children().any { child ->
                            child.tagName() in setOf("p", "li", "blockquote", "h1", "h2", "h3")
                        }
                    ) {
                        continue
                    }
                    val text = el.text().trim()
                    if (text.isNotEmpty()) {
                        blocks.add(
                            ParagraphBlock(
                                paragraphIndex = pIdx++,
                                text = text,
                                domSelector = buildCssSelector(el, pIdx)
                            )
                        )
                    }
                }
            } else {
                val fallbackText = doc.body().text().trim()
                if (fallbackText.isNotEmpty()) {
                    blocks.add(ParagraphBlock(0, fallbackText, "body"))
                }
            }
        } else {
            val rawParagraphs = rawHtmlOrText.split(Regex("\\n\\s*\\n+"))
            var pIdx = 0
            for (para in rawParagraphs) {
                val cleaned = para.trim()
                if (cleaned.isNotEmpty()) {
                    blocks.add(ParagraphBlock(pIdx, cleaned, "p:nth-of-type(${pIdx + 1})"))
                    pIdx++
                }
            }
        }

        val totalCharLength = max(1, blocks.sumOf { it.text.length + 1 })
        val sentenceLocators = ArrayList<SentenceLocator>()
        val breakIterator = BreakIterator.getSentenceInstance(locale)

        var runningCharOffset = 0
        var sentenceGlobalIndex = 0

        for (block in blocks) {
            val paragraphText = block.text
            breakIterator.setText(paragraphText)
            var start = breakIterator.first()
            var end = breakIterator.next()

            while (end != BreakIterator.DONE) {
                val rawSlice = paragraphText.substring(start, end)
                val leadingTrim = rawSlice.indexOfFirst { !it.isWhitespace() }
                val trailingTrim = rawSlice.indexOfLast { !it.isWhitespace() }

                if (leadingTrim >= 0 && trailingTrim >= leadingTrim) {
                    val cleanSentence = rawSlice.substring(leadingTrim, trailingTrim + 1)
                    val sentenceStartOffset = runningCharOffset + start + leadingTrim
                    val sentenceEndOffset = runningCharOffset + start + trailingTrim + 1
                    val progression = (sentenceStartOffset.toFloat() / totalCharLength.toFloat())
                        .coerceIn(0f, 1f)

                    sentenceLocators.add(
                        SentenceLocator(
                            sentenceId = "${bookId}_ch${chapterIndex}_s${sentenceGlobalIndex}",
                            bookId = bookId,
                            chapterIndex = chapterIndex,
                            chapterHref = chapterHref,
                            sentenceIndex = sentenceGlobalIndex,
                            text = cleanSentence,
                            startCharOffset = sentenceStartOffset,
                            endCharOffset = sentenceEndOffset,
                            paragraphIndex = block.paragraphIndex,
                            domCssSelector = block.domSelector,
                            cfiRange = "epubcfi(/6/${(chapterIndex + 1) * 2}!/4/${(block.paragraphIndex + 1) * 2}/1:${start + leadingTrim},${start + trailingTrim + 1})",
                            progressionInChapter = progression
                        )
                    )
                    sentenceGlobalIndex++
                }

                start = end
                end = breakIterator.next()
            }

            runningCharOffset += paragraphText.length + 1
        }

        return sentenceLocators
    }

    /**
     * Updates the current visible viewport character boundary so the coordinator can trigger
     * automatic page-turn / smooth scroll when the spoken sentence reaches the end of the page.
     */
    fun updateVisibleViewportCharBounds(startCharOffset: Int, endCharOffset: Int) {
        viewportEndCharOffset = endCharOffset
    }

    override suspend fun startReadAloud(
        bookId: String,
        chapterIndex: Int,
        sentences: List<SentenceLocator>,
        startSentenceIndex: Int
    ) {
        stopReadAloudInternal(resetBookState = false)

        if (sentences.isEmpty()) {
            _sessionState.update {
                it.copy(
                    status = LiveTtsReadAloudStatus.ERROR,
                    errorMessage = "No readable sentences found in chapter ${chapterIndex + 1}."
                )
            }
            return
        }

        currentChapterSentences = sentences
        val clampedStartIndex = startSentenceIndex.coerceIn(0, sentences.size - 1)
        isPausedFlag.set(false)

        _sessionState.update { state ->
            state.copy(
                status = LiveTtsReadAloudStatus.INITIALIZING_MODEL,
                bookId = bookId,
                chapterIndex = chapterIndex,
                activeSentence = sentences[clampedStartIndex],
                bufferedSentenceIndices = emptyList(),
                errorMessage = null
            )
        }
        _activeSentenceFlow.value = sentences[clampedStartIndex]

        requestAudioFocus()
        launchProducerConsumerPipeline(
            bookId = bookId,
            chapterIndex = chapterIndex,
            sentences = sentences,
            startIndex = clampedStartIndex
        )
    }

    override fun pauseReadAloud() {
        isPausedFlag.set(true)
        runCatching { audioTrack?.pause() }
        _sessionState.update { state ->
            if (state.status == LiveTtsReadAloudStatus.PLAYING ||
                state.status == LiveTtsReadAloudStatus.PRE_BUFFERING
            ) {
                state.copy(status = LiveTtsReadAloudStatus.PAUSED)
            } else {
                state
            }
        }
    }

    override fun resumeReadAloud() {
        val currentState = _sessionState.value
        if (currentState.status != LiveTtsReadAloudStatus.PAUSED) return
        isPausedFlag.set(false)
        runCatching { audioTrack?.play() }
        _sessionState.update { it.copy(status = LiveTtsReadAloudStatus.PLAYING) }
    }

    override fun stopReadAloud() {
        stopReadAloudInternal(resetBookState = true)
    }

    override suspend fun jumpToSentence(sentenceLocator: SentenceLocator) {
        val targetIndex = currentChapterSentences.indexOfFirst {
            it.sentenceIndex == sentenceLocator.sentenceIndex ||
                it.sentenceId == sentenceLocator.sentenceId ||
                (it.startCharOffset <= sentenceLocator.startCharOffset &&
                    it.endCharOffset >= sentenceLocator.startCharOffset)
        }.takeIf { it >= 0 } ?: sentenceLocator.sentenceIndex.coerceIn(
            0,
            max(0, currentChapterSentences.size - 1)
        )

        val bookId = _sessionState.value.bookId ?: sentenceLocator.bookId
        val chapterIndex = sentenceLocator.chapterIndex

        if (currentChapterSentences.isNotEmpty() && bookId == sentenceLocator.bookId) {
            startReadAloud(
                bookId = bookId,
                chapterIndex = chapterIndex,
                sentences = currentChapterSentences,
                startSentenceIndex = targetIndex
            )
        }
    }

    override suspend fun skipToNextSentence() {
        val current = _sessionState.value.activeSentence ?: return
        val nextIdx = (current.sentenceIndex + 1).coerceAtMost(currentChapterSentences.size - 1)
        if (nextIdx != current.sentenceIndex && currentChapterSentences.isNotEmpty()) {
            jumpToSentence(currentChapterSentences[nextIdx])
        }
    }

    override suspend fun skipToPreviousSentence() {
        val current = _sessionState.value.activeSentence ?: return
        val prevIdx = (current.sentenceIndex - 1).coerceAtLeast(0)
        if (currentChapterSentences.isNotEmpty()) {
            jumpToSentence(currentChapterSentences[prevIdx])
        }
    }

    override fun setEngineAndVoice(engineType: TtsEngineType, voiceId: String, speakerId: Int) {
        _sessionState.update {
            it.copy(
                activeEngine = engineType,
                activeVoiceId = voiceId,
                activeSpeakerId = speakerId
            )
        }
        val activeSentence = _sessionState.value.activeSentence
        val activeBookId = _sessionState.value.bookId
        if (activeSentence != null && activeBookId != null &&
            (_sessionState.value.status == LiveTtsReadAloudStatus.PLAYING ||
                _sessionState.value.status == LiveTtsReadAloudStatus.PRE_BUFFERING)
        ) {
            coordinatorScope.launch {
                startReadAloud(
                    bookId = activeBookId,
                    chapterIndex = activeSentence.chapterIndex,
                    sentences = currentChapterSentences,
                    startSentenceIndex = activeSentence.sentenceIndex
                )
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.5f)
        _sessionState.update { it.copy(playbackSpeed = clamped) }
    }

    override fun setPreBufferLookahead(sentenceCount: Int) {
        val clamped = sentenceCount.coerceIn(1, 5)
        _sessionState.update { it.copy(preBufferLookaheadCount = clamped) }
    }

    private fun launchProducerConsumerPipeline(
        bookId: String,
        chapterIndex: Int,
        sentences: List<SentenceLocator>,
        startIndex: Int
    ) {
        val lookaheadCapacity = _sessionState.value.preBufferLookaheadCount.coerceIn(2, 4)
        // Bounded Channel of capacity = lookaheadCapacity (default 3 sentences ahead)
        val sentenceChannel = Channel<SynthesizedSentencePacket>(capacity = lookaheadCapacity)

        pipelineJob = coordinatorScope.launch {
            val provider = selectActiveTtsProvider(_sessionState.value.activeEngine)
            val initResult = provider.initialize()
            if (initResult.isFailure) {
                _sessionState.update {
                    it.copy(
                        status = LiveTtsReadAloudStatus.ERROR,
                        errorMessage = initResult.exceptionOrNull()?.message
                            ?: "Failed to initialize TTS engine"
                    )
                }
                return@launch
            }

            _sessionState.update { it.copy(status = LiveTtsReadAloudStatus.PRE_BUFFERING) }

            // Producer Coroutine: pre-synthesizes the next N sentences in parallel ahead of playback
            val producerJob = launch(Dispatchers.Default) {
                try {
                    for (idx in startIndex until sentences.size) {
                        if (!isActive) break
                        val locator = sentences[idx]
                        val currentSnapshot = _sessionState.value

                        val request = TtsSynthesisRequest(
                            requestId = "${bookId}_ch${chapterIndex}_s${idx}_${System.currentTimeMillis()}",
                            sentenceLocator = locator,
                            text = locator.text,
                            engineType = currentSnapshot.activeEngine,
                            voiceId = currentSnapshot.activeVoiceId,
                            speakerId = currentSnapshot.activeSpeakerId,
                            speed = currentSnapshot.playbackSpeed
                        )

                        val collectedChunks = ArrayList<SynthesizedAudioChunk>()
                        var sampleRate = LocalKokoroTtsProviderImpl.SAMPLE_RATE_24KHZ
                        var totalDurationMs = 0L

                        try {
                            provider.synthesizeStream(request).collect { chunk ->
                                collectedChunks.add(chunk)
                                sampleRate = chunk.sampleRateHz
                                totalDurationMs += chunk.durationMs
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (err: Exception) {
                            Log.w(TAG, "Primary TTS provider failed on sentence $idx, falling back to Local Kokoro", err)
                            localKokoroProvider.synthesizeStream(request).collect { chunk ->
                                collectedChunks.add(chunk)
                                sampleRate = chunk.sampleRateHz
                                totalDurationMs += chunk.durationMs
                            }
                        }

                        if (collectedChunks.isNotEmpty() && isActive) {
                            val packet = SynthesizedSentencePacket(
                                sentenceIndex = idx,
                                locator = locator,
                                chunks = collectedChunks,
                                sampleRateHz = sampleRate,
                                totalDurationMs = totalDurationMs
                            )
                            _sessionState.update { state ->
                                state.copy(
                                    bufferedSentenceIndices = (state.bufferedSentenceIndices + idx)
                                        .distinct()
                                        .takeLast(lookaheadCapacity + 1)
                                )
                            }
                            sentenceChannel.send(packet)
                        }
                    }
                } finally {
                    sentenceChannel.close()
                }
            }

            // Consumer Coroutine: pulls pre-buffered packets from Channel, updates active SentenceLocator
            // StateFlow, triggers auto-page-turn if needed, and streams float32 PCM to AudioTrack
            var cumulativeChapterTimeMs = 0L
            try {
                for (packet in sentenceChannel) {
                    if (!isActive) break

                    while (isPausedFlag.get() && isActive) {
                        delay(60L)
                    }
                    if (!isActive) break

                    val locator = packet.locator
                    val shouldTurnPage = _sessionState.value.autoTurnPageOnViewportEnd &&
                        locator.startCharOffset >= viewportEndCharOffset

                    _activeSentenceFlow.value = locator
                    _sessionState.update { state ->
                        state.copy(
                            status = if (isPausedFlag.get()) {
                                LiveTtsReadAloudStatus.PAUSED
                            } else {
                                LiveTtsReadAloudStatus.PLAYING
                            },
                            activeSentence = locator,
                            bufferedSentenceIndices = state.bufferedSentenceIndices.filter { it > packet.sentenceIndex }
                        )
                    }

                    _uiEvents.tryEmit(
                        ReadAloudUiEvent.HighlightSentenceChanged(
                            sentenceLocator = locator,
                            shouldAutoAdvanceViewport = shouldTurnPage
                        )
                    )

                    val startTimeMs = cumulativeChapterTimeMs
                    val endTimeMs = startTimeMs + packet.totalDurationMs
                    cumulativeChapterTimeMs = endTimeMs

                    // Persist TtsSyncCueEntity & reading position in background
                    launch(Dispatchers.IO) {
                        val cue = TtsSyncCueEntity(
                            id = locator.sentenceId.ifBlank { UUID.randomUUID().toString() },
                            bookId = bookId,
                            chapterIndex = chapterIndex,
                            sentenceIndex = locator.sentenceIndex,
                            textContent = locator.text,
                            startCharOffset = locator.startCharOffset,
                            endCharOffset = locator.endCharOffset,
                            domSelector = locator.domCssSelector,
                            locatorJson = locator.cfiRange,
                            startTimeMs = startTimeMs,
                            endTimeMs = endTimeMs,
                            cachedPcmOrAudioPath = packet.chunks.firstOrNull()?.cachedDiskFilePath,
                            ttsEngine = _sessionState.value.activeEngine,
                            voiceId = _sessionState.value.activeVoiceId
                        )
                        ttsSyncCueDao.upsertCue(cue)
                        bookDao.updateReadingPosition(
                            bookId = bookId,
                            chapterIndex = chapterIndex,
                            locatorJson = locator.cfiRange,
                            charOffset = locator.startCharOffset,
                            progression = locator.progressionInChapter,
                            status = ReadingStatus.READING
                        )
                    }

                    // Stream all float32 PCM chunks for this sentence through AudioTrack
                    val track = ensureAudioTrackInitialized(packet.sampleRateHz)
                    for (chunk in packet.chunks) {
                        if (!isActive) break
                        writeFloatPcmBlockingWithPauseSupport(track, chunk.pcmFloatSamples)
                    }
                }

                if (isActive) {
                    _sessionState.update { it.copy(status = LiveTtsReadAloudStatus.IDLE) }
                    _uiEvents.tryEmit(
                        ReadAloudUiEvent.ChapterCompleted(
                            bookId = bookId,
                            completedChapterIndex = chapterIndex,
                            nextChapterIndex = chapterIndex + 1
                        )
                    )
                }
            } finally {
                producerJob.cancel()
            }
        }
    }

    private fun selectActiveTtsProvider(engineType: TtsEngineType): TtsProvider {
        return when (engineType) {
            TtsEngineType.LOCAL_KOKORO -> localKokoroProvider
            TtsEngineType.CLOUD_OPENAI,
            TtsEngineType.CLOUD_ELEVENLABS,
            TtsEngineType.CLOUD_GEMINI -> cloudApiProvider
        }
    }

    private fun ensureAudioTrackInitialized(sampleRateHz: Int): AudioTrack {
        val current = audioTrack
        if (current != null &&
            current.state == AudioTrack.STATE_INITIALIZED &&
            current.sampleRate == sampleRateHz
        ) {
            if (current.playState != AudioTrack.PLAYSTATE_PLAYING && !isPausedFlag.get()) {
                current.play()
            }
            return current
        }

        runCatching {
            current?.stop()
            current?.release()
        }

        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRateHz * 4 / 2)

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBufBytes * 2)
            .build()

        newTrack.play()
        audioTrack = newTrack
        return newTrack
    }

    private suspend fun writeFloatPcmBlockingWithPauseSupport(
        track: AudioTrack,
        samples: FloatArray
    ) {
        val subChunkFloats = 2400 // 100ms slices @ 24kHz for responsive pause/jump
        var offset = 0
        while (offset < samples.size) {
            while (isPausedFlag.get()) {
                delay(50L)
            }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            val count = min(subChunkFloats, samples.size - offset)
            val written = track.write(
                samples,
                offset,
                count,
                AudioTrack.WRITE_BLOCKING
            )
            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned error code: $written")
                break
            }
            offset += written
        }
    }

    private fun stopReadAloudInternal(resetBookState: Boolean) {
        isPausedFlag.set(false)
        localKokoroProvider.cancelActiveSynthesis()
        cloudApiProvider.cancelActiveSynthesis()
        pipelineJob?.cancel()
        pipelineJob = null

        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        abandonAudioFocus()

        if (resetBookState) {
            _activeSentenceFlow.value = null
            _sessionState.update {
                it.copy(
                    status = LiveTtsReadAloudStatus.IDLE,
                    activeSentence = null,
                    bufferedSentenceIndices = emptyList(),
                    errorMessage = null
                )
            }
        }
    }

    private fun requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseReadAloud()
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            // Keep user pause state if manually paused
                        }
                    }
                }
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        }
    }

    private fun abandonAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        }
    }

    private fun buildCssSelector(element: Element, fallbackIndex: Int): String {
        val id = element.id()
        if (id.isNotBlank()) return "#$id"
        val tag = element.tagName()
        val siblingIndex = element.elementSiblingIndex() + 1
        return "$tag:nth-child($siblingIndex)"
    }

    companion object {
        private const val TAG = "ReadAloudCoordinator"
    }
}
