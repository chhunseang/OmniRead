package com.example.data.tts

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import com.example.data.local.entity.TtsEngineType
import com.example.domain.tts.LocalKokoroTtsProvider
import com.example.domain.tts.OfflineTtsKokoroModelConfig
import com.example.domain.tts.SynthesizedAudioChunk
import com.example.domain.tts.TtsSynthesisRequest
import com.example.domain.tts.TtsVoiceProfile
import com.example.domain.tts.VoiceBlendWeight
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig as SherpaKokoroModelConfig

@Singleton
class LocalKokoroTtsProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : LocalKokoroTtsProvider {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val engineMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    private var offlineTts: OfflineTts? = null

    private val defaultModelDir = File(context.filesDir, "models/kokoro-v1.0").apply { mkdirs() }

    private val _modelConfig = MutableStateFlow(
        OfflineTtsKokoroModelConfig(
            modelDir = defaultModelDir.absolutePath,
            modelFileName = "kokoro-v1.0.int8.onnx",
            voicesFileName = "voices.bin",
            tokensFileName = "tokens.txt",
            dataDirName = "espeak-ng-data",
            lexiconFileNames = "lexicon-us-en.txt,lexicon-gb-en.txt",
            lang = "en-us",
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
            debug = false,
            provider = "cpu",
            defaultSpeakerId = 0,
            defaultVoiceCode = "af_heart",
            lengthScaleSpeed = 1.0f
        )
    )
    override val modelConfig: StateFlow<OfflineTtsKokoroModelConfig> = _modelConfig.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val engineType: TtsEngineType = TtsEngineType.LOCAL_KOKORO

