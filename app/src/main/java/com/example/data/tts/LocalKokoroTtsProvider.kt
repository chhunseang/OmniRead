package com.example.data.tts

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.local.entity.TtsEngineType
import com.example.domain.tts.KokoroModelState
import com.example.domain.tts.LocalKokoroTtsProvider
import com.example.domain.tts.OfflineTtsKokoroModelConfig
import com.example.domain.tts.SynthesizedAudioChunk
import com.example.domain.tts.TtsSynthesisRequest
import com.example.domain.tts.TtsVoiceProfile
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig as SherpaKokoroModelConfig

/**
 * On-device Kokoro-82M text-to-speech via the official sherpa-onnx Android library
 * (`app/libs/sherpa-onnx-*.aar`, arm64-v8a).
 *
 * The model (≈130 MB, int8 multi-lang v1.0: model.int8.onnx, voices.bin, tokens.txt,
 * espeak-ng-data/, lexicons, dict/) is not bundled in the APK – it is downloaded once from the
 * sherpa-onnx GitHub release (or imported from a file) into app storage.
 */
@Singleton
class LocalKokoroTtsProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttpClient: OkHttpClient
) : LocalKokoroTtsProvider {

    private val engineMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)
    private var offlineTts: OfflineTts? = null

    private val downloadClient = okHttpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val modelsRoot = File(context.filesDir, "models")
    private val defaultModelDir = File(modelsRoot, MODEL_DIR_NAME)

    private val _modelConfig = MutableStateFlow(
        OfflineTtsKokoroModelConfig(
            modelDir = defaultModelDir.absolutePath,
            modelFileName = "model.int8.onnx",
            voicesFileName = "voices.bin",
            tokensFileName = "tokens.txt",
            dataDirName = "espeak-ng-data",
            lexiconFileNames = "lexicon-us-en.txt,lexicon-zh.txt",
            lang = "en-us",
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            defaultSpeakerId = 3,
            defaultVoiceCode = "af_heart",
            lengthScaleSpeed = 1.0f
        )
    )
    override val modelConfig: StateFlow<OfflineTtsKokoroModelConfig> = _modelConfig.asStateFlow()

    private val _modelState = MutableStateFlow(KokoroModelState())
    override val modelState: StateFlow<KokoroModelState> = _modelState.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val engineType: TtsEngineType = TtsEngineType.LOCAL_KOKORO

    init {
        refreshInstalledState()
    }

    private fun refreshInstalledState() {
        val installed = filesPresent(File(_modelConfig.value.modelDir))
        _modelState.value = KokoroModelState(
            installed = installed,
            statusText = if (installed) "Installed" else "Not installed – download the voice model to use Kokoro",
            installedSizeBytes = if (installed) dirSize(File(_modelConfig.value.modelDir)) else 0L
        )
    }

    private fun resolveModelFile(dir: File, configuredName: String): File? {
        return listOf(configuredName, "model.int8.onnx", "model.onnx", "kokoro-v1.0.int8.onnx", "kokoro-v1.0.onnx")
            .map { File(dir, it) }
            .firstOrNull { it.isFile && it.length() > 1_000_000L }
    }

    private fun filesPresent(dir: File): Boolean {
        val cfg = _modelConfig.value
        return resolveModelFile(dir, cfg.modelFileName) != null &&
            File(dir, cfg.voicesFileName).isFile &&
            File(dir, cfg.tokensFileName).isFile &&
            File(dir, cfg.dataDirName).isDirectory
    }

    override suspend fun verifyModelFilesExist(): Boolean = withContext(Dispatchers.IO) {
        filesPresent(File(_modelConfig.value.modelDir))
    }

    override suspend fun initialize(): Result<Unit> = withContext(Dispatchers.Default) {
        engineMutex.withLock {
            if (offlineTts != null && _isReady.value) return@withLock Result.success(Unit)
            runCatching {
                val cfg = _modelConfig.value
                val baseDir = File(cfg.modelDir)
                // sherpa-onnx aborts the whole process on missing files, so verify first.
                val modelFile = resolveModelFile(baseDir, cfg.modelFileName)
                if (modelFile == null || !filesPresent(baseDir)) {
                    throw IllegalStateException(
                        "Kokoro voice model is not installed. Open TTS Studio and tap \"Download model\"."
                    )
                }

                val lexiconPaths = cfg.lexiconFileNames.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { File(baseDir, it) }
                    .filter { it.isFile }
                    .joinToString(",") { it.absolutePath }

                val sherpaConfig = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = SherpaKokoroModelConfig(
                            model = modelFile.absolutePath,
                            voices = File(baseDir, cfg.voicesFileName).absolutePath,
                            tokens = File(baseDir, cfg.tokensFileName).absolutePath,
                            dataDir = File(baseDir, cfg.dataDirName).absolutePath,
                            lexicon = lexiconPaths,
                            lang = cfg.lang,
                            dictDir = File(baseDir, "dict").takeIf { it.isDirectory }?.absolutePath.orEmpty(),
                            lengthScale = 1.0f / cfg.lengthScaleSpeed.coerceIn(0.25f, 4.0f)
                        ),
                        numThreads = cfg.numThreads.coerceIn(1, 8),
                        debug = cfg.debug,
                        provider = "cpu"
                    ),
                    maxNumSentences = 1,
                    silenceScale = 0.2f
                )

                offlineTts?.release()
                offlineTts = OfflineTts(config = sherpaConfig)
                _isReady.value = true
                Log.i(TAG, "Kokoro ready: ${offlineTts?.numSpeakers()} speakers @ ${offlineTts?.sampleRate()} Hz")
                Unit
            }.onFailure { error ->
                Log.e(TAG, "Failed to initialize Kokoro", error)
                _isReady.value = false
            }
        }
    }

    override suspend fun updateKokoroModelConfig(config: OfflineTtsKokoroModelConfig): Result<Unit> {
        engineMutex.withLock {
            offlineTts?.release()
            offlineTts = null
            _isReady.value = false
        }
        _modelConfig.value = config
        refreshInstalledState()
        return initialize()
    }

    // ---------------------------------------------------------------------------------------
    // Model installation
    // ---------------------------------------------------------------------------------------

    override suspend fun downloadAndInstallModel(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            _modelState.update {
                it.copy(busy = true, progress = 0f, statusText = "Connecting…", errorMessage = null)
            }
            modelsRoot.mkdirs()
            val archive = File(context.cacheDir, "kokoro-model-download.tar.bz2")
            val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("Download failed: HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("Empty download response")
                val total = body.contentLength().takeIf { it > 0 } ?: MODEL_APPROX_BYTES
                body.byteStream().use { input ->
                    FileOutputStream(archive).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport > 512 * 1024) {
                                lastReport = done
                                _modelState.update {
                                    it.copy(
                                        progress = (done.toFloat() / total).coerceIn(0f, 1f),
                                        statusText = "Downloading ${done / 1_048_576} / ${total / 1_048_576} MB"
                                    )
                                }
                            }
                        }
                    }
                }
            }
            archive.inputStream().use { installFromArchiveStream(it, isZip = false) }
            archive.delete()
        }.onFailure { err ->
            Log.e(TAG, "Kokoro model download failed", err)
            _modelState.update {
                it.copy(busy = false, progress = null, errorMessage = err.message ?: "Download failed")
            }
            if (err is CancellationException) throw err
        }.map { }
    }

    override suspend fun installBundledOrDownloadedModel(
        sourceArchiveUri: String
    ): Result<OfflineTtsKokoroModelConfig> = withContext(Dispatchers.IO) {
        runCatching {
            _modelState.update { it.copy(busy = true, progress = null, statusText = "Reading archive…", errorMessage = null) }
            val uri = Uri.parse(sourceArchiveUri)
            val head = context.contentResolver.openInputStream(uri)?.use { s ->
                ByteArray(4).also { s.read(it) }
            } ?: throw IllegalArgumentException("Unable to open the selected file")
            val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            val isBz2 = head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte()
            if (!isZip && !isBz2) {
                throw IllegalArgumentException("Pick the kokoro .tar.bz2 from sherpa-onnx releases (or a .zip of that folder)")
            }
            context.contentResolver.openInputStream(uri)!!.use { installFromArchiveStream(it, isZip) }
            _modelConfig.value
        }.onFailure { err ->
            _modelState.update { it.copy(busy = false, progress = null, errorMessage = err.message ?: "Import failed") }
        }
    }

    /** Extracts a model archive into a staging dir, verifies it, then swaps it in atomically. */
    private suspend fun installFromArchiveStream(input: InputStream, isZip: Boolean) {
        _modelState.update { it.copy(busy = true, progress = null, statusText = "Unpacking model…") }
        val staging = File(modelsRoot, "$MODEL_DIR_NAME.staging").apply { deleteRecursively(); mkdirs() }

        fun target(entryName: String): File? {
            // Strip the archive's top-level folder (e.g. "kokoro-int8-multi-lang-v1_0/")
            val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            val relative = if (parts.size > 1) parts.drop(1).joinToString("/") else parts[0]
            return File(staging, relative)
        }

        if (isZip) {
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val out = target(entry.name)
                    if (out != null) {
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zis.copyTo(it, 64 * 1024) }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } else {
            TarArchiveInputStream(BZip2CompressorInputStream(input.buffered(256 * 1024))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val out = target(entry.name)
                    if (out != null) {
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { tar.copyTo(it, 64 * 1024) }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        // A zip of the folder itself (no top-level dir) puts files one level too deep-stripped; handle both.
        val root = if (filesPresent(staging)) staging else staging.listFiles()?.firstOrNull { it.isDirectory && filesPresent(it) }
            ?: throw IllegalStateException("Archive is missing model.onnx / voices.bin / tokens.txt / espeak-ng-data")

        engineMutex.withLock {
            offlineTts?.release()
            offlineTts = null
            _isReady.value = false
            defaultModelDir.deleteRecursively()
            if (!root.renameTo(defaultModelDir)) {
                root.copyRecursively(defaultModelDir, overwrite = true)
            }
            staging.deleteRecursively()
        }
        _modelConfig.update { it.copy(modelDir = defaultModelDir.absolutePath) }
        refreshInstalledState()
        _modelState.update { it.copy(busy = false, progress = null, statusText = "Installed") }
    }

    override suspend fun deleteInstalledModel() {
        engineMutex.withLock {
            offlineTts?.release()
            offlineTts = null
            _isReady.value = false
        }
        withContext(Dispatchers.IO) { defaultModelDir.deleteRecursively() }
        refreshInstalledState()
    }

    private fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------------------------------------------------------------------------------------
    // Synthesis
    // ---------------------------------------------------------------------------------------

    override suspend fun availableVoices(): List<TtsVoiceProfile> = KOKORO_V1_VOICE_CATALOG

    override suspend fun synthesizeSentence(
        request: TtsSynthesisRequest
    ): Result<SynthesizedAudioChunk> = withContext(Dispatchers.Default) {
        runCatching {
            initialize().getOrThrow()
            cancelRequested.set(false)
            val generated = engineMutex.withLock {
                val engine = offlineTts ?: throw IllegalStateException("Kokoro engine is not loaded")
                // NOTE: don't use generateWithCallback – its JNI layer looks up a typed
                // `invoke([F)Ljava/lang/Integer;` method that Kotlin 2.x lambdas don't have,
                // which aborts the whole process. Sentences are short, so plain generate() is fine.
                engine.generate(
                    text = request.text,
                    sid = resolveSpeakerId(request.voiceId, request.speakerId),
                    speed = request.speed.coerceIn(0.5f, 3.0f)
                )
            }
            if (cancelRequested.get()) throw CancellationException("Kokoro synthesis cancelled")
            SynthesizedAudioChunk(
                requestId = request.requestId,
                sentenceLocator = request.sentenceLocator,
                pcmFloatSamples = generated.samples,
                sampleRateHz = generated.sampleRate.takeIf { it > 0 } ?: SAMPLE_RATE_24KHZ,
                channels = 1
            )
        }
    }

    override fun synthesizeStream(request: TtsSynthesisRequest): Flow<SynthesizedAudioChunk> = flow {
        emit(synthesizeSentence(request).getOrThrow())
    }.flowOn(Dispatchers.Default)

    override fun cancelActiveSynthesis() {
        cancelRequested.set(true)
    }

    override fun release() {
        cancelRequested.set(true)
        offlineTts?.release()
        offlineTts = null
        _isReady.value = false
    }

    private fun resolveSpeakerId(voiceId: String, fallbackSpeakerId: Int): Int {
        return KOKORO_V1_VOICE_CATALOG.firstOrNull { it.id.equals(voiceId, ignoreCase = true) }?.speakerIndex
            ?: fallbackSpeakerId.coerceIn(0, KOKORO_V1_VOICE_CATALOG.size - 1)
    }

    companion object {
        private const val TAG = "LocalKokoroTtsProvider"
        const val SAMPLE_RATE_24KHZ = 24000

        private const val MODEL_DIR_NAME = "kokoro-int8-multi-lang-v1_0"
        const val MODEL_DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2"
        const val MODEL_APPROX_BYTES = 132_353_410L

        private fun v(id: String, sid: Int, name: String, lang: String, gender: String) =
            TtsVoiceProfile(id = id, speakerIndex = sid, name = name, languageCode = lang, gender = gender)

        /**
         * Speaker IDs of sherpa-onnx `kokoro-multi-lang-v1_0` (53 speakers, IDs 0–52), see
         * https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html
         */
        val KOKORO_V1_VOICE_CATALOG: List<TtsVoiceProfile> = listOf(
            v("af_alloy", 0, "Alloy", "en-US", "Female"),
            v("af_aoede", 1, "Aoede", "en-US", "Female"),
            v("af_bella", 2, "Bella", "en-US", "Female"),
            v("af_heart", 3, "Heart", "en-US", "Female"),
            v("af_jessica", 4, "Jessica", "en-US", "Female"),
            v("af_kore", 5, "Kore", "en-US", "Female"),
            v("af_nicole", 6, "Nicole", "en-US", "Female"),
            v("af_nova", 7, "Nova", "en-US", "Female"),
            v("af_river", 8, "River", "en-US", "Female"),
            v("af_sarah", 9, "Sarah", "en-US", "Female"),
            v("af_sky", 10, "Sky", "en-US", "Female"),
            v("am_adam", 11, "Adam", "en-US", "Male"),
            v("am_echo", 12, "Echo", "en-US", "Male"),
            v("am_eric", 13, "Eric", "en-US", "Male"),
            v("am_fenrir", 14, "Fenrir", "en-US", "Male"),
            v("am_liam", 15, "Liam", "en-US", "Male"),
            v("am_michael", 16, "Michael", "en-US", "Male"),
            v("am_onyx", 17, "Onyx", "en-US", "Male"),
            v("am_puck", 18, "Puck", "en-US", "Male"),
            v("am_santa", 19, "Santa", "en-US", "Male"),
            v("bf_alice", 20, "Alice", "en-GB", "Female"),
            v("bf_emma", 21, "Emma", "en-GB", "Female"),
            v("bf_isabella", 22, "Isabella", "en-GB", "Female"),
            v("bf_lily", 23, "Lily", "en-GB", "Female"),
            v("bm_daniel", 24, "Daniel", "en-GB", "Male"),
            v("bm_fable", 25, "Fable", "en-GB", "Male"),
            v("bm_george", 26, "George", "en-GB", "Male"),
            v("bm_lewis", 27, "Lewis", "en-GB", "Male"),
            v("ef_dora", 28, "Dora", "es", "Female"),
            v("em_alex", 29, "Alex", "es", "Male"),
            v("ff_siwis", 30, "Siwis", "fr", "Female"),
            v("hf_alpha", 31, "Alpha", "hi", "Female"),
            v("hf_beta", 32, "Beta", "hi", "Female"),
            v("hm_omega", 33, "Omega", "hi", "Male"),
            v("hm_psi", 34, "Psi", "hi", "Male"),
            v("if_sara", 35, "Sara", "it", "Female"),
            v("im_nicola", 36, "Nicola", "it", "Male"),
            v("jf_alpha", 37, "Alpha", "ja", "Female"),
            v("jf_gongitsune", 38, "Gongitsune", "ja", "Female"),
            v("jf_nezumi", 39, "Nezumi", "ja", "Female"),
            v("jf_tebukuro", 40, "Tebukuro", "ja", "Female"),
            v("jm_kumo", 41, "Kumo", "ja", "Male"),
            v("pf_dora", 42, "Dora", "pt-BR", "Female"),
            v("pm_alex", 43, "Alex", "pt-BR", "Male"),
            v("pm_santa", 44, "Santa", "pt-BR", "Male"),
            v("zf_xiaobei", 45, "Xiaobei", "zh", "Female"),
            v("zf_xiaoni", 46, "Xiaoni", "zh", "Female"),
            v("zf_xiaoxiao", 47, "Xiaoxiao", "zh", "Female"),
            v("zf_xiaoyi", 48, "Xiaoyi", "zh", "Female"),
            v("zm_yunjian", 49, "Yunjian", "zh", "Male"),
            v("zm_yunxi", 50, "Yunxi", "zh", "Male"),
            v("zm_yunxia", 51, "Yunxia", "zh", "Male"),
            v("zm_yunyang", 52, "Yunyang", "zh", "Male")
        )
    }
}
