package com.stenograf.app.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.stenograf.app.i18n.AppLocaleManager
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface FileAsrWindow : Closeable {
    val language: String?
    val progress: Float
    fun nextWords(): List<TranscriptWord>?
}

internal class FileAsr private constructor(
    private val manager: ModelManager,
    private val preset: LanguagePreset,
    private val tiny: ModelLease?,
    private val gigaOnnx: OnnxModelLease?,
    private val giga: OfflineRecognizer?,
    private val gigaV4: GigaAmV4OfflineLease?
) {
    fun open(file: File, startByte: Long, sampleCount: Long, beam: Int): FileAsrWindow {
        gigaV4?.let { return openGigaV4(it, file, startByte, sampleCount) }
        giga?.let { return openGiga(it, file, startByte, sampleCount) }
        val handle = TinyAsrNative.nativeFileCreateFromPcm16(
            requireNotNull(tiny).handle, file.absolutePath, startByte, sampleCount, preset.langsCsv, beam
        )
        check(handle != 0L) { AppLocaleManager.currentStrings().asrSessionError }
        return object : FileAsrWindow {
            override val language get() = TinyAsrNative.nativeFileLang(handle)
            override val progress get() = TinyAsrNative.nativeFileProgress(handle)
            override fun nextWords() = TinyAsrNative.nativeFileNext(handle)?.let(AsrJsonParser::parseWords)
            override fun close() = TinyAsrNative.nativeFileFree(handle)
        }
    }

    suspend fun release() {
        var failure: Throwable? = null
        runCatching { giga?.release() }.onFailure { failure = it }
        runCatching { manager.releaseOnnxLease(gigaOnnx) }.onFailure { if (failure == null) failure = it }
        runCatching { manager.releaseGigaV4OfflineLease(gigaV4) }.onFailure { if (failure == null) failure = it }
        runCatching { manager.releaseModelLease(tiny) }.onFailure { if (failure == null) failure = it }
        failure?.let { throw it }
    }

    private fun openGigaV4(
        lease: GigaAmV4OfflineLease,
        file: File,
        startByte: Long,
        sampleCount: Long
    ): FileAsrWindow {
        require(sampleCount >= 1L)
        return object : FileAsrWindow {
            private var consumedSamples = 0L
            private var lastLanguage: String? = null
            override val language: String?
                get() = lastLanguage
            override val progress: Float
                get() = if (sampleCount > 0L) (consumedSamples.toFloat() / sampleCount).coerceIn(0f, 1f) else 1f

            override fun nextWords(): List<TranscriptWord>? {
                if (consumedSamples >= sampleCount) return null
                val chunkSamples = minOf(MAX_GIGA_V4_WINDOW_SAMPLES, sampleCount - consumedSamples).toInt()
                val offsetSec = consumedSamples.toFloat() / SAMPLE_RATE
                val bytePos = startByte + consumedSamples * 2L
                consumedSamples += chunkSamples
                if (chunkSamples < 160) return emptyList()

                val pcmBytes = ByteArray(chunkSamples * 2)
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(bytePos)
                    raf.readFully(pcmBytes)
                }
                val shortSamples = ShortArray(chunkSamples)
                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortSamples)

                val errBuf = ByteArray(512)
                val rawJson = GigaAmV4Native.nativeOfflineTranscribePcm16(
                    lease.handle,
                    shortSamples,
                    0,
                    chunkSamples,
                    errBuf
                )
                val errMsg = GigaAmV4JsonParser.decodeError(errBuf)
                if (rawJson == null && errMsg.isNotBlank()) {
                    throw IllegalStateException(errMsg)
                }
                if (rawJson == null) return emptyList()
                val parsed = GigaAmV4JsonParser.parseOfflineResult(rawJson)
                    ?: throw IllegalStateException(AppLocaleManager.currentStrings().asrSessionError)
                lastLanguage = mergeDetectedLanguages(lastLanguage, parsed.language)
                return if (offsetSec == 0f) {
                    parsed.words
                } else {
                    parsed.words.map { word ->
                        word.copy(start = word.start + offsetSec, end = word.end + offsetSec)
                    }
                }
            }

            override fun close() = Unit
        }
    }

    private fun openGiga(
        recognizer: OfflineRecognizer, file: File, startByte: Long, sampleCount: Long
    ): FileAsrWindow {
        require(sampleCount in 1..(SAMPLE_RATE * 31L))
        val pcm = ByteArray(sampleCount.toInt() * 2)
        RandomAccessFile(file, "r").use {
            it.seek(startByte)
            it.readFully(pcm)
        }
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(sampleCount.toInt()) { shorts.get() / 32768f }
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val duration = samples.size.toFloat() / SAMPLE_RATE
            val tokens = result.tokens
            val times = result.timestamps
            val words = if (tokens.size == times.size && times.all { it.isFinite() }) {
                timedCtcWords(tokens, times, duration)
            } else {
                emptyList()
            }
            check(result.text.isBlank() || words.isNotEmpty()) { AppLocaleManager.currentStrings().asrSessionError }
            return object : FileAsrWindow {
                private var pending: List<TranscriptWord>? = words
                override val language = "ru"
                override val progress = 1f
                override fun nextWords() = pending.also { pending = null }
                override fun close() = Unit
            }
        } finally {
            stream.release()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val MAX_GIGA_V4_WINDOW_SAMPLES = SAMPLE_RATE * 30L

        suspend fun acquire(
            context: Context, manager: ModelManager, preset: LanguagePreset, threads: Int
        ): FileAsr {
            if (preset.modelId == TinyAsrCatalog.GIGAAM_V4) {
                val lease = manager.acquireGigaV4OfflineLease(threads.coerceIn(1, 4))
                return FileAsr(manager, preset, null, null, null, lease)
            }
            if (preset.modelId != TinyAsrCatalog.GIGAAM) {
                return FileAsr(manager, preset, manager.acquireModelLease(preset.modelId, threads), null, null, null)
            }
            val onnxLease = manager.acquireOnnxLease(preset.modelId)
            val tokens = File.createTempFile("gigaam-tokens-", ".txt", context.cacheDir)
            try {
                context.assets.open("models/gigaam-v3-tokens.txt").use { source ->
                    tokens.outputStream().use { source.copyTo(it) }
                }
                val config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 64),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = onnxLease.path.absolutePath),
                        tokens = tokens.absolutePath,
                        numThreads = threads.coerceIn(1, 4),
                        provider = "cpu"
                    ),
                    decodingMethod = "greedy_search"
                )
                val recognizer = OfflineRecognizer(config = config)
                return FileAsr(manager, preset, null, onnxLease, recognizer, null)
            } catch (t: Throwable) {
                runCatching { manager.releaseOnnxLease(onnxLease) }
                throw t
            } finally {
                tokens.delete()
            }
        }
    }
}

internal fun mergeDetectedLanguages(current: String?, incoming: String?): String? {
    val parts = buildSet {
        current?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.let(::addAll)
        incoming?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.let(::addAll)
    }
    return when {
        parts.isEmpty() -> null
        parts == setOf("ru", "en") -> "ru,en"
        else -> parts.joinToString(",")
    }
}

internal fun timedCtcWords(tokens: Array<String>, times: FloatArray, duration: Float): List<TranscriptWord> {
    val words = mutableListOf<TranscriptWord>()
    val word = StringBuilder()
    var start = 0f
    var end = 0f
    tokens.forEachIndexed { index, token ->
        val time = times[index].coerceIn(0f, duration)
        for (char in token) {
            if (char.isWhitespace() || char == '▁') {
                if (word.isNotEmpty()) {
                    words += TranscriptWord(word.toString(), start, end)
                    word.clear()
                }
            } else {
                if (word.isEmpty()) start = time
                word.append(char)
                end = minOf(duration, maxOf(start, time + 0.04f))
            }
        }
    }
    if (word.isNotEmpty()) words += TranscriptWord(word.toString(), start, end)
    return words
}