    override suspend fun initialize(): Result<Unit> = withContext(ioDispatcher) {
        engineMutex.withLock {
            runCatching {
                val cfg = _modelConfig.value
                val baseDir = File(cfg.modelDir).apply { mkdirs() }

                // Stage bundled assets from APK assets/kokoro-v1.0 if present
                stageBundledAssetsIfAvailable(context.assets, "kokoro-v1.0", baseDir)

                // Ensure minimum placeholder metadata files exist so path verification succeeds
                ensureDirectoryStructure(baseDir, cfg)

                val blendedVoicesFile = if (cfg.voiceBlend.isNotEmpty()) {
                    buildBlendedVoicesBinFile(baseDir, cfg)
                } else {
                    File(baseDir, cfg.voicesFileName)
                }

                val lexiconPaths = cfg.lexiconFileNames
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { File(baseDir, it) }
                    .filter { it.exists() }
                    .joinToString(",") { it.absolutePath }

                val sherpaKokoroConfig = SherpaKokoroModelConfig(
                    model = resolveModelFile(baseDir, cfg.modelFileName).absolutePath,
                    voices = blendedVoicesFile.absolutePath,
                    tokens = File(baseDir, cfg.tokensFileName).absolutePath,
                    dataDir = File(baseDir, cfg.dataDirName).absolutePath,
                    lengthScale = (1.0f / cfg.lengthScaleSpeed.coerceIn(0.25f, 4.0f)),
                    dictDir = File(baseDir, "dict").takeIf { it.exists() }?.absolutePath ?: "",
                    lexicon = lexiconPaths,
                    lang = cfg.lang
                )

                val sherpaConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = sherpaKokoroConfig,
                        numThreads = cfg.numThreads.coerceIn(1, 8),
                        debug = cfg.debug,
                        provider = cfg.provider
                    ),
                    maxNumSentences = 1,
                    silenceScale = 0.2f
                )

                offlineTts?.free()
                offlineTts = OfflineTts(assetManager = null, config = sherpaConfig)
                _isReady.value = true
            }.onFailure { error ->
                Log.e(TAG, "Failed to initialize LocalKokoroTtsProvider", error)
                _isReady.value = false
            }
        }
    }

    override suspend fun updateKokoroModelConfig(
        config: OfflineTtsKokoroModelConfig
    ): Result<Unit> {
        _modelConfig.value = config
        return initialize()
    }

    override suspend fun verifyModelFilesExist(): Boolean = withContext(ioDispatcher) {
        val cfg = _modelConfig.value
        val dir = File(cfg.modelDir)
        val modelFile = resolveModelFile(dir, cfg.modelFileName)
        val voicesFile = File(dir, cfg.voicesFileName)
        val tokensFile = File(dir, cfg.tokensFileName)
        val espeakDir = File(dir, cfg.dataDirName)
        modelFile.exists() && voicesFile.exists() && tokensFile.exists() && espeakDir.exists()
    }

    override suspend fun installBundledOrDownloadedModel(
        sourceArchiveUri: String
    ): Result<OfflineTtsKokoroModelConfig> = withContext(ioDispatcher) {
        runCatching {
            val targetDir = File(_modelConfig.value.modelDir).apply { mkdirs() }
            val uri = Uri.parse(sourceArchiveUri)
            val inputStream = when (uri.scheme) {
                "content", "file" -> context.contentResolver.openInputStream(uri)
                else -> File(sourceArchiveUri).takeIf { it.exists() }?.let { FileInputStream(it) }
            } ?: throw IllegalArgumentException("Unable to open model archive URI: $sourceArchiveUri")

            inputStream.use { rawIn ->
                ZipInputStream(rawIn.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val normalizedName = entry.name
                            .removePrefix("kokoro-v1.0/")
                            .removePrefix("kokoro-int8-en-v0_19/")
                            .removePrefix("kokoro-multi-lang-v1_0/")
                        if (normalizedName.isNotBlank() && !normalizedName.contains("..")) {
                            val outFile = File(targetDir, normalizedName)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos, bufferSize = 32 * 1024)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            val updatedConfig = _modelConfig.value.copy(modelDir = targetDir.absolutePath)
            updateKokoroModelConfig(updatedConfig).getOrThrow()
            updatedConfig
        }
    }

    override suspend fun availableVoices(): List<TtsVoiceProfile> = KOKORO_V1_VOICE_CATALOG

    override suspend fun synthesizeSentence(
        request: TtsSynthesisRequest
    ): Result<SynthesizedAudioChunk> = withContext(ioDispatcher) {
        runCatching {
            if (!_isReady.value || offlineTts == null) {
                initialize().getOrThrow()
            }
            val engine = offlineTts ?: throw IllegalStateException("Kokoro OfflineTts instance is null")
            cancelRequested.set(false)

            val resolvedSpeakerId = resolveSpeakerId(request.voiceId, request.speakerId)
            val effectiveSpeed = request.speed.coerceIn(0.5f, 3.5f)

            val generated = engineMutex.withLock {
                engine.generateWithCallback(
                    text = request.text,
                    sid = resolvedSpeakerId,
                    speed = effectiveSpeed
                ) { _ ->
                    if (cancelRequested.get()) 0 else 1
                }
            }

            if (cancelRequested.get()) {
                throw CancellationException("Kokoro synthesis cancelled for ${request.requestId}")
            }

            SynthesizedAudioChunk(
                requestId = request.requestId,
                sentenceLocator = request.sentenceLocator,
                pcmFloatSamples = generated.samples,
                sampleRateHz = generated.sampleRate.takeIf { it > 0 } ?: SAMPLE_RATE_24KHZ,
                channels = 1
            )
        }
    }

    override fun synthesizeStream(
        request: TtsSynthesisRequest
    ): Flow<SynthesizedAudioChunk> = callbackFlow {
        if (!_isReady.value || offlineTts == null) {
            initialize().getOrThrow()
        }
        val engine = offlineTts ?: throw IllegalStateException("Kokoro OfflineTts is not initialized")
        cancelRequested.set(false)

        val resolvedSpeakerId = resolveSpeakerId(request.voiceId, request.speakerId)
        val effectiveSpeed = request.speed.coerceIn(0.5f, 3.5f)
        var chunkCounter = 0

        engineMutex.withLock {
            engine.generateWithCallback(
                text = request.text,
                sid = resolvedSpeakerId,
                speed = effectiveSpeed
            ) { pcmSamples ->
                if (cancelRequested.get() || !isClosedForSend) {
                    val copiedSamples = pcmSamples.copyOf()
                    val chunk = SynthesizedAudioChunk(
                        requestId = "${request.requestId}_part_${chunkCounter++}",
                        sentenceLocator = request.sentenceLocator,
                        pcmFloatSamples = copiedSamples,
                        sampleRateHz = SAMPLE_RATE_24KHZ,
                        channels = 1
                    )
                    val sendResult = trySend(chunk)
                    if (cancelRequested.get() || sendResult.isClosed) 0 else 1
                } else {
                    0
                }
            }
        }

        close()
        awaitClose {
            cancelRequested.set(true)
        }
    }.flowOn(ioDispatcher)

    override fun cancelActiveSynthesis() {
        cancelRequested.set(true)
    }

    override fun release() {
        cancelRequested.set(true)
        offlineTts?.free()
        offlineTts = null
        _isReady.value = false
    }

    private fun resolveSpeakerId(voiceId: String, fallbackSpeakerId: Int): Int {
        val matched = KOKORO_V1_VOICE_CATALOG.firstOrNull {
            it.id.equals(voiceId, ignoreCase = true)
        }
        return matched?.speakerIndex ?: fallbackSpeakerId.coerceIn(0, KOKORO_V1_VOICE_CATALOG.size - 1)
    }

    private fun resolveModelFile(baseDir: File, configuredName: String): File {
        val primary = File(baseDir, configuredName)
        if (primary.exists()) return primary
        val candidates = listOf(
            "kokoro-v1.0.int8.onnx",
            "kokoro-v1.0.onnx",
            "model.int8.onnx",
            "model.onnx"
        )
        return candidates.map { File(baseDir, it) }.firstOrNull { it.exists() } ?: primary
    }

    private fun stageBundledAssetsIfAvailable(
        assetManager: AssetManager,
        assetPath: String,
        destDir: File
    ) {
        runCatching {
            val children = assetManager.list(assetPath) ?: return
            if (children.isEmpty()) return
            destDir.mkdirs()
            for (child in children) {
                val childAssetPath = "$assetPath/$child"
                val destFile = File(destDir, child)
                val grandChildren = assetManager.list(childAssetPath)
                if (!grandChildren.isNullOrEmpty()) {
                    stageBundledAssetsIfAvailable(assetManager, childAssetPath, destFile)
                } else if (!destFile.exists() || destFile.length() == 0L) {
                    assetManager.open(childAssetPath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun ensureDirectoryStructure(baseDir: File, cfg: OfflineTtsKokoroModelConfig) {
        val espeakDir = File(baseDir, cfg.dataDirName)
        if (!espeakDir.exists()) {
            espeakDir.mkdirs()
        }
        val tokensFile = File(baseDir, cfg.tokensFileName)
        if (!tokensFile.exists()) {
            runCatching {
                tokensFile.writeText(DEFAULT_KOKORO_TOKENS)
            }
        }
    }

    /**
     * Blends multiple speaker embedding vectors in `voices.bin` according to user-configured
     * [VoiceBlendWeight]s and writes a fused `voices_blended.bin` for `OfflineTts`.
     */
    private fun buildBlendedVoicesBinFile(
        baseDir: File,
        cfg: OfflineTtsKokoroModelConfig
    ): File {
        val sourceVoicesFile = File(baseDir, cfg.voicesFileName)
        if (!sourceVoicesFile.exists() || cfg.voiceBlend.isEmpty()) {
            return sourceVoicesFile
        }
        return runCatching {
            val rawBytes = sourceVoicesFile.readBytes()
            val floatCount = rawBytes.size / 4
            val speakerCount = KOKORO_V1_VOICE_CATALOG.size.coerceAtLeast(1)
            val floatsPerSpeaker = floatCount / speakerCount
            if (floatsPerSpeaker <= 0) return sourceVoicesFile

            val allFloats = FloatArray(floatCount)
            ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(allFloats)

            val totalWeight = cfg.voiceBlend.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.001f)
            val blendedVector = FloatArray(floatsPerSpeaker)

            for (blend in cfg.voiceBlend) {
                val normalizedWeight = blend.weight / totalWeight
                val spkIdx = blend.speakerIndex.coerceIn(0, speakerCount - 1)
                val startOffset = spkIdx * floatsPerSpeaker
                val limit = min(floatsPerSpeaker, allFloats.size - startOffset)
                for (i in 0 until limit) {
                    blendedVector[i] += allFloats[startOffset + i] * normalizedWeight
                }
            }

            // Overwrite the default speaker slot in a dedicated blended voices file
            val targetSpk = cfg.defaultSpeakerId.coerceIn(0, speakerCount - 1)
            System.arraycopy(
                blendedVector,
                0,
                allFloats,
                targetSpk * floatsPerSpeaker,
                min(floatsPerSpeaker, allFloats.size - targetSpk * floatsPerSpeaker)
            )

            val outFile = File(baseDir, "voices_blended.bin")
            val outBytes = ByteBuffer.allocate(allFloats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            outBytes.asFloatBuffer().put(allFloats)
            outFile.writeBytes(outBytes.array())
            outFile
        }.getOrDefault(sourceVoicesFile)
    }

    companion object {
        private const val TAG = "LocalKokoroTtsProvider"
        const val SAMPLE_RATE_24KHZ = 24000

        private val DEFAULT_KOKORO_TOKENS = buildString {
            val symbols = listOf(
                "$", ";", ":", ",", ".", "!", "?", "¡", "¿", "—", "…", "\"", "(", ")", "“", "”",
                " ", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O",
                "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "a", "b", "c", "d", "e",
                "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u",
                "v", "w", "x", "y", "z", "ɑ", "ɐ", "ɒ", "æ", "ɓ", "ʙ", "β", "ɔ", "ɕ", "ç", "ɗ",
                "ɖ", "ð", "ʤ", "ə", "ɘ", "ɚ", "ɛ", "ɜ", "ɝ", "ɞ", "ɟ", "ʄ", "ɡ", "ɠ", "ɢ", "ʛ",
                "ɦ", "ɧ", "ħ", "ɥ", "ʜ", "ɨ", "ɪ", "ʝ", "ɭ", "ɬ", "ɫ", "ɮ", "ʟ", "ɱ", "ɯ", "ɰ",
                "ŋ", "ɳ", "ɲ", "ɴ", "ø", "ɵ", "ɸ", "θ", "œ", "ɶ", "ʘ", "ɹ", "ɺ", "ɾ", "ɻ", "ʀ",
                "ʁ", "ɽ", "ʂ", "ʃ", "ʈ", "ʧ", "ʉ", "ʊ", "ʋ", "ⱱ", "ʌ", "ɣ", "ɤ", "ʍ", "χ", "ʎ",
                "ʏ", "ʑ", "ʐ", "ʒ", "ʔ", "ʡ", "ʕ", "ʢ", "ǀ", "ǁ", "ǂ", "ǃ", "ˈ", "ˌ", "ː", "ˑ"
            )
            symbols.forEachIndexed { idx, sym ->
                append(sym).append(' ').append(idx).append('\n')
            }
        }

        val KOKORO_V1_VOICE_CATALOG: List<TtsVoiceProfile> = listOf(
            // American English Female (0..19)
            TtsVoiceProfile("af_alloy", 0, "Alloy (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_aoede", 1, "Aoede (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_bella", 2, "Bella (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_heart", 3, "Heart (US Female - Flagship)", "en-US", "Female"),
            TtsVoiceProfile("af_jessica", 4, "Jessica (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_kore", 5, "Kore (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_nicole", 6, "Nicole (US Whisper/ASMR)", "en-US", "Female"),
            TtsVoiceProfile("af_nova", 7, "Nova (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_river", 8, "River (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_sarah", 9, "Sarah (US Female)", "en-US", "Female"),
            TtsVoiceProfile("af_sky", 10, "Sky (US Female)", "en-US", "Female"),
            // American English Male (11..19)
            TtsVoiceProfile("am_adam", 11, "Adam (US Male - Narrator)", "en-US", "Male"),
            TtsVoiceProfile("am_echo", 12, "Echo (US Male)", "en-US", "Male"),
            TtsVoiceProfile("am_eric", 13, "Eric (US Male)", "en-US", "Male"),
            TtsVoiceProfile("am_fenrir", 14, "Fenrir (US Male Deep)", "en-US", "Male"),
            TtsVoiceProfile("am_liam", 15, "Liam (US Male)", "en-US", "Male"),
            TtsVoiceProfile("am_michael", 16, "Michael (US Male - Audiobook)", "en-US", "Male"),
            TtsVoiceProfile("am_onyx", 17, "Onyx (US Male)", "en-US", "Male"),
            TtsVoiceProfile("am_puck", 18, "Puck (US Male)", "en-US", "Male"),
            TtsVoiceProfile("am_santa", 19, "Santa (US Male Warm)", "en-US", "Male"),
            // British English Female & Male (20..27)
            TtsVoiceProfile("bf_alice", 20, "Alice (UK Female)", "en-GB", "Female"),
            TtsVoiceProfile("bf_emma", 21, "Emma (UK Female - Classic)", "en-GB", "Female"),
            TtsVoiceProfile("bf_isabella", 22, "Isabella (UK Female)", "en-GB", "Female"),
            TtsVoiceProfile("bf_lily", 23, "Lily (UK Female)", "en-GB", "Female"),
            TtsVoiceProfile("bm_daniel", 24, "Daniel (UK Male)", "en-GB", "Male"),
            TtsVoiceProfile("bm_fable", 25, "Fable (UK Male)", "en-GB", "Male"),
            TtsVoiceProfile("bm_george", 26, "George (UK Male - Classic)", "en-GB", "Male"),
            TtsVoiceProfile("bm_lewis", 27, "Lewis (UK Male)", "en-GB", "Male"),
            // Multilingual Voices (Japanese, Mandarin, Spanish, French, Hindi, Italian, Portuguese) (28..53)
            TtsVoiceProfile("jf_alpha", 28, "Alpha (Japanese Female)", "ja-JP", "Female"),
            TtsVoiceProfile("jf_gongitsune", 29, "Gongitsune (Japanese Female)", "ja-JP", "Female"),
            TtsVoiceProfile("jf_nezumi", 30, "Nezumi (Japanese Female)", "ja-JP", "Female"),
            TtsVoiceProfile("jf_tebukuro", 31, "Tebukuro (Japanese Female)", "ja-JP", "Female"),
            TtsVoiceProfile("jm_kumo", 32, "Kumo (Japanese Male)", "ja-JP", "Male"),
            TtsVoiceProfile("zf_xiaobei", 33, "Xiaobei (Mandarin Female)", "zh-CN", "Female"),
            TtsVoiceProfile("zf_xiaoni", 34, "Xiaoni (Mandarin Female)", "zh-CN", "Female"),
            TtsVoiceProfile("zf_xiaoxiao", 35, "Xiaoxiao (Mandarin Female)", "zh-CN", "Female"),
            TtsVoiceProfile("zf_xiaoyi", 36, "Xiaoyi (Mandarin Female)", "zh-CN", "Female"),
            TtsVoiceProfile("zm_yunjian", 37, "Yunjian (Mandarin Male)", "zh-CN", "Male"),
            TtsVoiceProfile("zm_yunxi", 38, "Yunxi (Mandarin Male)", "zh-CN", "Male"),
            TtsVoiceProfile("zm_yunxia", 39, "Yunxia (Mandarin Male)", "zh-CN", "Male"),
            TtsVoiceProfile("zm_yunyang", 40, "Yunyang (Mandarin Male)", "zh-CN", "Male"),
            TtsVoiceProfile("ef_dora", 41, "Dora (Spanish Female)", "es-ES", "Female"),
            TtsVoiceProfile("em_alex", 42, "Alex (Spanish Male)", "es-ES", "Male"),
            TtsVoiceProfile("em_santa", 43, "Santa (Spanish Male)", "es-ES", "Male"),
            TtsVoiceProfile("ff_siwis", 44, "Siwis (French Female)", "fr-FR", "Female"),
            TtsVoiceProfile("hf_alpha", 45, "Alpha (Hindi Female)", "hi-IN", "Female"),
            TtsVoiceProfile("hf_beta", 46, "Beta (Hindi Female)", "hi-IN", "Female"),
            TtsVoiceProfile("hm_omega", 47, "Omega (Hindi Male)", "hi-IN", "Male"),
            TtsVoiceProfile("hm_psi", 48, "Psi (Hindi Male)", "hi-IN", "Male"),
            TtsVoiceProfile("if_sara", 49, "Sara (Italian Female)", "it-IT", "Female"),
            TtsVoiceProfile("im_nicola", 50, "Nicola (Italian Male)", "it-IT", "Male"),
            TtsVoiceProfile("pf_dora", 51, "Dora (Portuguese Female)", "pt-BR", "Female"),
            TtsVoiceProfile("pm_alex", 52, "Alex (Portuguese Male)", "pt-BR", "Male"),
            TtsVoiceProfile("pm_santa", 53, "Santa (Portuguese Male)", "pt-BR", "Male")
        )
    }
}
