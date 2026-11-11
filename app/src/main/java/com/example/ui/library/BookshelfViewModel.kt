package com.example.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.library.LibraryScannerService
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.ReadingSessionDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.ReadingSessionEntity
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.isAudiobook
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

enum class BookshelfFilterTab(val label: String) {
    ALL("All"),
    READING("Reading"),
    NOT_STARTED("Not started"),
    FINISHED("Finished"),
    FAVORITES("Favorites")
}

/** Where you are in a book. */
enum class ReadingState { NOT_STARTED, READING, FINISHED }

fun BookEntity.readingState(): ReadingState = when {
    readingStatus == ReadingStatus.COMPLETED || progressionPercentage >= 0.99f -> ReadingState.FINISHED
    progressionPercentage > 0.005f || lastOpenedAt != null -> ReadingState.READING
    else -> ReadingState.NOT_STARTED
}

/** The book to pick back up, with the chapter you're in. */
data class ContinueReading(val book: BookEntity, val chapterTitle: String?)

data class ReadingAnalyticsSummary(
    val totalHoursRead: Float = 0f,
    val totalWordsRead: Int = 0,
    val averageWpm: Int = 0,
    val currentDailyStreakDays: Int = 0,
    val booksCompletedCount: Int = 0
)

data class MetadataEditorDraft(
    val book: BookEntity,
    val title: String = book.title,
    val subtitle: String = book.subtitle.orEmpty(),
    val author: String = book.author,
    val narrator: String = book.narrator.orEmpty(),
    val seriesName: String = book.seriesName.orEmpty(),
    val seriesIndexText: String = book.seriesIndex?.toString().orEmpty(),
    val tagsCommaSeparated: String = book.tags.joinToString(", "),
    val collectionsCommaSeparated: String = book.collections.joinToString(", ")
)

