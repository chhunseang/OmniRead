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
    val readiumPublication: Publication? = null
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
                _viewportState.update { it.copy(isLoading = true, errorMessage = null) }

                val book = bookDao.getBookById(bookId)
                    ?: throw IllegalArgumentException("Book not found: $bookId")

                val parsedPkg = parseEbookPackage(book)
                activeParsedPackage = parsedPkg
                activeReadiumPublication = parsedPkg.readiumPublication

                var chapters = chapterDao.getChaptersForBook(bookId)
                if (chapters.isEmpty() && parsedPkg.chapters.isNotEmpty()) {
                    chapterDao.upsertChapters(parsedPkg.chapters)
                    chapters = parsedPkg.chapters
                }

                val initialChapterIndex = book.currentChapterIndex.coerceIn(
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
            val doc = buildReaderChapterDocument(book, chapters, chapterIndex, pkg.chapterRawHtmlMap)
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
                val doc = buildReaderChapterDocument(book, chapters, chapter.chapterIndex, pkg.chapterRawHtmlMap)
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
     * CBZ, or falls back to the built-in rich 5-chapter OmniRead starter book.
     */
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

        return buildStarterRichBookPackage(book)
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
                    for (item in opfDoc.select("manifest > item")) {
                        val id = item.attr("id")
                        val href = item.attr("href")
                        if (id.isNotBlank() && href.isNotBlank()) {
                            val resolvedPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                            manifestHrefById[id] = resolvedPath
                        }
                    }

                    val spineItemRefs = opfDoc.select("spine > itemref")
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
                    for (entryPath in orderedPaths) {
                        val zipEntry = entriesByPath[entryPath] ?: continue
                        val html = zip.getInputStream(zipEntry).bufferedReader().readText()
                        val htmlDoc = Jsoup.parse(html)
                        val bodyText = htmlDoc.body()?.text()?.trim().orEmpty()
                        if (bodyText.length < 25) continue

                        val chTitle = htmlDoc.selectFirst("h1, h2, h3, title")?.text()?.trim()
                            ?.takeIf { it.isNotBlank() } ?: "Chapter ${chIdx + 1}"
                        val wordCount = bodyText.split(Regex("\\s+")).count { it.isNotBlank() }

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

                    return ParsedPublicationPackage(
                        bookId = book.id,
                        title = title,
                        author = author,
                        description = description,
                        language = language,
                        chapters = chapters,
                        chapterRawHtmlMap = rawHtmlMap,
                        readiumPublication = readiumPublication
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "EPUB OPF parsing encountered an error, falling back", e)
        } finally {
            tempZipFile.delete()
        }
        return buildStarterRichBookPackage(book)
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
            val textRecordCount = (buffer.getShort(rec0Offset + 8).toInt() and 0xFFFF)
                .coerceAtMost(numRecords - 1)

            val decompressedOut = ByteArrayOutputStream()
            for (recIdx in 1..textRecordCount) {
                val start = recordOffsets[recIdx]
                val end = if (recIdx + 1 < numRecords) recordOffsets[recIdx + 1] else bytes.size
                if (start in 0 until end && end <= bytes.size) {
                    val slice = bytes.copyOfRange(start, end)
                    val decoded = when (compressionType) {
                        2 -> decompressPalmDocLz77(slice)
                        else -> slice
                    }
                    decompressedOut.write(decoded)
                }
            }

            val fullDecodedHtml = String(decompressedOut.toByteArray(), StandardCharsets.UTF_8)
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
        val out = ByteArrayOutputStream(compressed.size * 2)
        var i = 0
        while (i < compressed.size) {
            val b = compressed[i].toInt() and 0xFF
            i++
            when {
                b == 0x00 -> out.write(0x00)
                b in 0x01..0x08 -> {
                    val count = min(b, compressed.size - i)
                    out.write(compressed, i, count)
                    i += count
                }
                b in 0x09..0x7F -> out.write(b)
                b in 0x80..0xBF -> {
                    if (i >= compressed.size) break
                    val next = compressed[i].toInt() and 0xFF
                    i++
                    val pair = (b shl 8) or next
                    val distance = (pair shr 3) and 0x07FF
                    val length = (pair and 0x07) + 3
                    val currentBytes = out.toByteArray()
                    val copyStart = currentBytes.size - distance
                    if (copyStart >= 0 && distance > 0) {
                        for (k in 0 until length) {
                            val srcIdx = copyStart + (k % distance)
                            if (srcIdx in currentBytes.indices) {
                                out.write(currentBytes[srcIdx].toInt())
                            }
                        }
                    }
                }
                b in 0xC0..0xFF -> {
                    out.write(' '.code)
                    out.write(b xor 0x80)
                }
            }
        }
        return out.toByteArray()
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
                    val pagesDir = File(context.cacheDir, "pdf_pages/${book.id}").apply { mkdirs() }
                    for (pageIdx in 0 until min(pageCount, 50)) {
                        renderer.openPage(pageIdx).use { page ->
                            val bmp = Bitmap.createBitmap(
                                max(1, page.width * 2),
                                max(1, page.height * 2),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pageFile = File(pagesDir, "page_$pageIdx.png")
                            FileOutputStream(pageFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                            }
                            bmp.recycle()

                            val title = "Page ${pageIdx + 1}"
                            val html = """
                                <div class="pdf-page">
                                  <h2>$title</h2>
                                  <img src="file://${pageFile.absolutePath}" alt="$title" />
                                  <p>PDF Page ${pageIdx + 1} of $pageCount in ${book.title}.</p>
                                </div>
                            """.trimIndent()
                            chapters.add(
                                ChapterEntity(
                                    id = "${book.id}_ch_$pageIdx",
                                    bookId = book.id,
                                    chapterIndex = pageIdx,
                                    title = title,
                                    href = "page_$pageIdx.xhtml",
                                    wordCount = 45,
                                    charCount = 240,
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
            buildStarterRichBookPackage(book)
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
                val start = matches[i].range.first
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
            val cleanText = Jsoup.parse(rawHtmlOrText).text().trim()
            if (cleanText.isNotEmpty()) {
                val chunkChars = 5500
                var offset = 0
                var idx = 0
                while (offset < cleanText.length) {
                    val end = min(offset + chunkChars, cleanText.length)
                    val chunk = cleanText.substring(offset, end)
                    val paragraphsHtml = chunk.split(Regex("\\n+"))
                        .filter { it.isNotBlank() }
                        .joinToString("\n") { "<p>${it.trim()}</p>" }
                    val title = "Chapter ${idx + 1}"
                    chapters.add(
                        ChapterEntity(
                            id = "${book.id}_ch_$idx",
                            bookId = book.id,
                            chapterIndex = idx,
                            title = title,
                            href = "chapter_$idx.xhtml",
                            wordCount = chunk.split(Regex("\\s+")).count { it.isNotBlank() },
                            charCount = chunk.length
                        )
                    )
                    rawHtmlMap[idx] = "<h2>$title</h2>\n$paragraphsHtml"
                    offset = end
                    idx++
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
            buildStarterRichBookPackage(book)
        }
    }

    private fun buildReaderChapterDocument(
        book: BookEntity,
        chapters: List<ChapterEntity>,
        chapterIndex: Int,
        rawHtmlMap: Map<Int, String>
    ): ReaderChapterDocument {
        val chapter = chapters.getOrNull(chapterIndex)
        val chapterTitle = chapter?.title ?: "Chapter ${chapterIndex + 1}"
        val chapterHref = chapter?.href ?: "chapter_$chapterIndex.xhtml"
        val rawHtml = rawHtmlMap[chapterIndex] ?: buildStarterChapterHtml(chapterIndex, chapterTitle)

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
            totalWords = plainText.split(Regex("\\s+")).count { it.isNotBlank() }
        )
    }

    private fun buildStarterRichBookPackage(book: BookEntity): ParsedPublicationPackage {
        val titles = listOf(
            "Chapter 1: The Architecture of Deep Reading",
            "Chapter 2: Acoustic Prosody & Dual-Coding Memory",
            "Chapter 3: On-Device Neural Synthesis with Kokoro-82M",
            "Chapter 4: Sentence Locators & Real-Time DOM Highlighting",
            "Chapter 5: Building a Lifelong Personal Library"
        )
        val chapters = ArrayList<ChapterEntity>()
        val htmlMap = LinkedHashMap<Int, String>()

        titles.forEachIndexed { idx, title ->
            val html = buildStarterChapterHtml(idx, title)
            val plain = Jsoup.parse(html).text()
            chapters.add(
                ChapterEntity(
                    id = "${book.id}_ch_$idx",
                    bookId = book.id,
                    chapterIndex = idx,
                    title = title,
                    href = "ch${idx + 1}.xhtml",
                    wordCount = plain.split(Regex("\\s+")).count { it.isNotBlank() },
                    charCount = plain.length,
                    startPage = idx * 12 + 1,
                    endPage = (idx + 1) * 12
                )
            )
            htmlMap[idx] = html
        }

        return ParsedPublicationPackage(
            bookId = book.id,
            title = book.title,
            author = book.author,
            description = book.description,
            language = book.language,
            chapters = chapters,
            chapterRawHtmlMap = htmlMap
        )
    }

    private fun buildStarterChapterHtml(chapterIndex: Int, title: String): String {
        return when (chapterIndex) {
            0 -> """
                <h1>$title</h1>
                <p>Deep reading is an active, contemplative cognitive process in which the reader constructs vivid mental models, questions assumptions, and synthesizes new insight from written text.</p>
                <p>Unlike rapid skimming across fragmented digital feeds, sustained immersion in long-form literature activates bilateral neural networks linking visual word-form recognition, semantic memory, and emotional resonance.</p>
                <p>In OmniRead, every typographic parameter—from font weight and line spacing to paragraph rhythm and page margins—is engineered to eliminate visual fatigue and preserve effortless reading flow.</p>
                <p>Tap any sentence on this page while Read-Aloud mode is active to jump the neural voice directly to that exact sentence boundary, or long-press any passage to attach a multi-color highlight, underline, or Markdown study note.</p>
                <p>Whether you prefer crisp Daylight paper, Warm Sepia parchment, Slate Night, or zero-glare AMOLED Pitch Black with a customizable blue-light warmth filter, your eyes remain comfortable across hours of study.</p>
            """.trimIndent()

            1 -> """
                <h1>$title</h1>
                <p>Dual-coding theory demonstrates that human comprehension and long-term retention improve dramatically when visual typography is paired with expressive, natural-prosody spoken audio.</p>
                <p>When your eyes track a highlighted sentence in real time while your ears process pitch contours, cadence, and phrasing, working memory load is distributed across both visual and auditory channels.</p>
                <p>OmniRead's producer-consumer coroutine pipeline pre-buffers the next three sentences ahead of playback, ensuring zero gaps between sentences even during page turns or chapter transitions.</p>
                <p>You can adjust synthesis speed from 0.5x up to 3.5x with pitch preservation, allowing rapid literature review without losing syllable clarity.</p>
            """.trimIndent()

            2 -> """
                <h1>$title</h1>
                <p>Traditional offline text-to-speech engines relied on robotic concatenative diphones or heavyweight cloud servers that compromised reader privacy and required constant connectivity.</p>
                <p>By integrating the quantized Kokoro-82M neural architecture through Sherpa-ONNX and ONNX Runtime Mobile, OmniRead delivers studio-grade 24kHz float32 speech synthesis directly on your Android device.</p>
                <p>With fifty-four distinct speaker embeddings—spanning American, British, and multilingual voices—plus custom voice blending weights, you can cast distinct narrators for fiction and non-fiction alike.</p>
                <p>When connected to Wi-Fi, you can also switch seamlessly to OpenAI TTS HD, ElevenLabs Multilingual v2, or Gemini Audio with automatic local disk caching keyed by book, chapter, sentence hash, and voice ID.</p>
            """.trimIndent()

            3 -> """
                <h1>$title</h1>
                <p>Synchronizing an eBook with an audiobook requires deterministic alignment between character offsets in the document object model and millisecond timestamps in the audio stream.</p>
                <p>Every chapter opened in OmniRead is tokenized into SentenceLocator objects that record the exact start and end character offsets, paragraph index, CSS selector, and EPUB Canonical Fragment Identifier.</p>
                <p>When you convert an EPUB or MOBI book into an M4B audiobook using the Background Studio Worker, OmniRead writes both an enhanced LRC file and a JSON TtsSyncCueEntity sidecar.</p>
                <p>As a result, playing the generated audiobook later still illuminates every spoken sentence inside the reader viewport and automatically turns the page when the narrator reaches the bottom margin.</p>
            """.trimIndent()

            else -> """
                <h1>$title</h1>
                <p>A personal library is more than a folder of files; it is a living archive of ideas, marginalia, bookmarks, and reading milestones accumulated over years of curiosity.</p>
                <p>OmniRead's 9-zone customizable tap grid, vertical edge-swipe brightness and volume controls, and full-text regular expression search give you complete command over every book you own.</p>
                <p>All highlights, underlines, strikethroughs, and Markdown notes can be exported to portable JSON bundles at any time, ensuring your intellectual work stays yours forever.</p>
            """.trimIndent()
        }
    }

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
