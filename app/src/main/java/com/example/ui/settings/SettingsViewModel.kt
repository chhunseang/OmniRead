package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.StudioAudiobookStore
import com.example.data.library.LibraryScannerService
import com.example.data.local.dao.BookDao
import com.example.data.local.entity.isAudiobook
import com.example.data.local.entity.isStudioMade
import com.example.data.reader.EbookParserService
import com.example.data.reader.ReaderPreferencesRepository
import com.example.data.settings.AppSettings
import com.example.data.settings.AppSettingsRepository
import com.example.domain.reader.ReaderPreferences
import com.example.domain.tts.KokoroModelState
import com.example.domain.tts.LocalKokoroTtsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryCounts(val books: Int = 0, val audiobooks: Int = 0, val studio: Int = 0)

data class StorageState(
    val studioBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val rescanning: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettingsRepository,
    private val readerPrefs: ReaderPreferencesRepository,
    private val scanner: LibraryScannerService,
    private val audioStore: StudioAudiobookStore,
    private val parser: EbookParserService,
    kokoro: LocalKokoroTtsProvider,
    bookDao: BookDao
) : ViewModel() {

    val settings: StateFlow<AppSettings> = appSettings.settings
    val reader: StateFlow<ReaderPreferences> = readerPrefs.preferences
    val kokoroState: StateFlow<KokoroModelState> = kokoro.modelState

    val watchedFolders: StateFlow<Set<String>> = scanner.watchedFolderUris
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val counts: StateFlow<LibraryCounts> = bookDao.observeAllBooks()
        .map { books ->
            LibraryCounts(
                books = books.count { !it.isAudiobook },
                audiobooks = books.count { it.isAudiobook },
                studio = books.count { it.isStudioMade }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryCounts())

    private val _storage = MutableStateFlow(StorageState())
    val storage: StateFlow<StorageState> = _storage.asStateFlow()

    init { refreshStorage() }

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { audioStore.pruneOrphanFiles() }
            _storage.update { it.copy(studioBytes = audioStore.studioBytes(), cacheBytes = parser.cacheBytes()) }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) = appSettings.update(transform)

    fun updateReader(transform: (ReaderPreferences) -> ReaderPreferences) {
        viewModelScope.launch { readerPrefs.updatePreferences(transform) }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val freed = parser.clearCaches()
            _storage.update {
                it.copy(cacheBytes = parser.cacheBytes(), message = "Freed ${freed / (1024 * 1024)} MB")
            }
        }
    }

    fun rescanFolders() {
        viewModelScope.launch {
            _storage.update { it.copy(rescanning = true) }
            val result = scanner.rescanAllWatchedFolders()
            _storage.update {
                it.copy(
                    rescanning = false,
                    message = result.fold({ "Folders checked · your library is up to date" }, { e -> "Rescan failed: ${e.message}" })
                )
            }
        }
    }

    fun addFolder(treeUri: String) {
        viewModelScope.launch {
            _storage.update { it.copy(rescanning = true) }
            val result = scanner.addWatchedFolderAndScan(treeUri)
            _storage.update {
                it.copy(
                    rescanning = false,
                    message = result.fold(
                        { s -> "Added ${s.importedEbooksCount} books and ${s.importedAudiobooksCount} audiobooks" },
                        { e -> "Couldn't add that folder: ${e.message}" }
                    )
                )
            }
        }
    }

    fun dismissMessage() = _storage.update { it.copy(message = null) }
}
