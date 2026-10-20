package com.example.data.tts

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.local.entity.TtsEngineType
import com.example.domain.tts.CloudApiTtsConfig
import com.example.domain.tts.CloudApiTtsProvider
import com.example.domain.tts.SynthesizedAudioChunk
import com.example.domain.tts.TtsSynthesisRequest
import com.example.domain.tts.TtsVoiceProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

interface CloudTtsRetrofitService {
    @Streaming
    @POST
    suspend fun streamOpenAiCompatibleSpeech(
        @Url fullEndpointUrl: String,
        @HeaderMap headers: Map<String, String>,
        @Body requestBody: RequestBody
    ): Response<ResponseBody>
}

@Serializable
data class OpenAiSpeechRequestPayload(
    val model: String,
    val input: String,
    val voice: String,
    val response_format: String = "pcm",
    val speed: Float = 1.0f
)

@Serializable
data class ElevenLabsVoiceSettings(
    val stability: Float = 0.55f,
    val similarity_boost: Float = 0.78f,
    val speed: Float = 1.0f
)

@Serializable
data class ElevenLabsSpeechRequestPayload(
    val text: String,
    val model_id: String = "eleven_multilingual_v2",
    val voice_settings: ElevenLabsVoiceSettings = ElevenLabsVoiceSettings()
)

