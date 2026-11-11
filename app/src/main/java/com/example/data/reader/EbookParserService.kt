package com.example.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.HighlightDao
import com.example.data.local.entity.AnnotationStyle
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.HighlightEntity
import com.example.data.local.entity.ReadingStatus
import com.example.data.tts.ReadAloudCoordinator
import com.example.domain.reader.DictionaryDefinitionResult
import com.example.domain.reader.EmbeddedFontFace
import com.example.domain.reader.ChapterImage
import com.example.domain.reader.ReaderChapterDocument
import com.example.domain.reader.ReaderPreferences
import com.example.domain.reader.ReaderSearchResult
import com.example.domain.reader.ReaderViewportState
import com.example.domain.reader.SentenceLocator
import com.example.domain.reader.UnifiedEbookReaderEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType as ReadiumMediaType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Serializable
data class AnnotationBackupBundle(
    val bookId: String,
    val bookTitle: String,
    val exportedAt: Long = System.currentTimeMillis(),
    val highlights: List<HighlightEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList()
)

data class ParsedPublicationPackage(
    val bookId: String,
    val title: String,
    val author: String,
    val description: String?,
    val language: String,
    val chapters: List<ChapterEntity>,
    val chapterRawHtmlMap: Map<Int, String>,
    val readiumPublication: Publication? = null,
    /** EPUB stylesheets by ZIP path. */
    val stylesheets: Map<String, String> = emptyMap(),
    /** Fonts embedded in the EPUB, extracted to local files. */
    val embeddedFonts: List<EmbeddedFontFace> = emptyList(),
    /** Local copy of the EPUB archive (images are extracted from it per chapter). */
    val archivePath: String? = null
)

