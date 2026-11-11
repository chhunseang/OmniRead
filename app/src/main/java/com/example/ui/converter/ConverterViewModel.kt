package com.example.ui.converter

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.TtsEngineType
import com.example.data.tts.BatchAudiobookExportManagerImpl
import com.example.data.tts.CloudApiTtsProviderImpl
import com.example.data.tts.LocalKokoroTtsProviderImpl
import com.example.data.tts.ReadAloudCoordinator
import com.example.domain.reader.SentenceLocator
import com.example.domain.tts.AudioExportFormat
import com.example.domain.tts.BatchAudiobookConversionSpec
import com.example.domain.tts.BatchConversionProgress
import com.example.domain.tts.CloudApiTtsProvider
import com.example.domain.tts.KokoroModelState
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
    val cloudBaseUrl: String = "https://api.openai.com/v1/",
    val cloudApiKey: String = "",
    val cloudModelId: String = "tts-1-hd",
    val kokoroModel: KokoroModelState = KokoroModelState(),
    val isPreviewPlaying: Boolean = false,
    val previewingVoiceId: String? = null,
    val previewSampleText: String = ConverterViewModel.DEFAULT_PREVIEW_EN,
    val conversionQueue: List<BatchConversionProgress> = emptyList(),
    val statusMessage: String? = null
) {
    /** Whether the currently selected engine can synthesize right now. */
    val engineReady: Boolean
        get() = when (selectedEngine) {
            TtsEngineType.LOCAL_KOKORO -> kokoroModel.installed
            else -> cloudApiKey.isNotBlank()
        }
}

/**
 * TTS Studio:
 *  1. choose the engine (on-device Kokoro, or a cloud API) and get it ready
 *     (download the Kokoro model once / enter an API key),
 *  2. choose voice + speed and preview it — this same voice is used by "Read aloud" in the Reader,
 *  3. pick a book & chapters and convert them into an .m4b/.wav audiobook (with LRC / JSON sync files).
 */
