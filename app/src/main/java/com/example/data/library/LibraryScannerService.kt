package com.example.data.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.documentfile.provider.DocumentFile
import com.example.data.local.dao.AudioTrackDao
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.entity.AudioTrackEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.MediaType
import com.example.data.reader.EbookParserService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sin

data class FolderScanSummary(
    val scannedFilesCount: Int,
    val importedEbooksCount: Int,
    val importedAudiobooksCount: Int,
    val folderUri: String
)

@Singleton
class LibraryScannerService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val audioTrackDao: AudioTrackDao,
    private val ebookParserService: EbookParserService,
    private val dataStore: DataStore<Preferences>
) {
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    val watchedFolderUris: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_WATCHED_FOLDER_URIS] ?: emptySet()
    }

    suspend fun addWatchedFolderAndScan(treeUriString: String): Result<FolderScanSummary> =
        withContext(ioDispatcher) {
            runCatching {
                val treeUri = Uri.parse(treeUriString)
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                dataStore.edit { prefs ->
                    val existing = prefs[KEY_WATCHED_FOLDER_URIS] ?: emptySet()
                    prefs[KEY_WATCHED_FOLDER_URIS] = existing + treeUriString
                }
                scanSafDirectoryTree(treeUriString)
            }
        }

    suspend fun removeWatchedFolder(treeUriString: String) = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            val existing = prefs[KEY_WATCHED_FOLDER_URIS] ?: emptySet()
            prefs[KEY_WATCHED_FOLDER_URIS] = existing - treeUriString
        }
    }

    suspend fun rescanAllWatchedFolders(): Result<Int> = withContext(ioDispatcher) {
        runCatching {
            val watched = dataStore.data.firstOrNull()?.get(KEY_WATCHED_FOLDER_URIS) ?: emptySet()
            var totalImported = 0
            for (folderUri in watched) {
                runCatching {
                    val summary = scanSafDirectoryTree(folderUri)
                    totalImported += summary.importedEbooksCount + summary.importedAudiobooksCount
                }
            }
            totalImported
        }
    }

    suspend fun importSingleDocumentUri(uriString: String): Result<BookEntity> =
        withContext(ioDispatcher) {
            runCatching {
                val uri = Uri.parse(uriString)
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val docFile = DocumentFile.fromSingleUri(context, uri)
                val fileName = docFile?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "ImportedBook.epub"
                val fileSize = docFile?.length() ?: 0L
                importSingleFile(
                    fileUri = uriString,
                    fileName = fileName,
                    fileSizeBytes = fileSize,
                    parentFolderUri = null
                ) ?: throw IllegalArgumentException("Unsupported book or audio format: $fileName")
            }
        }

    private suspend fun scanSafDirectoryTree(treeUriString: String): FolderScanSummary {
        val treeUri = Uri.parse(treeUriString)
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("Cannot access SAF directory: $treeUriString")

        var scannedFiles = 0
        var importedEbooks = 0
        var importedAudiobooks = 0

        suspend fun traverseDirectory(dir: DocumentFile) {
            val children = dir.listFiles()
            val audioChildren = children.filter { file ->
                file.isFile && isSupportedAudioExtension(file.name.orEmpty())
            }

            // If a subfolder contains multiple MP3/M4A/FLAC files, import as a unified multi-file Audiobook
            if (dir != rootDoc && audioChildren.size >= 2) {
                scannedFiles += audioChildren.size
                importMultiFileAudiobookDirectory(dir, audioChildren, treeUriString)
                importedAudiobooks++
                return
            }

            for (child in children) {
                if (child.isDirectory) {
                    traverseDirectory(child)
                } else if (child.isFile) {
                    val name = child.name ?: continue
                    scannedFiles++
                    val imported = importSingleFile(
                        fileUri = child.uri.toString(),
                        fileName = name,
                        fileSizeBytes = child.length(),
                        parentFolderUri = dir.uri.toString()
                    )
                    if (imported != null) {
                        if (imported.mediaType == MediaType.AUDIOBOOK) {
                            importedAudiobooks++
                        } else {
                            importedEbooks++
                        }
                    }
                }
            }
        }

        traverseDirectory(rootDoc)
        return FolderScanSummary(
            scannedFilesCount = scannedFiles,
            importedEbooksCount = importedEbooks,
            importedAudiobooksCount = importedAudiobooks,
            folderUri = treeUriString
        )
    }

    private suspend fun importSingleFile(
        fileUri: String,
        fileName: String,
        fileSizeBytes: Long,
        parentFolderUri: String?
    ): BookEntity? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXTENSIONS) return null

        val existing = bookDao.getBookByUri(fileUri)
        if (existing != null) return existing

        val format = BookFormat.fromExtension(ext)
        val bookId = "book_${(fileUri.hashCode() and 0x7FFFFFFF)}_${System.currentTimeMillis() % 10000}"
        val rawTitle = fileName.substringBeforeLast('.').replace('_', ' ').trim()

        return if (format.isAudioFormat) {
            val audioMeta = extractSingleAudioFileMetadata(fileUri, bookId, rawTitle)
            val book = BookEntity(
                id = bookId,
                title = audioMeta.title,
                author = audioMeta.author,
                narrator = audioMeta.narrator,
                format = format,
                mediaType = MediaType.AUDIOBOOK,
                fileUri = fileUri,
                parentFolderUri = parentFolderUri,
                coverImagePath = audioMeta.coverPath,
                fileSizeBytes = fileSizeBytes,
                totalChapters = 1,
                totalDurationMs = audioMeta.durationMs
            )
            bookDao.upsertBook(book)
            audioTrackDao.upsertTrack(
                AudioTrackEntity(
                    id = "${bookId}_track_0",
                    bookId = bookId,
                    trackIndex = 0,
                    title = audioMeta.title,
                    fileUri = fileUri,
                    mimeType = format.mimeType,
                    durationMs = audioMeta.durationMs,
                    startOffsetInBookMs = 0L,
                    fileSizeBytes = fileSizeBytes,
                    waveformPeaks = generateDefaultWaveform(bookId.hashCode())
                )
            )
            book
        } else {
            val coverPath = extractEbookCoverImage(fileUri, bookId, format)
            val draftBook = BookEntity(
                id = bookId,
                title = rawTitle,
                author = "Unknown Author",
                format = format,
                mediaType = MediaType.EBOOK,
                fileUri = fileUri,
                parentFolderUri = parentFolderUri,
                coverImagePath = coverPath,
                fileSizeBytes = fileSizeBytes
            )
            val parsedPkg = ebookParserService.parseEbookPackage(draftBook)
            val totalWords = parsedPkg.chapters.sumOf { it.wordCount }
            val finalBook = draftBook.copy(
                title = parsedPkg.title.ifBlank { rawTitle },
                author = parsedPkg.author.ifBlank { "Unknown Author" },
                description = parsedPkg.description,
                language = parsedPkg.language,
                totalChapters = parsedPkg.chapters.size,
                totalPages = max(1, totalWords / 250),
                totalWords = totalWords
            )
            bookDao.upsertBook(finalBook)
            if (parsedPkg.chapters.isNotEmpty()) {
                chapterDao.upsertChapters(parsedPkg.chapters)
            }
            finalBook
        }
    }

    private suspend fun importMultiFileAudiobookDirectory(
        dir: DocumentFile,
        audioFiles: List<DocumentFile>,
        rootWatchedUri: String
    ) {
        val folderUriStr = dir.uri.toString()
        if (bookDao.getBookByUri(folderUriStr) != null) return

        val bookId = "audiobook_folder_${folderUriStr.hashCode() and 0x7FFFFFFF}"
        val sortedFiles = audioFiles.sortedBy { it.name.orEmpty() }
        val firstMeta = extractSingleAudioFileMetadata(
            sortedFiles.first().uri.toString(),
            bookId,
            dir.name ?: "Audiobook Folder"
        )

        var cumulativeMs = 0L
        val tracks = sortedFiles.mapIndexed { idx, docFile ->
            val trackName = docFile.name?.substringBeforeLast('.') ?: "Track ${idx + 1}"
            val itemMeta = extractSingleAudioFileMetadata(docFile.uri.toString(), bookId, trackName)
            val dur = itemMeta.durationMs.coerceAtLeast(30_000L)
            val track = AudioTrackEntity(
                id = "${bookId}_track_$idx",
                bookId = bookId,
                trackIndex = idx,
                title = trackName,
                fileUri = docFile.uri.toString(),
                mimeType = docFile.type ?: "audio/mpeg",
                durationMs = dur,
                startOffsetInBookMs = cumulativeMs,
                fileSizeBytes = docFile.length(),
                waveformPeaks = generateDefaultWaveform(idx + 1)
            )
            cumulativeMs += dur
            track
        }

        val folderBook = BookEntity(
            id = bookId,
            title = dir.name ?: firstMeta.title,
            author = firstMeta.author,
            narrator = firstMeta.narrator ?: "Multi-Track Audiobook",
            format = BookFormat.AUDIO_FOLDER,
            mediaType = MediaType.AUDIOBOOK,
            fileUri = folderUriStr,
            parentFolderUri = rootWatchedUri,
            coverImagePath = firstMeta.coverPath,
            fileSizeBytes = sortedFiles.sumOf { it.length() },
            totalChapters = tracks.size,
            totalDurationMs = cumulativeMs
        )
        bookDao.upsertBook(folderBook)
        audioTrackDao.upsertTracks(tracks)
    }

    private data class ExtractedAudioMeta(
        val title: String,
        val author: String,
        val narrator: String?,
        val durationMs: Long,
        val coverPath: String?
    )

    private fun extractSingleAudioFileMetadata(
        fileUri: String,
        bookId: String,
        fallbackTitle: String
    ): ExtractedAudioMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(fileUri))
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "Unknown Author"
            val narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 120_000L

            val embeddedPic = retriever.embeddedPicture
            val coverPath = if (embeddedPic != null && embeddedPic.isNotEmpty()) {
                val outFile = File(coversDir, "${bookId}_cover.jpg")
                outFile.writeBytes(embeddedPic)
                outFile.absolutePath
            } else null

            ExtractedAudioMeta(title, author, narrator, durationMs, coverPath)
        } catch (e: Exception) {
            ExtractedAudioMeta(fallbackTitle, "Local Audiobook", null, 120_000L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun extractEbookCoverImage(
        fileUri: String,
        bookId: String,
        format: BookFormat
    ): String? {
        return runCatching {
            val uri = Uri.parse(fileUri)
            when (format) {
                BookFormat.EPUB, BookFormat.CBZ -> {
                    val tempZip = File.createTempFile("cover_scan_", ".zip", context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempZip).use { output -> input.copyTo(output) }
                        }
                        ZipFile(tempZip).use { zip ->
                            val imageEntry = zip.entries().asSequence().firstOrNull { entry ->
                                val lower = entry.name.lowercase()
                                !entry.isDirectory &&
                                    (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) &&
                                    (lower.contains("cover") || format == BookFormat.CBZ)
                            } ?: zip.entries().asSequence().firstOrNull { entry ->
                                val lower = entry.name.lowercase()
                                !entry.isDirectory && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))
                            }

                            if (imageEntry != null) {
                                val outFile = File(coversDir, "${bookId}_cover.jpg")
                                zip.getInputStream(imageEntry).use { imgIn ->
                                    FileOutputStream(outFile).use { imgOut -> imgIn.copyTo(imgOut) }
                                }
                                return@runCatching outFile.absolutePath
                            }
                        }
                    } finally {
                        tempZip.delete()
                    }
                    null
                }

                BookFormat.PDF -> {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (renderer.pageCount > 0) {
                                renderer.openPage(0).use { page ->
                                    val bmp = Bitmap.createBitmap(
                                        max(1, page.width),
                                        max(1, page.height),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val outFile = File(coversDir, "${bookId}_cover.png")
                                    FileOutputStream(outFile).use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 85, out)
                                    }
                                    bmp.recycle()
                                    return@runCatching outFile.absolutePath
                                }
                            }
                        }
                    }
                    null
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun isSupportedAudioExtension(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in setOf("m4b", "mp3", "m4a", "flac", "ogg", "wav")
    }

    private fun generateDefaultWaveform(seed: Int): List<Float> {
        return List(96) { idx ->
            (0.22 + 0.58 * kotlin.math.abs(sin((idx + seed) * 0.41))).toFloat().coerceIn(0.12f, 0.96f)
        }
    }

    companion object {
        private val KEY_WATCHED_FOLDER_URIS = stringSetPreferencesKey("omniread_watched_folder_uris")

        val SUPPORTED_EXTENSIONS = setOf(
            "epub", "mobi", "azw3", "pdf", "txt", "fb2", "cbz",
            "m4b", "mp3", "m4a", "flac", "ogg", "wav"
        )
    }
}
