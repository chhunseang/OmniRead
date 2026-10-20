package com.example.domain.reader

import com.example.data.local.entity.AnnotationStyle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.HighlightEntity
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class PageTurnMode {
    CURL_FLIP,
    SLIDE,
    FADE,
    VERTICAL_CONTINUOUS
}

@Serializable
enum class ReaderThemePreset(
    val displayName: String,
    val backgroundArgb: Long,
    val textArgb: Long,
    val linkArgb: Long,
    val selectionArgb: Long
) {
    DAY(
        displayName = "Daylight",
        backgroundArgb = 0xFFFAF8F5,
        textArgb = 0xFF1C1B1F,
        linkArgb = 0xFF1565C0,
        selectionArgb = 0x6690CAF9
    ),
    SEPIA(
        displayName = "Warm Sepia",
        backgroundArgb = 0xFFF4ECD8,
        textArgb = 0xFF3E2723,
        linkArgb = 0xFF8D6E63,
        selectionArgb = 0x66FFCC80
    ),
    NIGHT(
        displayName = "Slate Night",
        backgroundArgb = 0xFF1E222A,
        textArgb = 0xFFD8DEE9,
        linkArgb = 0xFF88C0D0,
        selectionArgb = 0x665E81AC
    ),
    AMOLED_BLACK(
        displayName = "AMOLED Pitch Black",
        backgroundArgb = 0xFF000000,
        textArgb = 0xFFB0B0B0,
        linkArgb = 0xFF64B5F6,
        selectionArgb = 0x6637474F
    ),
    CUSTOM(
        displayName = "Custom Theme",
        backgroundArgb = 0xFFFAF8F5,
        textArgb = 0xFF1C1B1F,
        linkArgb = 0xFF1565C0,
        selectionArgb = 0x6690CAF9
    )
}

@Serializable
enum class TapZoneAction {
    NONE,
    NEXT_PAGE,
    PREVIOUS_PAGE,
    TOGGLE_CHROME_TOOLBAR,
    TOGGLE_AUTO_SCROLL,
    TOGGLE_TTS_READ_ALOUD,
    ADD_BOOKMARK,
    OPEN_TOC,
    OPEN_SETTINGS
}

@Serializable
data class TapZoneGridConfig(
    val topLeft: TapZoneAction = TapZoneAction.PREVIOUS_PAGE,
    val topCenter: TapZoneAction = TapZoneAction.TOGGLE_CHROME_TOOLBAR,
    val topRight: TapZoneAction = TapZoneAction.ADD_BOOKMARK,
    val middleLeft: TapZoneAction = TapZoneAction.PREVIOUS_PAGE,
    val middleCenter: TapZoneAction = TapZoneAction.TOGGLE_CHROME_TOOLBAR,
    val middleRight: TapZoneAction = TapZoneAction.NEXT_PAGE,
    val bottomLeft: TapZoneAction = TapZoneAction.PREVIOUS_PAGE,
    val bottomCenter: TapZoneAction = TapZoneAction.TOGGLE_TTS_READ_ALOUD,
    val bottomRight: TapZoneAction = TapZoneAction.NEXT_PAGE
) {
    fun actionForZone(row: Int, col: Int): TapZoneAction {
        return when (row.coerceIn(0, 2) to col.coerceIn(0, 2)) {
            0 to 0 -> topLeft
            0 to 1 -> topCenter
            0 to 2 -> topRight
            1 to 0 -> middleLeft
            1 to 1 -> middleCenter
            1 to 2 -> middleRight
            2 to 0 -> bottomLeft
            2 to 1 -> bottomCenter
            else -> bottomRight
        }
    }
}

