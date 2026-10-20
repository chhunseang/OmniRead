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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt

enum class BookshelfFilterTab(val label: String) {
    ALL("All Books"),
    EBOOKS("eBooks"),
    AUDIOBOOKS("Audiobooks"),
    HYBRID_SYNCED("Synced AI"),
    FAVORITES("Favorites"),
    SERIES("Series")
}

data class ReadingAnalyticsSummary(
    val totalHoursRead: Float = 0f,
    val totalWordsRead: Int = 0,
    val averageWpm: Int = 245,
    val currentDailyStreakDays: Int = 1,
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
    private val readingSessionDao: ReadingSessionDao,
    private val scannerService: LibraryScannerService
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    private val allBooksFlow = bookDao.observeAllBooks()

    val watchedFolderUris: StateFlow<Set<String>> = scannerService.watchedFolderUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val filteredBooks: StateFlow<List<BookEntity>> = combine(
        allBooksFlow,
        _uiState
    ) { books, state ->
        books.filter { book ->
            val matchesTab = when (state.filterTab) {
                BookshelfFilterTab.ALL -> true
                BookshelfFilterTab.EBOOKS -> book.mediaType == MediaType.EBOOK
                BookshelfFilterTab.AUDIOBOOKS -> book.mediaType == MediaType.AUDIOBOOK
                BookshelfFilterTab.HYBRID_SYNCED -> book.mediaType == MediaType.HYBRID_SYNCED
                BookshelfFilterTab.FAVORITES -> book.isFavorite
                BookshelfFilterTab.SERIES -> !book.seriesName.isNullOrBlank()
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
            averageWpm = avgWpm.roundToInt().takeIf { it > 0 } ?: 245,
            currentDailyStreakDays = streak,
            booksCompletedCount = completed
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingAnalyticsSummary())

    init {
        seedInitialAnalyticsSessionIfNeeded()
    }

    private fun seedInitialAnalyticsSessionIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            val todayEpochDay = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
            val starterSession = ReadingSessionEntity(
                id = "starter_session_day_$todayEpochDay",
                bookId = "omniread_starter_odyssey",
                mediaType = MediaType.EBOOK,
                sessionDateEpochDay = todayEpochDay,
                startedAt = System.currentTimeMillis() - 1_800_000L,
                endedAt = System.currentTimeMillis(),
                durationMs = 1_800_000L, // 0.5h
                wordsRead = 7_350,
                pagesRead = 29,
                averageWpm = 245,
                startProgression = 0f,
                endProgression = 0.22f,
                usedTtsReadAloud = true
            )
            runCatching {
                readingSessionDao.insertSession(starterSession)
            }
        }
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
                            statusMessage = "Scanned ${summary.scannedFilesCount} files: imported ${summary.importedEbooksCount} eBooks & ${summary.importedAudiobooksCount} Audiobooks"
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
                        it.copy(statusMessage = "Imported \"${imported.title}\" (${imported.format.name})")
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(statusMessage = "Import error: ${err.message}")
                    }
                }
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
        if (sortedEpochDaysDesc.isEmpty()) return 1
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
