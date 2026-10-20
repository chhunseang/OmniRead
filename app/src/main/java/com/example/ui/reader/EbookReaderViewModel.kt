package com.example.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.HighlightDao
import com.example.data.local.dao.ReadingSessionDao
import com.example.data.local.entity.AnnotationStyle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.ReadingSessionEntity
import com.example.data.local.entity.TtsEngineType
import com.example.data.reader.ReaderPreferencesRepository
import com.example.data.tts.LocalKokoroTtsProviderImpl
import com.example.data.tts.ReadAloudCoordinator
import com.example.data.tts.ReadAloudUiEvent
import com.example.domain.reader.DictionaryDefinitionResult
import com.example.domain.reader.PageTurnMode
import com.example.domain.reader.ReaderPreferences
import com.example.domain.reader.ReaderSearchResult
import com.example.domain.reader.ReaderThemePreset
import com.example.domain.reader.ReaderViewportState
import com.example.domain.reader.SentenceLocator
import com.example.domain.reader.TapZoneAction
import com.example.domain.reader.UnifiedEbookReaderEngine
import com.example.domain.tts.LiveTtsReadAloudStatus
import com.example.domain.tts.LiveTtsSessionState
import com.example.domain.tts.TtsVoiceProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

enum class ReaderBottomSheetTab {
    NONE,
    TOC_AND_BOOKMARKS,
    THEMES_AND_TYPOGRAPHY,
    TAP_GRID_AND_GESTURES,
    SEARCH_IN_BOOK,
    LIVE_TTS_READ_ALOUD
}

data class PendingSentenceAnnotationState(
    val sentenceLocator: SentenceLocator,
    val selectedColorArgb: Long = 0xFFFFEB3B,
    val selectedStyle: AnnotationStyle = AnnotationStyle.HIGHLIGHT,
    val noteMarkdown: String = "",
    val dictionaryResult: DictionaryDefinitionResult? = null
)

data class EbookReaderUiOverlayState(
    val isChromeVisible: Boolean = true,
    val activeBottomSheetTab: ReaderBottomSheetTab = ReaderBottomSheetTab.NONE,
    val showTapGridVisualGuide: Boolean = false,
    val screenBrightnessLevel: Float = 0.78f,
    val audioVolumeLevel: Float = 0.75f,
    val activeGestureHudLabel: String? = null,
    val searchQuery: String = "",
    val searchUseRegex: Boolean = false,
    val searchCaseSensitive: Boolean = false,
    val searchResults: List<ReaderSearchResult> = emptyList(),
    val pendingAnnotation: PendingSentenceAnnotationState? = null,
    val availableVoices: List<TtsVoiceProfile> = LocalKokoroTtsProviderImpl.KOKORO_V1_VOICE_CATALOG,
    val exportedAnnotationsJsonPreview: String? = null,
    val statusBannerMessage: String? = null
)

