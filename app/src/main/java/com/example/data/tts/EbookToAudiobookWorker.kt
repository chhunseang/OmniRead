package com.example.data.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.data.local.dao.AudioTrackDao
import com.example.data.local.dao.BookDao
import com.example.data.local.dao.ChapterDao
import com.example.data.local.dao.TtsSyncCueDao
import com.example.data.local.entity.AudioTrackEntity
import com.example.data.local.entity.BookEntity
import com.example.data.local.entity.BookFormat
import com.example.data.local.entity.ChapterEntity
import com.example.data.local.entity.ReadingStatus
import com.example.data.local.entity.MediaType
import com.example.data.local.entity.TtsEngineType
import com.example.data.local.entity.TtsSyncCueEntity
import com.example.domain.reader.SentenceLocator
import com.example.domain.tts.AudioExportFormat
import com.example.domain.tts.BatchAudiobookConversionSpec
import com.example.domain.tts.BatchAudiobookExportManager
import com.example.domain.tts.BatchConversionProgress
import com.example.domain.tts.CloudApiTtsProvider
import com.example.domain.tts.LocalKokoroTtsProvider
import com.example.domain.tts.TtsProvider
import com.example.domain.tts.TtsSynthesisRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

@Serializable
data class SyncedAudiobookSidecarDocument(
    val bookId: String,
    val audiobookId: String,
    val title: String,
    val author: String,
    val ttsEngine: TtsEngineType,
    val voiceId: String,
    val exportFormat: AudioExportFormat,
    val sampleRateHz: Int,
    val totalDurationMs: Long,
    val chapters: List<SyncedSidecarChapterEntry>,
    val cues: List<TtsSyncCueEntity>
)

@Serializable
data class SyncedSidecarChapterEntry(
    val chapterIndex: Int,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val sentenceCount: Int
)