@HiltViewModel
class ConverterViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val localKokoroProvider: LocalKokoroTtsProvider,
    private val cloudApiProvider: CloudApiTtsProvider,
    private val exportManager: BatchAudiobookExportManagerImpl,
    private val readAloudCoordinator: ReadAloudCoordinator,
    private val ebookParserService: com.example.data.reader.EbookParserService,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {

    private val prefs = appContext.getSharedPreferences("tts_studio", android.content.Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()

    private var previewJob: Job? = null
    private var previewAudioTrack: AudioTrack? = null
    private var chaptersObserverJob: Job? = null
    private var chapterSyncJob: Job? = null
    private var modelJob: Job? = null

    init {
        observeLibraryBooks()
        observeCloudConfig()
        observeKokoroModel()
        observeConversionQueue()
        restoreSavedVoice()
    }

    /** Restores the engine / voice / speed chosen last time (also used by Read aloud). */
    private fun restoreSavedVoice() {
        val engine = prefs.getString(KEY_ENGINE, null)?.let { runCatching { TtsEngineType.valueOf(it) }.getOrNull() }
            ?: TtsEngineType.LOCAL_KOKORO
        val voiceId = prefs.getString(KEY_VOICE, null)
        val speed = prefs.getFloat(KEY_SPEED, 1.0f)
        _uiState.update { it.copy(synthesisSpeed = speed) }
        selectEngine(engine, preferredVoiceId = voiceId)
    }

    private fun saveVoiceChoice() {
        val s = _uiState.value
        prefs.edit()
            .putString(KEY_ENGINE, s.selectedEngine.name)
            .putString(KEY_VOICE, s.selectedVoice.id)
            .putFloat(KEY_SPEED, s.synthesisSpeed)
            .apply()
    }

    private fun observeLibraryBooks() {
        viewModelScope.launch {
            bookDao.observeAllBooks().collectLatest { allBooks ->
                val ebooks = allBooks.filter {
                    it.mediaType == MediaType.EBOOK || it.mediaType == MediaType.HYBRID_SYNCED
                }
                val currentSelected = _uiState.value.selectedBook
                val nextSelected = ebooks.firstOrNull { it.id == currentSelected?.id } ?: ebooks.firstOrNull()
                _uiState.update { it.copy(availableEbooks = ebooks, selectedBook = nextSelected) }
                if (nextSelected == null) {
                    chaptersObserverJob?.cancel()
                    _uiState.update { it.copy(chapters = emptyList(), selectedChapterIndices = emptySet()) }
                } else if (nextSelected.id != currentSelected?.id) {
                    loadChaptersForBook(nextSelected)
                }
            }
        }
    }

    private fun observeCloudConfig() {
        viewModelScope.launch {
            cloudApiProvider.cloudConfig.collectLatest { cfg ->
                _uiState.update { state ->
                    if (state.selectedEngine == TtsEngineType.LOCAL_KOKORO || state.selectedEngine == cfg.engineType) {
                        state.copy(cloudBaseUrl = cfg.baseUrl, cloudApiKey = cfg.apiKey, cloudModelId = cfg.modelId)
                    } else state
                }
            }
        }
    }

    private fun observeKokoroModel() {
        viewModelScope.launch {
            localKokoroProvider.modelState.collectLatest { model ->
                _uiState.update { it.copy(kokoroModel = model) }
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

    /** The Reader's "Read aloud" uses whatever engine / voice / speed is selected here. */
    private fun pushVoiceToReadAloud() {
        val s = _uiState.value
        readAloudCoordinator.setEngineAndVoice(s.selectedEngine, s.selectedVoice.id, s.selectedVoice.speakerIndex)
        readAloudCoordinator.setSpeed(s.synthesisSpeed)
    }

    // --- Book & chapters ---

    fun selectBook(book: BookEntity) {
        _uiState.update { it.copy(selectedBook = book, selectedChapterIndices = emptySet()) }
        loadChaptersForBook(book)
    }

    private fun loadChaptersForBook(book: BookEntity) {
        val bookId = book.id
        // Make sure the stored chapter list matches the book as it parses today, so what you
        // pick here is exactly what gets narrated.
        chapterSyncJob?.cancel()
        chapterSyncJob = viewModelScope.launch(Dispatchers.IO) {
            val parsed = runCatching { ebookParserService.parseEbookPackage(book).chapters }.getOrNull().orEmpty()
            if (parsed.isEmpty()) return@launch
            val stored = chapterDao.getChaptersForBook(bookId)
            if (stored.map { it.href to it.title } == parsed.map { it.href to it.title }) return@launch
            _uiState.update { if (it.selectedBook?.id == bookId) it.copy(selectedChapterIndices = emptySet()) else it }
            ebookParserService.syncStoredChapters(book, parsed)
        }
        chaptersObserverJob?.cancel()
        chaptersObserverJob = viewModelScope.launch {
            chapterDao.observeChaptersForBook(bookId).collectLatest { chapterList ->
                _uiState.update { state ->
                    val keep = state.selectedChapterIndices.filter { idx -> chapterList.any { it.chapterIndex == idx } }.toSet()
                    state.copy(
                        chapters = chapterList,
                        selectedChapterIndices = keep.ifEmpty { storyChapterIndices(chapterList) }
                    )
                }
            }
        }
    }

    fun toggleChapterSelection(chapterIndex: Int) {
        _uiState.update { state ->
            val updated = state.selectedChapterIndices.toMutableSet()
            if (!updated.add(chapterIndex)) updated.remove(chapterIndex)
            state.copy(selectedChapterIndices = updated)
        }
    }

    fun selectAllChapters() {
        _uiState.update { state -> state.copy(selectedChapterIndices = state.chapters.map { it.chapterIndex }.toSet()) }
    }

    fun clearChapterSelection() {
        _uiState.update { it.copy(selectedChapterIndices = emptySet()) }
    }

    /** Only chapters with real text: skips covers, picture pages, copyright and contents pages. */
    fun selectStoryChapters() {
        _uiState.update { state -> state.copy(selectedChapterIndices = storyChapterIndices(state.chapters)) }
    }

    private fun storyChapterIndices(chapters: List<ChapterEntity>): Set<Int> {
        val story = chapters.filter { it.wordCount >= STORY_MIN_WORDS && !it.title.looksLikeFrontMatter() }
        return (story.ifEmpty { chapters }).map { it.chapterIndex }.toSet()
    }

    private fun String.looksLikeFrontMatter(): Boolean {
        val t = lowercase().trim()
        return FRONT_MATTER.any { t == it || t.startsWith("$it ") || t.startsWith("$it:") }
    }

    /** Removes a finished, failed or cancelled job from the list. */
    fun dismissConversionJob(jobId: String) {
        exportManager.removeJob(jobId)
    }

    // --- Engine & voice ---

    fun selectEngine(engineType: TtsEngineType, preferredVoiceId: String? = null) {
        stopVoicePreview()
        val voices = voicesFor(engineType)
        val defaultVoice = voices.firstOrNull { it.id == preferredVoiceId }
            ?: voices.firstOrNull { it.id == "af_heart" } ?: voices.first()
        _uiState.update { it.copy(selectedEngine = engineType, availableVoices = voices, selectedVoice = defaultVoice) }

        if (engineType != TtsEngineType.LOCAL_KOKORO) {
            viewModelScope.launch {
                val current = cloudApiProvider.cloudConfig.value
                // Remember the key of the previously used cloud engine, restore the key for this one
                val keys = current.apiKeysByEngine.toMutableMap()
                if (current.apiKey.isNotBlank()) keys[current.engineType.name] = current.apiKey
                val (defaultBase, defaultModel) = cloudDefaults(engineType)
                val sameEngine = current.engineType == engineType
                val updated = current.copy(
                    engineType = engineType,
                    baseUrl = if (sameEngine) current.baseUrl else defaultBase,
                    modelId = if (sameEngine) current.modelId else defaultModel,
                    apiKey = keys[engineType.name].orEmpty(),
                    voiceId = defaultVoice.id,
                    responseFormat = "pcm",
                    sampleRateHz = 24000,
                    apiKeysByEngine = keys
                )
                cloudApiProvider.updateCloudConfig(updated)
                _uiState.update {
                    it.copy(cloudBaseUrl = updated.baseUrl, cloudApiKey = updated.apiKey, cloudModelId = updated.modelId)
                }
            }
        }
        pushVoiceToReadAloud()
        saveVoiceChoice()
    }

    private fun voicesFor(engineType: TtsEngineType): List<TtsVoiceProfile> = when (engineType) {
        TtsEngineType.LOCAL_KOKORO -> LocalKokoroTtsProviderImpl.KOKORO_V1_VOICE_CATALOG
        TtsEngineType.CLOUD_OPENAI -> CloudApiTtsProviderImpl.OPENAI_COMPATIBLE_VOICES
        TtsEngineType.CLOUD_ELEVENLABS -> CloudApiTtsProviderImpl.ELEVENLABS_DEFAULT_VOICES
        TtsEngineType.CLOUD_GEMINI -> CloudApiTtsProviderImpl.GEMINI_AUDIO_VOICES
    }

    private fun cloudDefaults(engineType: TtsEngineType): Pair<String, String> = when (engineType) {
        TtsEngineType.CLOUD_ELEVENLABS -> "https://api.elevenlabs.io/v1" to "eleven_multilingual_v2"
        TtsEngineType.CLOUD_GEMINI -> "https://generativelanguage.googleapis.com/v1beta" to "gemini-2.5-flash-preview-tts"
        else -> "https://api.openai.com/v1/" to "tts-1-hd"
    }

    fun selectVoice(voice: TtsVoiceProfile) {
        _uiState.update { it.copy(selectedVoice = voice) }
        pushVoiceToReadAloud()
        saveVoiceChoice()
    }

    fun setSynthesisSpeed(speed: Float) {
        _uiState.update { it.copy(synthesisSpeed = speed.coerceIn(0.5f, 2.5f)) }
        readAloudCoordinator.setSpeed(_uiState.value.synthesisSpeed)
        saveVoiceChoice()
    }

    fun updateCloudEndpointSettings(baseUrl: String, apiKey: String, modelId: String) {
        _uiState.update { it.copy(cloudBaseUrl = baseUrl, cloudApiKey = apiKey, cloudModelId = modelId) }
        viewModelScope.launch {
            val state = _uiState.value
            val currentCfg = cloudApiProvider.cloudConfig.value
            cloudApiProvider.updateCloudConfig(
                currentCfg.copy(
                    engineType = state.selectedEngine,
                    baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(),
                    modelId = modelId.trim(),
                    voiceId = state.selectedVoice.id,
                    apiKeysByEngine = currentCfg.apiKeysByEngine + (state.selectedEngine.name to apiKey.trim())
                )
            )
        }
    }

    // --- Kokoro model management ---

    fun downloadKokoroModel() {
        if (modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            localKokoroProvider.downloadAndInstallModel()
                .onSuccess { _uiState.update { it.copy(statusMessage = "Kokoro voice model installed – ready to speak.") } }
                .onFailure { err -> _uiState.update { it.copy(statusMessage = "Model download failed: ${err.message}") } }
        }
    }

    fun cancelKokoroDownload() {
        modelJob?.cancel()
    }

    fun importKokoroModelArchive(uriString: String) {
        if (modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            localKokoroProvider.installBundledOrDownloadedModel(uriString)
                .onSuccess { _uiState.update { it.copy(statusMessage = "Kokoro voice model installed.") } }
                .onFailure { err -> _uiState.update { it.copy(statusMessage = "Model import failed: ${err.message}") } }
        }
    }

    fun deleteKokoroModel() {
        viewModelScope.launch {
            localKokoroProvider.deleteInstalledModel()
            _uiState.update { it.copy(statusMessage = "Kokoro voice model removed.") }
        }
    }

    // --- Preview ---

    fun updatePreviewSampleText(text: String) {
        _uiState.update { it.copy(previewSampleText = text) }
    }

    private fun readinessProblem(state: ConverterUiState): String? = when {
        state.selectedEngine == TtsEngineType.LOCAL_KOKORO && !state.kokoroModel.installed ->
            "Download the Kokoro voice model first (step 1)."
        state.selectedEngine != TtsEngineType.LOCAL_KOKORO && state.cloudApiKey.isBlank() ->
            "Enter your API key first (step 1)."
        else -> null
    }

    fun previewSpeakerVoice(voice: TtsVoiceProfile = _uiState.value.selectedVoice) {
        if (_uiState.value.isPreviewPlaying && _uiState.value.previewingVoiceId == voice.id) {
            stopVoicePreview()
            return
        }
        stopVoicePreview()
        selectVoice(voice)
        readinessProblem(_uiState.value)?.let { problem ->
            _uiState.update { it.copy(statusMessage = problem) }
            return
        }
        _uiState.update {
            it.copy(
                isPreviewPlaying = true,
                previewingVoiceId = voice.id,
                statusMessage = if (it.selectedEngine == TtsEngineType.LOCAL_KOKORO) {
                    "Generating preview with ${voice.name}… (the first run loads the model, ~5 s)"
                } else {
                    "Requesting preview from the cloud…"
                }
            )
        }

        previewJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val state = _uiState.value
                val text = state.previewSampleText.ifBlank { "Hello from OmniRead." }
                val request = TtsSynthesisRequest(
                    requestId = "preview_${System.currentTimeMillis()}",
                    sentenceLocator = SentenceLocator(
                        sentenceId = "preview_${voice.id}",
                        bookId = "preview",
                        chapterIndex = 0,
                        chapterHref = "preview",
                        sentenceIndex = 0,
                        text = text,
                        startCharOffset = 0,
                        endCharOffset = text.length,
                        paragraphIndex = 0
                    ),
                    text = text,
                    engineType = state.selectedEngine,
                    voiceId = voice.id,
                    speakerId = voice.speakerIndex,
                    speed = state.synthesisSpeed
                )
                val provider = when (state.selectedEngine) {
                    TtsEngineType.LOCAL_KOKORO -> localKokoroProvider
                    else -> cloudApiProvider
                }
                provider.initialize().getOrThrow()
                val chunk = provider.synthesizeSentence(request).getOrThrow()
                _uiState.update { it.copy(statusMessage = "Playing preview (${voice.name})") }
                playPreviewPcmSamples(chunk.pcmFloatSamples, chunk.sampleRateHz)
                _uiState.update { it.copy(isPreviewPlaying = false, previewingVoiceId = null, statusMessage = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isPreviewPlaying = false, previewingVoiceId = null, statusMessage = "Preview failed: ${e.message}")
                }
            }
        }
    }

    fun stopVoicePreview() {
        previewJob?.cancel()
        previewJob = null
        localKokoroProvider.cancelActiveSynthesis()
        runCatching {
            previewAudioTrack?.pause()
            previewAudioTrack?.flush()
            previewAudioTrack?.stop()
            previewAudioTrack?.release()
        }
        previewAudioTrack = null
        _uiState.update { it.copy(isPreviewPlaying = false, previewingVoiceId = null) }
    }

    // --- Conversion ---

    fun setExportFormat(format: AudioExportFormat) {
        _uiState.update { it.copy(exportFormat = format) }
    }

    fun toggleGenerateLrcSidecar(enabled: Boolean) {
        _uiState.update { it.copy(generateLrcSidecar = enabled) }
    }

    fun toggleGenerateJsonSidecar(enabled: Boolean) {
        _uiState.update { it.copy(generateJsonSyncSidecar = enabled) }
    }

    fun enqueueBatchAudiobookConversion() {
        val state = _uiState.value
        readinessProblem(state)?.let { problem ->
            _uiState.update { it.copy(statusMessage = problem) }
            return
        }
        val book = state.selectedBook
        if (book == null) {
            _uiState.update { it.copy(statusMessage = "Choose a book to convert (step 3).") }
            return
        }
        if (state.selectedChapterIndices.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Select at least one chapter to convert.") }
            return
        }

        val sortedChapters = state.selectedChapterIndices.sorted()
        val spec = BatchAudiobookConversionSpec(
            jobId = UUID.randomUUID().toString().take(8),
            bookId = book.id,
            chapterIndices = sortedChapters,
            engineType = state.selectedEngine,
            voiceId = state.selectedVoice.id,
            speakerId = state.selectedVoice.speakerIndex,
            speed = state.synthesisSpeed,
            exportFormat = state.exportFormat,
            generateLrcSidecar = state.generateLrcSidecar,
            generateJsonSyncSidecar = state.generateJsonSyncSidecar,
            embedChapterMarkersAndCover = false
        )
        exportManager.enqueueConversionJob(spec)
        _uiState.update {
            it.copy(
                statusMessage = "Started converting ${sortedChapters.size} chapter(s) of \"${book.title}\" " +
                    "with ${state.selectedVoice.name}. Progress is shown below and in the notification."
            )
        }
    }

    fun cancelConversionJob(jobId: String) {
        exportManager.cancelConversionJob(jobId)
    }

    fun dismissStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    private suspend fun playPreviewPcmSamples(samples: FloatArray, sampleRateHz: Int) = withContext(Dispatchers.IO) {
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
        // Wait for the buffered tail to finish before releasing
        val totalFrames = samples.size
        var waited = 0
        while (waited < 3000 && runCatching { track.playbackHeadPosition }.getOrDefault(totalFrames) < totalFrames) {
            kotlinx.coroutines.delay(30)
            waited += 30
        }
        runCatching {
            track.stop()
            track.release()
        }
        if (previewAudioTrack === track) previewAudioTrack = null
    }

    override fun onCleared() {
        super.onCleared()
        stopVoicePreview()
    }

    companion object {
        private const val KEY_ENGINE = "engine"
        private const val KEY_VOICE = "voice"
        private const val KEY_SPEED = "speed"
        private const val STORY_MIN_WORDS = 30
        private val FRONT_MATTER = listOf(
            "table of contents", "contents", "copyright", "front cover", "full cover", "back cover",
            "cover", "title page", "image gallery", "illustrations"
        )
        const val DEFAULT_PREVIEW_EN = "The quick brown fox jumps over the lazy dog. This is how your books will sound."
    }
}
