package com.example.ui.converter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.TtsEngineType
import com.example.data.tts.BatchAudiobookExportManagerImpl
import com.example.data.tts.CloudApiTtsProviderImpl
import com.example.data.tts.LocalKokoroTtsProviderImpl
import com.example.domain.reader.SentenceLocator
import com.example.domain.tts.AudioExportFormat
import com.example.domain.tts.BatchAudiobookConversionSpec
import com.example.domain.tts.BatchConversionProgress
import com.example.domain.tts.CloudApiTtsConfig
import com.example.domain.tts.CloudApiTtsProvider
import com.example.domain.tts.LocalKokoroTtsProvider
import com.example.domain.tts.TtsSynthesisRequest
import com.example.domain.tts.TtsVoiceProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class ConverterUiState(
    val availableEbooks: List<BookEntity> = emptyList(),
    val selectedBook: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val selectedChapterIndices: Set<Int> = emptySet(),
    val selectedEngine: TtsEngineType = TtsEngineType.LOCAL_KOKORO,
    val availableVoices: List<TtsVoiceProfile> = LocalKokoroTtsProviderImpl.KOKORO_V1_VOICE_CATALOG,
    val selectedVoice: TtsVoiceProfile = LocalKokoroTtsProviderImpl.KOKORO_V1_VOICE_CATALOG[3], // af_heart
    val synthesisSpeed: Float = 1.0f,
    val exportFormat: AudioExportFormat = AudioExportFormat.M4B,
    val generateLrcSidecar: Boolean = true,
    val generateJsonSyncSidecar: Boolean = true,
    val embedChapterMarkers: Boolean = true,
    val cloudBaseUrl: String = "https://api.openai.com/v1/",
    val cloudApiKey: String = "",
    val cloudModelId: String = "tts-1-hd",
    val isPreviewPlaying: Boolean = false,
    val previewingVoiceId: String? = null,
    val previewSampleText: String = "Every sentence in OmniRead is synchronized with 24kHz neural audio so you can switch seamlessly between reading and listening.",
    val conversionQueue: List<BatchConversionProgress> = emptyList(),
    val statusMessage: String? = null
)