@Singleton
class CloudApiTtsProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val dataStore: DataStore<Preferences>,
    private val json: Json
) : CloudApiTtsProvider {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val cancelRequested = AtomicBoolean(false)

    private val cacheRootDir = File(context.cacheDir, "tts_cloud_segments").apply { mkdirs() }

    private val _cloudConfig = MutableStateFlow(CloudApiTtsConfig())
    override val cloudConfig: StateFlow<CloudApiTtsConfig> = _cloudConfig.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val engineType: TtsEngineType
        get() = _cloudConfig.value.engineType

    private val retrofitService: CloudTtsRetrofitService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")
            .client(okHttpClient)
            .build()
            .create(CloudTtsRetrofitService::class.java)
    }

    init {
        scope.launch {
            loadPersistedConfig()
        }
    }

    override suspend fun initialize(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            loadPersistedConfig()
            cacheRootDir.mkdirs()
            _isReady.value = _cloudConfig.value.apiKey.isNotBlank() ||
                _cloudConfig.value.baseUrl.startsWith("http://127.0.0.1") ||
                _cloudConfig.value.baseUrl.startsWith("http://10.0.2.2") ||
                _cloudConfig.value.baseUrl.startsWith("http://localhost")
        }
    }

    override suspend fun updateCloudConfig(config: CloudApiTtsConfig) {
        _cloudConfig.value = config
        _isReady.value = config.apiKey.isNotBlank() ||
            config.baseUrl.startsWith("http://127.0.0.1") ||
            config.baseUrl.startsWith("http://10.0.2.2") ||
            config.baseUrl.startsWith("http://localhost")
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                prefs[PREF_CLOUD_TTS_CONFIG] = json.encodeToString(config)
            }
        }
    }

    override suspend fun availableVoices(): List<TtsVoiceProfile> {
        return when (_cloudConfig.value.engineType) {
            TtsEngineType.CLOUD_ELEVENLABS -> ELEVENLABS_DEFAULT_VOICES
            TtsEngineType.CLOUD_GEMINI -> GEMINI_AUDIO_VOICES
            else -> OPENAI_COMPATIBLE_VOICES
        }
    }

    /**
     * Deterministic cache lookup keyed by `(bookId, chapterIndex, sentenceHash, voiceId)`.
     */
    fun buildDiskCacheFile(
        bookId: String,
        chapterIndex: Int,
        sentenceText: String,
        voiceId: String,
        speed: Float
    ): File {
        val safeBookId = bookId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeVoiceId = voiceId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val sentenceHash = sha256Hex("${sentenceText.trim()}|speed=${"%.2f".format(speed)}")
        val chapterDir = File(cacheRootDir, "$safeBookId/chapter_$chapterIndex").apply { mkdirs() }
        return File(chapterDir, "${sentenceHash}_${safeVoiceId}.pcm")
    }

    override suspend fun getCachedSegmentPath(cacheKey: String): String? = withContext(ioDispatcher) {
        val directFile = File(cacheRootDir, cacheKey)
        if (directFile.exists() && directFile.length() > 0L) {
            return@withContext directFile.absolutePath
        }
        val matched = cacheRootDir.walkTopDown().firstOrNull { file ->
            file.isFile && (file.name == cacheKey || file.nameWithoutExtension == cacheKey)
        }
        matched?.takeIf { it.length() > 0L }?.absolutePath
    }

    override suspend fun clearDiskCache(): Result<Long> = withContext(ioDispatcher) {
        runCatching {
            var freedBytes = 0L
            cacheRootDir.walkBottomUp().forEach { file ->
                if (file.isFile) {
                    val len = file.length()
                    if (file.delete()) {
                        freedBytes += len
                    }
                } else if (file != cacheRootDir) {
                    file.delete()
                }
            }
            freedBytes
        }
    }

    override suspend fun synthesizeSentence(
        request: TtsSynthesisRequest
    ): Result<SynthesizedAudioChunk> = withContext(ioDispatcher) {
        runCatching {
            val allFloats = ArrayList<FloatArray>()
            var totalSamples = 0
            var cachedPath: String? = null

            synthesizeStream(request).collect { chunk ->
                allFloats.add(chunk.pcmFloatSamples)
                totalSamples += chunk.pcmFloatSamples.size
                if (chunk.cachedDiskFilePath != null) {
                    cachedPath = chunk.cachedDiskFilePath
                }
            }

            val merged = FloatArray(totalSamples)
            var offset = 0
            for (part in allFloats) {
                System.arraycopy(part, 0, merged, offset, part.size)
                offset += part.size
            }

            SynthesizedAudioChunk(
                requestId = request.requestId,
                sentenceLocator = request.sentenceLocator,
                pcmFloatSamples = merged,
                sampleRateHz = _cloudConfig.value.sampleRateHz,
                channels = 1,
                cachedDiskFilePath = cachedPath
            )
        }
    }

    override fun synthesizeStream(
        request: TtsSynthesisRequest
    ): Flow<SynthesizedAudioChunk> = flow {
        cancelRequested.set(false)
        val cfg = _cloudConfig.value
        val locator = request.sentenceLocator
        val effectiveVoiceId = request.voiceId.ifBlank { cfg.voiceId }
        val effectiveSpeed = request.speed.coerceIn(0.25f, 4.0f)

        val cacheFile = buildDiskCacheFile(
            bookId = locator.bookId,
            chapterIndex = locator.chapterIndex,
            sentenceText = request.text,
            voiceId = effectiveVoiceId,
            speed = effectiveSpeed
        )

        // 1. Serve immediately from disk cache if already synthesized
        if (cfg.diskCacheEnabled && cacheFile.exists() && cacheFile.length() > 0L) {
            val cachedFloats = readFloat32PcmFromDisk(cacheFile)
            val chunkSize = 4800 // 200ms @ 24kHz
            var offset = 0
            var partIndex = 0
            while (offset < cachedFloats.size && !cancelRequested.get()) {
                val end = min(offset + chunkSize, cachedFloats.size)
                emit(
                    SynthesizedAudioChunk(
                        requestId = "${request.requestId}_cache_${partIndex++}",
                        sentenceLocator = locator,
                        pcmFloatSamples = cachedFloats.copyOfRange(offset, end),
                        sampleRateHz = cfg.sampleRateHz,
                        channels = 1,
                        cachedDiskFilePath = cacheFile.absolutePath
                    )
                )
                offset = end
            }
            return@flow
        }

        // 2. Build endpoint URL, headers, and payload for OpenAI / ElevenLabs / OpenAI-compatible
        val (endpointUrl, headers, bodyJson) = buildHttpRequestParameters(
            cfg = cfg,
            text = request.text,
            voiceId = effectiveVoiceId,
            speed = effectiveSpeed
        )

        val response = retrofitService.streamOpenAiCompatibleSpeech(
            fullEndpointUrl = endpointUrl,
            headers = headers,
            requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        )

        if (!response.isSuccessful) {
            val errBody = response.errorBody()?.string()?.take(300) ?: "HTTP ${response.code()}"
            throw IllegalStateException("Cloud TTS HTTP ${response.code()}: $errBody")
        }

        val responseBody = response.body()
            ?: throw IllegalStateException("Cloud TTS returned an empty response body")

        val contentType = response.headers()["Content-Type"]?.lowercase().orEmpty()
        val isCompressedAudio = contentType.contains("mpeg") ||
            contentType.contains("mp3") ||
            cfg.responseFormat.equals("mp3", ignoreCase = true)

        if (isCompressedAudio) {
            val compressedBytes = responseBody.bytes()
            val decodedFloats = decodeCompressedAudioToFloat24kHz(compressedBytes)
            if (cfg.diskCacheEnabled && decodedFloats.isNotEmpty()) {
                writeFloat32PcmToDisk(cacheFile, decodedFloats)
                enforceMaxDiskCacheQuota(cfg.maxDiskCacheBytes)
            }
            emit(
                SynthesizedAudioChunk(
                    requestId = request.requestId,
                    sentenceLocator = locator,
                    pcmFloatSamples = decodedFloats,
                    sampleRateHz = cfg.sampleRateHz,
                    channels = 1,
                    cachedDiskFilePath = cacheFile.takeIf { it.exists() }?.absolutePath
                )
            )
        } else {
            // Stream raw 16-bit little-endian 24kHz PCM directly from OkHttp source
            val tempCacheFile = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            FileOutputStream(tempCacheFile).use { cacheOut ->
                responseBody.byteStream().use { inputStream ->
                    val byteBuffer = ByteArray(9600) // 4800 16-bit samples = 200ms @ 24kHz
                    var leftoverByte: Int = -1
                    var partIdx = 0

                    while (!cancelRequested.get()) {
                        val bytesRead = inputStream.read(byteBuffer)
                        if (bytesRead <= 0) break

                        val combinedBytes = if (leftoverByte != -1) {
                            ByteArray(bytesRead + 1).also { arr ->
                                arr[0] = leftoverByte.toByte()
                                System.arraycopy(byteBuffer, 0, arr, 1, bytesRead)
                            }
                        } else {
                            byteBuffer.copyOfRange(0, bytesRead)
                        }

                        val evenByteCount = combinedBytes.size and 1.inv()
                        leftoverByte = if (combinedBytes.size % 2 != 0) {
                            combinedBytes.last().toInt() and 0xFF
                        } else {
                            -1
                        }

                        if (evenByteCount > 0) {
                            val sampleCount = evenByteCount / 2
                            val floatSamples = FloatArray(sampleCount)
                            val shortBuf = ByteBuffer.wrap(combinedBytes, 0, evenByteCount)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()

                            var startSampleIndex = 0
                            // Strip WAV 44-byte header on first chunk if server returned audio/wav
                            if (partIdx == 0 && evenByteCount > 44 &&
                                combinedBytes[0] == 'R'.code.toByte() &&
                                combinedBytes[1] == 'I'.code.toByte() &&
                                combinedBytes[2] == 'F'.code.toByte() &&
                                combinedBytes[3] == 'F'.code.toByte()
                            ) {
                                startSampleIndex = 22
                            }

                            val validFloats = FloatArray((sampleCount - startSampleIndex).coerceAtLeast(0))
                            for (i in startSampleIndex until sampleCount) {
                                val pcm16 = shortBuf.get(i)
                                validFloats[i - startSampleIndex] = (pcm16 / 32768.0f).coerceIn(-1.0f, 1.0f)
                            }

                            if (cfg.diskCacheEnabled && validFloats.isNotEmpty()) {
                                val floatByteBuf = ByteBuffer.allocate(validFloats.size * 4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                floatByteBuf.asFloatBuffer().put(validFloats)
                                cacheOut.write(floatByteBuf.array())
                            }

                            if (validFloats.isNotEmpty()) {
                                emit(
                                    SynthesizedAudioChunk(
                                        requestId = "${request.requestId}_stream_${partIdx++}",
                                        sentenceLocator = locator,
                                        pcmFloatSamples = validFloats,
                                        sampleRateHz = cfg.sampleRateHz,
                                        channels = 1,
                                        cachedDiskFilePath = cacheFile.absolutePath
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (cancelRequested.get()) {
                tempCacheFile.delete()
                throw CancellationException("Cloud TTS stream cancelled for ${request.requestId}")
            } else if (cfg.diskCacheEnabled && tempCacheFile.exists() && tempCacheFile.length() > 0L) {
                tempCacheFile.renameTo(cacheFile)
                enforceMaxDiskCacheQuota(cfg.maxDiskCacheBytes)
            } else {
                tempCacheFile.delete()
            }
        }
    }.flowOn(ioDispatcher)

    override fun cancelActiveSynthesis() {
        cancelRequested.set(true)
    }

    override fun release() {
        cancelRequested.set(true)
    }

    private fun buildHttpRequestParameters(
        cfg: CloudApiTtsConfig,
        text: String,
        voiceId: String,
        speed: Float
    ): Triple<String, Map<String, String>, String> {
        val cleanBase = cfg.baseUrl.trim().trimEnd('/')
        return when (cfg.engineType) {
            TtsEngineType.CLOUD_ELEVENLABS -> {
                val url = if (cleanBase.contains("elevenlabs.io")) {
                    "$cleanBase/text-to-speech/$voiceId/stream?output_format=pcm_24000"
                } else {
                    "https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream?output_format=pcm_24000"
                }
                val headers = buildMap {
                    put("xi-api-key", cfg.apiKey)
                    put("Accept", "audio/pcm")
                }
                val payload = ElevenLabsSpeechRequestPayload(
                    text = text,
                    model_id = cfg.modelId.ifBlank { "eleven_multilingual_v2" },
                    voice_settings = ElevenLabsVoiceSettings(speed = speed.coerceIn(0.7f, 1.2f))
                )
                Triple(url, headers, json.encodeToString(payload))
            }

            else -> {
                val endpoint = if (cleanBase.endsWith("/audio/speech")) {
                    cleanBase
                } else if (cleanBase.endsWith("/v1")) {
                    "$cleanBase/audio/speech"
                } else {
                    "$cleanBase/v1/audio/speech"
                }
                val headers = buildMap {
                    if (cfg.apiKey.isNotBlank()) {
                        put("Authorization", "Bearer ${cfg.apiKey}")
                    }
                    put("Accept", "application/octet-stream")
                }
                val payload = OpenAiSpeechRequestPayload(
                    model = cfg.modelId.ifBlank { "tts-1-hd" },
                    input = text,
                    voice = voiceId.ifBlank { "alloy" },
                    response_format = cfg.responseFormat.ifBlank { "pcm" },
                    speed = speed.coerceIn(0.25f, 4.0f)
                )
                Triple(endpoint, headers, json.encodeToString(payload))
            }
        }
    }

    private suspend fun loadPersistedConfig() {
        runCatching {
            val prefs = dataStore.data.firstOrNull() ?: return
            val rawJson = prefs[PREF_CLOUD_TTS_CONFIG] ?: return
            val restored = json.decodeFromString<CloudApiTtsConfig>(rawJson)
            _cloudConfig.value = restored
            _isReady.value = restored.apiKey.isNotBlank()
        }.onFailure { err ->
            Log.w(TAG, "Failed to restore CloudApiTtsConfig from DataStore", err)
        }
    }

    private fun writeFloat32PcmToDisk(targetFile: File, samples: FloatArray) {
        targetFile.parentFile?.mkdirs()
        val byteBuf = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        byteBuf.asFloatBuffer().put(samples)
        targetFile.writeBytes(byteBuf.array())
    }

    private fun readFloat32PcmFromDisk(sourceFile: File): FloatArray {
        val bytes = FileInputStream(sourceFile).use { it.readBytes() }
        val floatCount = bytes.size / 4
        val floats = FloatArray(floatCount)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }

    private fun decodeCompressedAudioToFloat24kHz(compressedBytes: ByteArray): FloatArray {
        val tempFile = File.createTempFile("cloud_tts_compressed_", ".mp3", context.cacheDir)
        try {
            tempFile.writeBytes(compressedBytes)
            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)
            if (extractor.trackCount == 0) return FloatArray(0)
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outputIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val rawPcmBytes = pcmOut.toByteArray()
            val shortCount = rawPcmBytes.size / 2
            val floats = FloatArray(shortCount)
            val shortBuffer = ByteBuffer.wrap(rawPcmBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
            for (i in 0 until shortCount) {
                floats[i] = (shortBuffer.get(i) / 32768.0f).coerceIn(-1.0f, 1.0f)
            }
            return floats
        } catch (e: Exception) {
            Log.e(TAG, "Failed decoding compressed cloud TTS stream", e)
            return FloatArray(0)
        } finally {
            tempFile.delete()
        }
    }

    private fun enforceMaxDiskCacheQuota(maxBytes: Long) {
        runCatching {
            val allFiles = cacheRootDir.walkTopDown().filter { it.isFile }.toList()
            var currentBytes = allFiles.sumOf { it.length() }
            if (currentBytes <= maxBytes) return

            val sortedByOldest = allFiles.sortedBy { it.lastModified() }
            for (file in sortedByOldest) {
                if (currentBytes <= maxBytes) break
                val size = file.length()
                if (file.delete()) {
                    currentBytes -= size
                }
            }
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString().take(24)
    }

    companion object {
        private const val TAG = "CloudApiTtsProvider"
        private val PREF_CLOUD_TTS_CONFIG = stringPreferencesKey("cloud_api_tts_config_json")

        val OPENAI_COMPATIBLE_VOICES = listOf(
            TtsVoiceProfile("alloy", 0, "Alloy (OpenAI HD)", "en-US", "Neutral", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("ash", 1, "Ash (OpenAI HD)", "en-US", "Male", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("coral", 2, "Coral (OpenAI HD)", "en-US", "Female", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("echo", 3, "Echo (OpenAI HD)", "en-US", "Male", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("fable", 4, "Fable (OpenAI HD)", "en-GB", "Neutral", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("onyx", 5, "Onyx (OpenAI HD)", "en-US", "Male", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("nova", 6, "Nova (OpenAI HD)", "en-US", "Female", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("sage", 7, "Sage (OpenAI HD)", "en-US", "Female", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz"),
            TtsVoiceProfile("shimmer", 8, "Shimmer (OpenAI HD)", "en-US", "Female", TtsEngineType.CLOUD_OPENAI, "OpenAI TTS-1-HD 24kHz")
        )

        val ELEVENLABS_DEFAULT_VOICES = listOf(
            TtsVoiceProfile("21m00Tcm4TlvDq8ikWAM", 0, "Rachel (ElevenLabs Narrative)", "en-US", "Female", TtsEngineType.CLOUD_ELEVENLABS, "ElevenLabs Multilingual v2"),
            TtsVoiceProfile("29vD33N1CtxCmqQRPOHJ", 1, "Drew (ElevenLabs Audiobook)", "en-US", "Male", TtsEngineType.CLOUD_ELEVENLABS, "ElevenLabs Multilingual v2"),
            TtsVoiceProfile("EXAVITQu4vr4xnSDxMaL", 2, "Sarah (ElevenLabs Expressive)", "en-US", "Female", TtsEngineType.CLOUD_ELEVENLABS, "ElevenLabs Multilingual v2"),
            TtsVoiceProfile("ErXwobaYiN019PkySvjV", 3, "Antoni (ElevenLabs Warm)", "en-US", "Male", TtsEngineType.CLOUD_ELEVENLABS, "ElevenLabs Multilingual v2")
        )

        val GEMINI_AUDIO_VOICES = listOf(
            TtsVoiceProfile("Puck", 0, "Puck (Gemini Live Voice)", "en-US", "Male", TtsEngineType.CLOUD_GEMINI, "Gemini 24kHz PCM"),
            TtsVoiceProfile("Charon", 1, "Charon (Gemini Live Voice)", "en-US", "Male", TtsEngineType.CLOUD_GEMINI, "Gemini 24kHz PCM"),
            TtsVoiceProfile("Kore", 2, "Kore (Gemini Live Voice)", "en-US", "Female", TtsEngineType.CLOUD_GEMINI, "Gemini 24kHz PCM"),
            TtsVoiceProfile("Fenrir", 3, "Fenrir (Gemini Live Voice)", "en-US", "Male", TtsEngineType.CLOUD_GEMINI, "Gemini 24kHz PCM"),
            TtsVoiceProfile("Aoede", 4, "Aoede (Gemini Live Voice)", "en-US", "Female", TtsEngineType.CLOUD_GEMINI, "Gemini 24kHz PCM")
        )
    }
}
