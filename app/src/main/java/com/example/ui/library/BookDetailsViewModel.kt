package com.example.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.library.LibraryScannerService
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.ReadingSessionDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.ReadingSessionEntity
import com.example.data.local.entity.ReadingStatus
import com.example.data.reader.EbookParserService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Reading statistics for one book, derived from its reading sessions. */
data class BookReadingStats(
    val totalReadMs: Long = 0L,
    val sessionCount: Int = 0,
    val averageWpm: Int = 0,
    val wordsRead: Int = 0,
    val firstReadAt: Long? = null,
    val lastReadAt: Long? = null,
    val readingDays: Int = 0,
    val estimatedRemainingMs: Long? = null
)

/** Everything the metadata editor can change (Calibre-style fields). */
data class BookMetadataDraft(
    val title: String,
    val subtitle: String,
    val authors: String,
    val narrator: String,
    val seriesName: String,
    val seriesIndex: String,
    val publisher: String,
    val publishedDate: String,
    val language: String,
    val isbn: String,
    val description: String,
    val tags: List<String>,
    val collections: List<String>,
    val readingStatus: ReadingStatus,
    val coverPath: String?
) {
    companion object {
        fun from(book: BookEntity) = BookMetadataDraft(
            title = book.title,
            subtitle = book.subtitle.orEmpty(),
            authors = book.author,
            narrator = book.narrator.orEmpty(),
            seriesName = book.seriesName.orEmpty(),
            seriesIndex = book.seriesIndex?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }.orEmpty(),
            publisher = book.publisher.orEmpty(),
            publishedDate = book.publishedDate.orEmpty(),
            language = book.language,
            isbn = book.isbn.orEmpty(),
            description = book.description.orEmpty(),
            tags = book.tags,
            collections = book.collections,
            readingStatus = book.readingStatus,
            coverPath = book.coverImagePath
        )
    }
}

