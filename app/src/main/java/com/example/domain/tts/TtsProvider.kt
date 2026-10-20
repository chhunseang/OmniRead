package com.example.domain.tts

import com.example.data.local.entity.TtsEngineType
import com.example.domain.reader.SentenceLocator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class TtsVoiceProfile(
    val id: String,
    val speakerIndex: Int = 0,
    val name: String,
    val languageCode: String = "en-US",
    val gender: String = "Female",
    val engineType: TtsEngineType = TtsEngineType.LOCAL_KOKORO,
    val qualityTier: String = "Kokoro-82M Neural 24kHz",
    val previewSampleText: String = "Welcome to OmniRead hybrid neural text-to-speech."
)

@Serializable
data class VoiceBlendWeight(
    val voiceId: String,
    val speakerIndex: Int,
    val weight: Float
)

@Serializable
data class OfflineTtsKokoroModelConfig(
    val modelDir: String,
    val modelFileName: String = "kokoro-v1.0.int8.onnx",
    val voicesFileName: String = "voices.bin",
    val tokensFileName: String = "tokens.txt",
    val dataDirName: String = "espeak-ng-data",
    val lexiconFileNames: String = "",
    val lang: String = "en-us",
    val numThreads: Int = 4,
    val debug: Boolean = false,
    val provider: String = "cpu",
    val defaultSpeakerId: Int = 0,
    val defaultVoiceCode: String = "af_heart",
    val voiceBlend: List<VoiceBlendWeight> = emptyList(),
    val lengthScaleSpeed: Float = 1.0f
)

@Serializable
data class CloudApiTtsConfig(
    val engineType: TtsEngineType = TtsEngineType.CLOUD_OPENAI,
    val baseUrl: String = "https://api.openai.com/v1/",
    val apiKey: String = "",
    val modelId: String = "tts-1-hd",
    val voiceId: String = "alloy",
    val speed: Float = 1.0f,
    val responseFormat: String = "pcm",
    val sampleRateHz: Int = 24000,
    val diskCacheEnabled: Boolean = true,
    val maxDiskCacheBytes: Long = 512L * 1024L * 1024L
)

@Serializable
data class TtsSynthesisRequest(
    val requestId: String,
    val sentenceLocator: SentenceLocator,
    val text: String,
    val engineType: TtsEngineType,
    val voiceId: String,
    val speakerId: Int = 0,
    val voiceBlend: List<VoiceBlendWeight> = emptyList(),
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f
)

class SynthesizedAudioChunk(
    val requestId: String,
    val sentenceLocator: SentenceLocator,
    val pcmFloatSamples: FloatArray,
    val sampleRateHz: Int = 24000,
    val channels: Int = 1,
    val durationMs: Long = if (sampleRateHz > 0) (pcmFloatSamples.size * 1000L) / sampleRateHz else 0L,
    val cachedDiskFilePath: String? = null
)

interface TtsProvider {
    val engineType: TtsEngineType
    val isReady: StateFlow<Boolean>

    suspend fun initialize(): Result<Unit>
    suspend fun availableVoices(): List<TtsVoiceProfile>

    suspend fun synthesizeSentence(request: TtsSynthesisRequest): Result<SynthesizedAudioChunk>

    fun synthesizeStream(request: TtsSynthesisRequest): Flow<SynthesizedAudioChunk>

    fun cancelActiveSynthesis()
    fun release()
}

interface LocalKokoroTtsProvider : TtsProvider {
    val modelConfig: StateFlow<OfflineTtsKokoroModelConfig>

    suspend fun updateKokoroModelConfig(config: OfflineTtsKokoroModelConfig): Result<Unit>
    suspend fun verifyModelFilesExist(): Boolean
    suspend fun installBundledOrDownloadedModel(sourceArchiveUri: String): Result<OfflineTtsKokoroModelConfig>
}

interface CloudApiTtsProvider : TtsProvider {
    val cloudConfig: StateFlow<CloudApiTtsConfig>

    suspend fun updateCloudConfig(config: CloudApiTtsConfig)
    suspend fun getCachedSegmentPath(cacheKey: String): String?
    suspend fun clearDiskCache(): Result<Long>
}

@Serializable
enum class LiveTtsReadAloudStatus {
    IDLE,
    INITIALIZING_MODEL,
    PRE_BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

data class LiveTtsSessionState(
    val status: LiveTtsReadAloudStatus = LiveTtsReadAloudStatus.IDLE,
    val bookId: String? = null,
    val chapterIndex: Int = 0,
    val activeSentence: SentenceLocator? = null,
    val bufferedSentenceIndices: List<Int> = emptyList(),
    val preBufferLookaheadCount: Int = 3,
    val activeEngine: TtsEngineType = TtsEngineType.LOCAL_KOKORO,
    val activeVoiceId: String = "af_heart",
    val activeSpeakerId: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val autoTurnPageOnViewportEnd: Boolean = true,
    val errorMessage: String? = null
)

interface LiveTtsSyncCoordinator {
    val sessionState: StateFlow<LiveTtsSessionState>

    suspend fun startReadAloud(
        bookId: String,
        chapterIndex: Int,
        sentences: List<SentenceLocator>,
        startSentenceIndex: Int = 0
    )

    fun pauseReadAloud()
    fun resumeReadAloud()
    fun stopReadAloud()

    suspend fun jumpToSentence(sentenceLocator: SentenceLocator)
    suspend fun skipToNextSentence()
    suspend fun skipToPreviousSentence()

    fun setEngineAndVoice(engineType: TtsEngineType, voiceId: String, speakerId: Int = 0)
    fun setSpeed(speed: Float)
    fun setPreBufferLookahead(sentenceCount: Int)
}

@Serializable
enum class AudioExportFormat(val extension: String, val mimeType: String) {
    M4B("m4b", "audio/mp4"),
    MP3("mp3", "audio/mpeg"),
    WAV("wav", "audio/wav")
}

@Serializable
data class BatchAudiobookConversionSpec(
    val jobId: String,
    val bookId: String,
    val chapterIndices: List<Int>,
    val engineType: TtsEngineType = TtsEngineType.LOCAL_KOKORO,
    val voiceId: String = "af_heart",
    val speakerId: Int = 0,
    val speed: Float = 1.0f,
    val exportFormat: AudioExportFormat = AudioExportFormat.M4B,
    val generateLrcSidecar: Boolean = true,
    val generateJsonSyncSidecar: Boolean = true,
    val embedChapterMarkersAndCover: Boolean = true,
    val outputDirectoryUri: String? = null
)

@Serializable
data class BatchConversionProgress(
    val jobId: String,
    val bookId: String,
    val isRunning: Boolean = false,
    val currentChapterIndex: Int = 0,
    val completedChaptersCount: Int = 0,
    val totalChaptersCount: Int = 0,
    val currentSentenceIndex: Int = 0,
    val totalSentencesInChapter: Int = 0,
    val overallProgressFraction: Float = 0f,
    val outputAudiobookFileUri: String? = null,
    val outputLrcSidecarPath: String? = null,
    val outputJsonSidecarPath: String? = null,
    val errorMessage: String? = null
)

interface BatchAudiobookExportManager {
    fun enqueueConversionJob(spec: BatchAudiobookConversionSpec): String
    fun observeJobProgress(jobId: String): Flow<BatchConversionProgress?>
    fun observeBookConversionProgress(bookId: String): Flow<List<BatchConversionProgress>>
    fun cancelConversionJob(jobId: String)
}
