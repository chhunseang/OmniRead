package com.example.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.ReadingSessionDao
import com.example.data.local.entity.ReadingSessionEntity
import com.example.data.reader.ReaderPreferencesRepository
import com.example.data.tts.ReadAloudCoordinator
import com.example.data.tts.ReadAloudUiEvent
import com.example.domain.reader.ReaderPreferences
import com.example.domain.reader.ReaderThemePreset
import com.example.domain.reader.ReaderViewportState
import com.example.domain.reader.SentenceLocator
import com.example.domain.reader.UnifiedEbookReaderEngine
import com.example.domain.tts.LiveTtsReadAloudStatus
import com.example.domain.tts.LiveTtsSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max

enum class ReaderSheet { NONE, CONTENTS, SETTINGS }

data class ReaderUiState(
    val chromeVisible: Boolean = false,
    val sheet: ReaderSheet = ReaderSheet.NONE,
    val message: String? = null
)

/**
 * Reader screen state: opening books, chapter navigation, reading-position persistence,
 * display (typography / colour / brightness) preferences and the optional read-aloud
 * session whose voice & engine are configured in TTS Studio.
 */
@HiltViewModel
class EbookReaderViewModel @Inject constructor(
    private val readerEngine: UnifiedEbookReaderEngine,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val readAloudCoordinator: ReadAloudCoordinator,
    private val readingSessionDao: ReadingSessionDao
) : ViewModel() {

    val viewportState: StateFlow<ReaderViewportState> = readerEngine.viewportState
    val readerPreferences: StateFlow<ReaderPreferences> = readerEngine.readerPreferences
    val ttsSessionState: StateFlow<LiveTtsSessionState> = readAloudCoordinator.sessionState
    val activeTtsSentence: StateFlow<SentenceLocator?> = readAloudCoordinator.activeSentenceFlow

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var sessionStartTimestampMs: Long = System.currentTimeMillis()
    private var sessionStartProgression: Float = 0f
    private var positionSaveJob: Job? = null

    init {
        observeReadAloudEvents()
    }

    private fun observeReadAloudEvents() {
        viewModelScope.launch {
            readAloudCoordinator.uiEvents.collect { event ->
                when (event) {
                    is ReadAloudUiEvent.HighlightSentenceChanged -> Unit
                    is ReadAloudUiEvent.ChapterCompleted -> {
                        // Continue reading aloud into the next chapter
                        if (event.nextChapterIndex < viewportState.value.chapters.size) {
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

    // --- Book & chapter navigation ---

    fun openBook(bookId: String) {
        viewModelScope.launch {
            if (viewportState.value.book?.id == bookId && viewportState.value.activeChapterDocument != null) {
                return@launch
            }
            recordReadingSession()
            readAloudCoordinator.stopReadAloud()
            _uiState.update { ReaderUiState() }
            readerEngine.openPublication(bookId).onSuccess { book ->
                sessionStartTimestampMs = System.currentTimeMillis()
                sessionStartProgression = book.progressionPercentage
            }
        }
    }

    fun loadChapter(chapterIndex: Int) {
        _uiState.update { it.copy(sheet = ReaderSheet.NONE) }
        viewModelScope.launch {
            val wasReadingAloud = isReadAloudActive()
            if (wasReadingAloud) readAloudCoordinator.stopReadAloud()
            readerEngine.loadChapter(chapterIndex, 0).onSuccess { doc ->
                if (wasReadingAloud && doc.sentences.isNotEmpty()) {
                    readAloudCoordinator.startReadAloud(doc.bookId, chapterIndex, doc.sentences, 0)
                }
            }
        }
    }

    fun nextChapter() {
        val state = viewportState.value
        if (state.currentChapterIndex + 1 < state.chapters.size) loadChapter(state.currentChapterIndex + 1)
    }

    fun previousChapter() {
        val state = viewportState.value
        if (state.currentChapterIndex > 0) loadChapter(state.currentChapterIndex - 1)
    }

    /** Called (frequently) while scrolling; persisted with a short debounce. */
    fun onReadingPositionChanged(charOffset: Int) {
        positionSaveJob?.cancel()
        positionSaveJob = viewModelScope.launch {
            delay(600L)
            readerEngine.updateReadingCharOffset(charOffset)
        }
    }

    // --- Chrome & sheets ---

    fun toggleChrome() {
        _uiState.update { it.copy(chromeVisible = !it.chromeVisible, sheet = ReaderSheet.NONE) }
    }

    fun showSheet(sheet: ReaderSheet) {
        _uiState.update { it.copy(sheet = sheet) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // --- Display adjustments ---

    private fun updatePrefs(transform: (ReaderPreferences) -> ReaderPreferences) {
        viewModelScope.launch { preferencesRepository.updatePreferences(transform) }
    }

    fun selectTheme(preset: ReaderThemePreset) {
        viewModelScope.launch { preferencesRepository.setThemePreset(preset) }
    }

    fun setCustomColors(backgroundArgb: Long, textArgb: Long) {
        viewModelScope.launch { preferencesRepository.setCustomThemeColors(backgroundArgb, textArgb) }
    }

    fun setFontFamily(name: String) = updatePrefs { it.copy(customFontFamilyName = name) }

    fun setFontSize(sizeSp: Float) = updatePrefs { it.copy(fontSizeSp = sizeSp.coerceIn(12f, 40f)) }

    fun setFontWeight(weight: Int) = updatePrefs { it.copy(fontWeight = weight.coerceIn(300, 700)) }

    fun setLineSpacing(multiplier: Float) =
        updatePrefs { it.copy(lineSpacingMultiplier = multiplier.coerceIn(1.0f, 2.4f)) }

    fun setParagraphSpacing(dp: Float) = updatePrefs { it.copy(paragraphSpacingDp = dp.coerceIn(0f, 32f)) }

    fun setHorizontalMargin(dp: Float) =
        updatePrefs { it.copy(marginLeftDp = dp.coerceIn(0f, 64f), marginRightDp = dp.coerceIn(0f, 64f)) }

    fun setMarginLeft(dp: Float) = updatePrefs { it.copy(marginLeftDp = dp.coerceIn(0f, 80f)) }

    fun setMarginRight(dp: Float) = updatePrefs { it.copy(marginRightDp = dp.coerceIn(0f, 80f)) }

    fun setMarginTop(dp: Float) = updatePrefs { it.copy(marginTopDp = dp.coerceIn(0f, 120f)) }

    fun setMarginBottom(dp: Float) = updatePrefs { it.copy(marginBottomDp = dp.coerceIn(0f, 120f)) }

    fun setVerticalMargin(dp: Float) =
        updatePrefs { it.copy(marginTopDp = dp.coerceIn(0f, 64f), marginBottomDp = dp.coerceIn(0f, 64f)) }

    fun setJustified(enabled: Boolean) = updatePrefs { it.copy(fullJustification = enabled) }

    fun setHyphenation(enabled: Boolean) = updatePrefs { it.copy(hyphenationEnabled = enabled) }

    /** [level] in 0.05..1, or -1 for the system brightness. */
    fun setBrightness(level: Float) =
        updatePrefs { it.copy(screenBrightness = if (level < 0f) -1f else level.coerceIn(0.05f, 1f)) }

    fun setWarmthFilter(enabled: Boolean, intensity: Float) {
        viewModelScope.launch { preferencesRepository.setBlueLightFilter(enabled, intensity) }
    }

    fun setKeepScreenOn(enabled: Boolean) = updatePrefs { it.copy(keepScreenOn = enabled) }

    fun setReadingMode(mode: com.example.domain.reader.ReadingMode) = updatePrefs { it.copy(readingMode = mode) }

    fun setTapLayout(layout: com.example.domain.reader.TapLayout) = updatePrefs { it.copy(tapLayout = layout) }

    fun setInvertTapZones(enabled: Boolean) = updatePrefs { it.copy(invertTapZones = enabled) }

    fun setShowPageNumber(enabled: Boolean) = updatePrefs { it.copy(showPageNumber = enabled) }

    fun setShowStatusBar(enabled: Boolean) = updatePrefs { it.copy(showStatusBar = enabled) }

    fun setPublisherStyles(enabled: Boolean) = updatePrefs { it.copy(publisherStyles = enabled) }

    fun setFirstLineIndent(enabled: Boolean) = updatePrefs { it.copy(firstLineIndent = enabled) }

    fun setBackgroundTexture(texture: com.example.domain.reader.ReaderTexture) =
        updatePrefs { it.copy(backgroundTexture = texture) }

    /** Opens chapter [chapterIndex] at its last page (used when paging backwards across chapters). */
    fun loadChapterAtEnd(chapterIndex: Int) {
        viewModelScope.launch {
            if (isReadAloudActive()) readAloudCoordinator.stopReadAloud()
            readerEngine.loadChapter(chapterIndex, Int.MAX_VALUE)
        }
    }

    fun importCustomFont(uriString: String, displayName: String) {
        viewModelScope.launch {
            preferencesRepository.importCustomFontFile(uriString, displayName)
                .onSuccess { _uiState.update { it.copy(message = "Font \"$displayName\" applied") } }
                .onFailure { err -> _uiState.update { it.copy(message = "Couldn't load font: ${err.message}") } }
        }
    }

    // --- Read aloud (voice & engine are chosen in TTS Studio) ---

    private fun isReadAloudActive(): Boolean = ttsSessionState.value.status.let {
        it == LiveTtsReadAloudStatus.PLAYING ||
            it == LiveTtsReadAloudStatus.PRE_BUFFERING ||
            it == LiveTtsReadAloudStatus.INITIALIZING_MODEL ||
            it == LiveTtsReadAloudStatus.PAUSED
    }

    fun toggleReadAloud() {
        when (ttsSessionState.value.status) {
            LiveTtsReadAloudStatus.PLAYING,
            LiveTtsReadAloudStatus.PRE_BUFFERING -> readAloudCoordinator.pauseReadAloud()

            LiveTtsReadAloudStatus.PAUSED -> readAloudCoordinator.resumeReadAloud()

            LiveTtsReadAloudStatus.INITIALIZING_MODEL -> Unit

            else -> {
                val doc = viewportState.value.activeChapterDocument ?: return
                val currentChar = viewportState.value.currentCharOffset
                val startIdx = doc.sentences.indexOfFirst { it.endCharOffset > currentChar }
                    .takeIf { it >= 0 } ?: 0
                viewModelScope.launch {
                    readAloudCoordinator.startReadAloud(doc.bookId, doc.chapterIndex, doc.sentences, startIdx)
                }
            }
        }
    }

    fun stopReadAloud() = readAloudCoordinator.stopReadAloud()

    fun skipToNextSentence() {
        viewModelScope.launch { readAloudCoordinator.skipToNextSentence() }
    }

    fun skipToPreviousSentence() {
        viewModelScope.launch { readAloudCoordinator.skipToPreviousSentence() }
    }

    /** Tapping a sentence while read-aloud is active jumps playback there. Returns true if handled. */
    fun onSentenceTapped(sentence: SentenceLocator): Boolean {
        if (!isReadAloudActive()) return false
        viewModelScope.launch { readAloudCoordinator.jumpToSentence(sentence) }
        return true
    }

    // --- Reading statistics ---

    private suspend fun recordReadingSession() {
        val currentBook = viewportState.value.book ?: return
        val now = System.currentTimeMillis()
        val elapsedMs = now - sessionStartTimestampMs
        if (elapsedMs < 30_000L) return

        val endProg = viewportState.value.overallProgression
        val progDelta = (endProg - sessionStartProgression).coerceAtLeast(0f)
        val minutes = elapsedMs / 60_000.0
        // Jumping ahead (Contents, slider) isn't reading: cap at a fast-but-human 600 wpm
        val wordsRead = (progDelta * currentBook.totalWords).toInt().coerceAtLeast(0)
            .coerceAtMost((minutes * 600).toInt())
        val wpm = if (minutes > 0.5 && wordsRead > 0) (wordsRead / minutes).toInt() else 0

        runCatching {
            readingSessionDao.insertSession(
                ReadingSessionEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = currentBook.id,
                    mediaType = currentBook.mediaType,
                    sessionDateEpochDay = now / (24L * 60L * 60L * 1000L),
                    startedAt = sessionStartTimestampMs,
                    endedAt = now,
                    durationMs = elapsedMs,
                    wordsRead = wordsRead,
                    pagesRead = max(0, wordsRead / 250),
                    averageWpm = wpm,
                    startProgression = sessionStartProgression,
                    endProgression = endProg,
                    usedTtsReadAloud = isReadAloudActive()
                )
            )
        }
        sessionStartTimestampMs = now
        sessionStartProgression = endProg
    }

    fun onReaderHidden() {
        viewModelScope.launch { recordReadingSession() }
    }

    override fun onCleared() {
        super.onCleared()
        readAloudCoordinator.stopReadAloud()
    }
}