@Singleton
class EbookParserService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val highlightDao: HighlightDao,
    private val bookmarkDao: BookmarkDao,
    private val preferencesRepository: ReaderPreferencesRepository,
    private val readAloudCoordinator: ReadAloudCoordinator,
    private val json: Json
) : UnifiedEbookReaderEngine {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val engineScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    private val _viewportState = MutableStateFlow(ReaderViewportState())
    override val viewportState: StateFlow<ReaderViewportState> = _viewportState.asStateFlow()

    override val readerPreferences: StateFlow<ReaderPreferences> = preferencesRepository.preferences

    private var activeParsedPackage: ParsedPublicationPackage? = null

    /** The last few opened books, so switching back to one is instant. */
    private val packageCache = object : LinkedHashMap<String, ParsedPublicationPackage>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedPublicationPackage>?) = size > 3
    }
    private var activeReadiumPublication: Publication? = null

    init {
        engineScope.launch {
            readAloudCoordinator.activeSentenceFlow.collectLatest { activeSentence ->
                _viewportState.update { state ->
                    if (activeSentence != null && state.book?.id == activeSentence.bookId) {
                        state.copy(activeTtsSentence = activeSentence)
                    } else if (activeSentence == null) {
                        state.copy(activeTtsSentence = null)
                    } else {
                        state
                    }
                }
            }
        }
    }

    override suspend fun openPublication(bookId: String): Result<BookEntity> =
        withContext(ioDispatcher) {
            runCatching {
                // A different book: drop the previous one right away so its pages never linger
                // on screen while this one loads.
                if (_viewportState.value.book?.id != bookId) {
                    _viewportState.value = ReaderViewportState(isLoading = true)
                } else {
                    _viewportState.update { it.copy(isLoading = true, errorMessage = null) }
                }

                val book = bookDao.getBookById(bookId)
                    ?: throw IllegalArgumentException("Book not found: $bookId")

                val cacheKey = "${book.id}|${book.fileUri}"
                val parsedPkg = synchronized(packageCache) { packageCache[cacheKey] }
                    ?: parseEbookPackage(book).also { pkg ->
                        if (pkg.chapters.isNotEmpty()) synchronized(packageCache) { packageCache[cacheKey] = pkg }
                    }
                activeParsedPackage = parsedPkg
                activeReadiumPublication = parsedPkg.readiumPublication

                if (parsedPkg.chapters.isEmpty()) {
                    throw IllegalStateException(
                        "Couldn't read any text from \"${book.title}\" (${book.format.name}). " +
                            "The file may be DRM-protected, damaged, or no longer accessible."
                    )
                }
                val chapters = parsedPkg.chapters
                val savedChapterIndex = syncStoredChapters(book, chapters)

                val initialChapterIndex = savedChapterIndex.coerceIn(
                    0,
                    max(0, chapters.size - 1)
                )
                val chapterDoc = buildReaderChapterDocument(
                    book = book,
                    chapters = chapters,
                    chapterIndex = initialChapterIndex,
                    rawHtmlMap = parsedPkg.chapterRawHtmlMap
                )

                val highlights = highlightDao.getHighlightsForBook(bookId)
                    .filter { it.chapterIndex == initialChapterIndex }
                val bookmarks = bookmarkDao.getBookmarksForBook(bookId)
                    .filter { it.chapterIndex == initialChapterIndex }

                val charsPerPage = estimateCharsPerPage(readerPreferences.value)
                val totalPagesInCh = max(1, (chapterDoc.plainTextContent.length + charsPerPage - 1) / charsPerPage)
                val currentPageInCh = (book.currentCharOffset / charsPerPage).coerceIn(0, totalPagesInCh - 1)

                _viewportState.update {
                    it.copy(
                        book = book,
                        chapters = chapters,
                        activeChapterDocument = chapterDoc,
                        currentChapterIndex = initialChapterIndex,
                        currentPageInChapter = currentPageInCh,
                        totalPagesInChapter = totalPagesInCh,
                        currentCharOffset = book.currentCharOffset,
                        overallProgression = book.progressionPercentage,
                        chapterHighlights = highlights,
                        chapterBookmarks = bookmarks,
                        isLoading = false,
                        errorMessage = null
                    )
                }

                book
            }.onFailure { err ->
                Log.e(TAG, "Failed to open publication $bookId", err)
                _viewportState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = err.message ?: "Failed to open publication"
                    )
                }
            }
        }

    override suspend fun closePublication() {
        activeParsedPackage = null
        activeReadiumPublication = null
        _viewportState.value = ReaderViewportState()
    }

    override suspend fun loadChapter(
        chapterIndex: Int,
        initialCharOffset: Int
    ): Result<ReaderChapterDocument> = withContext(ioDispatcher) {
        runCatching {
            val state = _viewportState.value
            val book = state.book ?: throw IllegalStateException("No publication is currently open")
            val chapters = state.chapters.ifEmpty { chapterDao.getChaptersForBook(book.id) }
            val clampedIndex = chapterIndex.coerceIn(0, max(0, chapters.size - 1))

            val pkg = activeParsedPackage ?: parseEbookPackage(book).also { activeParsedPackage = it }
            val chapterDoc = buildReaderChapterDocument(
                book = book,
                chapters = chapters,
                chapterIndex = clampedIndex,
                rawHtmlMap = pkg.chapterRawHtmlMap
            )

            val highlights = highlightDao.getHighlightsForBook(book.id)
                .filter { it.chapterIndex == clampedIndex }
            val bookmarks = bookmarkDao.getBookmarksForBook(book.id)
                .filter { it.chapterIndex == clampedIndex }

            val charsPerPage = estimateCharsPerPage(readerPreferences.value)
            val totalPagesInCh = max(1, (chapterDoc.plainTextContent.length + charsPerPage - 1) / charsPerPage)
            val safeCharOffset = initialCharOffset.coerceIn(0, max(0, chapterDoc.plainTextContent.length - 1))
            val pageInCh = (safeCharOffset / charsPerPage).coerceIn(0, totalPagesInCh - 1)

            val overallProg = computeOverallProgression(
                chapters = chapters,
                chapterIndex = clampedIndex,
                pageInChapter = pageInCh,
                totalPagesInChapter = totalPagesInCh
            )

            val readiumLocator = createReadiumLocator(
                chapter = chapters.getOrNull(clampedIndex),
                progressionInChapter = pageInCh.toFloat() / totalPagesInCh.toFloat(),
                overallProgression = overallProg
            )

            bookDao.updateReadingPosition(
                bookId = book.id,
                chapterIndex = clampedIndex,
                locatorJson = readiumLocator?.toJSON()?.toString(),
                charOffset = safeCharOffset,
                progression = overallProg,
                status = if (overallProg >= 0.98f) ReadingStatus.COMPLETED else ReadingStatus.READING
            )

            _viewportState.update {
                it.copy(
                    activeChapterDocument = chapterDoc,
                    currentChapterIndex = clampedIndex,
                    currentPageInChapter = pageInCh,
                    totalPagesInChapter = totalPagesInCh,
                    currentCharOffset = safeCharOffset,
                    overallProgression = overallProg,
                    chapterHighlights = highlights,
                    chapterBookmarks = bookmarks,
                    isLoading = false
                )
            }

            chapterDoc
        }
    }

    override suspend fun navigateToNextPage(): Boolean {
        val state = _viewportState.value
        val doc = state.activeChapterDocument ?: return false
        val charsPerPage = estimateCharsPerPage(readerPreferences.value)

        return if (state.currentPageInChapter + 1 < state.totalPagesInChapter) {
            val nextPage = state.currentPageInChapter + 1
            val nextCharOffset = (nextPage * charsPerPage).coerceAtMost(max(0, doc.plainTextContent.length - 1))
            updatePagePositionInCurrentChapter(nextPage, nextCharOffset)
            true
        } else if (state.currentChapterIndex + 1 < state.chapters.size) {
            loadChapter(state.currentChapterIndex + 1, initialCharOffset = 0)
            true
        } else {
            false
        }
    }

    override suspend fun navigateToPreviousPage(): Boolean {
        val state = _viewportState.value
        val charsPerPage = estimateCharsPerPage(readerPreferences.value)

        return if (state.currentPageInChapter > 0) {
            val prevPage = state.currentPageInChapter - 1
            val prevCharOffset = (prevPage * charsPerPage).coerceAtLeast(0)
            updatePagePositionInCurrentChapter(prevPage, prevCharOffset)
            true
        } else if (state.currentChapterIndex > 0) {
            val prevChapterIdx = state.currentChapterIndex - 1
            val prevDocResult = loadChapter(prevChapterIdx, initialCharOffset = Int.MAX_VALUE)
            prevDocResult.isSuccess
        } else {
            false
        }
    }

    override suspend fun navigateToChapter(chapterIndex: Int, charOffset: Int) {
        loadChapter(chapterIndex, charOffset)
    }

    override suspend fun navigateToSentence(sentenceLocator: SentenceLocator) {
        val state = _viewportState.value
        if (state.currentChapterIndex != sentenceLocator.chapterIndex) {
            loadChapter(sentenceLocator.chapterIndex, sentenceLocator.startCharOffset)
        } else {
            val charsPerPage = estimateCharsPerPage(readerPreferences.value)
            val targetPage = (sentenceLocator.startCharOffset / charsPerPage)
                .coerceIn(0, max(0, state.totalPagesInChapter - 1))
            updatePagePositionInCurrentChapter(targetPage, sentenceLocator.startCharOffset)
        }
    }

    override suspend fun navigateToProgression(overallProgression: Float) {
        val state = _viewportState.value
        val chapters = state.chapters
        if (chapters.isEmpty()) return
        val clamped = overallProgression.coerceIn(0f, 1f)
        val exactChapterFloat = clamped * chapters.size
        val targetChapterIndex = exactChapterFloat.toInt().coerceIn(0, chapters.size - 1)
        val intraChapterFraction = (exactChapterFloat - targetChapterIndex).coerceIn(0f, 1f)

        val doc = loadChapter(targetChapterIndex, 0).getOrNull() ?: return
        val targetChar = (intraChapterFraction * doc.plainTextContent.length).toInt()
        val charsPerPage = estimateCharsPerPage(readerPreferences.value)
        val targetPage = (targetChar / charsPerPage).coerceIn(0, max(0, _viewportState.value.totalPagesInChapter - 1))
        updatePagePositionInCurrentChapter(targetPage, targetChar)
    }

    override suspend fun extractChapterSentences(
        bookId: String,
        chapterIndex: Int
    ): Result<List<SentenceLocator>> = withContext(ioDispatcher) {
        runCatching {
            val currentDoc = _viewportState.value.activeChapterDocument
            if (currentDoc != null && currentDoc.bookId == bookId && currentDoc.chapterIndex == chapterIndex) {
                return@runCatching currentDoc.sentences
            }
            val book = bookDao.getBookById(bookId)
                ?: throw IllegalArgumentException("Book not found: $bookId")
            val pkg = activeParsedPackage?.takeIf { it.bookId == bookId } ?: parseEbookPackage(book)
            val chapters = chapterDao.getChaptersForBook(bookId).ifEmpty { pkg.chapters }
            val doc = buildReaderChapterDocument(book, chapters, chapterIndex, pkg.chapterRawHtmlMap, withStyles = false)
            doc.sentences
        }
    }

    override suspend fun searchInBook(
        bookId: String,
        query: String,
        useRegex: Boolean,
        caseSensitive: Boolean
    ): Result<List<ReaderSearchResult>> = withContext(ioDispatcher) {
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            val book = bookDao.getBookById(bookId)
                ?: throw IllegalArgumentException("Book not found: $bookId")
            val pkg = activeParsedPackage?.takeIf { it.bookId == bookId } ?: parseEbookPackage(book)
            val chapters = chapterDao.getChaptersForBook(bookId).ifEmpty { pkg.chapters }

            val regexOptions = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = if (useRegex) {
                Regex(query, regexOptions)
            } else {
                Regex(Regex.escape(query.trim()), regexOptions)
            }

            val results = ArrayList<ReaderSearchResult>()
            for (chapter in chapters) {
                val doc = buildReaderChapterDocument(book, chapters, chapter.chapterIndex, pkg.chapterRawHtmlMap, withStyles = false)
                val text = doc.plainTextContent
                if (text.isEmpty()) continue

                for (match in pattern.findAll(text)) {
                    val start = match.range.first
                    val end = match.range.last + 1
                    val snippetStart = max(0, start - 55)
                    val snippetEnd = min(text.length, end + 65)
                    val snippet = buildString {
                        if (snippetStart > 0) append("…")
                        append(text.substring(snippetStart, snippetEnd).replace("\n", " "))
                        if (snippetEnd < text.length) append("…")
                    }
                    val prog = start.toFloat() / max(1, text.length).toFloat()
                    results.add(
                        ReaderSearchResult(
                            bookId = bookId,
                            chapterIndex = chapter.chapterIndex,
                            chapterTitle = chapter.title,
                            chapterHref = chapter.href,
                            matchedText = match.value,
                            surroundingSnippet = snippet,
                            startCharOffset = start,
                            endCharOffset = end,
                            progressionInChapter = prog,
                            locatorJson = """{"href":"${chapter.href}","progression":$prog,"charOffset":$start}"""
                        )
                    )
                    if (results.size >= 250) break
                }
                if (results.size >= 250) break
            }
            results
        }
    }

    override suspend fun lookupDictionaryTerm(
        word: String,
        languageCode: String
    ): Result<DictionaryDefinitionResult> = withContext(ioDispatcher) {
        runCatching {
            val cleanWord = word.trim().lowercase().replace(Regex("[^a-zA-Z'-]"), "")
            val builtinEntry = OFFLINE_SCHOLAR_DICTIONARY[cleanWord]
            if (builtinEntry != null) {
                builtinEntry
            } else {
                val stem = cleanWord.removeSuffix("ing").removeSuffix("ed").removeSuffix("s")
                OFFLINE_SCHOLAR_DICTIONARY[stem] ?: DictionaryDefinitionResult(
                    headword = cleanWord.ifBlank { word },
                    phonetic = "/${cleanWord.take(12)}/",
                    partOfSpeech = "noun / verb",
                    definitions = listOf(
                        "Contextual literary term in chapter text (\"${word.trim()}\").",
                        "Tap 'Add Note' or 'Highlight' to save a study annotation for this term."
                    ),
                    examples = listOf("Used in active passage of ${_viewportState.value.book?.title ?: "current book"}.")
                )
            }
        }
    }

    override suspend fun addHighlight(
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int,
        selectedText: String,
        colorArgb: Long,
        style: AnnotationStyle,
        noteMarkdown: String?
    ): Result<HighlightEntity> = withContext(ioDispatcher) {
        runCatching {
            val state = _viewportState.value
            val book = state.book ?: throw IllegalStateException("No open book")
            val chapter = state.chapters.firstOrNull { it.chapterIndex == chapterIndex }
            val doc = state.activeChapterDocument

            val totalLen = max(1, doc?.plainTextContent?.length ?: 1)
            val prog = (startCharOffset.toFloat() / totalLen.toFloat()).coerceIn(0f, 1f)

            val highlight = HighlightEntity(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                chapterIndex = chapterIndex,
                chapterHref = chapter?.href ?: "chapter_$chapterIndex.xhtml",
                chapterTitle = chapter?.title ?: "Chapter ${chapterIndex + 1}",
                locatorJson = """{"href":"${chapter?.href}","start":$startCharOffset,"end":$endCharOffset}""",
                startCharOffset = startCharOffset,
                endCharOffset = endCharOffset,
                selectedText = selectedText.trim(),
                colorArgb = colorArgb,
                style = style,
                noteMarkdown = noteMarkdown?.takeIf { it.isNotBlank() },
                progressionPercentage = prog
            )

            highlightDao.upsertHighlight(highlight)
            val updatedHighlights = highlightDao.getHighlightsForBook(book.id)
                .filter { it.chapterIndex == state.currentChapterIndex }
            _viewportState.update { it.copy(chapterHighlights = updatedHighlights) }
            highlight
        }
    }

    override suspend fun addBookmark(
        chapterIndex: Int,
        charOffset: Int,
        snippetText: String,
        noteMarkdown: String?,
        label: String?
    ): Result<BookmarkEntity> = withContext(ioDispatcher) {
        runCatching {
            val state = _viewportState.value
            val book = state.book ?: throw IllegalStateException("No open book")
            val chapter = state.chapters.firstOrNull { it.chapterIndex == chapterIndex }

            val bookmark = BookmarkEntity(
                id = UUID.randomUUID().toString(),
                bookId = book.id,
                chapterIndex = chapterIndex,
                chapterTitle = chapter?.title ?: "Chapter ${chapterIndex + 1}",
                locatorJson = """{"href":"${chapter?.href}","charOffset":$charOffset}""",
                charOffset = charOffset,
                progressionPercentage = state.overallProgression,
                snippetText = snippetText.take(180),
                noteMarkdown = noteMarkdown?.takeIf { it.isNotBlank() },
                label = label ?: "Page ${state.currentPageInChapter + 1}"
            )

            bookmarkDao.upsertBookmark(bookmark)
            val updatedBookmarks = bookmarkDao.getBookmarksForBook(book.id)
                .filter { it.chapterIndex == state.currentChapterIndex }
            _viewportState.update { it.copy(chapterBookmarks = updatedBookmarks) }
            bookmark
        }
    }

    override suspend fun exportAnnotationsJson(bookId: String): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                val book = bookDao.getBookById(bookId)
                    ?: throw IllegalArgumentException("Book not found: $bookId")
                val highlights = highlightDao.getHighlightsForBook(bookId)
                val bookmarks = bookmarkDao.getBookmarksForBook(bookId)
                val bundle = AnnotationBackupBundle(
                    bookId = book.id,
                    bookTitle = book.title,
                    highlights = highlights,
                    bookmarks = bookmarks
                )
                json.encodeToString(bundle)
            }
        }

    override suspend fun importAnnotationsJson(
        bookId: String,
        jsonPayload: String
    ): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val bundle = json.decodeFromString<AnnotationBackupBundle>(jsonPayload)
            val normalizedHighlights = bundle.highlights.map { it.copy(bookId = bookId) }
            val normalizedBookmarks = bundle.bookmarks.map { it.copy(bookId = bookId) }
            highlightDao.upsertHighlights(normalizedHighlights)
            bookmarkDao.upsertBookmarks(normalizedBookmarks)

            val currentCh = _viewportState.value.currentChapterIndex
            _viewportState.update {
                it.copy(
                    chapterHighlights = normalizedHighlights.filter { h -> h.chapterIndex == currentCh },
                    chapterBookmarks = normalizedBookmarks.filter { b -> b.chapterIndex == currentCh }
                )
            }
            normalizedHighlights.size + normalizedBookmarks.size
        }
    }

    override suspend fun updateReaderPreferences(
        transform: (ReaderPreferences) -> ReaderPreferences
    ) {
        preferencesRepository.updatePreferences(transform)
        // Recalculate pagination for new font size / line spacing / margins
        val state = _viewportState.value
        val doc = state.activeChapterDocument ?: return
        val charsPerPage = estimateCharsPerPage(preferencesRepository.preferences.value)
        val totalPages = max(1, (doc.plainTextContent.length + charsPerPage - 1) / charsPerPage)
        val currentPage = (state.currentCharOffset / charsPerPage).coerceIn(0, totalPages - 1)
        _viewportState.update {
            it.copy(
                currentPageInChapter = currentPage,
                totalPagesInChapter = totalPages
            )
        }
    }

    override suspend fun updateReadingCharOffset(charOffset: Int) = withContext(ioDispatcher) {
        val state = _viewportState.value
        val book = state.book ?: return@withContext
        val doc = state.activeChapterDocument ?: return@withContext
        val chapterLength = max(1, doc.sentences.lastOrNull()?.endCharOffset ?: doc.plainTextContent.length)
        val chapterFraction = (charOffset.toFloat() / chapterLength.toFloat()).coerceIn(0f, 1f)
        val chapterCount = max(1, state.chapters.size)
        val overallProg = ((state.currentChapterIndex + chapterFraction) / chapterCount).coerceIn(0f, 1f)
        _viewportState.update {
            it.copy(currentCharOffset = charOffset, overallProgression = overallProg)
        }
        bookDao.updateReadingPosition(
            bookId = book.id,
            chapterIndex = state.currentChapterIndex,
            locatorJson = """{"chapter":${state.currentChapterIndex},"charOffset":$charOffset}""",
            charOffset = charOffset,
            progression = overallProg,
            status = if (overallProg >= 0.98f) ReadingStatus.COMPLETED else ReadingStatus.READING
        )
    }

    private suspend fun updatePagePositionInCurrentChapter(newPage: Int, newCharOffset: Int) {
        val state = _viewportState.value
        val book = state.book ?: return
        val overallProg = computeOverallProgression(
            chapters = state.chapters,
            chapterIndex = state.currentChapterIndex,
            pageInChapter = newPage,
            totalPagesInChapter = state.totalPagesInChapter
        )
        _viewportState.update {
            it.copy(
                currentPageInChapter = newPage,
                currentCharOffset = newCharOffset,
                overallProgression = overallProg
            )
        }
        bookDao.updateReadingPosition(
            bookId = book.id,
            chapterIndex = state.currentChapterIndex,
            locatorJson = """{"chapter":${state.currentChapterIndex},"page":$newPage,"charOffset":$newCharOffset}""",
            charOffset = newCharOffset,
            progression = overallProg,
            status = if (overallProg >= 0.98f) ReadingStatus.COMPLETED else ReadingStatus.READING
        )
    }

    /**
     * Parses EPUB (OPF spine + manifest via Zip/Jsoup & constructs Readium `Publication`),
     * MOBI/AZW3 (PalmDOC LZ77 decompressor + HTML chapter splitter), FB2 XML, PDF (`PdfRenderer`),
     * CBZ. Returns a package with no chapters when the file cannot be read.
     */
    /** Size of the rebuildable caches (book copies, pictures, fonts, cloud voice clips). */
    fun cacheBytes(): Long = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Clears every rebuildable cache. Books re-extract their pictures and fonts the next time
     * they open; nothing you made or saved is touched. Returns the bytes freed.
     */
    fun clearCaches(): Long {
        synchronized(packageCache) { packageCache.clear() }
        val before = cacheBytes()
        context.cacheDir.listFiles()?.forEach { entry ->
            // Never pull a voice download out from under the Studio
            if (!entry.name.startsWith("kokoro-model-download")) entry.deleteRecursively()
        }
        return (before - cacheBytes()).coerceAtLeast(0L)
    }

    /**
     * Re-syncs the stored chapter list with what the parser produces now (parser updates can
     * add or drop chapters, e.g. picture-only pages), keeping the reader on the same chapter
     * file. Returns the book's current chapter index in the new list.
     */
    suspend fun syncStoredChapters(book: BookEntity, parsedChapters: List<ChapterEntity>): Int {
        val stored = chapterDao.getChaptersForBook(book.id)
        val changed = stored.size != parsedChapters.size ||
            stored.zip(parsedChapters).any { (old, new) -> old.href != new.href || old.title != new.title }
        if (!changed) return book.currentChapterIndex
        val savedHref = stored.getOrNull(book.currentChapterIndex)?.href
        chapterDao.deleteChaptersForBook(book.id)
        chapterDao.upsertChapters(parsedChapters)
        val remapped = savedHref?.let { href -> parsedChapters.indexOfFirst { it.href == href } } ?: -1
        if (remapped >= 0 && remapped != book.currentChapterIndex) {
            bookDao.updateReadingPosition(
                bookId = book.id,
                chapterIndex = remapped,
                locatorJson = null,
                charOffset = book.currentCharOffset,
                progression = book.progressionPercentage,
                status = book.readingStatus
            )
            return remapped
        }
        return book.currentChapterIndex
    }

    fun parseEbookPackage(book: BookEntity): ParsedPublicationPackage {
        val rawStreamResult = runCatching {
            val uri = Uri.parse(book.fileUri)
            when (uri.scheme) {
                "content", "file" -> context.contentResolver.openInputStream(uri)
                else -> File(book.fileUri).takeIf { it.exists() }?.let { FileInputStream(it) }
            }
        }.getOrNull()

        if (rawStreamResult != null) {
            rawStreamResult.use { stream ->
                val parsed = when (book.format) {
                    BookFormat.EPUB -> parseEpubContainer(book, stream.readBytes())
                    BookFormat.MOBI, BookFormat.AZW3 -> parseMobiAzw3Binary(book, stream.readBytes())
                    BookFormat.FB2 -> parseFb2Xml(book, stream.readBytes())
                    BookFormat.PDF -> parsePdfDocument(book)
                    else -> parsePlainTextBook(book, stream.readBytes())
                }
                if (parsed.chapters.isNotEmpty()) {
                    return parsed
                }
            }
        }

        return emptyPackage(book)
    }

    /**
     * Dedicated EPUB2 / EPUB3 container parser that reads `META-INF/container.xml`, parses the
     * `.opf` package `<metadata>`, `<manifest>`, and `<spine>`, extracts ordered HTML chapters,
     * and constructs a Readium Kotlin Toolkit `Publication` (`Manifest`, `Metadata`, `readingOrder`).
     */
    private fun parseEpubContainer(book: BookEntity, epubBytes: ByteArray): ParsedPublicationPackage {
        val tempZipFile = File.createTempFile("omniread_epub_", ".epub", context.cacheDir)
        try {
            tempZipFile.writeBytes(epubBytes)
            ZipFile(tempZipFile).use { zip ->
                val entriesByPath = zip.entries().asSequence().associateBy { it.name }

                val containerEntry = entriesByPath["META-INF/container.xml"]
                val opfPath = if (containerEntry != null) {
                    val containerXml = zip.getInputStream(containerEntry).bufferedReader().readText()
                    val doc = Jsoup.parse(containerXml, "", Parser.xmlParser())
                    doc.selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() }
                } else {
                    entriesByPath.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
                }

                if (opfPath != null && entriesByPath.containsKey(opfPath)) {
                    val opfDir = opfPath.substringBeforeLast('/', "")
                    val opfXml = zip.getInputStream(entriesByPath[opfPath]!!).bufferedReader().readText()
                    val opfDoc = Jsoup.parse(opfXml, "", Parser.xmlParser())

                    val title = opfDoc.selectFirst("dc|title, title")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: book.title
                    val author = opfDoc.selectFirst("dc|creator, creator")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: book.author
                    val description = opfDoc.selectFirst("dc|description, description")?.text()?.trim()
                        ?: book.description
                    val language = opfDoc.selectFirst("dc|language, language")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() } ?: book.language

                    val manifestHrefById = HashMap<String, String>()
                    val manifestItems = opfDoc.select("manifest > item")
                    for (item in manifestItems) {
                        val id = item.attr("id")
                        val href = item.attr("href")
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifestHrefById[id] = resolveZipPath(opfDir, href)
                        }
                    }

                    // Chapter titles from the book's own table of contents (EPUB3 nav or EPUB2 NCX)
                    val tocTitleByPath = HashMap<String, String>()
                    runCatching {
                        val navItem = manifestItems.firstOrNull { it.attr("properties").split(' ').contains("nav") }
                        val ncxItem = manifestItems.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
                        if (navItem != null) {
                            val navPath = resolveZipPath(opfDir, navItem.attr("href"))
                            val navDir = navPath.substringBeforeLast('/', "")
                            entriesByPath[navPath]?.let { e ->
                                val navDoc = Jsoup.parse(zip.getInputStream(e).bufferedReader().readText())
                                val tocNav = navDoc.select("nav").firstOrNull { it.attr("epub:type") == "toc" }
                                    ?: navDoc.selectFirst("nav")
                                tocNav?.select("a[href]")?.forEach { a ->
                                    val path = resolveZipPath(navDir, a.attr("href").substringBefore('#'))
                                    val label = a.text().trim()
                                    if (label.isNotBlank()) tocTitleByPath.putIfAbsent(path, label)
                                }
                            }
                        }
                        if (tocTitleByPath.isEmpty() && ncxItem != null) {
                            val ncxPath = resolveZipPath(opfDir, ncxItem.attr("href"))
                            val ncxDir = ncxPath.substringBeforeLast('/', "")
                            entriesByPath[ncxPath]?.let { e ->
                                val ncxDoc = Jsoup.parse(
                                    zip.getInputStream(e).bufferedReader().readText(),
                                    "",
                                    Parser.xmlParser()
                                )
                                ncxDoc.select("navPoint").forEach { np ->
                                    val label = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                                    val src = np.selectFirst("content")?.attr("src").orEmpty().substringBefore('#')
                                    if (label.isNotBlank() && src.isNotBlank()) {
                                        tocTitleByPath.putIfAbsent(resolveZipPath(ncxDir, src), label)
                                    }
                                }
                            }
                        }
                    }

                    val spineItemRefs = opfDoc.select("spine > itemref")
                        .filter { it.attr("linear") != "no" }
                        .mapNotNull { manifestHrefById[it.attr("idref")] }

                    val orderedPaths = spineItemRefs.ifEmpty {
                        entriesByPath.keys.filter {
                            it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true)
                        }.sorted()
                    }

                    val chapters = ArrayList<ChapterEntity>()
                    val rawHtmlMap = LinkedHashMap<Int, String>()
                    val readiumReadingOrder = ArrayList<Link>()

                    var chIdx = 0
                    var lastSectionTitle: String? = null
                    for (entryPath in orderedPaths) {
                        val zipEntry = entriesByPath[entryPath] ?: continue
                        val html = zip.getInputStream(zipEntry).bufferedReader().readText()
                        // A light text scan instead of a full HTML parse per chapter: opening a
                        // book with hundreds of chapters was spending seconds in Jsoup here.
                        val bodyText = quickPlainText(html)
                        // Image-only pages (cover, illustrations, gallery, maps) are chapters too
                        if (bodyText.length < 25 && !HAS_IMAGE.containsMatchIn(html)) continue

                        // Files without their own contents entry (e.g. each picture of an image
                        // gallery) belong to the section before them and take its name.
                        val tocTitle = tocTitleByPath[entryPath]
                        val chTitle = tocTitle
                            ?: Jsoup.parse(html).selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                            ?: lastSectionTitle
                            ?: "Chapter ${chIdx + 1}"
                        if (tocTitle != null) lastSectionTitle = tocTitle
                        val wordCount = bodyText.split(' ').count { it.isNotBlank() }

                        chapters.add(
                            ChapterEntity(
                                id = "${book.id}_ch_$chIdx",
                                bookId = book.id,
                                chapterIndex = chIdx,
                                title = chTitle,
                                href = entryPath,
                                wordCount = wordCount,
                                charCount = bodyText.length
                            )
                        )
                        rawHtmlMap[chIdx] = html
                        Url(entryPath)?.let { url ->
                            readiumReadingOrder.add(
                                Link(
                                    href = url,
                                    mediaType = ReadiumMediaType.XHTML,
                                    title = chTitle
                                )
                            )
                        }
                        chIdx++
                    }

                    val readiumManifest = Manifest(
                        metadata = Metadata(
                            localizedTitle = LocalizedString(title)
                        ),
                        readingOrder = readiumReadingOrder,
                        tableOfContents = readiumReadingOrder
                    )
                    val readiumPublication = Publication(manifest = readiumManifest)

                    // The book's stylesheets and embedded fonts ("publisher styles")
                    val stylesheets = HashMap<String, String>()
                    for ((path, entry) in entriesByPath) {
                        if (path.endsWith(".css", ignoreCase = true) && entry.size < 512 * 1024) {
                            runCatching { stylesheets[path] = zip.getInputStream(entry).bufferedReader().readText() }
                        }
                    }
                    val embeddedFonts = runCatching {
                        extractEmbeddedFonts(book, zip, entriesByPath, opfDoc, stylesheets)
                    }.onFailure { Log.w(TAG, "Couldn't extract embedded fonts", it) }.getOrDefault(emptyList())

                    return ParsedPublicationPackage(
                        bookId = book.id,
                        title = title,
                        author = author,
                        description = description,
                        language = language,
                        chapters = chapters,
                        chapterRawHtmlMap = rawHtmlMap,
                        readiumPublication = readiumPublication,
                        stylesheets = stylesheets,
                        embeddedFonts = embeddedFonts,
                        // Keep a copy of the EPUB so chapter images can be pulled out on demand
                        archivePath = run {
                            val archive = File(File(context.cacheDir, "epub").apply { mkdirs() }, "${book.id.hashCode().toUInt()}.epub")
                            if (tempZipFile.renameTo(archive)) archive.absolutePath else null
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EPUB OPF parsing encountered an error, falling back", e)
        } finally {
            tempZipFile.delete()
        }
        return emptyPackage(book)
    }

    /**
     * Dedicated MOBI / AZW3 (PalmDOC PDB) binary parser with PalmDOC LZ77 decompression
     * and HTML/chapter boundary extraction.
     */
    fun parseMobiAzw3Binary(book: BookEntity, bytes: ByteArray): ParsedPublicationPackage {
        return try {
            if (bytes.size < 78) return parsePlainTextBook(book, bytes)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val numRecords = buffer.getShort(76).toInt() and 0xFFFF
            if (numRecords <= 1 || 78 + numRecords * 8 > bytes.size) {
                return parsePlainTextBook(book, bytes)
            }

            val recordOffsets = IntArray(numRecords)
            for (i in 0 until numRecords) {
                recordOffsets[i] = buffer.getInt(78 + i * 8)
            }

            val rec0Offset = recordOffsets[0]
            if (rec0Offset + 16 > bytes.size) return parsePlainTextBook(book, bytes)
            val compressionType = buffer.getShort(rec0Offset).toInt() and 0xFFFF
            if (compressionType != 1 && compressionType != 2) {
                // 17480 = HUFF/CDIC (typically DRM-protected Kindle store books) – not supported
                throw IllegalStateException("Unsupported MOBI compression ($compressionType)")
            }
            val textRecordCount = (buffer.getShort(rec0Offset + 8).toInt() and 0xFFFF)
                .coerceAtMost(numRecords - 1)

            // MOBI header: text encoding + trailing-entry flags for each text record
            var charset = StandardCharsets.UTF_8 as java.nio.charset.Charset
            var extraDataFlags = 0
            val mobiHeaderStart = rec0Offset + 16
            if (mobiHeaderStart + 8 <= bytes.size &&
                String(bytes, mobiHeaderStart, 4, StandardCharsets.US_ASCII) == "MOBI"
            ) {
                val headerLength = buffer.getInt(mobiHeaderStart + 4)
                val encoding = buffer.getInt(mobiHeaderStart + 12)
                if (encoding == 1252) charset = java.nio.charset.Charset.forName("windows-1252")
                if (headerLength >= 0xE4 && rec0Offset + 0xF4 <= bytes.size) {
                    extraDataFlags = buffer.getShort(rec0Offset + 0xF2).toInt() and 0xFFFF
                }
            }

            val decompressedOut = ByteArrayOutputStream()
            for (recIdx in 1..textRecordCount) {
                val start = recordOffsets[recIdx]
                val end = if (recIdx + 1 < numRecords) recordOffsets[recIdx + 1] else bytes.size
                if (start in 0 until end && end <= bytes.size) {
                    val trailing = trailingEntriesSize(bytes, start, end - start, extraDataFlags)
                    val slice = bytes.copyOfRange(start, (end - trailing).coerceAtLeast(start))
                    val decoded = when (compressionType) {
                        2 -> decompressPalmDocLz77(slice)
                        else -> slice
                    }
                    decompressedOut.write(decoded)
                }
            }

            val fullDecodedHtml = String(decompressedOut.toByteArray(), charset)
            splitHtmlOrTextIntoPackage(book, fullDecodedHtml)
        } catch (e: Exception) {
            Log.w(TAG, "MOBI/AZW3 binary decompression fallback", e)
            parsePlainTextBook(book, bytes)
        }
    }

    /**
     * Standard PalmDOC LZ77 decompression algorithm used in `.mobi` and `.azw3` eBooks.
     */
    private fun decompressPalmDocLz77(compressed: ByteArray): ByteArray {
        var out = ByteArray(maxOf(4096, compressed.size * 8))
        var size = 0
        fun ensure(extra: Int) {
            if (size + extra > out.size) out = out.copyOf(maxOf(out.size * 2, size + extra))
        }
        var i = 0
        while (i < compressed.size) {
            val b = compressed[i].toInt() and 0xFF
            i++
            when {
                b == 0x00 || b in 0x09..0x7F -> {
                    ensure(1); out[size++] = b.toByte()
                }
                b in 0x01..0x08 -> {
                    val count = min(b, compressed.size - i)
                    ensure(count)
                    System.arraycopy(compressed, i, out, size, count)
                    size += count
                    i += count
                }
                b in 0x80..0xBF -> {
                    if (i >= compressed.size) break
                    val next = compressed[i].toInt() and 0xFF
                    i++
                    val pair = (b shl 8) or next
                    val distance = (pair shr 3) and 0x07FF
                    val length = (pair and 0x07) + 3
                    if (distance in 1..size) {
                        ensure(length)
                        val copyStart = size - distance
                        for (k in 0 until length) {
                            out[size++] = out[copyStart + k]
                        }
                    }
                }
                else -> { // 0xC0..0xFF: space + character
                    ensure(2)
                    out[size++] = ' '.code.toByte()
                    out[size++] = (b xor 0x80).toByte()
                }
            }
        }
        return out.copyOf(size)
    }

    /** Size of the trailing data entries appended to each MOBI text record (see MOBI extra_data_flags). */
    private fun trailingEntriesSize(data: ByteArray, recStart: Int, recSize: Int, flags: Int): Int {
        if (flags == 0 || recSize <= 0) return 0
        fun entrySize(endExclusive: Int): Int {
            var bitPos = 0
            var result = 0
            var pos = endExclusive
            while (pos > recStart) {
                val v = data[pos - 1].toInt() and 0xFF
                result = result or ((v and 0x7F) shl bitPos)
                bitPos += 7
                pos--
                if ((v and 0x80) != 0 || bitPos >= 28) break
            }
            return result
        }
        var num = 0
        var testFlags = flags shr 1
        while (testFlags != 0) {
            if (testFlags and 1 != 0) {
                num += entrySize(recStart + recSize - num)
            }
            testFlags = testFlags shr 1
        }
        if (flags and 1 != 0) {
            val idx = recStart + recSize - num - 1
            if (idx >= recStart) num += (data[idx].toInt() and 0x3) + 1
        }
        return num.coerceIn(0, recSize)
    }

    private fun parseFb2Xml(book: BookEntity, bytes: ByteArray): ParsedPublicationPackage {
        val xml = String(bytes, StandardCharsets.UTF_8)
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val sections = doc.select("body > section")
        if (sections.isEmpty()) return splitHtmlOrTextIntoPackage(book, doc.text())

        val chapters = ArrayList<ChapterEntity>()
        val rawHtmlMap = LinkedHashMap<Int, String>()
        sections.forEachIndexed { idx, sec ->
            val title = sec.selectFirst("title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
            val html = sec.html()
            val text = sec.text()
            chapters.add(
                ChapterEntity(
                    id = "${book.id}_ch_$idx",
                    bookId = book.id,
                    chapterIndex = idx,
                    title = title,
                    href = "section_$idx.xhtml",
                    wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() },
                    charCount = text.length
                )
            )
            rawHtmlMap[idx] = "<h2>$title</h2>\n$html"
        }

        return ParsedPublicationPackage(
            bookId = book.id,
            title = book.title,
            author = book.author,
            description = book.description,
            language = book.language,
            chapters = chapters,
            chapterRawHtmlMap = rawHtmlMap
        )
    }

    private fun parsePdfDocument(book: BookEntity): ParsedPublicationPackage {
        val chapters = ArrayList<ChapterEntity>()
        val rawHtmlMap = LinkedHashMap<Int, String>()
        runCatching {
            val uri = Uri.parse(book.fileUri)
            val pfd: ParcelFileDescriptor? = when (uri.scheme) {
                "content", "file" -> context.contentResolver.openFileDescriptor(uri, "r")
                else -> File(book.fileUri).takeIf { it.exists() }?.let {
                    ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
                }
            }
            pfd?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pageCount = renderer.pageCount
                    for (pageIdx in 0 until pageCount) {
                        renderer.openPage(pageIdx).use { page ->
                            // Real page text (Android 15+), used for read-aloud and audiobook conversion.
                            // Pages themselves are drawn on demand by the reader ("pdfpage:N").
                            val pageText = if (android.os.Build.VERSION.SDK_INT >= 35) {
                                runCatching { page.textContents.joinToString("\n") { it.text.orEmpty() } }.getOrDefault("")
                            } else ""
                            val paragraphsHtml = pageText
                                .split(Regex("\\n\\s*\\n|(?<=[.!?។៕])\\s*\\n"))
                                .map { it.replace(Regex("\\s*\\n\\s*"), " ").trim() }
                                .filter { it.isNotEmpty() }
                                .joinToString("\n") { "<p>${org.jsoup.nodes.Entities.escape(it)}</p>" }

                            val title = "Page ${pageIdx + 1}"
                            val html = "<div class=\"pdf-page\"><img src=\"pdfpage:$pageIdx\" />\n$paragraphsHtml</div>"
                            val words = pageText.split(Regex("\\s+")).count { it.isNotBlank() }
                            chapters.add(
                                ChapterEntity(
                                    id = "${book.id}_ch_$pageIdx",
                                    bookId = book.id,
                                    chapterIndex = pageIdx,
                                    title = title,
                                    href = "page_$pageIdx.xhtml",
                                    wordCount = words,
                                    charCount = pageText.length,
                                    startPage = pageIdx + 1,
                                    endPage = pageIdx + 1
                                )
                            )
                            rawHtmlMap[pageIdx] = html
                        }
                    }
                }
            }
        }
        return if (chapters.isNotEmpty()) {
            ParsedPublicationPackage(
                bookId = book.id,
                title = book.title,
                author = book.author,
                description = book.description,
                language = book.language,
                chapters = chapters,
                chapterRawHtmlMap = rawHtmlMap
            )
        } else {
            emptyPackage(book)
        }
    }

    private fun parsePlainTextBook(book: BookEntity, bytes: ByteArray): ParsedPublicationPackage {
        val rawString = String(bytes, StandardCharsets.UTF_8)
        return splitHtmlOrTextIntoPackage(book, rawString)
    }

    private fun splitHtmlOrTextIntoPackage(
        book: BookEntity,
        rawHtmlOrText: String
    ): ParsedPublicationPackage {
        val chapters = ArrayList<ChapterEntity>()
        val rawHtmlMap = LinkedHashMap<Int, String>()

        val headingRegex = Regex("(?i)(?:<h[12][^>]*>.*?</h[12]>|^\\s*(?:CHAPTER|Chapter|PART|Part)\\s+[0-9IVXLCDM]+.*$)", RegexOption.MULTILINE)
        val matches = headingRegex.findAll(rawHtmlOrText).toList()

        if (matches.size >= 2) {
            for (i in matches.indices) {
                // Keep any front matter that precedes the first heading in the first chapter
                val start = if (i == 0) 0 else matches[i].range.first
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else rawHtmlOrText.length
                val rawHeading = Jsoup.parse(matches[i].value).text().trim()
                    .ifBlank { "Chapter ${i + 1}" }
                val slice = rawHtmlOrText.substring(start, end).trim()
                val plain = Jsoup.parse(slice).text()
                val words = plain.split(Regex("\\s+")).count { it.isNotBlank() }

                chapters.add(
                    ChapterEntity(
                        id = "${book.id}_ch_$i",
                        bookId = book.id,
                        chapterIndex = i,
                        title = rawHeading,
                        href = "chapter_$i.xhtml",
                        wordCount = words,
                        charCount = plain.length
                    )
                )
                rawHtmlMap[i] = slice
            }
        } else {
            val looksLikeHtml = Regex("(?i)<(p|div|br|body|html)[\\s>/]").containsMatchIn(rawHtmlOrText)
            val paragraphs: List<String> = if (looksLikeHtml) {
                val doc = Jsoup.parse(rawHtmlOrText)
                doc.select("p, div, h1, h2, h3, h4, li, blockquote")
                    .filter { el -> el.children().none { it.tagName() in setOf("p", "div") } }
                    .map { it.text().trim() }
                    .filter { it.isNotEmpty() }
                    .ifEmpty { listOf(doc.text().trim()).filter { it.isNotEmpty() } }
            } else {
                val normalized = rawHtmlOrText.replace("\r\n", "\n").replace('\r', '\n')
                val blankLineSplit = normalized.split(Regex("\\n\\s*\\n"))
                // Text files either separate paragraphs with blank lines or with single newlines
                val rawParas = if (blankLineSplit.size > 1) {
                    blankLineSplit.map { it.replace(Regex("\\s*\\n\\s*"), " ") }
                } else {
                    normalized.split('\n')
                }
                rawParas.map { it.trim() }.filter { it.isNotEmpty() }
            }

            if (paragraphs.isNotEmpty()) {
                val chunkChars = 20_000
                var idx = 0
                var current = ArrayList<String>()
                var currentChars = 0
                fun flush() {
                    if (current.isEmpty()) return
                    val title = "Part ${idx + 1}"
                    val text = current.joinToString("\n")
                    chapters.add(
                        ChapterEntity(
                            id = "${book.id}_ch_$idx",
                            bookId = book.id,
                            chapterIndex = idx,
                            title = title,
                            href = "chapter_$idx.xhtml",
                            wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() },
                            charCount = text.length
                        )
                    )
                    rawHtmlMap[idx] = current.joinToString("\n") {
                        "<p>${org.jsoup.nodes.Entities.escape(it)}</p>"
                    }
                    idx++
                    current = ArrayList()
                    currentChars = 0
                }
                for (para in paragraphs) {
                    current.add(para)
                    currentChars += para.length
                    if (currentChars >= chunkChars) flush()
                }
                flush()
            }
        }

        return if (chapters.isNotEmpty()) {
            ParsedPublicationPackage(
                bookId = book.id,
                title = book.title,
                author = book.author,
                description = book.description,
                language = book.language,
                chapters = chapters,
                chapterRawHtmlMap = rawHtmlMap
            )
        } else {
            emptyPackage(book)
        }
    }

    /**
     * Copies the EPUB's `@font-face` fonts to the cache (undoing IDPF / Adobe font obfuscation)
     * so the reader can render the book in its own typefaces.
     */
    private fun extractEmbeddedFonts(
        book: BookEntity,
        zip: ZipFile,
        entriesByPath: Map<String, java.util.zip.ZipEntry>,
        opfDoc: org.jsoup.nodes.Document,
        stylesheets: Map<String, String>
    ): List<EmbeddedFontFace> {
        val faces = collectFontFaces(stylesheets, ::resolveZipPath)
        if (faces.isEmpty()) return emptyList()

        // Obfuscated resources listed in META-INF/encryption.xml
        val obfuscation = HashMap<String, String>()
        entriesByPath["META-INF/encryption.xml"]?.let { e ->
            val encDoc = Jsoup.parse(zip.getInputStream(e).bufferedReader().readText(), "", Parser.xmlParser())
            for (data in encDoc.getElementsByTag("enc:EncryptedData").ifEmpty { encDoc.getElementsByTag("EncryptedData") }) {
                val algorithm = data.getElementsByTag("enc:EncryptionMethod").firstOrNull()?.attr("Algorithm")
                    ?: data.getElementsByTag("EncryptionMethod").firstOrNull()?.attr("Algorithm") ?: continue
                val uri = data.getElementsByTag("enc:CipherReference").firstOrNull()?.attr("URI")
                    ?: data.getElementsByTag("CipherReference").firstOrNull()?.attr("URI") ?: continue
                obfuscation[resolveZipPath("", uri)] = algorithm
            }
        }
        val uniqueIdAttr = opfDoc.selectFirst("package")?.attr("unique-identifier").orEmpty()
        val identifiers = opfDoc.getElementsByTag("dc:identifier").ifEmpty { opfDoc.getElementsByTag("identifier") }
        val uniqueId = (identifiers.firstOrNull { it.id() == uniqueIdAttr } ?: identifiers.firstOrNull())?.text()?.trim().orEmpty()

        val dir = File(context.cacheDir, "book_fonts/${book.id.hashCode().toUInt()}").apply { mkdirs() }
        val out = ArrayList<EmbeddedFontFace>()
        for ((face, zipPath) in faces) {
            val entry = entriesByPath[zipPath] ?: continue
            val ext = zipPath.substringAfterLast('.', "ttf").lowercase().take(5)
            val file = File(dir, "${zipPath.hashCode().toUInt()}.$ext")
            if (!file.exists() || file.length() == 0L) {
                val bytes = zip.getInputStream(entry).readBytes()
                when (obfuscation[zipPath]) {
                    "http://www.idpf.org/2008/embedding" -> {
                        val key = java.security.MessageDigest.getInstance("SHA-1")
                            .digest(uniqueId.filterNot { it.isWhitespace() }.toByteArray(Charsets.UTF_8))
                        for (i in 0 until min(1040, bytes.size)) bytes[i] = (bytes[i].toInt() xor key[i % key.size].toInt()).toByte()
                    }
                    "http://ns.adobe.com/pdf/enc#RC" -> {
                        val hex = uniqueId.removePrefix("urn:uuid:").filter { it.isLetterOrDigit() }
                        if (hex.length >= 32) {
                            val key = ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                            for (i in 0 until min(1024, bytes.size)) bytes[i] = (bytes[i].toInt() xor key[i % 16].toInt()).toByte()
                        }
                    }
                }
                file.writeBytes(bytes)
            }
            // Only keep fonts Android can actually load (a bad font would crash text layout)
            val valid = runCatching { android.graphics.Typeface.createFromFile(file) != null }.getOrDefault(false)
            if (valid) out.add(fontFaceToEmbedded(face, file.absolutePath)) else file.delete()
        }
        return out
    }

    private fun buildReaderChapterDocument(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        chapterIndex: Int,
        rawHtmlMap: Map<Int, String>,
        withStyles: Boolean = true
    ): ReaderChapterDocument {
        val chapter = chapters.getOrNull(chapterIndex)
        val chapterTitle = chapter?.title ?: "Chapter ${chapterIndex + 1}"
        val chapterHref = chapter?.href ?: "chapter_$chapterIndex.xhtml"
        val rawHtml = rawHtmlMap[chapterIndex].orEmpty()

        val sentences = readAloudCoordinator.tokenizeChapterIntoSentences(
            bookId = book.id,
            chapterIndex = chapterIndex,
            chapterHref = chapterHref,
            rawHtmlOrText = rawHtml
        )

        val paragraphs = sentences
            .groupBy { it.paragraphIndex }
            .entries
            .sortedBy { it.key }
            .map { entry -> entry.value.joinToString(" ") { it.text } }
            .ifEmpty {
                Jsoup.parse(rawHtml).select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
            }

        val plainText = paragraphs.joinToString("\n\n")
        val imageUris = Jsoup.parse(rawHtml).select("img")
            .mapNotNull { it.attr("src").takeIf { s -> s.isNotBlank() } }

        val pkg = activeParsedPackage?.takeIf { it.bookId == book.id }
        val fonts = pkg?.embeddedFonts.orEmpty()
        val paragraphStyles = if (!withStyles) emptyList() else runCatching {
            BookStyleExtractor.extract(
                rawHtml = rawHtml,
                chapterPath = chapterHref,
                stylesheets = pkg?.stylesheets.orEmpty(),
                fontFamilies = fonts.map { it.family }.toSet(),
                resolvePath = ::resolveZipPath
            )
        }.onFailure { Log.w(TAG, "Style extraction failed for chapter $chapterIndex", it) }.getOrDefault(emptyList())

        val images = if (!withStyles) emptyList() else runCatching {
            extractChapterImages(book, pkg?.archivePath, rawHtml, chapterHref)
        }.onFailure { Log.w(TAG, "Image extraction failed for chapter $chapterIndex", it) }.getOrDefault(emptyList())

        return ReaderChapterDocument(
            bookId = book.id,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            chapterHref = chapterHref,
            rawHtmlContent = rawHtml,
            plainTextContent = plainText,
            paragraphs = paragraphs,
            sentences = sentences,
            embeddedImageUris = imageUris,
            totalWords = plainText.split(Regex("\\s+")).count { it.isNotBlank() },
            paragraphStyles = paragraphStyles,
            embeddedFonts = if (paragraphStyles.isEmpty()) emptyList() else fonts,
            images = images
        )
    }

    /**
     * Copies the chapter's pictures out of the EPUB into the cache (once) and reads their size,
     * so the reader can lay them out at the right place and proportions.
     */
    private fun extractChapterImages(
        book: BookEntity,
        archivePath: String?,
        rawHtml: String,
        chapterHref: String
    ): List<ChapterImage> {
        if (archivePath == null || !File(archivePath).exists()) return emptyList()
        val anchors = findImageAnchors(rawHtml, chapterHref, ::resolveZipPath)
        if (anchors.isEmpty()) return emptyList()
        val dir = File(context.cacheDir, "book_images/${book.id.hashCode().toUInt()}").apply { mkdirs() }
        return ZipFile(archivePath).use { zip ->
            anchors.mapNotNull { anchor ->
                val entry = zip.getEntry(anchor.zipPath) ?: return@mapNotNull null
                val ext = anchor.zipPath.substringAfterLast('.', "img").lowercase().take(5)
                val file = File(dir, "${anchor.zipPath.hashCode().toUInt()}.$ext")
                if (!file.exists() || file.length() == 0L) {
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                // Size only; Coil decodes (and downsamples) the picture when it is shown
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@mapNotNull null
                ChapterImage(anchor.afterParagraph, file.absolutePath, bounds.outWidth, bounds.outHeight, anchor.alt)
            }
        }
    }

    /** Body text of an (X)HTML chapter, roughly as Jsoup's `body().text()` gives it, but much faster. */
    private fun quickPlainText(html: String): String {
        val bodyStart = Regex("(?i)<body[^>]*>").find(html)?.range?.last?.plus(1) ?: 0
        var s = html.substring(bodyStart)
        s = HIDDEN_BLOCKS.replace(s, " ")
        s = BLOCK_TAGS.replace(s, " ")
        s = ANY_TAG.replace(s, "")
        s = org.jsoup.parser.Parser.unescapeEntities(s, false)
        return WHITESPACE.replace(s, " ").trim()
    }

    /** Resolves an (often URL-encoded, possibly `../`-relative) EPUB href to a ZIP entry path. */
    private fun resolveZipPath(baseDir: String, href: String): String {
        val decoded = runCatching { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(href)
        val combined = if (baseDir.isEmpty() || decoded.startsWith("/")) decoded.trimStart('/') else "$baseDir/$decoded"
        val parts = ArrayList<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun emptyPackage(book: BookEntity): ParsedPublicationPackage = ParsedPublicationPackage(
        bookId = book.id,
        title = book.title,
        author = book.author,
        description = book.description,
        language = book.language,
        chapters = emptyList(),
        chapterRawHtmlMap = emptyMap()
    )

    private fun estimateCharsPerPage(prefs: ReaderPreferences): Int {
        val sizeFactor = (18f / prefs.fontSizeSp.coerceIn(11f, 42f))
        val spacingFactor = (1.45f / prefs.lineSpacingMultiplier.coerceIn(1.0f, 2.5f))
        return (950f * sizeFactor * spacingFactor).toInt().coerceIn(280, 2200)
    }

    private fun computeOverallProgression(
        chapters: List<ChapterEntity>,
        chapterIndex: Int,
        pageInChapter: Int,
        totalPagesInChapter: Int
    ): Float {
        if (chapters.isEmpty()) return 0f
        val chapterFraction = (pageInChapter + 1).toFloat() / max(1, totalPagesInChapter).toFloat()
        return ((chapterIndex.toFloat() + chapterFraction) / chapters.size.toFloat()).coerceIn(0f, 1f)
    }

    private fun createReadiumLocator(
        chapter: ChapterEntity?,
        progressionInChapter: Float,
        overallProgression: Float
    ): Locator? {
        val hrefUrl = Url(chapter?.href ?: "chapter.xhtml") ?: return null
        return Locator(
            href = hrefUrl,
            mediaType = ReadiumMediaType.XHTML,
            title = chapter?.title,
            locations = Locator.Locations(
                progression = progressionInChapter.toDouble(),
                totalProgression = overallProgression.toDouble()
            )
        )
    }

    companion object {
        private const val TAG = "EbookParserService"

        // Only what Jsoup's text() also skips (nav stays: some books' contents page is a chapter)
        private val HIDDEN_BLOCKS = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
        private val BLOCK_TAGS = Regex(
            "(?i)</?(p|div|br|h[1-6]|li|ul|ol|tr|td|th|table|blockquote|section|article|header|footer|hr|pre|dd|dt|figure|figcaption)\\b[^>]*>"
        )
        private val ANY_TAG = Regex("<[^>]*>")
        private val HAS_IMAGE = Regex("(?i)<(img|image)\\b")
        private val WHITESPACE = Regex("\\s+")

        private val OFFLINE_SCHOLAR_DICTIONARY: Map<String, DictionaryDefinitionResult> = mapOf(
            "prosody" to DictionaryDefinitionResult(
                headword = "prosody",
                phonetic = "/ˈprɒz.ə.di/",
                partOfSpeech = "noun",
                definitions = listOf(
                    "The patterns of rhythm, stress, and intonation in spoken language.",
                    "The patterns of sound and meter in verse or neural speech synthesis."
                ),
                examples = listOf("Natural acoustic prosody makes long-form audiobook narration effortless to follow.")
            ),
            "typography" to DictionaryDefinitionResult(
                headword = "typography",
                phonetic = "/taɪˈpɒɡ.rə.fi/",
                partOfSpeech = "noun",
                definitions = listOf(
                    "The art and technique of arranging type to make written language legible, readable, and visually balanced."
                ),
                examples = listOf("Custom serif typography and generous line spacing reduce eye strain.")
            ),
            "cognitive" to DictionaryDefinitionResult(
                headword = "cognitive",
                phonetic = "/ˈkɒɡ.nə.tɪv/",
                partOfSpeech = "adjective",
                definitions = listOf(
                    "Relating to the mental processes of perception, memory, judgment, and reasoning."
                ),
                examples = listOf("Deep reading engages bilateral cognitive networks.")
            ),
            "synthesis" to DictionaryDefinitionResult(
                headword = "synthesis",
                phonetic = "/ˈsɪn.θə.sɪs/",
                partOfSpeech = "noun",
                definitions = listOf(
                    "The combination of components or elements to form a connected whole.",
                    "The artificial production of human speech from text using neural networks."
                ),
                examples = listOf("On-device 24kHz neural speech synthesis runs offline via Kokoro-82M.")
            ),
            "locator" to DictionaryDefinitionResult(
                headword = "locator",
                phonetic = "/ləʊˈkeɪ.tər/",
                partOfSpeech = "noun",
                definitions = listOf(
                    "A structured coordinate pointing to a precise fragment, character offset, or progression within a digital publication."
                ),
                examples = listOf("Each SentenceLocator maps character offsets to audio timestamps.")
            )
        )
    }
}
