package com.example.data.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.local.entity.AudioTrackEntity
import com.example.data.local.entity.ChapterEntity
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** A chapter marker stored inside an audio file. */
data class EmbeddedChapter(val startMs: Long, val title: String)

/**
 * Reads the chapter markers embedded in MP4-family audiobooks (.m4b / .m4a / .mp4 / .aac in MP4).
 *
 * Two layouts are common and both are supported:
 *  - QuickTime chapter track: a `text` track referenced by the audio track's `tref/chap`, one
 *    timed text sample per chapter (iTunes, Audible conversions, ffmpeg, most tools).
 *  - Nero chapters: a `moov/udta/chpl` box with start times and titles.
 *
 * Only the index (`moov`) is read, by seeking past the audio data, so this is fast even for
 * multi-hundred-megabyte files whose index sits at the end.
 */
object EmbeddedChapterReader {
    private const val TAG = "EmbeddedChapterReader"
    private const val MAX_TABLE_BYTES = 4L * 1024 * 1024
    private val MP4_EXTENSIONS = setOf("m4b", "m4a", "mp4", "aac", "m4p", "3gp")

    /** Book chapters built from the files: embedded markers where present, else one per file. */
    fun chaptersForTracks(context: Context, bookId: String, tracks: List<AudioTrackEntity>): List<ChapterEntity> {
        val result = ArrayList<ChapterEntity>()
        for (track in tracks) {
            val trackEnd = track.startOffsetInBookMs + track.durationMs
            val embedded = if (mightHaveChapters(track.fileUri)) {
                read(context, track.fileUri)
                    .filter { track.durationMs <= 0 || it.startMs < track.durationMs }
            } else emptyList()
            if (embedded.size > 1) {
                embedded.forEachIndexed { i, ch ->
                    val start = track.startOffsetInBookMs + ch.startMs
                    val end = embedded.getOrNull(i + 1)?.let { track.startOffsetInBookMs + it.startMs } ?: trackEnd
                    result += ChapterEntity(
                        id = "${bookId}_ch_${result.size}",
                        bookId = bookId,
                        chapterIndex = result.size,
                        title = ch.title.ifBlank { "Chapter ${result.size + 1}" },
                        href = "${track.fileUri}#t=${ch.startMs}",
                        mimeType = track.mimeType,
                        audioTrackId = track.id,
                        startTimeMs = start,
                        endTimeMs = end
                    )
                }
            } else {
                result += ChapterEntity(
                    id = "${bookId}_ch_${result.size}",
                    bookId = bookId,
                    chapterIndex = result.size,
                    title = embedded.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: track.title,
                    href = track.fileUri,
                    audioTrackId = track.id,
                    startTimeMs = track.startOffsetInBookMs,
                    endTimeMs = trackEnd
                )
            }
        }
        return result
    }

    fun mightHaveChapters(fileNameOrUri: String): Boolean =
        Uri.decode(fileNameOrUri).substringAfterLast('.', "").lowercase().substringBefore('?') in MP4_EXTENSIONS

    fun read(context: Context, uriString: String): List<EmbeddedChapter> = runCatching {
        val uri = Uri.parse(uriString)
        val pfd = when (uri.scheme) {
            "content", "file" -> context.contentResolver.openFileDescriptor(uri, "r")
            else -> null
        }
        if (pfd != null) {
            pfd.use { FileInputStream(it.fileDescriptor).channel.use { ch -> readChannel(ch) } }
        } else {
            FileInputStream(File(uriString)).channel.use { ch -> readChannel(ch) }
        }
    }.onFailure { Log.w(TAG, "Couldn't read chapters from $uriString", it) }.getOrDefault(emptyList())