data class BookshelfUiState(
    val filterTab: BookshelfFilterTab = BookshelfFilterTab.ALL,
    val searchQuery: String = "",
    val selectedTagFilter: String? = null,
    val isScanningFolders: Boolean = false,
    val editingBookDraft: MetadataEditorDraft? = null,
    val statusMessage: String? = null
)

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: com.example.data.local.dao.ChapterDao,
    private val readingSessionDao: ReadingSessionDao,
    private val scannerService: LibraryScannerService
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    // The Library holds books you read; audiobooks live in Listen
    private val allBooksFlow = bookDao.observeAllBooks().map { books -> books.filter { !it.isAudiobook } }

    /** The book you were last reading (not finished), for the "Continue reading" card. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val continueReading: StateFlow<ContinueReading?> = allBooksFlow
        .map { books ->
            books.filter { it.lastOpenedAt != null && it.readingState() != ReadingState.FINISHED }
                .maxByOrNull { it.lastOpenedAt ?: 0L }
        }
        .distinctUntilChanged()
        .mapLatest { book ->
            book?.let {
                val title = runCatching { chapterDao.getChapterByIndex(it.id, it.currentChapterIndex)?.title }.getOrNull()
                ContinueReading(it, title)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val scanProgress: StateFlow<String?> = scannerService.scanProgress

    /** Every book (unfiltered) – for the sources sheet and tag / collection suggestions. */
    val allBooks: StateFlow<List<BookEntity>> = allBooksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val watchedFolderUris: StateFlow<Set<String>> = scannerService.watchedFolderUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredBooks: StateFlow<List<BookEntity>> = combine(
        allBooksFlow,
        _uiState
    ) { books, state ->
        books.filter { book ->
            val matchesTab = when (state.filterTab) {
                BookshelfFilterTab.ALL -> true
                BookshelfFilterTab.READING -> book.readingState() == ReadingState.READING
                BookshelfFilterTab.NOT_STARTED -> book.readingState() == ReadingState.NOT_STARTED
                BookshelfFilterTab.FINISHED -> book.readingState() == ReadingState.FINISHED
                BookshelfFilterTab.FAVORITES -> book.isFavorite
            }
            val q = state.searchQuery.trim()
            val matchesSearch = q.isEmpty() ||
                book.title.contains(q, ignoreCase = true) ||
                book.author.contains(q, ignoreCase = true) ||
                (book.seriesName?.contains(q, ignoreCase = true) == true) ||
                book.tags.any { it.contains(q, ignoreCase = true) }

            val matchesTag = state.selectedTagFilter == null ||
                book.tags.any { it.equals(state.selectedTagFilter, ignoreCase = true) } ||
                book.collections.any { it.equals(state.selectedTagFilter, ignoreCase = true) }

            matchesTab && matchesSearch && matchesTag
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val analyticsSummary: StateFlow<ReadingAnalyticsSummary> = combine(
        allBooksFlow,
        readingSessionDao.observeTotalReadingDurationMs(),
        readingSessionDao.observeTotalWordsRead(),
        readingSessionDao.observeOverallAverageWpm(),
        readingSessionDao.observeActiveReadingEpochDays()
    ) { books, totalDurMs, totalWords, avgWpm, epochDays ->
        val completed = books.count { it.readingStatus == ReadingStatus.COMPLETED }
        val hours = (totalDurMs.toFloat() / 3_600_000f * 10f).roundToInt() / 10f
        val streak = calculateConsecutiveStreakDays(epochDays)
        ReadingAnalyticsSummary(
            totalHoursRead = hours,
            totalWordsRead = totalWords,
            averageWpm = avgWpm.roundToInt().coerceAtLeast(0),
            currentDailyStreakDays = streak,
            booksCompletedCount = completed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingAnalyticsSummary())

    init {
        purgeLegacyDemoContent()
    }

    /**
     * Earlier builds seeded a fake starter eBook, a synthetic audiobook and a fake reading
     * session. Remove them (and their generated files) so the library only holds the user's own books.
     */
    private fun purgeLegacyDemoContent() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { bookDao.revertHybridBooksToEbooks() }
            LEGACY_DEMO_BOOK_IDS.forEach { id ->
                runCatching {
                    readingSessionDao.deleteSessionsForBook(id)
                    bookDao.deleteBookById(id)
                }
            }
            scannerService.deleteGeneratedFilesForBooks(LEGACY_DEMO_BOOK_IDS)
            runCatching { scannerService.backfillMissingCovers() }
        }
    }

    private companion object {
        val LEGACY_DEMO_BOOK_IDS = listOf(
            "omniread_starter_odyssey",
            "omniread_starter_audiobook_m4b"
        )
    }

    fun dismissStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun selectFilterTab(tab: BookshelfFilterTab) {
        _uiState.update { it.copy(filterTab = tab) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectTagFilter(tag: String?) {
        _uiState.update { state ->
            state.copy(selectedTagFilter = if (state.selectedTagFilter == tag) null else tag)
        }
    }

    fun toggleBookFavorite(book: BookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.updateBook(book.copy(isFavorite = !book.isFavorite))
        }
    }

    fun addAndScanWatchedFolder(treeUriString: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isScanningFolders = true, statusMessage = "Scanning folder for EPUB/MOBI/PDF/M4B/MP3...")
            }
            scannerService.addWatchedFolderAndScan(treeUriString)
                .onSuccess { summary ->
                    _uiState.update {
                        it.copy(
                            isScanningFolders = false,
                            statusMessage = "Added ${summary.importedEbooksCount} books" + (if (summary.importedAudiobooksCount > 0) " · ${summary.importedAudiobooksCount} audiobooks to Listen" else "")
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isScanningFolders = false,
                            statusMessage = "Folder scan failed: ${err.message}"
                        )
                    }
                }
        }
    }

    fun importSingleBookOrAudioUri(uriString: String) {
        viewModelScope.launch {
            scannerService.importSingleDocumentUri(uriString)
                .onSuccess { imported ->
                    _uiState.update {
                        it.copy(statusMessage = if (imported.isAudiobook) "Added \"${imported.title}\" to Listen" else "Added \"${imported.title}\"")
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(statusMessage = "Import error: ${err.message}")
                    }
                }
        }
    }

    fun removeWatchedFolder(treeUri: String) {
        viewModelScope.launch {
            scannerService.removeWatchedFolder(treeUri)
            _uiState.update { it.copy(statusMessage = "Folder removed (its books stay in your library)") }
        }
    }

    fun rescanWatchedFolders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningFolders = true) }
            scannerService.rescanAllWatchedFolders()
                .onSuccess { count ->
                    _uiState.update {
                        it.copy(
                            isScanningFolders = false,
                            statusMessage = "Watched folders synced ($count items verified)"
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isScanningFolders = false) }
                }
        }
    }

    fun openMetadataEditor(book: BookEntity) {
        _uiState.update { it.copy(editingBookDraft = MetadataEditorDraft(book = book)) }
    }

    fun dismissMetadataEditor() {
        _uiState.update { it.copy(editingBookDraft = null) }
    }

    fun updateMetadataDraft(transform: (MetadataEditorDraft) -> MetadataEditorDraft) {
        _uiState.update { state ->
            val current = state.editingBookDraft ?: return@update state
            state.copy(editingBookDraft = transform(current))
        }
    }

    fun saveMetadataEdits() {
        val draft = _uiState.value.editingBookDraft ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val parsedTags = draft.tagsCommaSeparated
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val parsedCollections = draft.collectionsCommaSeparated
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val updatedBook = draft.book.copy(
                title = draft.title.trim().ifBlank { draft.book.title },
                subtitle = draft.subtitle.trim().takeIf { it.isNotEmpty() },
                author = draft.author.trim().ifBlank { "Unknown Author" },
                narrator = draft.narrator.trim().takeIf { it.isNotEmpty() },
                seriesName = draft.seriesName.trim().takeIf { it.isNotEmpty() },
                seriesIndex = draft.seriesIndexText.toFloatOrNull(),
                tags = parsedTags,
                collections = parsedCollections
            )
            bookDao.updateBook(updatedBook)
            _uiState.update {
                it.copy(
                    editingBookDraft = null,
                    statusMessage = "Updated metadata for \"${updatedBook.title}\""
                )
            }
        }
    }

    fun deleteBookFromLibrary(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.deleteBookById(bookId)
            _uiState.update { it.copy(editingBookDraft = null) }
        }
    }

    private fun calculateConsecutiveStreakDays(sortedEpochDaysDesc: List<Long>): Int {
        if (sortedEpochDaysDesc.isEmpty()) return 0
        val todayEpochDay = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
        // A streak only counts if the most recent reading day is today or yesterday.
        if (todayEpochDay - sortedEpochDaysDesc.first() > 1L) return 0
        var streak = 1
        for (i in 0 until sortedEpochDaysDesc.size - 1) {
            val diff = sortedEpochDaysDesc[i] - sortedEpochDaysDesc[i + 1]
            if (diff == 1L) {
                streak++
            } else if (diff > 1L) {
                break
            }
        }
        return streak
    }
}
