package com.example.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.library.LibraryScannerService
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.isAudiobook
import com.example.data.local.entity.isStudioMade
import com.example.data.local.entity.TtsSyncCueEntity
import com.example.domain.audio.AudiobookPlaybackState
import com.example.domain.audio.AudiobookPlayerController
import com.example.domain.audio.SleepTimerMode
import com.example.domain.audio.VoiceBoostEqConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Listen shelf filter. */
enum class ShelfFilter(val label: String) { ALL("All"), YOURS("Your files"), STUDIO("Made in Studio") }

enum class AudiobookSheetType {
    NONE,
    CHAPTERS_AND_TRACKS,
    SPEED_PITCH_AND_EQ,
    SLEEP_TIMER,
    AUDIO_BOOKMARKS
}

data class AudiobookPlayerUiState(
    val availableAudiobooks: List<BookEntity> = emptyList(),
    val isSyncedTextViewMode: Boolean = false,
    val hasSyncedTextAvailable: Boolean = false,
    val chapterSyncCues: List<TtsSyncCueEntity> = emptyList(),
    val activeSheet: AudiobookSheetType = AudiobookSheetType.NONE,
    val showCreateBookmarkDialog: Boolean = false,
    val bookmarkNoteDraft: String = "",
    val bookmarkLabelDraft: String = "",
    val statusMessage: String? = null,
    /** The full-screen "now playing" view is open (otherwise the shelf + mini player show). */
    val playerExpanded: Boolean = false,
    /** Which audiobooks the shelf shows: all, the files you added, or Studio narrations. */
    val shelfFilter: ShelfFilter = ShelfFilter.ALL
)

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    private val playerController: AudiobookPlayerController,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val ttsSyncCueDao: TtsSyncCueDao,
    private val scannerService: LibraryScannerService,
    private val audioStore: com.example.data.audio.StudioAudiobookStore
) : ViewModel() {

    val playbackState: StateFlow<AudiobookPlaybackState> = playerController.playbackState

    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    init {
        observeAudiobookLibrary()
        viewModelScope.launch { runCatching { audioStore.pruneOrphanFiles() } }
        observeActiveChapterCues()
    }

    private fun observeAudiobookLibrary() {
        viewModelScope.launch {
            bookDao.observeAllBooks().collectLatest { books ->
                // Only real audiobooks (files you added + Studio narrations); books stay in Library.
                // Most recently listened first, then newest additions.
                val audiobooks = books.filter { it.isAudiobook }
                    .sortedByDescending { maxOf(it.lastOpenedAt ?: 0L, it.addedAt) }
                _uiState.update { state ->
                    state.copy(availableAudiobooks = audiobooks)
                }
            }
        }
    }

    // --- Shelf: filter and per-book actions ---

    fun setShelfFilter(filter: ShelfFilter) = _uiState.update { it.copy(shelfFilter = filter) }

    fun shareIntent(book: BookEntity): android.content.Intent? = audioStore.shareIntent(book)

    fun suggestedFileName(book: BookEntity) = audioStore.suggestedFileName(book)

    fun mimeType(book: BookEntity) = audioStore.mimeType(book)

    fun fileSizeBytes(book: BookEntity) = audioStore.sizeBytes(book)

    fun saveToDevice(book: BookEntity, destination: String) {
        viewModelScope.launch {
            audioStore.saveTo(book, android.net.Uri.parse(destination))
                .onSuccess { _uiState.update { it.copy(statusMessage = "Saved \"${book.title}\"") } }
                .onFailure { err -> _uiState.update { it.copy(statusMessage = "Couldn't save: ${err.message}") } }
        }
    }

    /** Studio narrations are deleted with their files; audiobooks you added just leave Listen. */
    fun deleteOrRemove(book: BookEntity) {
        viewModelScope.launch {
            if (book.isStudioMade) {
                audioStore.deleteStudioAudiobook(book)
                _uiState.update { it.copy(statusMessage = "Deleted \"${book.title}\"") }
            } else {
                audioStore.removeFromListen(book)
                _uiState.update { it.copy(statusMessage = "Removed \"${book.title}\" from Listen · the file is still on your phone") }
            }
        }
    }

    fun setFinished(book: BookEntity, finished: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = if (finished) {
                book.copy(readingStatus = ReadingStatus.COMPLETED, progressionPercentage = 1f, completedAt = System.currentTimeMillis())
            } else {
                book.copy(
                    readingStatus = ReadingStatus.UNREAD, progressionPercentage = 0f, completedAt = null,
                    currentAudioTrackIndex = 0, currentAudioPositionMs = 0L, currentChapterIndex = 0
                )
            }
            bookDao.updateBook(updated)
            if (!finished && playbackState.value.activeBook?.id == book.id) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { playerController.seekToBookPosition(0L) }
            }
        }
    }

    private fun observeActiveChapterCues() {
        viewModelScope.launch {
            playbackState.collectLatest { pb ->
                val book = pb.activeBook ?: return@collectLatest
                val cues = ttsSyncCueDao.getCuesForChapter(book.id, pb.currentChapterIndex)
                val hasSync = cues.isNotEmpty() ||
                    book.mediaType == MediaType.HYBRID_SYNCED ||
                    !book.syncedSidecarPath.isNullOrBlank()
                _uiState.update {
                    it.copy(
                        chapterSyncCues = cues,
                        hasSyncedTextAvailable = hasSync
                    )
                }
            }
        }
    }

    fun selectAndLoadAudiobook(bookId: String, autoPlay: Boolean = false) {
        viewModelScope.launch {
            playerController.loadAudiobook(bookId, autoPlay = autoPlay)
        }
    }

    /** Tapping a book on the shelf: start (or resume) it and open the full player. */
    fun openAudiobook(bookId: String) {
        _uiState.update { it.copy(playerExpanded = true) }
        if (playbackState.value.activeBook?.id == bookId) {
            if (!playbackState.value.isPlaying) playerController.play()
            return
        }
        viewModelScope.launch {
            playerController.loadAudiobook(bookId, autoPlay = true)
                .onFailure { err ->
                    _uiState.update { it.copy(playerExpanded = false, statusMessage = "Couldn't play this audiobook: ${err.message}") }
                }
        }
    }

    fun expandPlayer() = _uiState.update { it.copy(playerExpanded = true) }

    fun collapsePlayer() = _uiState.update { it.copy(playerExpanded = false, activeSheet = AudiobookSheetType.NONE) }

    fun dismissStatus() = _uiState.update { it.copy(statusMessage = null) }

    fun importAudiobookFileOrFolderUri(uriString: String, displayName: String, isFolder: Boolean) {
        viewModelScope.launch {
            val result = if (isFolder) {
                scannerService.importAudiobookFolder(uriString)
            } else {
                scannerService.importSingleDocumentUri(uriString)
            }
            result
                .onSuccess { imported ->
                    if (imported.mediaType == MediaType.EBOOK) {
                        _uiState.update {
                            it.copy(statusMessage = "\"${imported.title}\" is an eBook – added to your Library")
                        }
                        return@onSuccess
                    }
                    playerController.loadAudiobook(imported.id, autoPlay = true)
                        .onFailure { err ->
                            _uiState.update { it.copy(statusMessage = "Could not play ${imported.title}: ${err.message}") }
                        }
                    _uiState.update { it.copy(statusMessage = "Added \"${imported.title}\"", playerExpanded = true) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(statusMessage = "Import failed ($displayName): ${err.message}") }
                }
        }
    }

    fun togglePlayPause() = playerController.togglePlayPause()

    fun seekToTrackPosition(positionMs: Long) = playerController.seekToTrackPosition(positionMs)

    fun seekToBookPosition(bookPositionMs: Long) = playerController.seekToBookPosition(bookPositionMs)

    fun seekToChapter(chapterIndex: Int) {
        playerController.seekToChapter(chapterIndex)
        _uiState.update { it.copy(activeSheet = AudiobookSheetType.NONE) }
    }

    fun seekToSyncCue(cue: TtsSyncCueEntity) {
        playerController.seekToBookPosition(cue.startTimeMs)
        playerController.play()
    }

    fun skipForward() = playerController.skipForward()

    fun skipBackward() = playerController.skipBackward()

    fun setCustomSkipIntervals(forwardSec: Int, backwardSec: Int) {
        playerController.setCustomSkipIntervals(
            forwardMs = forwardSec * 1_000L,
            backwardMs = backwardSec * 1_000L
        )
    }

    fun setPlaybackSpeed(speed: Float) = playerController.setPlaybackSpeed(speed)

    fun setPlaybackPitch(pitch: Float) = playerController.setPlaybackPitch(pitch)

    fun setSkipSilence(enabled: Boolean) = playerController.setSkipSilenceEnabled(enabled)

    fun setVoiceBoostEq(enabled: Boolean, gainMb: Int = 600) {
        playerController.setVoiceBoostEq(
            VoiceBoostEqConfig(enabled = enabled, loudnessGainMb = gainMb)
        )
    }

    fun startSleepTimer(mode: SleepTimerMode, minutes: Int, shakeToReset: Boolean = true) {
        playerController.startSleepTimer(mode, minutes, shakeToReset)
        _uiState.update {
            it.copy(
                activeSheet = AudiobookSheetType.NONE,
                statusMessage = if (mode == SleepTimerMode.OFF) {
                    "Sleep timer cancelled"
                } else {
                    "Sleep timer set (${mode.name.replace('_', ' ')})"
                }
            )
        }
    }

    fun cancelSleepTimer() {
        playerController.cancelSleepTimer()
    }

    fun toggleSyncedTextViewMode() {
        _uiState.update { it.copy(isSyncedTextViewMode = !it.isSyncedTextViewMode) }
    }

    fun openBottomSheet(sheet: AudiobookSheetType) {
        _uiState.update { current ->
            val next = if (current.activeSheet == sheet) AudiobookSheetType.NONE else sheet
            current.copy(activeSheet = next)
        }
    }

    fun openCreateBookmarkDialog() {
        val currentMs = playbackState.value.totalBookPositionMs
        val mins = (currentMs / 60_000L)
        val secs = (currentMs % 60_000L) / 1000L
        _uiState.update {
            it.copy(
                showCreateBookmarkDialog = true,
                bookmarkLabelDraft = String.format("Audio Note @ %02d:%02d", mins, secs),
                bookmarkNoteDraft = ""
            )
        }
    }

    fun dismissCreateBookmarkDialog() {
        _uiState.update { it.copy(showCreateBookmarkDialog = false) }
    }

    fun updateBookmarkDraft(label: String? = null, note: String? = null) {
        _uiState.update {
            it.copy(
                bookmarkLabelDraft = label ?: it.bookmarkLabelDraft,
                bookmarkNoteDraft = note ?: it.bookmarkNoteDraft
            )
        }
    }

    fun saveAudioBookmark() {
        val state = _uiState.value
        viewModelScope.launch {
            playerController.createAudioBookmark(
                noteMarkdown = state.bookmarkNoteDraft,
                label = state.bookmarkLabelDraft
            ).onSuccess { bm ->
                _uiState.update {
                    it.copy(
                        showCreateBookmarkDialog = false,
                        statusMessage = "Saved audio bookmark: ${bm.label}"
                    )
                }
            }
        }
    }

    fun jumpToBookmark(bookmark: BookmarkEntity) {
        viewModelScope.launch {
            playerController.jumpToAudioBookmark(bookmark)
            _uiState.update { it.copy(activeSheet = AudiobookSheetType.NONE) }
        }
    }

    fun deleteBookmark(bookmarkId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkDao.deleteBookmarkById(bookmarkId)
        }
    }
}