data class BookDetailsState(
    val book: BookEntity? = null,
    val sessions: List<ReadingSessionEntity> = emptyList(),
    val stats: BookReadingStats = BookReadingStats(),
    val filePath: String = "",
    val folderPath: String? = null,
    val draft: BookMetadataDraft? = null,
    val busy: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class BookDetailsViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val readingSessionDao: ReadingSessionDao,
    private val scannerService: LibraryScannerService,
    private val parserService: EbookParserService
) : ViewModel() {

    private val _state = MutableStateFlow(BookDetailsState())
    val state: StateFlow<BookDetailsState> = _state.asStateFlow()
    private var observeJob: Job? = null

    fun load(bookId: String) {
        if (_state.value.book?.id == bookId) return
        observeJob?.cancel()
        _state.value = BookDetailsState()
        observeJob = viewModelScope.launch {
            combine(bookDao.observeBookById(bookId), readingSessionDao.observeSessionsForBook(bookId)) { book, sessions ->
                book to sessions
            }.collect { (book, sessions) ->
                _state.update {
                    it.copy(
                        book = book,
                        sessions = sessions.sortedByDescending { s -> s.startedAt },
                        stats = computeStats(book, sessions),
                        filePath = StoragePaths.readable(book?.fileUri),
                        folderPath = book?.parentFolderUri?.let { p -> StoragePaths.readable(p) }
                    )
                }
            }
        }
    }

    private fun computeStats(book: BookEntity?, sessions: List<ReadingSessionEntity>): BookReadingStats {
        if (sessions.isEmpty()) return BookReadingStats()
        val total = sessions.sumOf { it.durationMs }
        // Sessions where the reader jumped ahead (e.g. via Contents) aren't real reading – cap them
        // at a fast-but-human 600 words per minute.
        fun realisticWords(s: ReadingSessionEntity): Int =
            minOf(s.wordsRead.toLong(), s.durationMs * MAX_WPM / 60_000L).toInt()
        val words = sessions.sumOf { realisticWords(it) }
        val timedSessions = sessions.filter { it.durationMs >= 60_000L && it.averageWpm in 1..MAX_WPM }
        val timedMinutes = timedSessions.sumOf { it.durationMs } / 60_000.0
        val wpm = if (timedMinutes >= 1.0) (timedSessions.sumOf { realisticWords(it) } / timedMinutes).toInt() else 0
        val remainingWords = book?.let { ((1f - it.progressionPercentage).coerceIn(0f, 1f) * it.totalWords).toInt() } ?: 0
        return BookReadingStats(
            totalReadMs = total,
            sessionCount = sessions.size,
            averageWpm = wpm,
            wordsRead = words,
            firstReadAt = sessions.minOf { it.startedAt },
            lastReadAt = sessions.maxOf { it.endedAt },
            readingDays = sessions.map { it.sessionDateEpochDay }.distinct().size,
            estimatedRemainingMs = if (wpm > 0 && remainingWords > 0) remainingWords * 60_000L / wpm else null
        )
    }

    // --- Quick actions ---

    fun setReadingStatus(status: ReadingStatus) {
        val book = _state.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.updateBook(
                book.copy(
                    readingStatus = status,
                    completedAt = if (status == ReadingStatus.COMPLETED) System.currentTimeMillis() else book.completedAt
                )
            )
        }
    }

    fun toggleFavorite() {
        val book = _state.value.book ?: return
        viewModelScope.launch(Dispatchers.IO) { bookDao.updateBook(book.copy(isFavorite = !book.isFavorite)) }
    }

    /** Removes the book from the library; with [deleteFile] also deletes its file from the phone. */
    fun removeFromLibrary(deleteFile: Boolean = false, onDone: () -> Unit) {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            if (deleteFile) {
                val result = runCatching { scannerService.deleteBookFile(book) }
                if (result.isFailure) {
                    // Keep the book so nothing is lost; explain why the file stayed
                    _state.update { it.copy(message = result.exceptionOrNull()?.message ?: "Couldn't delete the file") }
                    return@launch
                }
            }
            withContext(Dispatchers.IO) {
                readingSessionDao.deleteSessionsForBook(book.id)
                bookDao.deleteBookById(book.id)
            }
            onDone()
        }
    }

    // --- Metadata editor ---

    fun startEditing() {
        val book = _state.value.book ?: return
        _state.update { it.copy(draft = BookMetadataDraft.from(book)) }
    }

    fun cancelEditing() = _state.update { it.copy(draft = null) }

    fun updateDraft(transform: (BookMetadataDraft) -> BookMetadataDraft) {
        _state.update { s -> s.draft?.let { s.copy(draft = transform(it)) } ?: s }
    }

    fun pickCustomCover(imageUri: String) {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val path = scannerService.saveCustomCover(book.id, imageUri)
            _state.update {
                it.copy(
                    busy = false,
                    draft = it.draft?.copy(coverPath = path ?: it.draft.coverPath),
                    message = if (path == null) "Couldn't use that image" else null
                )
            }
        }
    }

    fun resetCoverFromFile() {
        val book = _state.value.book ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val path = scannerService.extractCoverFromFile(book)
            _state.update {
                it.copy(
                    busy = false,
                    draft = it.draft?.copy(coverPath = path),
                    message = if (path == null) "No cover found inside the file" else "Cover restored from the file"
                )
            }
        }
    }

    /** Re-reads title / author / description / language from the book file into the draft. */
    fun fillFromFile() {
        val book = _state.value.book ?: return
        if (book.format.isAudioFormat || book.format == BookFormat.PDF) {
            _state.update { it.copy(message = "This file type doesn't carry book metadata") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val pkg = withContext(Dispatchers.IO) { runCatching { parserService.parseEbookPackage(book) }.getOrNull() }
            _state.update { s ->
                s.copy(
                    busy = false,
                    draft = if (pkg == null) s.draft else s.draft?.copy(
                        title = pkg.title.ifBlank { s.draft.title },
                        authors = pkg.author.ifBlank { s.draft.authors },
                        description = pkg.description?.takeIf { it.isNotBlank() } ?: s.draft.description,
                        language = pkg.language.ifBlank { s.draft.language }
                    ),
                    message = if (pkg == null) "Couldn't read the file" else "Filled in from the book file"
                )
            }
        }
    }

    fun saveDraft() {
        val book = _state.value.book ?: return
        val d = _state.value.draft ?: return
        viewModelScope.launch(Dispatchers.IO) {
            bookDao.updateBook(
                book.copy(
                    title = d.title.trim().ifBlank { book.title },
                    subtitle = d.subtitle.trim().ifBlank { null },
                    author = d.authors.split('&', ';').map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString(" & ").ifBlank { "Unknown Author" },
                    narrator = d.narrator.trim().ifBlank { null },
                    seriesName = d.seriesName.trim().ifBlank { null },
                    seriesIndex = d.seriesIndex.trim().toFloatOrNull(),
                    publisher = d.publisher.trim().ifBlank { null },
                    publishedDate = d.publishedDate.trim().ifBlank { null },
                    language = d.language.trim().ifBlank { book.language },
                    isbn = d.isbn.filter { it.isDigit() || it == 'X' || it == 'x' }.ifBlank { null },
                    description = d.description.trim().ifBlank { null },
                    tags = d.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    collections = d.collections.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                    readingStatus = d.readingStatus,
                    coverImagePath = d.coverPath
                )
            )
            _state.update { it.copy(draft = null, message = "Saved") }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val MAX_WPM = 600
    }
}
