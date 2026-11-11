package com.example.data.audio

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.local.dao.AudioTrackDao
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.BookmarkDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.isStudioMade
import com.example.domain.audio.AudiobookPlayerController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything you can do with an audiobook's files: share or save a Studio narration, delete it,
 * or take an audiobook you added out of Listen (your own file is never touched).
 */
@Singleton
class StudioAudiobookStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val audioTrackDao: AudioTrackDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val ttsSyncCueDao: TtsSyncCueDao,
    private val playerController: AudiobookPlayerController
) {
    private val studioRoot get() = File(context.filesDir, "audiobooks")

    /** The narration's audio file on this phone (Studio audiobooks only). */
    fun audioFile(book: BookEntity): File? {
        if (!book.isStudioMade) return null
        val path = Uri.parse(book.fileUri).path ?: return null
        return File(path).takeIf { it.isFile }
    }

    fun sizeBytes(book: BookEntity): Long = audioFile(book)?.length() ?: book.fileSizeBytes

    /** A file name people recognise: "Lord of Mysteries · Chapter 1395.m4b". */
    fun suggestedFileName(book: BookEntity): String {
        val ext = audioFile(book)?.extension?.ifBlank { null } ?: "m4b"
        val safe = book.title.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return "$safe.$ext"
    }

    fun mimeType(book: BookEntity): String = when (audioFile(book)?.extension?.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        else -> "audio/mp4"
    }

    /** A share sheet for the narration (messaging apps, Drive, Bluetooth…). */
    fun shareIntent(book: BookEntity): Intent? {
        val file = audioFile(book) ?: return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            .buildUpon().appendQueryParameter("name", suggestedFileName(book)).build()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType(book)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, suggestedFileName(book))
            putExtra(Intent.EXTRA_SUBJECT, book.title)
            clipData = ClipData.newRawUri(book.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share \"${book.title}\"")
    }

    /** Copies the narration to a place you picked (Downloads, an SD card, Drive…). */
    suspend fun saveTo(book: BookEntity, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val file = audioFile(book) ?: error("This audiobook's file is missing")
            context.contentResolver.openOutputStream(destination, "w")?.use { out ->
                file.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: error("Couldn't write to that location")
            Unit
        }
    }

    /** Deletes a Studio narration and its files for good. */
    suspend fun deleteStudioAudiobook(book: BookEntity) = withContext(Dispatchers.IO) {
        audioFile(book)?.let { audio ->
            val base = audio.nameWithoutExtension
            audio.delete()
            File(audio.parentFile, "$base.lrc").delete()
            File(audio.parentFile, "$base.sync.json").delete()
            audio.parentFile?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
        book.syncedSidecarPath?.let { File(it).takeIf { f -> f.path.startsWith(studioRoot.path) }?.delete() }
        forget(book)
    }

    /** Takes an audiobook you added out of Listen; the file itself stays on your phone. */
    suspend fun removeFromListen(book: BookEntity) = withContext(Dispatchers.IO) { forget(book) }

    private suspend fun forget(book: BookEntity) {
        if (playerController.playbackState.value.activeBook?.id == book.id) {
            withContext(Dispatchers.Main) { playerController.unload() }
        }
        audioTrackDao.deleteTracksForBook(book.id)
        chapterDao.deleteChaptersForBook(book.id)
        ttsSyncCueDao.deleteCuesForBook(book.id)
        bookmarkDao.deleteBookmarksForBook(book.id)
        bookDao.deleteBookById(book.id)
    }

    /** Total space used by Studio narrations. */
    fun studioBytes(): Long = studioRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Removes narration files no audiobook points at any more (left behind by older builds
     * or interrupted jobs). In-progress conversion scratch files are left alone.
     */
    suspend fun pruneOrphanFiles() = withContext(Dispatchers.IO) {
        if (!studioRoot.isDirectory) return@withContext
        val books = bookDao.getAllBooksOnce()
        val keep = books.mapNotNull { b -> Uri.parse(b.fileUri).path?.let { File(it).nameWithoutExtension } }.toSet()
        val dayAgo = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        studioRoot.walkBottomUp().forEach { f ->
            when {
                f.isDirectory -> if (f != studioRoot && f.list().isNullOrEmpty()) f.delete()
                f.name.endsWith(".rawpcm") -> if (f.lastModified() < dayAgo) f.delete()
                else -> {
                    val base = f.name.removeSuffix(".sync.json").substringBeforeLast('.')
                    if (base !in keep) f.delete()
                }
            }
        }
    }
}