    // --- MP4 box walking ---

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val contentStart get() = start + headerSize
        val end get() = start + size
    }

    private class Track {
        var id = 0
        var handler = ""
        var timescale = 0L
        var chapterRefs = emptyList<Int>()
        var stts: ByteBuffer? = null
        var stsc: ByteBuffer? = null
        var stsz: ByteBuffer? = null
        var chunkOffsets: LongArray? = null
    }

    private fun readChannel(ch: FileChannel): List<EmbeddedChapter> {
        val moov = children(ch, 0, ch.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val tracks = ArrayList<Track>()
        var nero: List<EmbeddedChapter> = emptyList()
        var movieDurationMs = 0L
        for (box in children(ch, moov.contentStart, moov.end)) {
            when (box.type) {
                "trak" -> tracks += readTrack(ch, box)
                "udta" -> children(ch, box.contentStart, box.end).firstOrNull { it.type == "chpl" }
                    ?.let { nero = readNero(read(ch, it.contentStart, it.end - it.contentStart)) }
                "mvhd" -> movieDurationMs = readMvhdDurationMs(read(ch, box.contentStart, minOf(box.end - box.contentStart, 32)))
            }
        }
        val referenced = tracks.flatMap { it.chapterRefs }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in referenced && it.stts != null }
            ?: tracks.firstOrNull { it.handler == "text" && it.stts != null }
        val quickTime = chapterTrack?.let { readTextTrack(ch, it) }.orEmpty()
        val chapters = if (quickTime.size >= nero.size) quickTime else nero
        return chapters
            .filter { movieDurationMs <= 0 || it.startMs < movieDurationMs }
            .sortedBy { it.startMs }
            .distinctBy { it.startMs }
    }

    private fun readTrack(ch: FileChannel, trak: Box): Track {
        val t = Track()
        fun walk(parent: Box) {
            for (b in children(ch, parent.contentStart, parent.end)) {
                when (b.type) {
                    "tkhd" -> {
                        val buf = read(ch, b.contentStart, minOf(b.size - b.headerSize, 32))
                        val version = buf.get(0).toInt()
                        t.id = buf.getInt(if (version == 1) 20 else 12)
                    }
                    "tref" -> children(ch, b.contentStart, b.end).firstOrNull { it.type == "chap" }?.let { chap ->
                        val buf = read(ch, chap.contentStart, chap.end - chap.contentStart)
                        t.chapterRefs = (0 until buf.remaining() / 4).map { buf.getInt(it * 4) }
                    }
                    "mdhd" -> {
                        val buf = read(ch, b.contentStart, minOf(b.size - b.headerSize, 32))
                        val version = buf.get(0).toInt()
                        t.timescale = (if (version == 1) buf.getInt(20) else buf.getInt(12)).toLong() and 0xFFFFFFFFL
                    }
                    "hdlr" -> {
                        val buf = read(ch, b.contentStart, 12)
                        t.handler = String(ByteArray(4) { buf.get(8 + it) }, Charsets.US_ASCII)
                    }
                    "mdia", "minf" -> walk(b)
                    // Sample tables are only needed for tracks that can hold chapter titles
                    "stbl" -> if (t.handler !in setOf("soun", "vide", "hint")) {
                        for (s in children(ch, b.contentStart, b.end)) {
                            val len = s.end - s.contentStart
                            if (len > MAX_TABLE_BYTES) continue
                            when (s.type) {
                                "stts" -> t.stts = read(ch, s.contentStart, len)
                                "stsc" -> t.stsc = read(ch, s.contentStart, len)
                                "stsz" -> t.stsz = read(ch, s.contentStart, len)
                                "stco" -> {
                                    val buf = read(ch, s.contentStart, len)
                                    val n = buf.getInt(4)
                                    t.chunkOffsets = LongArray(n) { buf.getInt(8 + it * 4).toLong() and 0xFFFFFFFFL }
                                }
                                "co64" -> {
                                    val buf = read(ch, s.contentStart, len)
                                    val n = buf.getInt(4)
                                    t.chunkOffsets = LongArray(n) { buf.getLong(8 + it * 8) }
                                }
                            }
                        }
                    }
                }
            }
        }
        walk(trak)
        return t
    }

    private fun readTextTrack(ch: FileChannel, t: Track): List<EmbeddedChapter> {
        val stts = t.stts ?: return emptyList()
        val stsz = t.stsz ?: return emptyList()
        val stsc = t.stsc ?: return emptyList()
        val chunkOffsets = t.chunkOffsets ?: return emptyList()
        if (t.timescale <= 0) return emptyList()

        // Sample start times
        val starts = ArrayList<Long>()
        var ticks = 0L
        val sttsCount = stts.getInt(4)
        for (i in 0 until sttsCount) {
            val count = stts.getInt(8 + i * 8)
            val delta = stts.getInt(12 + i * 8).toLong() and 0xFFFFFFFFL
            repeat(count) { starts += ticks; ticks += delta }
        }
        // Sample sizes
        val uniformSize = stsz.getInt(4)
        val sampleCount = stsz.getInt(8)
        val sizes = IntArray(sampleCount) { if (uniformSize != 0) uniformSize else stsz.getInt(12 + it * 4) }
        // Sample → file offset, via sample-to-chunk runs
        val offsets = LongArray(sampleCount)
        val stscCount = stsc.getInt(4)
        var sample = 0
        for (e in 0 until stscCount) {
            val firstChunk = stsc.getInt(8 + e * 12)
            val perChunk = stsc.getInt(12 + e * 12)
            val lastChunk = if (e + 1 < stscCount) stsc.getInt(8 + (e + 1) * 12) - 1 else chunkOffsets.size
            for (chunk in firstChunk..lastChunk) {
                var pos = chunkOffsets.getOrNull(chunk - 1) ?: break
                repeat(perChunk) {
                    if (sample < sampleCount) {
                        offsets[sample] = pos
                        pos += sizes[sample]
                        sample++
                    }
                }
            }
        }

        val result = ArrayList<EmbeddedChapter>(sampleCount)
        for (i in 0 until minOf(sampleCount, starts.size)) {
            if (sizes[i] < 2) continue
            val buf = read(ch, offsets[i], minOf(sizes[i].toLong(), 1024))
            val textLen = (buf.getShort(0).toInt() and 0xFFFF).coerceAtMost(buf.remaining() - 2)
            val bytes = ByteArray(textLen) { buf.get(2 + it) }
            val title = decodeText(bytes).trim()
            result += EmbeddedChapter(starts[i] * 1000L / t.timescale, title)
        }
        return result
    }

    private fun readNero(buf: ByteBuffer): List<EmbeddedChapter> {
        var p = 0
        val version = buf.get(p).toInt(); p += 4
        if (version != 0) p += 4
        val count = buf.get(p).toInt() and 0xFF; p += 1
        val result = ArrayList<EmbeddedChapter>(count)
        repeat(count) {
            if (p + 9 > buf.limit()) return result
            val start100ns = buf.getLong(p); p += 8
            val len = buf.get(p).toInt() and 0xFF; p += 1
            if (p + len > buf.limit()) return result
            val bytes = ByteArray(len) { buf.get(p + it) }; p += len
            result += EmbeddedChapter(start100ns / 10_000L, decodeText(bytes).trim())
        }
        return result
    }

    private fun readMvhdDurationMs(buf: ByteBuffer): Long {
        val version = buf.get(0).toInt()
        return if (version == 1) {
            val ts = buf.getInt(20).toLong() and 0xFFFFFFFFL
            if (buf.limit() < 32 || ts == 0L) 0L else buf.getLong(24) * 1000L / ts
        } else {
            val ts = buf.getInt(12).toLong() and 0xFFFFFFFFL
            val dur = buf.getInt(16).toLong() and 0xFFFFFFFFL
            if (ts == 0L) 0L else dur * 1000L / ts
        }
    }

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        else -> String(bytes, Charset.forName("UTF-8"))
    }

    private fun children(ch: FileChannel, from: Long, to: Long): List<Box> {
        val boxes = ArrayList<Box>()
        var pos = from
        val header = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= to) {
            header.clear().limit(8)
            if (ch.read(header, pos) < 8) break
            var size = header.getInt(0).toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4) { header.get(4 + it) }, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L) {
                header.clear().limit(8)
                if (ch.read(header, pos + 8) < 8) break
                size = header.getLong(0)
                headerSize = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < headerSize) break
            boxes += Box(type, pos, headerSize, size)
            pos += size
        }
        return boxes
    }

    private fun read(ch: FileChannel, at: Long, length: Long): ByteBuffer {
        val buf = ByteBuffer.allocate(length.coerceIn(0, MAX_TABLE_BYTES).toInt()).order(ByteOrder.BIG_ENDIAN)
        var pos = at
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        buf.flip()
        return buf
    }
}
