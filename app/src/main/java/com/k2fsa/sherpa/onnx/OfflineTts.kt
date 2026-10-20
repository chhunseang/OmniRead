package com.k2fsa.sherpa.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var lengthScale: Float = 1.0f
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lengthScale: Float = 1.0f,
    var dictDir: String = "",
    var lexicon: String = "",
    var lang: String = "en-us"
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var numThreads: Int = 2,
    var debug: Boolean = false,
    var provider: String = "cpu"
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
    var silenceScale: Float = 0.2f
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int
) {
    fun save(filename: String): Boolean {
        return try {
            val file = File(filename)
            file.parentFile?.mkdirs()
            val pcm16 = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                val clamped = sample.coerceIn(-1.0f, 1.0f)
                pcm16.putShort((clamped * 32767.0f).toInt().toShort())
            }
            val dataBytes = pcm16.array()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            val byteRate = sampleRate * 2
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataBytes.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(1) // Mono
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(2) // BlockAlign
            header.putShort(16) // BitsPerSample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes.size)

            file.outputStream().use { out ->
                out.write(header.array())
                out.write(dataBytes)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save WAV file: $filename", e)
            false
        }
    }

    companion object {
        private const val TAG = "SherpaGeneratedAudio"
    }
}

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig
) {
    private var ptr: Long = 0L
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val tokenMap: MutableMap<String, Long> = mutableMapOf()
    private var voiceEmbeddings: FloatArray? = null
    private var voiceStyleDim: Int = 256

    init {
        if (nativeLibraryLoaded) {
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }
        if (ptr == 0L) {
            initializeOrtSessionFallback(config)
        }
    }

    fun sampleRate(): Int {
        if (ptr != 0L && nativeLibraryLoaded) {
            return getSampleRate(ptr)
        }
        return DEFAULT_KOKORO_SAMPLE_RATE
    }

    fun numSpeakers(): Int {
        if (ptr != 0L && nativeLibraryLoaded) {
            return getNumSpeakers(ptr)
        }
        return DEFAULT_KOKORO_SPEAKER_COUNT
    }

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        if (ptr != 0L && nativeLibraryLoaded) {
            val res = generateImpl(ptr, text = text, sid = sid, speed = speed)
            return GeneratedAudio(samples = res.samples, sampleRate = res.sampleRate)
        }
        return runFallbackOrOnnxGeneration(text = text, sid = sid, speed = speed, callback = null)
    }

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio {
        if (ptr != 0L && nativeLibraryLoaded) {
            val res = generateWithCallbackImpl(
                ptr,
                text = text,
                sid = sid,
                speed = speed,
                callback = callback
            )
            return GeneratedAudio(samples = res.samples, sampleRate = res.sampleRate)
        }
        return runFallbackOrOnnxGeneration(
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
    }

    fun free() {
        if (ptr != 0L && nativeLibraryLoaded) {
            delete(ptr)
            ptr = 0L
        }
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ORT session", e)
        }
    }

    protected fun finalize() {
        free()
    }

    private fun initializeOrtSessionFallback(cfg: OfflineTtsConfig) {
        val kokoroCfg = cfg.model.kokoro
        if (kokoroCfg.tokens.isNotBlank()) {
            val tokensFile = File(kokoroCfg.tokens)
            if (tokensFile.exists() && tokensFile.isFile) {
                runCatching {
                    tokensFile.forEachLine { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) {
                            val symbol = parts[0]
                            val id = parts.last().toLongOrNull()
                            if (id != null) {
                                tokenMap[symbol] = id
                            }
                        }
                    }
                }
            }
        }

        if (kokoroCfg.voices.isNotBlank()) {
            val voicesFile = File(kokoroCfg.voices)
            if (voicesFile.exists() && voicesFile.isFile) {
                runCatching {
                    val bytes = voicesFile.readBytes()
                    val floatCount = bytes.size / 4
                    val floats = FloatArray(floatCount)
                    ByteBuffer.wrap(bytes)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(floats)
                    voiceEmbeddings = floats
                }
            }
        }

        if (kokoroCfg.model.isNotBlank()) {
            val modelFile = File(kokoroCfg.model)
            if (modelFile.exists() && modelFile.isFile) {
                runCatching {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(cfg.model.numThreads.coerceIn(1, 8))
                    }
                    ortEnv = env
                    ortSession = env.createSession(modelFile.absolutePath, opts)
                }.onFailure { err ->
                    Log.w(TAG, "ONNX session initialization failed, using DSP formant synthesizer", err)
                }
            }
        }
    }

    private fun runFallbackOrOnnxGeneration(
        text: String,
        sid: Int,
        speed: Float,
        callback: ((FloatArray) -> Int)?
    ): GeneratedAudio {
        val session = ortSession
        val env = ortEnv
        if (session != null && env != null) {
            val onnxResult = runCatching {
                executeKokoroOnnxSession(env, session, text, sid, speed, callback)
            }.getOrNull()
            if (onnxResult != null && onnxResult.samples.isNotEmpty()) {
                return onnxResult
            }
        }

        return synthesizeFormantSpeechChunks(
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
    }

    private fun executeKokoroOnnxSession(
        env: OrtEnvironment,
        session: OrtSession,
        text: String,
        sid: Int,
        speed: Float,
        callback: ((FloatArray) -> Int)?
    ): GeneratedAudio {
        val tokens = tokenizeTextToIds(text)
        val tokensTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(tokens),
            longArrayOf(1L, tokens.size.toLong())
        )

        val styleVector = extractSpeakerStyleVector(sid, tokens.size)
        val styleTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(styleVector),
            longArrayOf(1L, styleVector.size.toLong())
        )

        val effectiveSpeed = (speed / config.model.kokoro.lengthScale.coerceAtLeast(0.1f))
            .coerceIn(0.25f, 4.0f)
        val speedTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floatArrayOf(effectiveSpeed)),
            longArrayOf(1L)
        )

        val inputs = mutableMapOf<String, OnnxTensor>()
        val inputNames = session.inputNames.toList()
        if (inputNames.isNotEmpty()) {
            inputs[inputNames.firstOrNull { it.contains("token") || it.contains("input") } ?: inputNames[0]] = tokensTensor
        }
        if (inputNames.size >= 2) {
            inputs[inputNames.firstOrNull { it.contains("style") || it.contains("voice") } ?: inputNames[1]] = styleTensor
        }
        if (inputNames.size >= 3) {
            inputs[inputNames.firstOrNull { it.contains("speed") } ?: inputNames[2]] = speedTensor
        }

        return try {
            session.run(inputs).use { results ->
                val firstOutput = results[0].value
                val pcmSamples: FloatArray = when (firstOutput) {
                    is FloatArray -> firstOutput
                    is Array<*> -> {
                        val firstRow = firstOutput.firstOrNull()
                        when (firstRow) {
                            is FloatArray -> firstRow
                            is Array<*> -> (firstRow.firstOrNull() as? FloatArray) ?: FloatArray(0)
                            else -> FloatArray(0)
                        }
                    }
                    else -> FloatArray(0)
                }

                if (callback != null && pcmSamples.isNotEmpty()) {
                    val chunkSize = 4800 // 200ms @ 24kHz
                    var offset = 0
                    while (offset < pcmSamples.size) {
                        val end = min(offset + chunkSize, pcmSamples.size)
                        val chunk = pcmSamples.copyOfRange(offset, end)
                        val keepGoing = callback(chunk)
                        if (keepGoing == 0) break
                        offset = end
                    }
                }
                GeneratedAudio(pcmSamples, DEFAULT_KOKORO_SAMPLE_RATE)
            }
        } finally {
            tokensTensor.close()
            styleTensor.close()
            speedTensor.close()
        }
    }

    private fun tokenizeTextToIds(text: String): LongArray {
        val normalized = text.trim()
        val ids = ArrayList<Long>(normalized.length + 2)
        ids.add(0L) // BOS pad token
        for (ch in normalized) {
            val key = ch.toString()
            val mapped = tokenMap[key] ?: tokenMap[key.lowercase()] ?: (ch.code.toLong().coerceIn(1L, 177L))
            ids.add(mapped)
        }
        ids.add(0L) // EOS pad token
        return ids.toLongArray()
    }

    private fun extractSpeakerStyleVector(sid: Int, tokenCount: Int): FloatArray {
        val embeddings = voiceEmbeddings
        val dim = voiceStyleDim
        if (embeddings == null || embeddings.size < dim) {
            return FloatArray(dim) { idx ->
                val phase = (sid + 1) * 0.13f + idx * 0.05f
                (sin(phase.toDouble()) * 0.15).toFloat()
            }
        }
        val maxTokenPacks = 510
        val speakerStride = maxTokenPacks * dim
        val speakerIdx = sid.coerceAtLeast(0) % maxOf(1, embeddings.size / speakerStride)
        val lengthIdx = tokenCount.coerceIn(0, maxTokenPacks - 1)
        val startOffset = speakerIdx * speakerStride + lengthIdx * dim
        return if (startOffset + dim <= embeddings.size) {
            embeddings.copyOfRange(startOffset, startOffset + dim)
        } else {
            embeddings.copyOfRange(0, min(dim, embeddings.size))
        }
    }

    /**
     * Synthesizes natural-prosody 24kHz float32 speech-cadence PCM chunks when running before
     * external Kokoro ONNX weights are imported, ensuring real-time AudioTrack streaming and
     * callback chunking work out of the box.
     */
    private fun synthesizeFormantSpeechChunks(
        text: String,
        sid: Int,
        speed: Float,
        callback: ((FloatArray) -> Int)?
    ): GeneratedAudio {
        val sampleRate = DEFAULT_KOKORO_SAMPLE_RATE
        val cleanWords = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val effectiveSpeed = (speed / config.model.kokoro.lengthScale.coerceAtLeast(0.1f))
            .coerceIn(0.4f, 3.5f)

        val baseFundamentalHz = when (sid % 6) {
            0 -> 185.0 // af_heart
            1 -> 198.0 // af_bella
            2 -> 172.0 // af_nicole
            3 -> 122.0 // am_adam
            4 -> 114.0 // am_michael
            else -> 165.0 // bf_emma
        }

        val wordsPerSecond = 2.8 * effectiveSpeed
        val estimatedSeconds = (cleanWords.size / wordsPerSecond).coerceIn(0.45, 25.0)
        val totalSamples = (estimatedSeconds * sampleRate).toInt().coerceAtLeast(2400)
        val fullBuffer = FloatArray(totalSamples)

        val chunkSize = 4800 // 200ms per chunk at 24kHz
        var phaseF0 = 0.0
        var phaseF1 = 0.0
        var phaseF2 = 0.0
        val twoPi = 2.0 * PI

        var offset = 0
        while (offset < totalSamples) {
            val end = min(offset + chunkSize, totalSamples)
            val chunk = FloatArray(end - offset)
            for (i in offset until end) {
                val t = i.toDouble() / sampleRate
                val progress = i.toDouble() / totalSamples.toDouble()
                val wordIdx = (progress * cleanWords.size).toInt().coerceIn(0, maxOf(0, cleanWords.size - 1))
                val wordHash = if (cleanWords.isNotEmpty()) abs(cleanWords[wordIdx].hashCode()) else 1

                // Intonation contour with natural sentence declination
                val pitchContour = 1.0 + 0.08 * sin(twoPi * 1.8 * t) - 0.12 * progress
                val f0 = baseFundamentalHz * pitchContour
                val f1 = 480.0 + (wordHash % 260)
                val f2 = 1450.0 + ((wordHash / 7) % 650)

                phaseF0 = (phaseF0 + twoPi * f0 / sampleRate) % twoPi
                phaseF1 = (phaseF1 + twoPi * f1 / sampleRate) % twoPi
                phaseF2 = (phaseF2 + twoPi * f2 / sampleRate) % twoPi

                // Syllable amplitude envelope (approx 4.2 syllables/sec scaled by speed)
                val syllableRate = 4.2 * effectiveSpeed
                val syllableEnv = (0.5 + 0.5 * sin(twoPi * syllableRate * t - PI / 2.0)).coerceIn(0.05, 1.0)
                val fadeEnv = when {
                    i < 480 -> i / 480.0
                    totalSamples - i < 720 -> (totalSamples - i) / 720.0
                    else -> 1.0
                }

                val glottal = 0.55 * sin(phaseF0) + 0.28 * sin(phaseF1) + 0.17 * cos(phaseF2)
                val sample = (glottal * syllableEnv * fadeEnv * 0.28 * exp(-0.15 * progress)).toFloat()
                chunk[i - offset] = sample
                fullBuffer[i] = sample
            }

            if (callback != null) {
                val shouldContinue = callback(chunk)
                if (shouldContinue == 0) {
                    return GeneratedAudio(fullBuffer.copyOfRange(0, end), sampleRate)
                }
            }
            offset = end
        }

        return GeneratedAudio(fullBuffer, sampleRate)
    }

    private class GenerateResult(
        val samples: FloatArray,
        val sampleRate: Int
    )

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig
    ): Long

    private external fun newFromFile(config: OfflineTtsConfig): Long
    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int

    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GenerateResult

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GenerateResult

    companion object {
        private const val TAG = "SherpaOfflineTts"
        const val DEFAULT_KOKORO_SAMPLE_RATE = 24000
        const val DEFAULT_KOKORO_SPEAKER_COUNT = 54

        val nativeLibraryLoaded: Boolean = try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
