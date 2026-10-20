package com.example.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.BookmarkEntity
import com.example.data.local.entity.MediaType
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
    val hasSyncedTextAvailable: Boolean = true,
    val chapterSyncCues: List<TtsSyncCueEntity> = emptyList(),
    val activeSheet: AudiobookSheetType = AudiobookSheetType.NONE,
    val showCreateBookmarkDialog: Boolean = false,
    val bookmarkNoteDraft: String = "",
    val bookmarkLabelDraft: String = "",
    val statusMessage: String? = null
)

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    private val playerController: AudiobookPlayerController,
    private val bookDao: BookDao,
    private val bookmarkDao: BookmarkDao,
    private val ttsSyncCueDao: TtsSyncCueDao
) : ViewModel() {

    val playbackState: StateFlow<AudiobookPlaybackState> = playerController.playbackState

    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    init {
        seedDefaultAudiobookIfNeeded()
        observeAudiobookLibrary()
        observeActiveChapterCues()
    }

    private fun seedDefaultAudiobookIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            val defaultAudioId = "omniread_starter_audiobook_m4b"
            var existing = bookDao.getBookById(defaultAudioId)
            if (existing == null) {
                val starterAudiobook = BookEntity(
                    id = defaultAudioId,
                    title = "The Art of Deep Reading (Mastered M4B Audiobook)",
                    subtitle = "24kHz Chapterized Edition with LRC/JSON Sync Cues",
                    author = "Elena Vance",
                    narrator = "Kokoro Heart (af_heart)",
                    description = "Full-length chapterized audiobook with embedded chapter markers, RMS waveform peaks, and synchronized sentence cues.",
                    publisher = "OmniRead Audio Studio",
                    language = "en",
                    format = BookFormat.M4B,
                    mediaType = MediaType.HYBRID_SYNCED,
                    fileUri = "file:///internal/starter_audiobook_24k.wav",
                    seriesName = "OmniRead Classics",
                    seriesIndex = 1f,
                    tags = listOf("Audiobook", "M4B", "Synced Text"),
                    totalChapters = 4,
                    totalDurationMs = 48_000L,
                    syncedSidecarPath = "internal://starter_audiobook.sync.json"
                )
                bookDao.upsertBook(starterAudiobook)
                existing = starterAudiobook
            }
            playerController.loadAudiobook(existing.id, autoPlay = false)
        }
    }

    private fun observeAudiobookLibrary() {
        viewModelScope.launch {
            bookDao.observeAllBooks().collectLatest { books ->
                val audioCapable = books.filter {
                    it.mediaType == MediaType.AUDIOBOOK ||
                        it.mediaType == MediaType.HYBRID_SYNCED ||
                        !it.syncedAudiobookUri.isNullOrBlank()
                }
                _uiState.update { state ->
                    state.copy(availableAudiobooks = audioCapable.ifEmpty { books })
                }
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

    fun importAudiobookFileOrFolderUri(uriString: String, displayName: String, isFolder: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = "imported_audio_${uriString.hashCode() and 0x7FFFFFFF}"
            val format = if (isFolder) {
                BookFormat.AUDIO_FOLDER
            } else {
                BookFormat.fromExtension(displayName.substringAfterLast('.', "m4b"))
            }
            val importedBook = BookEntity(
                id = id,
                title = displayName.substringBeforeLast('.').ifBlank { "Imported Audiobook" },
                author = "Local Audiobook",
                narrator = if (isFolder) "Multi-File Folder" else format.name,
                format = format,
                mediaType = MediaType.AUDIOBOOK,
                fileUri = uriString,
                totalDurationMs = 180_000L
            )
            bookDao.upsertBook(importedBook)
            playerController.loadAudiobook(id, autoPlay = true)
            _uiState.update {
                it.copy(statusMessage = "Loaded ${importedBook.title}")
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