@HiltWorker
class EbookToAudiobookWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val audioTrackDao: AudioTrackDao,
    private val ttsSyncCueDao: TtsSyncCueDao,
    private val localKokoroProvider: LocalKokoroTtsProvider,
    private val cloudApiProvider: CloudApiTtsProvider,
    private val readAloudCoordinator: ReadAloudCoordinator,
    private val ebookParserService: com.example.data.reader.EbookParserService,
    private val exportManager: BatchAudiobookExportManagerImpl,
    private val json: Json
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val rawSpecJson = inputData.getString(KEY_CONVERSION_SPEC_JSON)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Missing conversion spec JSON"))

        val spec = runCatching {
            json.decodeFromString<BatchAudiobookConversionSpec>(rawSpecJson)
        }.getOrElse { err ->
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Invalid conversion spec: ${err.message}"))
        }

        ensureNotificationChannel()
        val notificationId = (spec.jobId.hashCode() and 0x7FFFFFFF).coerceAtLeast(1001)
        var tempPcmFile: File? = null

        try {
            val book = bookDao.getBookById(spec.bookId)
                ?: return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Book not found: ${spec.bookId}"))

            setForeground(
                createForegroundInfo(
                    notificationId = notificationId,
                    bookTitle = book.title,
                    chapterTitle = "Preparing chapters...",
                    progressPercent = 0,
                    etaText = "Calculating ETA..."
                )
            )

            // 1. Parse the book with the same parser the Reader uses (same chapters, same text)
            val parsed = ebookParserService.parseEbookPackage(book)
            val allChapters = parsed.chapters
            if (allChapters.isEmpty()) {
                throw IllegalStateException("Couldn't read any text from \"${book.title}\"")
            }

            // The picker lists stored chapters; match them to the parsed book by file, not by
            // number, since a parser update can shift numbering (e.g. picture-only pages).
            val storedChapters = chapterDao.getChaptersForBook(book.id)
            val selectedChapters = if (spec.chapterIndices.isEmpty()) {
                allChapters
            } else {
                val wanted = spec.chapterIndices.mapNotNull { idx ->
                    storedChapters.firstOrNull { it.chapterIndex == idx }?.let { it.href to it.title }
                }.toSet()
                val byFile = allChapters.filter { (it.href to it.title) in wanted }
                    .ifEmpty { allChapters.filter { ch -> wanted.any { it.first == ch.href } } }
                byFile.ifEmpty {
                    val filterSet = spec.chapterIndices.toSet()
                    allChapters.filter { it.chapterIndex in filterSet }
                }
            }
            ebookParserService.syncStoredChapters(book, allChapters)

            if (selectedChapters.isEmpty()) {
                throw IllegalStateException("None of the selected chapters exist in this book")
            }

            // 2. Initialize the chosen TTS engine (no silent fallback to another voice/engine)
            val activeProvider: TtsProvider = when (spec.engineType) {
                TtsEngineType.LOCAL_KOKORO -> localKokoroProvider
                else -> cloudApiProvider
            }
            activeProvider.initialize().getOrThrow()

            // 3. Tokenize all selected chapters into SentenceLocators to calculate accurate progress & ETA
            val chapterSentencesMap = LinkedHashMap<ChapterEntity, List<SentenceLocator>>()
            var totalSentencesAcrossJob = 0

            for (chapter in selectedChapters) {
                val rawChapterContent = parsed.chapterRawHtmlMap[chapter.chapterIndex].orEmpty()
                val sentences = readAloudCoordinator.tokenizeChapterIntoSentences(
                    bookId = book.id,
                    chapterIndex = chapter.chapterIndex,
                    chapterHref = chapter.href,
                    rawHtmlOrText = rawChapterContent
                )
                chapterSentencesMap[chapter] = sentences
                totalSentencesAcrossJob += sentences.size
                Log.i(TAG, "Chapter ${chapter.chapterIndex} \"${chapter.title}\": ${rawChapterContent.length} chars → ${sentences.size} sentences")
            }

            if (totalSentencesAcrossJob == 0) {
                throw IllegalStateException("The selected chapters have no text to narrate")
            }
            // Progress and time left follow the amount of text, not the sentence count: a
            // one-word line and a long paragraph take very different times to narrate.
            val totalCharsAcrossJob = chapterSentencesMap.values.sumOf { list -> list.sumOf { it.text.length } }.coerceAtLeast(1)
            var completedCharsGlobal = 0

            // Each chapter selection is its own audiobook: narrating chapters 11-20 must never
            // overwrite the audiobook made from chapters 1-10. Re-running the same selection
            // with the same voice replaces it.
            val textChapterCount = allChapters.count { it.wordCount >= 30 }.coerceAtLeast(1)
            val isWholeBook = selectedChapters.size >= (textChapterCount * 0.9f)
            val chapterSetKey = if (isWholeBook) "" else
                "_" + Integer.toHexString(selectedChapters.joinToString("|") { it.href }.hashCode())
            fun shortTitle(ch: ChapterEntity) = ch.title.substringBefore(':').trim().take(40)
            val partLabel = when {
                isWholeBook -> null
                selectedChapters.size == 1 -> shortTitle(selectedChapters[0])
                else -> "${shortTitle(selectedChapters.first())} – ${shortTitle(selectedChapters.last())}"
            }

            val outputDir = File(appContext.filesDir, "audiobooks/${book.id}").apply { mkdirs() }
            val baseFileName = "${book.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)}_${spec.voiceId}$chapterSetKey"
            val rawPcmTempFile = File(outputDir, "${baseFileName}_${spec.jobId}.rawpcm")
            if (rawPcmTempFile.exists()) rawPcmTempFile.delete()
            tempPcmFile = rawPcmTempFile

            val allSyncCues = ArrayList<TtsSyncCueEntity>(totalSentencesAcrossJob)
            val sidecarChapters = ArrayList<SyncedSidecarChapterEntry>(selectedChapters.size)
            val waveformAccumulator = ArrayList<Float>(512)

            val startWallClockMs = System.currentTimeMillis()
            var completedSentencesGlobal = 0
            var cumulativeAudioTimeMs = 0L
            val sampleRateHz = LocalKokoroTtsProviderImpl.SAMPLE_RATE_24KHZ

            // 4. Synthesize each chapter & sentence, appending float32 PCM samples and recording TtsSyncCueEntities
            RandomAccessFile(rawPcmTempFile, "rw").use { pcmOutFile ->
                selectedChapters.forEachIndexed { chapterPosition, chapter ->
                    val sentences = chapterSentencesMap[chapter].orEmpty()
                    val chapterStartTimeMs = cumulativeAudioTimeMs

                    sentences.forEachIndexed { sentencePos, locator ->
                        if (isStopped) {
                            throw CancellationException("EbookToAudiobookWorker stopped by user")
                        }

                        val request = TtsSynthesisRequest(
                            requestId = "${spec.jobId}_ch${chapter.chapterIndex}_s$sentencePos",
                            sentenceLocator = locator,
                            text = locator.text,
                            engineType = spec.engineType,
                            voiceId = spec.voiceId,
                            speakerId = spec.speakerId,
                            speed = spec.speed
                        )

                        val synthesizedChunk = activeProvider.synthesizeSentence(request).getOrThrow()

                        val floatSamples = resampleLinear(
                            synthesizedChunk.pcmFloatSamples,
                            fromRateHz = synthesizedChunk.sampleRateHz,
                            toRateHz = sampleRateHz
                        )
                        // Add 140ms inter-sentence natural pause
                        val pauseSamples = FloatArray((sampleRateHz * 0.14f).toInt())
                        val combinedSamples = FloatArray(floatSamples.size + pauseSamples.size)
                        System.arraycopy(floatSamples, 0, combinedSamples, 0, floatSamples.size)

                        // Compute RMS peak for audiobook waveform scrubber
                        if (floatSamples.isNotEmpty()) {
                            var sumSq = 0.0
                            val step = max(1, floatSamples.size / 64)
                            var count = 0
                            var i = 0
                            while (i < floatSamples.size) {
                                val s = floatSamples[i].toDouble()
                                sumSq += s * s
                                count++
                                i += step
                            }
                            val rms = sqrt(sumSq / max(1, count)).toFloat().coerceIn(0.02f, 1.0f)
                            waveformAccumulator.add(rms)
                        }

                        // Write 16-bit little-endian PCM bytes to temp file for final muxing/encoding
                        val pcm16Bytes = ByteBuffer.allocate(combinedSamples.size * 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        for (sample in combinedSamples) {
                            val clamped = sample.coerceIn(-1.0f, 1.0f)
                            pcm16Bytes.putShort((clamped * 32767.0f).toInt().toShort())
                        }
                        pcmOutFile.write(pcm16Bytes.array())

                        val sentenceDurationMs = (combinedSamples.size * 1000L) / sampleRateHz
                        val sentenceStartMs = cumulativeAudioTimeMs
                        val sentenceEndMs = sentenceStartMs + sentenceDurationMs
                        cumulativeAudioTimeMs = sentenceEndMs

                        val cueEntity = TtsSyncCueEntity(
                            id = "${book.id}_ch${chapter.chapterIndex}_s${locator.sentenceIndex}",
                            bookId = book.id,
                            chapterIndex = chapter.chapterIndex,
                            sentenceIndex = locator.sentenceIndex,
                            audioTrackId = "${book.id}_track_0",
                            textContent = locator.text,
                            startCharOffset = locator.startCharOffset,
                            endCharOffset = locator.endCharOffset,
                            domSelector = locator.domCssSelector,
                            locatorJson = locator.cfiRange,
                            startTimeMs = sentenceStartMs,
                            endTimeMs = sentenceEndMs,
                            cachedPcmOrAudioPath = synthesizedChunk.cachedDiskFilePath,
                            ttsEngine = spec.engineType,
                            voiceId = spec.voiceId
                        )
                        allSyncCues.add(cueEntity)

                        completedSentencesGlobal++
                        completedCharsGlobal += locator.text.length
                        val fraction = (completedCharsGlobal.toFloat() / totalCharsAcrossJob.toFloat())
                            .coerceIn(0f, 0.96f)
                        val percentInt = (fraction * 100).toInt()
                        val etaText = formatEstimatedRemainingTime(
                            startWallClockMs = startWallClockMs,
                            completedUnits = completedCharsGlobal,
                            totalUnits = totalCharsAcrossJob
                        )

                        val progressSnapshot = BatchConversionProgress(
                            jobId = spec.jobId,
                            bookId = book.id,
                            isRunning = true,
                            currentChapterIndex = chapter.chapterIndex,
                            completedChaptersCount = chapterPosition,
                            totalChaptersCount = selectedChapters.size,
                            currentSentenceIndex = sentencePos + 1,
                            totalSentencesInChapter = sentences.size,
                            overallProgressFraction = fraction,
                            currentChapterTitle = chapter.title,
                            etaText = etaText
                        )
                        exportManager.updateJobProgressState(progressSnapshot)

                        setProgress(
                            buildProgressData(progressSnapshot, chapter.title, etaText)
                        )
                        setForeground(
                            createForegroundInfo(
                                notificationId = notificationId,
                                bookTitle = book.title,
                                chapterTitle = "Ch ${chapterPosition + 1}/${selectedChapters.size}: ${chapter.title}",
                                progressPercent = percentInt,
                                etaText = etaText
                            )
                        )
                    }

                    val chapterEndTimeMs = cumulativeAudioTimeMs
                    sidecarChapters.add(
                        SyncedSidecarChapterEntry(
                            chapterIndex = chapter.chapterIndex,
                            title = chapter.title,
                            startTimeMs = chapterStartTimeMs,
                            endTimeMs = chapterEndTimeMs,
                            sentenceCount = sentences.size
                        )
                    )
                }
            }

            // 5. Encode/Mux the raw 16-bit PCM file into the user's chosen AudioExportFormat (.m4b / .mp3 / .wav)
            if (rawPcmTempFile.length() == 0L) {
                throw IllegalStateException("No audio was produced for the selected chapters")
            }
            var finalAudioFile = File(outputDir, "$baseFileName.${spec.exportFormat.extension}")
            when (spec.exportFormat) {
                AudioExportFormat.M4B -> {
                    val encodedOk = encodePcm16FileToAacM4b(
                        pcm16File = rawPcmTempFile,
                        outputM4bFile = finalAudioFile,
                        sampleRateHz = sampleRateHz
                    )
                    if (!encodedOk) {
                        // Never throw away hours of narration over an encoder hiccup: keep it as WAV
                        finalAudioFile.delete()
                        finalAudioFile = File(outputDir, "$baseFileName.${AudioExportFormat.WAV.extension}")
                        writeWavFileFromRawPcm16(rawPcmTempFile, finalAudioFile, sampleRateHz)
                    }
                }
                AudioExportFormat.WAV -> {
                    writeWavFileFromRawPcm16(rawPcmTempFile, finalAudioFile, sampleRateHz)
                }
            }
            rawPcmTempFile.delete()

            // 6. Write LRC and JSON sentence-timestamp sidecar files
            val lrcFile = if (spec.generateLrcSidecar) {
                File(outputDir, "$baseFileName.lrc").also { file ->
                    file.writeText(buildEnhancedLrcContent(book, spec, allSyncCues))
                }
            } else null

            val audiobookEntityId = "${book.id}_audiobook_${spec.voiceId}$chapterSetKey"
            val jsonSidecarFile = if (spec.generateJsonSyncSidecar) {
                File(outputDir, "$baseFileName.sync.json").also { file ->
                    val doc = SyncedAudiobookSidecarDocument(
                        bookId = book.id,
                        audiobookId = audiobookEntityId,
                        title = book.title,
                        author = book.author,
                        ttsEngine = spec.engineType,
                        voiceId = spec.voiceId,
                        exportFormat = spec.exportFormat,
                        sampleRateHz = sampleRateHz,
                        totalDurationMs = cumulativeAudioTimeMs,
                        chapters = sidecarChapters,
                        cues = allSyncCues
                    )
                    file.writeText(json.encodeToString(doc))
                }
            } else null

            // 7. Persist TtsSyncCueEntities, ChapterEntity timestamps, AudioTrackEntity, and register in Library
            ttsSyncCueDao.deleteCuesForBook(book.id)
            ttsSyncCueDao.upsertCues(allSyncCues)

            for (sidecarCh in sidecarChapters) {
                val chapterId = "${book.id}_ch_${sidecarCh.chapterIndex}"
                chapterDao.markChapterTtsConverted(
                    chapterId = chapterId,
                    converted = true,
                    audioPath = finalAudioFile.absolutePath,
                    startTimeMs = sidecarCh.startTimeMs,
                    endTimeMs = sidecarCh.endTimeMs
                )
            }

            val downsampledWaveform = downsampleWaveformPeaks(waveformAccumulator, targetBuckets = 120)
            val primaryTrack = AudioTrackEntity(
                id = "${book.id}_track_0",
                bookId = book.id,
                trackIndex = 0,
                title = "${book.title} (${spec.voiceId})",
                fileUri = Uri.fromFile(finalAudioFile).toString(),
                mimeType = spec.exportFormat.mimeType,
                durationMs = cumulativeAudioTimeMs,
                startOffsetInBookMs = 0L,
                bitrateKbps = 128,
                sampleRateHz = sampleRateHz,
                channels = 1,
                fileSizeBytes = finalAudioFile.length(),
                waveformPeaks = downsampledWaveform,
                isTtsGenerated = true
            )
            audioTrackDao.upsertTrack(primaryTrack)

            // The narration becomes its own audiobook in Listen; the source ebook stays a book
            val finalAudioUri = Uri.fromFile(finalAudioFile).toString()

            // A fresh listen: none of the source ebook's reading progress carries over
            val previousAudiobook = bookDao.getBookById(audiobookEntityId)
            val standaloneAudiobook = book.copy(
                id = audiobookEntityId,
                title = if (partLabel == null) "${book.title} (AI Audiobook)" else "${book.title} · $partLabel",
                readingStatus = ReadingStatus.UNREAD,
                totalChapters = sidecarChapters.size,
                currentChapterIndex = 0,
                currentLocatorJson = null,
                currentCharOffset = 0,
                currentAudioTrackIndex = 0,
                currentAudioPositionMs = 0L,
                progressionPercentage = 0f,
                playbackSpeed = previousAudiobook?.playbackSpeed ?: 1.0f,
                lastOpenedAt = null, // new, not listened yet: first on the shelf (by addedAt), not "continue listening"
                completedAt = null,
                tags = listOf(com.example.data.local.entity.STUDIO_TAG),
                collections = emptyList(),
                isFavorite = previousAudiobook?.isFavorite ?: false,
                parentFolderUri = null,
                narrator = when (spec.engineType) {
                    TtsEngineType.LOCAL_KOKORO -> "Kokoro · ${spec.voiceId}"
                    TtsEngineType.CLOUD_OPENAI -> "OpenAI · ${spec.voiceId}"
                    TtsEngineType.CLOUD_ELEVENLABS -> "ElevenLabs"
                    TtsEngineType.CLOUD_GEMINI -> "Gemini · ${spec.voiceId}"
                },
                format = when (spec.exportFormat) {
                    AudioExportFormat.M4B -> BookFormat.M4B
                    AudioExportFormat.WAV -> BookFormat.fromExtension("wav")
                },
                mediaType = MediaType.AUDIOBOOK,
                fileUri = finalAudioUri,
                fileSizeBytes = finalAudioFile.length(),
                totalDurationMs = cumulativeAudioTimeMs,
                syncedAudiobookUri = finalAudioUri,
                syncedSidecarPath = jsonSidecarFile?.absolutePath ?: lrcFile?.absolutePath,
                addedAt = System.currentTimeMillis()
            )
            bookDao.upsertBook(standaloneAudiobook)
            audioTrackDao.upsertTrack(
                primaryTrack.copy(
                    id = "${audiobookEntityId}_track_0",
                    bookId = audiobookEntityId
                )
            )
            // Chapter markers for the generated audiobook (shown in the player's chapter list)
            chapterDao.deleteChaptersForBook(audiobookEntityId)
            chapterDao.upsertChapters(
                sidecarChapters.mapIndexed { idx, ch ->
                    ChapterEntity(
                        id = "${audiobookEntityId}_ch_$idx",
                        bookId = audiobookEntityId,
                        chapterIndex = idx,
                        title = ch.title,
                        href = "chapter_${ch.chapterIndex}",
                        audioTrackId = "${audiobookEntityId}_track_0",
                        startTimeMs = ch.startTimeMs,
                        endTimeMs = ch.endTimeMs,
                        isTtsConverted = true
                    )
                }
            )
            // Cues re-indexed to the audiobook's own chapter positions (0..n-1)
            val positionBySourceChapter = sidecarChapters.mapIndexed { pos, ch -> ch.chapterIndex to pos }.toMap()
            val audiobookCues = allSyncCues.map { cue ->
                val pos = positionBySourceChapter[cue.chapterIndex] ?: 0
                cue.copy(
                    id = "${audiobookEntityId}_ch${pos}_s${cue.sentenceIndex}",
                    bookId = audiobookEntityId,
                    chapterIndex = pos,
                    audioTrackId = "${audiobookEntityId}_track_0"
                )
            }
            ttsSyncCueDao.deleteCuesForBook(audiobookEntityId)
            ttsSyncCueDao.upsertCues(audiobookCues)

            val completedProgress = BatchConversionProgress(
                jobId = spec.jobId,
                bookId = book.id,
                isRunning = false,
                currentChapterIndex = selectedChapters.last().chapterIndex,
                completedChaptersCount = selectedChapters.size,
                totalChaptersCount = selectedChapters.size,
                currentSentenceIndex = totalSentencesAcrossJob,
                totalSentencesInChapter = totalSentencesAcrossJob,
                overallProgressFraction = 1.0f,
                outputAudiobookBookId = audiobookEntityId,
                outputAudiobookFileUri = finalAudioUri,
                outputLrcSidecarPath = lrcFile?.absolutePath,
                outputJsonSidecarPath = jsonSidecarFile?.absolutePath
            )
            exportManager.updateJobProgressState(completedProgress)

            showCompletionNotification(
                notificationId = notificationId + 10_000,
                bookTitle = book.title,
                durationMs = cumulativeAudioTimeMs
            )

            Result.success(
                workDataOf(
                    KEY_JOB_ID to spec.jobId,
                    KEY_BOOK_ID to book.id,
                    KEY_OUTPUT_AUDIO_URI to finalAudioUri,
                    KEY_OUTPUT_LRC_PATH to (lrcFile?.absolutePath ?: ""),
                    KEY_OUTPUT_JSON_PATH to (jsonSidecarFile?.absolutePath ?: "")
                )
            )
        } catch (ce: CancellationException) {
            tempPcmFile?.delete()
            exportManager.updateJobProgressState(
                BatchConversionProgress(
                    jobId = spec.jobId,
                    bookId = spec.bookId,
                    isRunning = false,
                    errorMessage = "Conversion cancelled"
                )
            )
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "EbookToAudiobookWorker failed", e)
            tempPcmFile?.delete()
            val failedState = BatchConversionProgress(
                jobId = spec.jobId,
                bookId = spec.bookId,
                isRunning = false,
                errorMessage = e.message ?: "Unexpected conversion failure"
            )
            exportManager.updateJobProgressState(failedState)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "Conversion error")))
        }
    }

    /** Linear resampling so every sentence matches the output sample rate. */
    private fun resampleLinear(input: FloatArray, fromRateHz: Int, toRateHz: Int): FloatArray {
        if (fromRateHz <= 0 || fromRateHz == toRateHz || input.isEmpty()) return input
        val outLength = ((input.size.toLong() * toRateHz) / fromRateHz).toInt().coerceAtLeast(1)
        val ratio = fromRateHz.toDouble() / toRateHz
        return FloatArray(outLength) { i ->
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (src - i0).toFloat()
            input[i0] * (1f - frac) + input[i1] * frac
        }
    }

    /**
     * Hardware-accelerated AAC / `.m4b` MP4 container encoder using Android `MediaCodec` + `MediaMuxer`.
     */
    private fun encodePcm16FileToAacM4b(
        pcm16File: File,
        outputM4bFile: File,
        sampleRateHz: Int
    ): Boolean {
        if (!pcm16File.exists() || pcm16File.length() == 0L) return false
        if (outputM4bFile.exists()) outputM4bFile.delete()

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, sampleRateHz, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }

            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputM4bFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var totalBytesSubmitted = 0L

            FileInputStream(pcm16File).use { fis ->
                val readBuf = ByteArray(8192)
                var inputDone = false
                var outputDone = false

                while (!outputDone) {
                    if (!inputDone) {
                        val inBufIdx = codec.dequeueInputBuffer(10_000L)
                        if (inBufIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inBufIdx)!!
                            inBuf.clear()
                            val bytesToRead = min(readBuf.size, inBuf.remaining())
                            val bytesRead = fis.read(readBuf, 0, bytesToRead)
                            if (bytesRead < 0) {
                                val ptsUs = (totalBytesSubmitted / 2L) * 1_000_000L / sampleRateHz
                                codec.queueInputBuffer(
                                    inBufIdx,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                inBuf.put(readBuf, 0, bytesRead)
                                val ptsUs = (totalBytesSubmitted / 2L) * 1_000_000L / sampleRateHz
                                codec.queueInputBuffer(inBufIdx, 0, bytesRead, ptsUs, 0)
                                totalBytesSubmitted += bytesRead
                            }
                        }
                    }

                    val outBufIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outBufIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outBufIdx)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outBufIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Hardware M4B muxing failed, falling back to WAV container", e)
            false
        } finally {
            runCatching {
                codec?.stop()
                codec?.release()
            }
            runCatching {
                if (muxerStarted) muxer?.stop()
                muxer?.release()
            }
        }
    }

    private fun writeWavFileFromRawPcm16(
        rawPcm16File: File,
        outputWavFile: File,
        sampleRateHz: Int
    ) {
        val dataSize = rawPcm16File.length().toInt()
        val byteRate = sampleRateHz * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(StandardCharsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(StandardCharsets.US_ASCII))
            put("fmt ".toByteArray(StandardCharsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(1) // Mono
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray(StandardCharsets.US_ASCII))
            putInt(dataSize)
        }

        outputWavFile.outputStream().use { out ->
            out.write(header.array())
            rawPcm16File.inputStream().use { input ->
                input.copyTo(out, bufferSize = 32 * 1024)
            }
        }
    }

    private fun buildEnhancedLrcContent(
        book: BookEntity,
        spec: BatchAudiobookConversionSpec,
        cues: List<TtsSyncCueEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[ti:${book.title}]")
        sb.appendLine("[ar:${book.author}]")
        sb.appendLine("[al:${book.title} - OmniRead AI Audiobook]")
        sb.appendLine("[by:OmniRead (${spec.engineType.name} / ${spec.voiceId})]")
        sb.appendLine()

        for (cue in cues) {
            val totalSeconds = cue.startTimeMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            val hundredths = (cue.startTimeMs % 1000) / 10
            val timestampTag = String.format("[%02d:%02d.%02d]", minutes, seconds, hundredths)
            val cleanLine = cue.textContent.replace("\n", " ").trim()
            sb.appendLine("$timestampTag $cleanLine")
        }
        return sb.toString()
    }

    private fun downsampleWaveformPeaks(rawPeaks: List<Float>, targetBuckets: Int): List<Float> {
        if (rawPeaks.isEmpty()) return List(targetBuckets) { 0.25f }
        if (rawPeaks.size <= targetBuckets) return rawPeaks
        val result = ArrayList<Float>(targetBuckets)
        val bucketSize = rawPeaks.size.toFloat() / targetBuckets.toFloat()
        for (i in 0 until targetBuckets) {
            val start = (i * bucketSize).toInt().coerceIn(0, rawPeaks.size - 1)
            val end = ((i + 1) * bucketSize).toInt().coerceIn(start + 1, rawPeaks.size)
            val avg = rawPeaks.subList(start, end).average().toFloat()
            result.add(avg.coerceIn(0.05f, 1.0f))
        }
        return result
    }

    private fun formatEstimatedRemainingTime(
        startWallClockMs: Long,
        completedUnits: Int,
        totalUnits: Int
    ): String {
        if (completedUnits <= 0 || completedUnits >= totalUnits) return "Almost done..."
        val elapsedMs = (System.currentTimeMillis() - startWallClockMs).coerceAtLeast(200L)
        val msPerUnit = elapsedMs.toDouble() / completedUnits.toDouble()
        val remainingUnits = (totalUnits - completedUnits).coerceAtLeast(0)
        val remainingSeconds = ((msPerUnit * remainingUnits) / 1000.0).roundToLong()
        return when {
            remainingSeconds < 60L -> "${remainingSeconds}s remaining"
            remainingSeconds < 3600L -> "${remainingSeconds / 60}m ${remainingSeconds % 60}s remaining"
            else -> "${remainingSeconds / 3600}h ${(remainingSeconds % 3600) / 60}m remaining"
        }
    }

    private fun buildProgressData(
        progress: BatchConversionProgress,
        chapterTitle: String,
        etaText: String
    ): Data {
        return workDataOf(
            KEY_JOB_ID to progress.jobId,
            KEY_BOOK_ID to progress.bookId,
            KEY_CURRENT_CHAPTER_INDEX to progress.currentChapterIndex,
            KEY_CURRENT_CHAPTER_TITLE to chapterTitle,
            KEY_COMPLETED_CHAPTERS to progress.completedChaptersCount,
            KEY_TOTAL_CHAPTERS to progress.totalChaptersCount,
            KEY_CURRENT_SENTENCE to progress.currentSentenceIndex,
            KEY_TOTAL_SENTENCES to progress.totalSentencesInChapter,
            KEY_PROGRESS_FRACTION to progress.overallProgressFraction,
            KEY_ETA_TEXT to etaText
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audiobook Studio Conversions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background progress for Ebook-to-Audiobook AI TTS conversions"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(
        notificationId: Int,
        bookTitle: String,
        chapterTitle: String,
        progressPercent: Int,
        etaText: String
    ): ForegroundInfo {
        val cancelPendingIntent = WorkManager.getInstance(appContext)
            .createCancelPendingIntent(id)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Converting: $bookTitle")
            .setContentText("$chapterTitle • $progressPercent% ($etaText)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$chapterTitle\n$progressPercent% complete • $etaText")
            )
            .setProgress(100, progressPercent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun showCompletionNotification(
        notificationId: Int,
        bookTitle: String,
        durationMs: Long
    ) {
        val minutes = (durationMs / 60_000L).coerceAtLeast(1L)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Audiobook Ready: $bookTitle")
            .setContentText("Synthesized ${minutes}m audiobook with synchronized text cues.")
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, notification)
    }

    companion object {
        private const val TAG = "EbookToAudiobookWorker"
        const val CHANNEL_ID = "omniread_audiobook_converter_channel"

        const val KEY_CONVERSION_SPEC_JSON = "conversion_spec_json"
        const val KEY_JOB_ID = "job_id"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CURRENT_CHAPTER_INDEX = "current_chapter_index"
        const val KEY_CURRENT_CHAPTER_TITLE = "current_chapter_title"
        const val KEY_COMPLETED_CHAPTERS = "completed_chapters"
        const val KEY_TOTAL_CHAPTERS = "total_chapters"
        const val KEY_CURRENT_SENTENCE = "current_sentence"
        const val KEY_TOTAL_SENTENCES = "total_sentences"
        const val KEY_PROGRESS_FRACTION = "progress_fraction"
        const val KEY_ETA_TEXT = "eta_text"
        const val KEY_OUTPUT_AUDIO_URI = "output_audio_uri"
        const val KEY_OUTPUT_LRC_PATH = "output_lrc_path"
        const val KEY_OUTPUT_JSON_PATH = "output_json_path"
        const val KEY_ERROR_MESSAGE = "error_message"

        fun workTagForBook(bookId: String): String = "audiobook_convert_book_$bookId"
        fun workTagForJob(jobId: String): String = "audiobook_convert_job_$jobId"
        fun uniqueWorkName(jobId: String): String = "omniread_ebook_to_audiobook_$jobId"
    }
}

