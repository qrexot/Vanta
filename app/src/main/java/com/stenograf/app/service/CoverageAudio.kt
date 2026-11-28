package com.stenograf.app.service

import android.content.Context
import android.net.Uri
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.stenograf.app.asr.FileAsr
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.SpeechGap
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.asr.TranscriptCoverage
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.audio.AudioDecoder
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

internal object CoverageAudio {
    private const val SAMPLE_RATE = 16_000
    private const val SAMPLES_PER_MS = 16L

    data class Source(val file: File, val pcmOffset: Long, val limitSamples: Long) {
        val limitMs get() = limitSamples / SAMPLES_PER_MS
    }

    suspend fun <T> withSource(
        context: Context,
        recording: RecordingEntity,
        onProgress: (Float) -> Unit,
        action: suspend (Source) -> T
    ): T = withContext(Dispatchers.IO) {
        val original = File(recording.audioPath)
        require(original.isFile && original.length() > 0L)
        val nativePcm = WavHelper.monoPcm16SampleRate(original) == SAMPLE_RATE
        val temporary = if (nativePcm) null else File.createTempFile("coverage-", ".pcm", context.cacheDir)
        try {
            val processedSamples = if (recording.hasUnprocessedAudio) {
                (recording.processedAsrBytes / 2).coerceAtLeast(0L)
            } else null
            if (temporary != null) {
                val uri = Uri.fromFile(original)
                val report = { progress: Float -> onProgress(progress * 0.25f) }
                when (processedSamples) {
                    null -> AudioDecoder.decodeUriToPcm16kFile(context, uri, temporary, report)
                    0L -> Unit
                    else -> AudioDecoder.decodeUriToPcm16kPrefix(context, uri, temporary, processedSamples, report)
                }
            }
            val file = temporary ?: original
            val base = if (nativePcm) WavHelper.HEADER_SIZE.toLong() else 0L
            val length = (file.length() - base).coerceAtLeast(0L) / 2
            val limit = minOf(processedSamples ?: length, length)
            action(Source(file, base, limit))
        } finally {
            temporary?.delete()
        }
    }

    suspend fun scan(
        source: Source,
        models: ModelManager,
        paragraphs: List<ParagraphEntity>,
        onProgress: (Float) -> Unit
    ): List<SpeechGap> = withContext(Dispatchers.IO) {
        if (source.limitSamples == 0L) return@withContext emptyList()
        val model = models.ensureVadInstalled()
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = model.absolutePath,
                    threshold = 0.55f,
                    minSilenceDuration = 0.32f,
                    minSpeechDuration = 0.30f,
                    windowSize = 512,
                    maxSpeechDuration = 22f
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1
            )
        )
        val spoken = mutableListOf<SpeechGap>()
        try {
            fun drain() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    val start = segment.start / SAMPLES_PER_MS
                    val end = (segment.start + segment.samples.size) / SAMPLES_PER_MS
                    if (end > start) spoken += SpeechGap(start, end)
                    vad.pop()
                }
            }
            RandomAccessFile(source.file, "r").use { pcm ->
                pcm.seek(source.pcmOffset)
                val block = ByteArray(8_192)
                val shorts = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val input = FloatArray(block.size / 2)
                var read = 0L
                var reportAfter = 0L
                while (read < source.limitSamples) {
                    coroutineContext.ensureActive()
                    val count = minOf(input.size.toLong(), source.limitSamples - read).toInt()
                    pcm.readFully(block, 0, count * 2)
                    for (i in 0 until count) input[i] = shorts.get(i) / 32768f
                    // Pad the final VAD window so speech that reaches EOF is also considered.
                    if (count < input.size) input.fill(0f, count)
                    vad.acceptWaveform(input)
                    drain()
                    read += count
                    if (read >= reportAfter) {
                        onProgress(0.25f + 0.74f * (read.toDouble() / source.limitSamples).toFloat())
                        reportAfter = read + SAMPLE_RATE * 5
                    }
                }
            }
            vad.flush()
            drain()
            val gaps = TranscriptCoverage.missingSpeech(spoken, paragraphs, source.limitMs)
            onProgress(1f)
            gaps
        } finally {
            vad.release()
        }
    }

    suspend fun recognize(
        context: Context,
        models: ModelManager,
        source: Source,
        gap: SpeechGap
    ): List<TranscriptWord> = withContext(Dispatchers.IO) {
        require(gap.startMs >= 0 && gap.endMs <= source.limitMs && gap.endMs > gap.startMs)
        val start = (gap.startMs - 700L).coerceAtLeast(0L)
        val end = (gap.endMs + 700L).coerceAtMost(source.limitMs)
        val presetId = if (
            models.isModelInstalled(TinyAsrCatalog.GIGAAM) ||
            !models.isModelInstalled(TinyAsrCatalog.GIGAAM_V4)
        ) {
            TinyAsrCatalog.GIGAAM
        } else {
            TinyAsrCatalog.GIGAAM_V4
        }
        val giga = FileAsr.acquire(context, models, TinyAsrCatalog.findPreset(presetId), threads = 2)
        try {
            val recognized = giga.open(
                source.file, source.pcmOffset + start * SAMPLES_PER_MS * 2, (end - start) * SAMPLES_PER_MS, beam = 1
            ).use { it.nextWords().orEmpty() }
            coroutineContext.ensureActive()
            val offset = start / 1000f
            recognized.mapNotNull { word ->
                val wordStart = word.start + offset
                val wordEnd = word.end + offset
                val center = (wordStart + wordEnd) / 2f
                if (center < gap.startSec || center >= gap.endSec) null
                else word.copy(start = maxOf(wordStart, gap.startSec), end = minOf(wordEnd, gap.endSec))
            }
        } finally {
            withContext(NonCancellable) { giga.release() }
        }
    }
}