@Serializable
data class ReaderPreferences(
    val customFontFamilyName: String = "Literata",
    val customFontFileUri: String? = null,
    val fontSizeSp: Float = 18f,
    val fontWeight: Int = 400,
    val lineSpacingMultiplier: Float = 1.45f,
    val paragraphSpacingDp: Float = 12f,
    val wordSpacingSp: Float = 0.5f,
    val marginLeftDp: Float = 20f,
    val marginRightDp: Float = 20f,
    val marginTopDp: Float = 24f,
    val marginBottomDp: Float = 24f,
    val fullJustification: Boolean = true,
    val hyphenationEnabled: Boolean = true,
    val themePreset: ReaderThemePreset = ReaderThemePreset.DAY,
    val customBackgroundArgb: Long = 0xFFFAF8F5,
    val customTextArgb: Long = 0xFF1C1B1F,
    val backgroundTextureUri: String? = null,
    val blueLightFilterEnabled: Boolean = false,
    val blueLightFilterIntensity: Float = 0.30f,
    val pageTurnMode: PageTurnMode = PageTurnMode.CURL_FLIP,
    val autoScrollEnabled: Boolean = false,
    val autoScrollSpeedPixelsPerSec: Float = 45f,
    val tapZoneGrid: TapZoneGridConfig = TapZoneGridConfig(),
    val leftEdgeBrightnessSwipe: Boolean = true,
    val rightEdgeVolumeSwipe: Boolean = true,
    val volumeKeyPageTurn: Boolean = true,
    val keepScreenOn: Boolean = true
)

@Serializable
data class SentenceLocator(
    val sentenceId: String,
    val bookId: String,
    val chapterIndex: Int,
    val chapterHref: String,
    val sentenceIndex: Int,
    val text: String,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val paragraphIndex: Int,
    val domCssSelector: String? = null,
    val cfiRange: String? = null,
    val progressionInChapter: Float = 0f
)

@Serializable
data class ReaderChapterDocument(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterHref: String,
    val rawHtmlContent: String,
    val plainTextContent: String,
    val paragraphs: List<String>,
    val sentences: List<SentenceLocator>,
    val embeddedImageUris: List<String> = emptyList(),
    val totalWords: Int = 0
)

@Serializable
data class ReaderSearchResult(
    val bookId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterHref: String,
    val matchedText: String,
    val surroundingSnippet: String,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val progressionInChapter: Float,
    val locatorJson: String
)

@Serializable
data class DictionaryDefinitionResult(
    val headword: String,
    val phonetic: String? = null,
    val partOfSpeech: String? = null,
    val definitions: List<String>,
    val examples: List<String> = emptyList(),
    val sourceDictionary: String = "Offline WordNet / FreeDictionary API"
)

data class ReaderViewportState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val activeChapterDocument: ReaderChapterDocument? = null,
    val currentChapterIndex: Int = 0,
    val currentPageInChapter: Int = 0,
    val totalPagesInChapter: Int = 1,
    val currentCharOffset: Int = 0,
    val overallProgression: Float = 0f,
    val activeTtsSentence: SentenceLocator? = null,
    val chapterHighlights: List<HighlightEntity> = emptyList(),
    val chapterBookmarks: List<BookmarkEntity> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

interface UnifiedEbookReaderEngine {
    val viewportState: StateFlow<ReaderViewportState>
    val readerPreferences: StateFlow<ReaderPreferences>

    suspend fun openPublication(bookId: String): Result<BookEntity>
    suspend fun closePublication()

    suspend fun loadChapter(chapterIndex: Int, initialCharOffset: Int = 0): Result<ReaderChapterDocument>
    suspend fun navigateToNextPage(): Boolean
    suspend fun navigateToPreviousPage(): Boolean
    suspend fun navigateToChapter(chapterIndex: Int, charOffset: Int = 0)
    suspend fun navigateToSentence(sentenceLocator: SentenceLocator)
    suspend fun navigateToProgression(overallProgression: Float)

    suspend fun extractChapterSentences(bookId: String, chapterIndex: Int): Result<List<SentenceLocator>>

    suspend fun searchInBook(
        bookId: String,
        query: String,
        useRegex: Boolean = false,
        caseSensitive: Boolean = false
    ): Result<List<ReaderSearchResult>>

    suspend fun lookupDictionaryTerm(word: String, languageCode: String = "en"): Result<DictionaryDefinitionResult>

    suspend fun addHighlight(
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int,
        selectedText: String,
        colorArgb: Long,
        style: AnnotationStyle,
        noteMarkdown: String?
    ): Result<HighlightEntity>

    suspend fun addBookmark(
        chapterIndex: Int,
        charOffset: Int,
        snippetText: String,
        noteMarkdown: String?,
        label: String?
    ): Result<BookmarkEntity>

    suspend fun exportAnnotationsJson(bookId: String): Result<String>
    suspend fun importAnnotationsJson(bookId: String, jsonPayload: String): Result<Int>

    suspend fun updateReaderPreferences(transform: (ReaderPreferences) -> ReaderPreferences)
}