@Singleton
class BatchAudiobookExportManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) : BatchAudiobookExportManager {

    private val workManager = WorkManager.getInstance(context)
    private val activeJobsMap = MutableStateFlow<Map<String, BatchConversionProgress>>(emptyMap())

    override fun enqueueConversionJob(spec: BatchAudiobookConversionSpec): String {
        val jobId = spec.jobId.ifBlank { UUID.randomUUID().toString() }
        val normalizedSpec = spec.copy(jobId = jobId)

        val initialProgress = BatchConversionProgress(
            jobId = jobId,
            bookId = normalizedSpec.bookId,
            isRunning = true,
            totalChaptersCount = normalizedSpec.chapterIndices.size
        )
        updateJobProgressState(initialProgress)

        val inputData = workDataOf(
            EbookToAudiobookWorker.KEY_CONVERSION_SPEC_JSON to json.encodeToString(normalizedSpec)
        )

        val request = OneTimeWorkRequestBuilder<EbookToAudiobookWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(EbookToAudiobookWorker.workTagForBook(normalizedSpec.bookId))
            .addTag(EbookToAudiobookWorker.workTagForJob(jobId))
            .build()

        workManager.enqueueUniqueWork(
            EbookToAudiobookWorker.uniqueWorkName(jobId),
            ExistingWorkPolicy.REPLACE,
            request
        )

        return jobId
    }

    override fun observeJobProgress(jobId: String): Flow<BatchConversionProgress?> {
        return activeJobsMap.map { it[jobId] }
    }

    override fun observeBookConversionProgress(bookId: String): Flow<List<BatchConversionProgress>> {
        return activeJobsMap.map { map ->
            map.values.filter { it.bookId == bookId }.sortedByDescending { it.isRunning }
        }
    }

    override fun cancelConversionJob(jobId: String) {
        workManager.cancelUniqueWork(EbookToAudiobookWorker.uniqueWorkName(jobId))
        activeJobsMap.update { current ->
            val existing = current[jobId] ?: return@update current
            current + (jobId to existing.copy(isRunning = false, errorMessage = "Cancelled by user"))
        }
    }

    fun removeJob(jobId: String) {
        activeJobsMap.update { current -> if (current[jobId]?.isRunning == true) current else current - jobId }
    }

    fun observeAllConversionJobs(): Flow<List<BatchConversionProgress>> {
        return activeJobsMap.map { it.values.toList().reversed() }
    }

    fun updateJobProgressState(progress: BatchConversionProgress) {
        activeJobsMap.update { current ->
            current + (progress.jobId to progress)
        }
    }
}
