package com.stenograf.app.audio

import android.content.Context
import android.net.Uri
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TinyAsrCatalog
import com.stenograf.app.data.model.RecordingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class AudioTreatment(val modelId: String, val sampleRate: Int = 16_000) {
    QUICK_CLEANUP("gtcrn"),
    DEEP_FILTER_3(TinyAsrCatalog.DEEPFILTER3, 48_000),
    ROOM_ECHO(TinyAsrCatalog.FRCRN),
    TWO_VOICES(TinyAsrCatalog.MOSSFORMER),
    DEEP_FILTER_2(TinyAsrCatalog.DEEPFILTER2, 48_000);

    val isBuiltIn: Boolean get() = this == QUICK_CLEANUP
    val isSeparation: Boolean get() = this == TWO_VOICES

    companion object {
        fun forModel(modelId: String): AudioTreatment? = entries.firstOrNull { it.modelId == modelId }
    }
}

internal data class TreatedAudio(val tracks: List<File>, val originalPreview: File? = null)

internal object RecordingAudioTreatment {
    const val PREVIEW_SECONDS = 8
    const val SEPARATION_SECONDS = 6

    suspend fun render(
        context: Context,
        models: ModelManager,
        recording: RecordingEntity,
        treatment: AudioTreatment,
        positionMs: Long,
        preview: Boolean,
        onProgress: (Float) -> Unit
    ): TreatedAudio = withContext(Dispatchers.IO) {
        val source = File(recording.audioPath)
        require(source.isFile && source.length() > 0L)
        val rate = treatment.sampleRate
        val limitSeconds = when {
            treatment.isSeparation -> SEPARATION_SECONDS
            preview -> PREVIEW_SECONDS
            else -> null
        }
        val position = positionMs.coerceAtLeast(0L)
        val directWav = WavHelper.monoPcm16SampleRate(source) == rate
        val decoded = if (directWav) null else File.createTempFile("audio-tools-", ".pcm", context.cacheDir)
        val cleanup = mutableListOf<File>()
        val worker = coroutineContext
        try {
            if (decoded != null) {
                val uri = Uri.fromFile(source)
                val progress = { value: Float -> onProgress(0.2f * value) }
                if (limitSeconds != null) {
                    AudioDecoder.decodeUriToPcmWindow(context, uri, decoded, rate, position, limitSeconds, progress)
                } else if (rate == 48_000) {
                    AudioDecoder.decodeUriToPcm48kFile(context, uri, decoded, progress)
                } else {
                    AudioDecoder.decodeUriToPcm16kFile(context, uri, decoded, progress)
                }
            }

            val outDir = File(context.cacheDir, if (preview) "audio_previews" else "exports").apply { mkdirs() }
            val pcmFile = decoded ?: source
            val headerBytes = if (directWav) WavHelper.HEADER_SIZE.toLong() else 0L
            val available = if (directWav) wavSamples(pcmFile) else pcmFile.length() / 2L
            val sampleWindow = treatmentSampleRange(
                available, if (directWav && limitSeconds != null) position * rate / 1_000L else 0L,
                limitSeconds?.let { rate.toLong() * it }
            )
            val count = sampleWindow.last - sampleWindow.first + 1L
            check(count in 1L..0x7fff_ffedL)

            RandomAccessFile(pcmFile, "r").use { pcm ->
                val base = headerBytes + sampleWindow.first * 2L
                val original = if (preview) {
                    val samples = readSamples(pcm, base, count.toInt())
                    createWav(outDir, "audio-preview-original-", rate, samples, samples.size, cleanup)
                } else null

                val tracks = if (treatment.isBuiltIn) {
                    val enhancer = checkNotNull(NeuralSpeechEnhancer.tryCreate(context.assets))
                    enhancer.use {
                        listOf(renderDenoised(pcm, base, count, rate, outDir, treatment, cleanup, onProgress) { audio ->
                            worker.ensureActive()
                            val input = ByteBuffer.allocate(audio.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                            audio.forEach { sample -> input.putShort((sample * 32_768f).roundToInt().coerceIn(-32768, 32767).toShort()) }
                            val filtered = ByteBuffer.wrap(it.denoisePcm16BytesExact(input.array())).order(ByteOrder.LITTLE_ENDIAN)
                            FloatArray(audio.size) { filtered.short / 32_768f }
                        })
                    }
                } else {
                    val lease = models.acquireOnnxLease(treatment.modelId)
                    try {
                        AudioOnnxSession.open(lease.path).use { session ->
                            if (treatment.isSeparation) {
                                separate(session, pcm, base, count.toInt(), rate, outDir, cleanup, worker::ensureActive)
                            } else {
                                val deep = if (rate == 48_000) DeepFilterAudio(session) else null
                                val room = if (treatment == AudioTreatment.ROOM_ECHO) RoomDereverberator() else null
                                listOf(renderDenoised(pcm, base, count, rate, outDir, treatment, cleanup, onProgress) { audio ->
                                    if (deep != null) deep.process(audio, worker::ensureActive)
                                    else frcrn(session, checkNotNull(room).process(audio, worker::ensureActive))
                                })
                            }
                        }
                    } finally {
                        withContext(NonCancellable) { models.releaseOnnxLease(lease) }
                    }
                }
                worker.ensureActive()
                onProgress(1f)
                TreatedAudio(tracks, original)
            }
        } catch (failure: Throwable) {
            cleanup.forEach { it.delete() }
            throw failure
        } finally {
            decoded?.delete()
        }
    }

    private fun createWav(
        directory: File, prefix: String, rate: Int, samples: FloatArray, count: Int,
        cleanup: MutableList<File>, gain: Float = 1f
    ): File {
        val file = File.createTempFile(prefix, ".wav", directory).also(cleanup::add)
        file.outputStream().buffered().use { output ->
            output.write(WavHelper.createHeader(rate, dataSize = count * 2L))
            output.writeSamples(samples, 0, count, gain)
        }
        return file
    }

    private fun separate(
        session: AudioOnnxSession, pcm: RandomAccessFile, base: Long, count: Int, rate: Int,
        directory: File, cleanup: MutableList<File>, checkActive: () -> Unit
    ): List<File> {
        val samples = readSamples(pcm, base, count)
        val input = samples.copyOf(maxOf(2_048, ((count + 15) / 16) * 16))
        val tracks = session.run(mapOf("inputs" to audioTensor(input, 1, input.size.toLong())), "spk0", "spk1")
        check(tracks.all { it.size == input.size && it.all(Float::isFinite) })
        val peak = tracks.maxOf { track -> track.maxOf { abs(it) } }
        val rootPower = tracks.maxOf { rms(it) }
        val gain = minOf(16f, rms(input) / (rootPower + 1e-8f), 0.99f / (peak + 1e-8f))
        return tracks.mapIndexed { index, track ->
            checkActive()
            createWav(directory, "voice-${index + 1}-", rate, track, count, cleanup, gain)
        }
    }

    private suspend fun renderDenoised(
        pcm: RandomAccessFile, base: Long, count: Long, rate: Int, outDir: File,
        treatment: AudioTreatment, cleanup: MutableList<File>, onProgress: (Float) -> Unit,
        clean: (FloatArray) -> FloatArray
    ): File {
        val out = File.createTempFile("${treatment.modelId}-", ".wav", outDir).also(cleanup::add)
        val worker = coroutineContext
        val block = rate * 4
        val context = rate / 2
        val blend = rate / 8
        out.outputStream().buffered().use { wav ->
            wav.write(WavHelper.createHeader(rate, dataSize = count * 2L))
            var start = 0L
            var previousEdge = FloatArray(0)
            while (start < count) {
                worker.ensureActive()
                val end = minOf(start + block, count)
                val left = (start - context).coerceAtLeast(0L)
                val right = minOf(end + context, count)
                val audio = readSamples(pcm, base + left * 2L, (right - left).toInt())
                val filtered = clean(audio)
                check(filtered.size == audio.size && filtered.all(Float::isFinite))
                val first = (start - left).toInt()
                val last = (end - left).toInt()
                for (sample in 0 until minOf(previousEdge.size, last - first)) {
                    val mix = (sample + 1f) / (previousEdge.size + 1f)
                    val index = first + sample
                    filtered[index] = previousEdge[sample] * (1f - mix) + filtered[index] * mix
                }
                previousEdge = filtered.copyOfRange(last, minOf(last + blend, filtered.size))
                wav.writeSamples(filtered, first, last - first)
                start = end
                onProgress(0.2f + 0.8f * (start.toDouble() / count).toFloat())
            }
        }
        return out
    }

    private fun wavSamples(file: File): Long = RandomAccessFile(file, "r").use { wav ->
        wav.seek(40L)
        val declared = Integer.toUnsignedLong(Integer.reverseBytes(wav.readInt()))
        minOf(declared, wav.length() - WavHelper.HEADER_SIZE) / 2L
    }

    private fun frcrn(session: AudioOnnxSession, signal: FloatArray): FloatArray {
        val meanPower = signal.sumOf { it.toDouble() * it } / signal.size
        if (meanPower < 1e-12) return signal
        var loudPower = 0.0
        var loudSamples = 0
        signal.forEach { sample ->
            val power = sample.toDouble() * sample
            if (power > meanPower) {
                loudPower += power
                loudSamples++
            }
        }
        val rms = sqrt(if (loudSamples == 0) meanPower else loudPower / loudSamples)
        val gain = (0.056234 / rms).coerceIn(0.01, 100.0).toFloat()
        val padded = maxOf(16_000, ((signal.size + 319) / 320) * 320)
        val input = FloatArray(padded) { (signal.getOrNull(it) ?: 0f) * gain }
        val output = session.run(mapOf("noisy" to audioTensor(input, 1, padded.toLong())), "enhanced").single()
        check(output.size >= signal.size)
        return FloatArray(signal.size) { output[it] / gain }
    }

    private fun readSamples(file: RandomAccessFile, position: Long, count: Int): FloatArray {
        val bytes = ByteArray(count * 2)
        file.seek(position)
        file.readFully(bytes)
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(count) { shorts.get() / 32_768f }
    }

    private fun OutputStream.writeSamples(samples: FloatArray, start: Int, count: Int, gain: Float = 1f) {
        val buffer = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (index in start until start + count) {
            val sample = (samples[index] * gain * 32_768f).roundToInt().coerceIn(-32768, 32767)
            buffer.putShort(sample.toShort())
        }
        write(buffer.array())
    }

    private fun rms(samples: FloatArray): Float =
        sqrt(samples.sumOf { (it * it).toDouble() } / samples.size.coerceAtLeast(1)).toFloat()
}

internal fun treatmentSampleRange(total: Long, position: Long, limit: Long?): LongRange {
    if (total <= 0L) return LongRange.EMPTY
    if (limit == null) return 0L until total
    val start = if (position < total) position.coerceAtLeast(0L) else (total - limit).coerceAtLeast(0L)
    return start until (start + minOf(limit, total - start))
}