@HiltViewModel
class ConverterViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val localKokoroProvider: LocalKokoroTtsProvider,
    private val cloudApiProvider: CloudApiTtsProvider,
    private val exportManager: BatchAudiobookExportManagerImpl
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private var previewAudioTrack: AudioTrack? = null
    private var chaptersObserverJob: Job? = null

    init {
        seedStarterEbookIfNeeded()
        observeLibraryBooks()
        observeCloudConfig()
        observeConversionQueue()
    }

    private fun seedStarterEbookIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            val sampleBookId = "omniread_starter_odyssey"
            val existing = bookDao.getBookById(sampleBookId)
            if (existing == null) {
                val starterBook = BookEntity(
                    id = sampleBookId,
                    title = "The Art of Deep Reading & Neural Audio",
                    subtitle = "Synchronized EPUB3 & Kokoro-82M Studio Edition",
                    author = "Elena Vance",
                    description = "An interactive guide to immersive reading, speed comprehension, and offline 24kHz neural speech synthesis.",
                    publisher = "OmniRead Press",
                    language = "en",
                    format = BookFormat.EPUB,
                    mediaType = MediaType.EBOOK,
                    fileUri = "file:///android_asset/starter_odyssey.epub",
                    seriesName = "OmniRead Classics",
                    seriesIndex = 1f,
                    tags = listOf("Non-Fiction", "Cognitive Science", "AI Audio"),
                    totalChapters = 5,
                    totalPages = 148,
                    totalWords = 18_450
                )
                bookDao.upsertBook(starterBook)

                val sampleChapters = listOf(
                    ChapterEntity(
                        id = "${sampleBookId}_ch_0",
                        bookId = sampleBookId,
                        chapterIndex = 0,
                        title = "Chapter 1: The Architecture of Deep Reading",
                        href = "ch1.xhtml",
                        wordCount = 3420,
                        charCount = 19800,
                        startPage = 1,
                        endPage = 28
                    ),
                    ChapterEntity(
                        id = "${sampleBookId}_ch_1",
                        bookId = sampleBookId,
                        chapterIndex = 1,
                        title = "Chapter 2: Acoustic Prosody & Dual-Coding Memory",
                        href = "ch2.xhtml",
                        wordCount = 4150,
                        charCount = 24100,
                        startPage = 29,
                        endPage = 62
                    ),
                    ChapterEntity(
                        id = "${sampleBookId}_ch_2",
                        bookId = sampleBookId,
                        chapterIndex = 2,
                        title = "Chapter 3: On-Device Neural Synthesis with Kokoro-82M",
                        href = "ch3.xhtml",
                        wordCount = 3890,
                        charCount = 22400,
                        startPage = 63,
                        endPage = 94
                    ),
                    ChapterEntity(
                        id = "${sampleBookId}_ch_3",
                        bookId = sampleBookId,
                        chapterIndex = 3,
                        title = "Chapter 4: Sentence Locators & Real-Time DOM Highlighting",
                        href = "ch4.xhtml",
                        wordCount = 3640,
                        charCount = 21200,
                        startPage = 95,
                        endPage = 124
                    ),
                    ChapterEntity(
                        id = "${sampleBookId}_ch_4",
                        bookId = sampleBookId,
                        chapterIndex = 4,
                        title = "Chapter 5: Building a Lifelong Personal Library",
                        href = "ch5.xhtml",
                        wordCount = 3350,
                        charCount = 19100,
                        startPage = 125,
                        endPage = 148
                    )
                )
                chapterDao.upsertChapters(sampleChapters)
            }
        }
    }

    private fun observeLibraryBooks() {
        viewModelScope.launch {
            bookDao.observeAllBooks().collectLatest { allBooks ->
                val ebooks = allBooks.filter {
                    it.mediaType == MediaType.EBOOK || it.mediaType == MediaType.HYBRID_SYNCED
                }
                val currentSelected = _uiState.value.selectedBook
                val nextSelected = ebooks.firstOrNull { it.id == currentSelected?.id }
                    ?: ebooks.firstOrNull()

                _uiState.update { state ->
                    state.copy(
                        availableEbooks = ebooks,
                        selectedBook = nextSelected
                    )
                }

                if (nextSelected != null && nextSelected.id != currentSelected?.id) {
                    loadChaptersForBook(nextSelected.id)
                }
            }
        }
    }

    private fun observeCloudConfig() {
        viewModelScope.launch {
            cloudApiProvider.cloudConfig.collectLatest { cfg ->
                _uiState.update { state ->
                    state.copy(
                        cloudBaseUrl = cfg.baseUrl,
                        cloudApiKey = cfg.apiKey,
                        cloudModelId = cfg.modelId
                    )
                }
            }
        }
    }

    private fun observeConversionQueue() {
        viewModelScope.launch {
            exportManager.observeAllConversionJobs().collectLatest { jobs ->
                _uiState.update { it.copy(conversionQueue = jobs) }
            }
        }
    }

    fun selectBook(book: BookEntity) {
        _uiState.update { it.copy(selectedBook = book) }
        loadChaptersForBook(book.id)
    }

    private fun loadChaptersForBook(bookId: String) {
        chaptersObserverJob?.cancel()
        chaptersObserverJob = viewModelScope.launch {
            chapterDao.observeChaptersForBook(bookId).collectLatest { chapterList ->
                _uiState.update { state ->
                    val autoSelected = if (state.selectedBook?.id == bookId && state.selectedChapterIndices.isNotEmpty()) {
                        state.selectedChapterIndices
                    } else {
                        chapterList.map { it.chapterIndex }.toSet()
                    }
                    state.copy(
                        chapters = chapterList,
                        selectedChapterIndices = autoSelected
                    )
                }
            }
        }
    }

    fun toggleChapterSelection(chapterIndex: Int) {
        _uiState.update { state ->
            val updated = state.selectedChapterIndices.toMutableSet()
            if (!updated.add(chapterIndex)) {
                updated.remove(chapterIndex)
            }
            state.copy(selectedChapterIndices = updated)
        }
    }

    fun selectAllChapters() {
        _uiState.update { state ->
            state.copy(selectedChapterIndices = state.chapters.map { it.chapterIndex }.toSet())
        }
    }

    fun clearChapterSelection() {
        _uiState.update { it.copy(selectedChapterIndices = emptySet()) }
    }

    fun selectEngine(engineType: TtsEngineType) {
        val voices = when (engineType) {
            TtsEngineType.LOCAL_KOKORO -> LocalKokoroTtsProviderImpl.KOKORO_V1_VOICE_CATALOG
            TtsEngineType.CLOUD_OPENAI -> CloudApiTtsProviderImpl.OPENAI_COMPATIBLE_VOICES
            TtsEngineType.CLOUD_ELEVENLABS -> CloudApiTtsProviderImpl.ELEVENLABS_DEFAULT_VOICES
            TtsEngineType.CLOUD_GEMINI -> CloudApiTtsProviderImpl.GEMINI_AUDIO_VOICES
        }
        val defaultVoice = voices.firstOrNull { it.id == "af_heart" } ?: voices.first()
        _uiState.update { state ->
            state.copy(
                selectedEngine = engineType,
                availableVoices = voices,
                selectedVoice = defaultVoice
            )
        }
    }

    fun selectVoice(voice: TtsVoiceProfile) {
        _uiState.update { it.copy(selectedVoice = voice) }
    }

    fun setSynthesisSpeed(speed: Float) {
        _uiState.update { it.copy(synthesisSpeed = speed.coerceIn(0.5f, 3.0f)) }
    }

    fun setExportFormat(format: AudioExportFormat) {
        _uiState.update { it.copy(exportFormat = format) }
    }

    fun toggleGenerateLrcSidecar(enabled: Boolean) {
        _uiState.update { it.copy(generateLrcSidecar = enabled) }
    }

    fun toggleGenerateJsonSidecar(enabled: Boolean) {
        _uiState.update { it.copy(generateJsonSyncSidecar = enabled) }
    }

    fun toggleEmbedChapterMarkers(enabled: Boolean) {
        _uiState.update { it.copy(embedChapterMarkers = enabled) }
    }

    fun updateCloudEndpointSettings(baseUrl: String, apiKey: String, modelId: String) {
        _uiState.update {
            it.copy(
                cloudBaseUrl = baseUrl,
                cloudApiKey = apiKey,
                cloudModelId = modelId
            )
        }
        viewModelScope.launch {
            val currentCfg = cloudApiProvider.cloudConfig.value
            cloudApiProvider.updateCloudConfig(
                currentCfg.copy(
                    engineType = _uiState.value.selectedEngine,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    modelId = modelId,
                    voiceId = _uiState.value.selectedVoice.id
                )
            )
        }
    }

    fun updatePreviewSampleText(text: String) {
        _uiState.update { it.copy(previewSampleText = text) }
    }

    fun previewSpeakerVoice(voice: TtsVoiceProfile = _uiState.value.selectedVoice) {
        if (_uiState.value.isPreviewPlaying && _uiState.value.previewingVoiceId == voice.id) {
            stopVoicePreview()
            return
        }

        stopVoicePreview()
        _uiState.update {
            it.copy(
                selectedVoice = voice,
                isPreviewPlaying = true,
                previewingVoiceId = voice.id,
                statusMessage = "Synthesizing 24kHz preview for ${voice.name}..."
            )
        }

        previewJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val state = _uiState.value
                val sampleLocator = SentenceLocator(
                    sentenceId = "preview_${voice.id}",
                    bookId = "preview",
                    chapterIndex = 0,
                    chapterHref = "preview.xhtml",
                    sentenceIndex = 0,
                    text = state.previewSampleText,
                    startCharOffset = 0,
                    endCharOffset = state.previewSampleText.length,
                    paragraphIndex = 0
                )

                val request = TtsSynthesisRequest(
                    requestId = "preview_${System.currentTimeMillis()}",
                    sentenceLocator = sampleLocator,
                    text = state.previewSampleText,
                    engineType = state.selectedEngine,
                    voiceId = voice.id,
                    speakerId = voice.speakerIndex,
                    speed = state.synthesisSpeed
                )

                val provider = if (state.selectedEngine == TtsEngineType.LOCAL_KOKORO) {
                    localKokoroProvider
                } else {
                    cloudApiProvider
                }

                val chunk = provider.synthesizeSentence(request)
                    .recoverCatching { localKokoroProvider.synthesizeSentence(request).getOrThrow() }
                    .getOrThrow()

                playPreviewPcmSamples(chunk.pcmFloatSamples, chunk.sampleRateHz)
                _uiState.update {
                    it.copy(
                        isPreviewPlaying = false,
                        previewingVoiceId = null,
                        statusMessage = "Completed speaker preview (${voice.name})"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isPreviewPlaying = false,
                        previewingVoiceId = null,
                        statusMessage = "Preview error: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopVoicePreview() {
        previewJob?.cancel()
        previewJob = null
        runCatching {
            previewAudioTrack?.pause()
            previewAudioTrack?.flush()
            previewAudioTrack?.stop()
            previewAudioTrack?.release()
        }
        previewAudioTrack = null
        _uiState.update {
            it.copy(isPreviewPlaying = false, previewingVoiceId = null)
        }
    }

    fun enqueueBatchAudiobookConversion() {
        val state = _uiState.value
        val book = state.selectedBook
        if (book == null) {
            _uiState.update { it.copy(statusMessage = "Please select an eBook to convert.") }
            return
        }
        if (state.selectedChapterIndices.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Select at least one chapter to convert.") }
            return
        }

        val sortedChapters = state.selectedChapterIndices.sorted()
        val jobId = UUID.randomUUID().toString().take(8)
        val spec = BatchAudiobookConversionSpec(
            jobId = jobId,
            bookId = book.id,
            chapterIndices = sortedChapters,
            engineType = state.selectedEngine,
            voiceId = state.selectedVoice.id,
            speakerId = state.selectedVoice.speakerIndex,
            speed = state.synthesisSpeed,
            exportFormat = state.exportFormat,
            generateLrcSidecar = state.generateLrcSidecar,
            generateJsonSyncSidecar = state.generateJsonSyncSidecar,
            embedChapterMarkersAndCover = state.embedChapterMarkers
        )

        exportManager.enqueueConversionJob(spec)
        _uiState.update {
            it.copy(
                statusMessage = "Queued ${sortedChapters.size} chapter(s) of \"${book.title}\" (${state.selectedVoice.name} • .${state.exportFormat.extension})"
            )
        }
    }

    fun cancelConversionJob(jobId: String) {
        exportManager.cancelConversionJob(jobId)
    }

    fun importKokoroModelArchive(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Importing Kokoro-82M ONNX model archive...") }
            localKokoroProvider.installBundledOrDownloadedModel(uriString)
                .onSuccess { cfg ->
                    _uiState.update {
                        it.copy(statusMessage = "Kokoro-82M model installed at ${File(cfg.modelDir).name}")
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(statusMessage = "Model import failed: ${err.message}")
                    }
                }
        }
    }

    private suspend fun playPreviewPcmSamples(samples: FloatArray, sampleRateHz: Int) =
        withContext(Dispatchers.IO) {
            if (samples.isEmpty()) return@withContext
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT
            ).coerceAtLeast(sampleRateHz * 2)

            val track = AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBuf)
                .build()

            previewAudioTrack = track
            track.play()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.stop()
            track.release()
            if (previewAudioTrack === track) {
                previewAudioTrack = null
            }
        }

    override fun onCleared() {
        super.onCleared()
        stopVoicePreview()
    }
}