@HiltViewModel
class EbookReaderViewModel @Inject constructor(
    private val readerEngine: UnifiedEbookReaderEngine,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val readAloudCoordinator: ReadAloudCoordinator,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao,
    private val readingSessionDao: ReadingSessionDao
) : ViewModel() {

    val viewportState: StateFlow<ReaderViewportState> = readerEngine.viewportState

    val readerPreferences: StateFlow<ReaderPreferences> = readerEngine.readerPreferences

    val ttsSessionState: StateFlow<LiveTtsSessionState> = readAloudCoordinator.sessionState

    val activeTtsSentence: StateFlow<SentenceLocator?> = readAloudCoordinator.activeSentenceFlow

    private val _overlayState = MutableStateFlow(EbookReaderUiOverlayState())
    val overlayState: StateFlow<EbookReaderUiOverlayState> = _overlayState.asStateFlow()

    val availableBooks: StateFlow<List<BookEntity>> = bookDao.observeAllBooks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private var sessionStartTimestampMs: Long = System.currentTimeMillis()
    private var sessionStartProgression: Float = 0f

    init {
        ensureDefaultBookOpened()
        observeReadAloudCoordinatorEvents()
    }

    private fun ensureDefaultBookOpened() {
        viewModelScope.launch(Dispatchers.IO) {
            val sampleBookId = "omniread_starter_odyssey"
            var existing = bookDao.getBookById(sampleBookId)
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
                existing = starterBook
            }
            openBook(existing.id)
        }
    }

    private fun observeReadAloudCoordinatorEvents() {
        viewModelScope.launch {
            readAloudCoordinator.uiEvents.collectLatest { event ->
                when (event) {
                    is ReadAloudUiEvent.HighlightSentenceChanged -> {
                        if (event.shouldAutoAdvanceViewport) {
                            readerEngine.navigateToSentence(event.sentenceLocator)
                        }
                    }

                    is ReadAloudUiEvent.ChapterCompleted -> {
                        val totalChapters = viewportState.value.chapters.size
                        if (event.nextChapterIndex < totalChapters) {
                            readerEngine.loadChapter(event.nextChapterIndex, 0).onSuccess { nextDoc ->
                                readAloudCoordinator.startReadAloud(
                                    bookId = event.bookId,
                                    chapterIndex = event.nextChapterIndex,
                                    sentences = nextDoc.sentences,
                                    startSentenceIndex = 0
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun openBook(bookId: String) {
        viewModelScope.launch {
            recordCurrentReadingSessionIfValid()
            readerEngine.openPublication(bookId).onSuccess { book ->
                sessionStartTimestampMs = System.currentTimeMillis()
                sessionStartProgression = book.progressionPercentage
            }
        }
    }

    fun loadChapter(chapterIndex: Int) {
        viewModelScope.launch {
            val wasPlayingTts = ttsSessionState.value.status == LiveTtsReadAloudStatus.PLAYING ||
                ttsSessionState.value.status == LiveTtsReadAloudStatus.PRE_BUFFERING
            if (wasPlayingTts) {
                readAloudCoordinator.stopReadAloud()
            }
            readerEngine.loadChapter(chapterIndex, 0).onSuccess { doc ->
                if (wasPlayingTts && doc.sentences.isNotEmpty()) {
                    readAloudCoordinator.startReadAloud(
                        bookId = doc.bookId,
                        chapterIndex = chapterIndex,
                        sentences = doc.sentences,
                        startSentenceIndex = 0
                    )
                }
            }
        }
    }

    fun nextPage() {
        viewModelScope.launch {
            readerEngine.navigateToNextPage()
        }
    }

    fun previousPage() {
        viewModelScope.launch {
            readerEngine.navigateToPreviousPage()
        }
    }

    fun updateVisiblePageCharBounds(startCharOffset: Int, endCharOffset: Int) {
        readAloudCoordinator.updateVisibleViewportCharBounds(startCharOffset, endCharOffset)
    }

    /**
     * Dispatches any of the 9-zone tap grid actions configured by the user.
     */
    fun onTapZoneTriggered(row: Int, col: Int) {
        val action = readerPreferences.value.tapZoneGrid.actionForZone(row, col)
        executeTapZoneAction(action)
    }

    fun executeTapZoneAction(action: TapZoneAction) {
        when (action) {
            TapZoneAction.NONE -> Unit
            TapZoneAction.NEXT_PAGE -> nextPage()
            TapZoneAction.PREVIOUS_PAGE -> previousPage()
            TapZoneAction.TOGGLE_CHROME_TOOLBAR -> {
                _overlayState.update {
                    it.copy(
                        isChromeVisible = !it.isChromeVisible,
                        activeBottomSheetTab = ReaderBottomSheetTab.NONE
                    )
                }
            }
            TapZoneAction.TOGGLE_AUTO_SCROLL -> {
                val prefs = readerPreferences.value
                setAutoScroll(!prefs.autoScrollEnabled, prefs.autoScrollSpeedPixelsPerSec)
            }
            TapZoneAction.TOGGLE_TTS_READ_ALOUD -> {
                toggleLiveTtsReadAloud()
            }
            TapZoneAction.ADD_BOOKMARK -> {
                addBookmarkAtCurrentViewport()
            }
            TapZoneAction.OPEN_TOC -> {
                _overlayState.update {
                    it.copy(
                        isChromeVisible = true,
                        activeBottomSheetTab = ReaderBottomSheetTab.TOC_AND_BOOKMARKS
                    )
                }
            }
            TapZoneAction.OPEN_SETTINGS -> {
                _overlayState.update {
                    it.copy(
                        isChromeVisible = true,
                        activeBottomSheetTab = ReaderBottomSheetTab.THEMES_AND_TYPOGRAPHY
                    )
                }
            }
        }
    }

    fun selectBottomSheetTab(tab: ReaderBottomSheetTab) {
        _overlayState.update { current ->
            val nextTab = if (current.activeBottomSheetTab == tab) ReaderBottomSheetTab.NONE else tab
            current.copy(activeBottomSheetTab = nextTab, isChromeVisible = true)
        }
    }

    fun toggleTapGridVisualGuide() {
        _overlayState.update { it.copy(showTapGridVisualGuide = !it.showTapGridVisualGuide) }
    }

    fun adjustBrightnessByDelta(deltaFraction: Float) {
        if (!readerPreferences.value.leftEdgeBrightnessSwipe) return
        _overlayState.update { state ->
            val next = (state.screenBrightnessLevel + deltaFraction).coerceIn(0.05f, 1.0f)
            state.copy(
                screenBrightnessLevel = next,
                activeGestureHudLabel = "Brightness: ${(next * 100).toInt()}%"
            )
        }
    }

    fun adjustVolumeByDelta(deltaFraction: Float) {
        if (!readerPreferences.value.rightEdgeVolumeSwipe) return
        _overlayState.update { state ->
            val next = (state.audioVolumeLevel + deltaFraction).coerceIn(0.0f, 1.0f)
            state.copy(
                audioVolumeLevel = next,
                activeGestureHudLabel = "Volume: ${(next * 100).toInt()}%"
            )
        }
    }

    fun clearGestureHudLabel() {
        _overlayState.update { it.copy(activeGestureHudLabel = null) }
    }

    // --- Live TTS Read-Aloud & Tap-to-Speak ---

    fun toggleLiveTtsReadAloud() {
        val status = ttsSessionState.value.status
        when (status) {
            LiveTtsReadAloudStatus.PLAYING,
            LiveTtsReadAloudStatus.PRE_BUFFERING -> readAloudCoordinator.pauseReadAloud()

            LiveTtsReadAloudStatus.PAUSED -> readAloudCoordinator.resumeReadAloud()

            else -> {
                val doc = viewportState.value.activeChapterDocument ?: return
                val currentChar = viewportState.value.currentCharOffset
                val startIdx = doc.sentences.indexOfFirst {
                    it.endCharOffset >= currentChar
                }.takeIf { it >= 0 } ?: 0

                viewModelScope.launch {
                    readAloudCoordinator.startReadAloud(
                        bookId = doc.bookId,
                        chapterIndex = doc.chapterIndex,
                        sentences = doc.sentences,
                        startSentenceIndex = startIdx
                    )
                }
            }
        }
    }

    fun stopLiveTtsReadAloud() {
        readAloudCoordinator.stopReadAloud()
    }

    fun onSentenceTappedInReader(sentence: SentenceLocator) {
        val ttsActive = ttsSessionState.value.status != LiveTtsReadAloudStatus.IDLE
        if (ttsActive) {
            viewModelScope.launch {
                readAloudCoordinator.jumpToSentence(sentence)
            }
        } else {
            openSentenceAnnotationPopup(sentence)
        }
    }

    fun startTtsFromSentence(sentence: SentenceLocator) {
        val doc = viewportState.value.activeChapterDocument ?: return
        _overlayState.update { it.copy(pendingAnnotation = null) }
        viewModelScope.launch {
            readAloudCoordinator.startReadAloud(
                bookId = doc.bookId,
                chapterIndex = doc.chapterIndex,
                sentences = doc.sentences,
                startSentenceIndex = sentence.sentenceIndex
            )
        }
    }

    fun skipTtsNextSentence() {
        viewModelScope.launch { readAloudCoordinator.skipToNextSentence() }
    }

    fun skipTtsPreviousSentence() {
        viewModelScope.launch { readAloudCoordinator.skipToPreviousSentence() }
    }

    fun setTtsVoiceAndEngine(engine: TtsEngineType, voice: TtsVoiceProfile) {
        readAloudCoordinator.setEngineAndVoice(engine, voice.id, voice.speakerIndex)
    }

    fun setTtsSpeed(speed: Float) {
        readAloudCoordinator.setSpeed(speed)
    }

    // --- Annotations, Highlights, Bookmarks & Dictionary ---

    fun openSentenceAnnotationPopup(sentence: SentenceLocator) {
        val firstWord = sentence.text.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        viewModelScope.launch {
            val dict = readerEngine.lookupDictionaryTerm(firstWord).getOrNull()
            _overlayState.update {
                it.copy(
                    pendingAnnotation = PendingSentenceAnnotationState(
                        sentenceLocator = sentence,
                        dictionaryResult = dict
                    )
                )
            }
        }
    }

    fun dismissSentenceAnnotationPopup() {
        _overlayState.update { it.copy(pendingAnnotation = null) }
    }

    fun updatePendingAnnotationDraft(
        colorArgb: Long? = null,
        style: AnnotationStyle? = null,
        noteMarkdown: String? = null
    ) {
        _overlayState.update { state ->
            val current = state.pendingAnnotation ?: return@update state
            state.copy(
                pendingAnnotation = current.copy(
                    selectedColorArgb = colorArgb ?: current.selectedColorArgb,
                    selectedStyle = style ?: current.selectedStyle,
                    noteMarkdown = noteMarkdown ?: current.noteMarkdown
                )
            )
        }
    }

    fun lookupWordInPendingPopup(word: String) {
        viewModelScope.launch {
            val result = readerEngine.lookupDictionaryTerm(word).getOrNull()
            _overlayState.update { state ->
                val current = state.pendingAnnotation ?: return@update state
                state.copy(pendingAnnotation = current.copy(dictionaryResult = result))
            }
        }
    }

    fun savePendingHighlightAndNote() {
        val draft = _overlayState.value.pendingAnnotation ?: return
        val loc = draft.sentenceLocator
        viewModelScope.launch {
            readerEngine.addHighlight(
                chapterIndex = loc.chapterIndex,
                startCharOffset = loc.startCharOffset,
                endCharOffset = loc.endCharOffset,
                selectedText = loc.text,
                colorArgb = draft.selectedColorArgb,
                style = draft.selectedStyle,
                noteMarkdown = draft.noteMarkdown
            ).onSuccess {
                _overlayState.update {
                    it.copy(
                        pendingAnnotation = null,
                        statusBannerMessage = "Saved ${draft.selectedStyle.name.lowercase()} annotation"
                    )
                }
            }
        }
    }

    fun deleteHighlight(highlightId: String) {
        viewModelScope.launch {
            highlightDao.deleteHighlightById(highlightId)
            val bookId = viewportState.value.book?.id ?: return@launch
            readerEngine.loadChapter(viewportState.value.currentChapterIndex, viewportState.value.currentCharOffset)
        }
    }

    fun addBookmarkAtCurrentViewport(noteMarkdown: String? = null) {
        val state = viewportState.value
        val doc = state.activeChapterDocument ?: return
        val activeSentence = doc.sentences.firstOrNull {
            it.endCharOffset >= state.currentCharOffset
        } ?: doc.sentences.firstOrNull()

        viewModelScope.launch {
            readerEngine.addBookmark(
                chapterIndex = state.currentChapterIndex,
                charOffset = state.currentCharOffset,
                snippetText = activeSentence?.text ?: doc.chapterTitle,
                noteMarkdown = noteMarkdown,
                label = "${doc.chapterTitle} • Page ${state.currentPageInChapter + 1}"
            ).onSuccess {
                _overlayState.update {
                    it.copy(statusBannerMessage = "Bookmark added at Page ${state.currentPageInChapter + 1}")
                }
            }
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            bookmarkDao.deleteBookmarkById(bookmarkId)
            readerEngine.loadChapter(viewportState.value.currentChapterIndex, viewportState.value.currentCharOffset)
        }
    }

    fun exportAllBookAnnotations() {
        val bookId = viewportState.value.book?.id ?: return
        viewModelScope.launch {
            readerEngine.exportAnnotationsJson(bookId).onSuccess { exportedJson ->
                _overlayState.update {
                    it.copy(
                        exportedAnnotationsJsonPreview = exportedJson,
                        statusBannerMessage = "Exported annotations bundle (${exportedJson.length} bytes)"
                    )
                }
            }
        }
    }

    // --- Full-Text Regex Search ---

    fun updateSearchQuery(
        query: String,
        useRegex: Boolean = _overlayState.value.searchUseRegex,
        caseSensitive: Boolean = _overlayState.value.searchCaseSensitive
    ) {
        _overlayState.update {
            it.copy(
                searchQuery = query,
                searchUseRegex = useRegex,
                searchCaseSensitive = caseSensitive
            )
        }
        val bookId = viewportState.value.book?.id ?: return
        viewModelScope.launch {
            readerEngine.searchInBook(
                bookId = bookId,
                query = query,
                useRegex = useRegex,
                caseSensitive = caseSensitive
            ).onSuccess { matches ->
                _overlayState.update { it.copy(searchResults = matches) }
            }
        }
    }

    fun jumpToSearchResult(result: ReaderSearchResult) {
        _overlayState.update { it.copy(activeBottomSheetTab = ReaderBottomSheetTab.NONE) }
        viewModelScope.launch {
            readerEngine.loadChapter(result.chapterIndex, result.startCharOffset)
        }
    }

    // --- Visual Customization & Typography ---

    fun selectThemePreset(preset: ReaderThemePreset) {
        viewModelScope.launch {
            preferencesRepository.setThemePreset(preset)
        }
    }

    fun setBlueLightFilter(enabled: Boolean, intensity: Float) {
        viewModelScope.launch {
            preferencesRepository.setBlueLightFilter(enabled, intensity)
        }
    }

    fun updateTypography(
        fontFamilyName: String? = null,
        fontSizeSp: Float? = null,
        fontWeight: Int? = null,
        lineSpacingMultiplier: Float? = null,
        paragraphSpacingDp: Float? = null,
        wordSpacingSp: Float? = null,
        fullJustification: Boolean? = null,
        hyphenationEnabled: Boolean? = null
    ) {
        viewModelScope.launch {
            readerEngine.updateReaderPreferences { current ->
                current.copy(
                    customFontFamilyName = fontFamilyName ?: current.customFontFamilyName,
                    fontSizeSp = (fontSizeSp ?: current.fontSizeSp).coerceIn(11f, 42f),
                    fontWeight = (fontWeight ?: current.fontWeight).coerceIn(300, 800),
                    lineSpacingMultiplier = (lineSpacingMultiplier ?: current.lineSpacingMultiplier).coerceIn(1.0f, 2.5f),
                    paragraphSpacingDp = (paragraphSpacingDp ?: current.paragraphSpacingDp).coerceIn(0f, 36f),
                    wordSpacingSp = (wordSpacingSp ?: current.wordSpacingSp).coerceIn(0f, 6f),
                    fullJustification = fullJustification ?: current.fullJustification,
                    hyphenationEnabled = hyphenationEnabled ?: current.hyphenationEnabled
                )
            }
        }
    }

    fun updatePageMargins(horizontalDp: Float, verticalDp: Float) {
        viewModelScope.launch {
            preferencesRepository.setMargins(
                leftDp = horizontalDp,
                rightDp = horizontalDp,
                topDp = verticalDp,
                bottomDp = verticalDp
            )
        }
    }

    fun setPageTurnMode(mode: PageTurnMode) {
        viewModelScope.launch {
            preferencesRepository.setPageTurnMode(mode)
        }
    }

    fun setAutoScroll(enabled: Boolean, speedPixelsPerSec: Float) {
        viewModelScope.launch {
            preferencesRepository.setAutoScroll(enabled, speedPixelsPerSec)
            if (enabled && readerPreferences.value.pageTurnMode != PageTurnMode.VERTICAL_CONTINUOUS) {
                preferencesRepository.setPageTurnMode(PageTurnMode.VERTICAL_CONTINUOUS)
            }
        }
    }

    fun updateTapZoneCell(row: Int, col: Int, action: TapZoneAction) {
        viewModelScope.launch {
            preferencesRepository.updateTapZoneAction(row, col, action)
        }
    }

    fun importCustomFontFile(uriString: String, displayName: String) {
        viewModelScope.launch {
            preferencesRepository.importCustomFontFile(uriString, displayName)
                .onSuccess {
                    _overlayState.update {
                        it.copy(statusBannerMessage = "Loaded custom font: $displayName")
                    }
                }
        }
    }

    private suspend fun recordCurrentReadingSessionIfValid() {
        val currentBook = viewportState.value.book ?: return
        val now = System.currentTimeMillis()
        val elapsedMs = now - sessionStartTimestampMs
        if (elapsedMs < 5_000L) return

        val endProg = viewportState.value.overallProgression
        val progDelta = (endProg - sessionStartProgression).coerceAtLeast(0f)
        val estimatedWordsRead = max(25, (progDelta * currentBook.totalWords.coerceAtLeast(1500)).toInt())
        val minutes = (elapsedMs / 60_000.0).coerceAtLeast(0.2)
        val wpm = (estimatedWordsRead / minutes).toInt().coerceIn(80, 650)

        val session = ReadingSessionEntity(
            id = UUID.randomUUID().toString(),
            bookId = currentBook.id,
            mediaType = currentBook.mediaType,
            sessionDateEpochDay = now / (24L * 60L * 60L * 1000L),
            startedAt = sessionStartTimestampMs,
            endedAt = now,
            durationMs = elapsedMs,
            wordsRead = estimatedWordsRead,
            pagesRead = max(1, estimatedWordsRead / 250),
            averageWpm = wpm,
            startProgression = sessionStartProgression,
            endProgression = endProg,
            usedTtsReadAloud = ttsSessionState.value.status != LiveTtsReadAloudStatus.IDLE
        )
        readingSessionDao.insertSession(session)
    }

    override fun onCleared() {
        super.onCleared()
        readAloudCoordinator.stopReadAloud()
    }
}
