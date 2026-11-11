package com.example.data.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
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

    private val _scanProgress = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Human-readable progress while a folder scan is running (null when idle). */
    val scanProgress: kotlinx.coroutines.flow.StateFlow<String?> = _scanProgress

    val watchedFolderUris: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_WATCHED_FOLDER_URIS] ?: emptySet()
    }

    suspend fun addWatchedFolderAndScan(treeUriString: String): Result<FolderScanSummary> =
        withContext(ioDispatcher) {
            runCatching {
                val treeUri = Uri.parse(treeUriString)
                runCatching {
                    persistAccess(treeUri)
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
                    persistAccess(uri)
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

    private data class SafEntry(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val uri: Uri
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** Lists one SAF directory with a single query (much faster than DocumentFile.listFiles()). */
    private fun listSafChildren(treeUri: Uri, parentDocumentId: String): List<SafEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val out = ArrayList<SafEntry>()
        runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    out.add(
                        SafEntry(
                            documentId = id,
                            name = c.getString(1).orEmpty(),
                            mimeType = c.getString(2).orEmpty(),
                            size = if (c.isNull(3)) 0L else c.getLong(3),
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Cannot list folder $parentDocumentId", it) }
        return out
    }

    /**
     * Scans the picked folder AND every subfolder (any depth). Each file is imported on its own,
     * so one unreadable file never stops the rest of the scan.
     */
    private suspend fun scanSafDirectoryTree(treeUriString: String): FolderScanSummary {
        val treeUri = Uri.parse(treeUriString)
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: throw IllegalArgumentException("Cannot access this folder")

        var scannedFiles = 0
        var importedEbooks = 0
        var importedAudiobooks = 0
        _scanProgress.value = "Scanning…"

        val pending = ArrayDeque<Pair<String, Uri>>()
        pending.add(rootId to DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId))
        try {
            while (pending.isNotEmpty()) {
                val (dirId, dirUri) = pending.removeFirst()
                val children = listSafChildren(treeUri, dirId)
                    .filterNot { it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase() }

                children.filter { it.isDirectory }.forEach { pending.add(it.documentId to it.uri) }

                val files = children.filterNot { it.isDirectory }
                val audioFiles = files.filter { isSupportedAudioExtension(it.name) }

                // A subfolder with several audio files is one multi-file audiobook
                val audioHandledAsFolder = dirId != rootId && audioFiles.size >= 2
                if (audioHandledAsFolder) {
                    scannedFiles += audioFiles.size
                    runCatching {
                        val dirDoc = DocumentFile.fromTreeUri(context, dirUri)
                            ?: throw IllegalStateException("Cannot open audiobook folder")
                        val docs = audioFiles.mapNotNull { DocumentFile.fromSingleUri(context, it.uri) }
                        importMultiFileAudiobookDirectory(dirDoc, docs, treeUriString)
                        importedAudiobooks++
                    }.onFailure { Log.w(TAG, "Skipping audiobook folder ${dirUri}", it) }
                }

                for (file in files) {
                    if (audioHandledAsFolder && isSupportedAudioExtension(file.name)) continue
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    if (ext !in SUPPORTED_EXTENSIONS) continue
                    scannedFiles++
                    _scanProgress.value = "Scanning… $scannedFiles found · ${file.name}"
                    val imported = runCatching {
                        importSingleFile(
                            fileUri = file.uri.toString(),
                            fileName = file.name,
                            fileSizeBytes = file.size,
                            parentFolderUri = dirUri.toString()
                        )
                    }.onFailure { Log.w(TAG, "Skipping unreadable file ${file.name}", it) }.getOrNull()
                    if (imported != null) {
                        if (imported.mediaType == MediaType.AUDIOBOOK) importedAudiobooks++ else importedEbooks++
                    }
                }
            }
        } finally {
            _scanProgress.value = null
        }
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
            val track = AudioTrackEntity(
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
            // Chapter markers stored inside the file (.m4b / .m4a)
            val chapters = com.example.data.audio.EmbeddedChapterReader.chaptersForTracks(context, bookId, listOf(track))
            val withChapters = book.copy(totalChapters = chapters.size.coerceAtLeast(1))
            bookDao.upsertBook(withChapters)
            audioTrackDao.upsertTrack(track)
            if (chapters.isNotEmpty()) chapterDao.upsertChapters(chapters)
            withChapters
        } else {
            if (format == BookFormat.PDF) {
                // PDFs: no full-text parse at import (that happens when the book is opened) – just page count
                val pages = runCatching {
                    context.contentResolver.openFileDescriptor(Uri.parse(fileUri), "r")
                        ?.use { pfd -> PdfRenderer(pfd).use { it.pageCount } } ?: 0
                }.getOrDefault(0)
                val pdfBook = BookEntity(
                    id = bookId,
                    title = rawTitle,
                    author = "Unknown Author",
                    format = format,
                    mediaType = MediaType.EBOOK,
                    fileUri = fileUri,
                    parentFolderUri = parentFolderUri,
                    coverImagePath = extractEbookCoverImage(fileUri, bookId, format),
                    fileSizeBytes = fileSizeBytes,
                    totalChapters = pages,
                    totalPages = pages
                )
                bookDao.upsertBook(pdfBook)
                return pdfBook
            }
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

    /**
     * Imports a user-picked folder as ONE audiobook (multi-file audiobook folders such as
     * "01.mp3, 02.mp3, ..." are played back-to-back as a single book).
     */
    suspend fun importAudiobookFolder(treeUriString: String): Result<BookEntity> =
        withContext(ioDispatcher) {
            runCatching {
                val treeUri = Uri.parse(treeUriString)
                runCatching {
                    persistAccess(treeUri)
                }
                val dir = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IllegalArgumentException("Cannot open the selected folder")
                val audioFiles = dir.listFiles().filter { file ->
                    file.isFile && isSupportedAudioExtension(file.name.orEmpty())
                }
                when {
                    audioFiles.isEmpty() ->
                        throw IllegalArgumentException("No audio files (.m4b/.mp3/.m4a/.flac/.ogg/.wav) in this folder")
                    audioFiles.size == 1 -> {
                        val only = audioFiles.first()
                        importSingleFile(
                            fileUri = only.uri.toString(),
                            fileName = only.name.orEmpty(),
                            fileSizeBytes = only.length(),
                            parentFolderUri = dir.uri.toString()
                        ) ?: throw IllegalArgumentException("Unsupported audio file: ${only.name}")
                    }
                    else -> importMultiFileAudiobookDirectory(dir, audioFiles, treeUriString)
                }
            }
        }

    /** Deletes covers / generated audio that belonged to the given (already removed) books. */
    suspend fun deleteGeneratedFilesForBooks(bookIds: List<String>) = withContext(ioDispatcher) {
        bookIds.forEach { id ->
            runCatching { File(context.filesDir, "audiobooks/$id").deleteRecursively() }
            runCatching {
                coversDir.listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
            }
        }
    }

    private suspend fun importMultiFileAudiobookDirectory(
        dir: DocumentFile,
        audioFiles: List<DocumentFile>,
        rootWatchedUri: String
    ): BookEntity {
        val folderUriStr = dir.uri.toString()
        bookDao.getBookByUri(folderUriStr)?.let { return it }

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
            val dur = itemMeta.durationMs.coerceAtLeast(1_000L)
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
        return folderBook
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

    /**
     * Fills in covers for books imported before cover extraction worked (or whose cover file
     * was cleared). Runs once per launch; cheap when every book already has a cover.
     */
    suspend fun backfillMissingCovers() = withContext(ioDispatcher) {
        val books = bookDao.observeAllBooks().firstOrNull().orEmpty()
        for (book in books) {
            val existing = book.coverImagePath
            if (existing != null && File(existing).isFile) continue
            val cover = runCatching {
                when {
                    !book.format.isAudioFormat && book.format != BookFormat.AUDIO_FOLDER ->
                        extractEbookCoverImage(book.fileUri, book.id, book.format)
                    book.format == BookFormat.AUDIO_FOLDER -> {
                        val tracks = audioTrackDao.getTracksForBook(book.id)
                        tracks.firstOrNull()?.let { extractSingleAudioFileMetadata(it.fileUri, book.id, book.title).coverPath }
                    }
                    else -> extractSingleAudioFileMetadata(book.fileUri, book.id, book.title).coverPath
                }
            }.getOrNull()
            if (cover != null) bookDao.updateBook(book.copy(coverImagePath = cover))
        }
    }

    private fun saveCoverBytes(bookId: String, bytes: ByteArray): String? {
        if (bytes.size < 64) return null
        // Validate it decodes, and downscale very large covers to keep the grid fast
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val outFile = File(coversDir, "${bookId}_cover.jpg")
        if (bounds.outHeight > 1200) {
            val sample = Integer.highestOneBit((bounds.outHeight / 900).coerceAtLeast(1))
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bmp.recycle()
        } else {
            outFile.writeBytes(bytes)
        }
        return outFile.absolutePath
    }

    /** Keeps read AND write access to a picked file/folder (write is needed to delete files later). */
    private fun persistAccess(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }
            .recoverCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * Permanently deletes the book's file from the phone (for a multi-file audiobook, its folder).
     * Throws with a readable message when Android doesn't allow it.
     */
    suspend fun deleteBookFile(book: BookEntity): Unit = withContext(ioDispatcher) {
        val uri = Uri.parse(book.fileUri)
        when (uri.scheme) {
            "file" -> {
                val f = File(uri.path.orEmpty())
                if (f.exists() && !f.deleteRecursively()) throw IllegalStateException("Couldn't delete ${f.name}")
            }
            "content" -> {
                val deleted = try {
                    DocumentsContract.deleteDocument(context.contentResolver, uri)
                } catch (e: SecurityException) {
                    throw IllegalStateException(
                        "OmniRead isn't allowed to delete this file. Re-add its folder from Folders & files " +
                            "(new folders allow deleting), or delete it in the Files app."
                    )
                } catch (e: java.io.FileNotFoundException) {
                    true // already gone
                }
                if (!deleted) throw IllegalStateException("Android refused to delete the file")
            }
            else -> throw IllegalStateException("This book has no file that can be deleted")
        }
        runCatching { coversDir.listFiles()?.filter { it.name.startsWith("${book.id}_") }?.forEach { it.delete() } }
        Unit
    }

    /** Re-extracts the cover embedded in the book file (used by "Reset cover" in the editor). */
    suspend fun extractCoverFromFile(book: BookEntity): String? = withContext(ioDispatcher) {
        coversDir.listFiles()?.filter { it.name.startsWith("${book.id}_cover") }?.forEach { it.delete() }
        if (book.format.isAudioFormat) extractSingleAudioFileMetadata(book.fileUri, book.id, book.title).coverPath
        else extractEbookCoverImage(book.fileUri, book.id, book.format)
    }

    /** Copies a user-picked image to be the book's cover. Returns the stored path. */
    suspend fun saveCustomCover(bookId: String, imageUri: String): String? = withContext(ioDispatcher) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { it.readBytes() }
                ?: return@runCatching null
            coversDir.listFiles()?.filter { it.name.startsWith("${bookId}_cover") }?.forEach { it.delete() }
            // New file name each time so image caches pick up the change
            val out = File(coversDir, "${bookId}_cover_${System.currentTimeMillis()}.jpg")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            val scale = (1200f / bmp.height).coerceAtMost(1f)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            FileOutputStream(out).use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            out.absolutePath
        }.getOrNull()
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
                        } ?: return@runCatching null
                        ZipFile(tempZip).use { zip ->
                            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                            fun isImage(name: String) = name.lowercase().let {
                                it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") ||
                                    it.endsWith(".webp") || it.endsWith(".gif")
                            }

                            // 1. The cover declared in the EPUB package (EPUB3 cover-image / EPUB2 meta name="cover")
                            val declared: String? = if (format == BookFormat.EPUB) runCatching {
                                val container = zip.getEntry("META-INF/container.xml") ?: return@runCatching null
                                val opfPath = Jsoup.parse(zip.getInputStream(container).bufferedReader().readText(), "", Parser.xmlParser())
                                    .selectFirst("rootfile")?.attr("full-path") ?: return@runCatching null
                                val opfEntry = zip.getEntry(opfPath) ?: return@runCatching null
                                val opf = Jsoup.parse(zip.getInputStream(opfEntry).bufferedReader().readText(), "", Parser.xmlParser())
                                val items = opf.select("manifest > item")
                                val coverId = opf.select("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
                                val item = items.firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
                                    ?: items.firstOrNull { coverId != null && it.attr("id") == coverId }
                                    ?: items.firstOrNull {
                                        it.attr("media-type").startsWith("image/") && it.attr("id").contains("cover", true)
                                    }
                                val href = item?.attr("href") ?: return@runCatching null
                                val opfDir = opfPath.substringBeforeLast('/', "")
                                val decoded = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
                                val parts = ArrayList<String>()
                                ("$opfDir/$decoded").split('/').forEach { seg ->
                                    when (seg) {
                                        "", "." -> Unit
                                        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                                        else -> parts.add(seg)
                                    }
                                }
                                parts.joinToString("/")
                            }.getOrNull() else null

                            val entry = declared?.let { zip.getEntry(it) }
                                ?: entries.firstOrNull { isImage(it.name) && it.name.contains("cover", ignoreCase = true) }
                                ?: if (format == BookFormat.CBZ) entries.filter { isImage(it.name) }.minByOrNull { it.name } else null
                                ?: entries.filter { isImage(it.name) }.maxByOrNull { it.size }
                            entry?.let { saveCoverBytes(bookId, zip.getInputStream(it).use { s -> s.readBytes() }) }
                        }
                    } finally {
                        tempZip.delete()
                    }
                }

                BookFormat.MOBI, BookFormat.AZW3 -> {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    extractMobiCover(bytes)?.let { saveCoverBytes(bookId, it) }
                }

                BookFormat.FB2 -> {
                    val xml = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.toString(Charsets.UTF_8) ?: return@runCatching null
                    val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                    val href = doc.selectFirst("coverpage image")?.attributes()?.firstOrNull { it.key.endsWith("href") }
                        ?.value?.removePrefix("#") ?: return@runCatching null
                    val b64 = doc.select("binary").firstOrNull { it.attr("id") == href }?.text() ?: return@runCatching null
                    saveCoverBytes(bookId, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                }

                BookFormat.PDF -> {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (renderer.pageCount > 0) {
                                renderer.openPage(0).use { page ->
                                    val scale = (600f / page.width.coerceAtLeast(1)).coerceAtMost(2f)
                                    val bmp = Bitmap.createBitmap(
                                        max(1, (page.width * scale).toInt()),
                                        max(1, (page.height * scale).toInt()),
                                        Bitmap.Config.ARGB_8888
                                    )
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val outFile = File(coversDir, "${bookId}_cover.jpg")
                                    FileOutputStream(outFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 88, out) }
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

    /** Reads the cover image record of a MOBI/AZW3 file via the EXTH 201 (cover offset) record. */
    private fun extractMobiCover(bytes: ByteArray): ByteArray? {
        if (bytes.size < 78) return null
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        val numRecords = buf.getShort(76).toInt() and 0xFFFF
        if (numRecords < 2 || 78 + numRecords * 8 > bytes.size) return null
        val offsets = IntArray(numRecords) { buf.getInt(78 + it * 8) }
        val rec0 = offsets[0]
        val mobi = rec0 + 16
        if (mobi + 0x84 > bytes.size || String(bytes, mobi, 4, Charsets.US_ASCII) != "MOBI") return null
        val headerLength = buf.getInt(mobi + 4)
        val firstImageIndex = buf.getInt(mobi + 0x5C)
        val exthFlags = buf.getInt(mobi + 0x70)
        if (exthFlags and 0x40 == 0 || firstImageIndex <= 0) return null
        val exth = mobi + headerLength
        if (exth + 12 > bytes.size || String(bytes, exth, 4, Charsets.US_ASCII) != "EXTH") return null
        val count = buf.getInt(exth + 8)
        var pos = exth + 12
        var coverOffset = -1
        var thumbOffset = -1
        repeat(count) {
            if (pos + 8 > bytes.size) return@repeat
            val type = buf.getInt(pos)
            val len = buf.getInt(pos + 4)
            if (len < 8) return null
            if (type == 201 && len >= 12) coverOffset = buf.getInt(pos + 8)
            if (type == 202 && len >= 12) thumbOffset = buf.getInt(pos + 8)
            pos += len
        }
        val idx = firstImageIndex + (if (coverOffset >= 0) coverOffset else thumbOffset.takeIf { it >= 0 } ?: return null)
        if (idx !in 0 until numRecords) return null
        val end = if (idx + 1 < numRecords) offsets[idx + 1] else bytes.size
        if (offsets[idx] !in 0 until end || end > bytes.size) return null
        return bytes.copyOfRange(offsets[idx], end)
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
        private const val TAG = "LibraryScanner"
        private val KEY_WATCHED_FOLDER_URIS = stringSetPreferencesKey("omniread_watched_folder_uris")

        val SUPPORTED_EXTENSIONS = setOf(
            "epub", "mobi", "azw3", "pdf", "txt", "fb2", "cbz",
            "m4b", "mp3", "m4a", "flac", "ogg", "wav"
        )
    }
}
