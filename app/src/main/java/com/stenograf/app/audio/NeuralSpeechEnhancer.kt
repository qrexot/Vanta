package com.stenograf.app.audio

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Streaming neural speech enhancement (GTCRN via sherpa-onnx ONNX Runtime) at 16 kHz mono.
 * Suppresses stationary wind, street traffic, fan noise, and room hum before audio enters
 * TinyASR, Groq Whisper, or speaker diarization.
 */
internal class NeuralSpeechEnhancer private constructor(
    private val denoiser: FloatSpeechDenoiser
) : Closeable {

    private var floatScratch = FloatArray(0)

    /**
     * Feeds [count] 16 kHz mono PCM16 samples and emits denoised PCM16 samples aligned to the
     * denoiser's hop frames. Remaining tail samples are emitted by [flushShorts].
     */
    fun processShorts(samples: ShortArray, count: Int = samples.size): ShortArray {
        val n = minOf(count, samples.size)
        if (n <= 0) return ShortArray(0)
        val floats = ensureFloatScratch(n)
        for (i in 0 until n) {
            floats[i] = samples[i].toFloat() * INV_SHORT_MAX
        }
        val input = if (floats.size == n) floats else floats.copyOf(n)
        val out = denoiser.run(input)
        return floatsToShorts(out, out.size)
    }

    /**
     * Flushes any trailing overlap-add frame held inside the denoiser without resetting its weights.
     */
    fun flushShorts(): ShortArray {
        val out = denoiser.flush()
        return floatsToShorts(out, out.size)
    }

    fun reset() {
        denoiser.reset()
    }

    /**
     * Denoises an arbitrary contiguous 16 kHz mono PCM16 byte slice while preserving exact sample
     * count so ASR word timestamps remain aligned to the original audio clock.
     */
    fun denoisePcm16BytesExact(pcm: ByteArray): ByteArray {
        val totalSamples = pcm.size / 2
        if (totalSamples <= 0) return pcm
        denoiser.reset()
        val inShorts = ShortArray(totalSamples)
        ByteBuffer.wrap(pcm, 0, totalSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(inShorts)

        val outSamples = FloatArray(totalSamples)
        var written = 0
        var offset = 0
        val chunk = 2560 // 160 ms blocks
        val floatBlock = FloatArray(chunk)
        while (offset < totalSamples && written < totalSamples) {
            val n = minOf(chunk, totalSamples - offset)
            val block = if (n == chunk) floatBlock else FloatArray(n)
            for (i in 0 until n) {
                block[i] = inShorts[offset + i].toFloat() * INV_SHORT_MAX
            }
            val emitted = denoiser.run(block)
            val copyCount = minOf(emitted.size, totalSamples - written)
            emitted.copyInto(outSamples, written, 0, copyCount)
            written += copyCount
            offset += n
        }
        if (written < totalSamples) {
            val flushed = denoiser.flush()
            val copyCount = minOf(flushed.size, totalSamples - written)
            flushed.copyInto(outSamples, written, 0, copyCount)
            written += copyCount
        }
        denoiser.reset()

        val outBytes = ByteArray(totalSamples * 2)
        val outShorts = ShortArray(totalSamples)
        for (i in 0 until written) {
            outShorts[i] = floatToShort(outSamples[i])
        }
        ByteBuffer.wrap(outBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(outShorts)
        return outBytes
    }

    private fun ensureFloatScratch(size: Int): FloatArray {
        if (floatScratch.size < size) {
            floatScratch = FloatArray(size)
        }
        return floatScratch
    }

    override fun close() {
        denoiser.close()
    }

    companion object {
        const val MODEL_ASSET_PATH = "models/gtcrn_simple.onnx"
        const val SAMPLE_RATE = 16_000
        private const val INV_SHORT_MAX = 1.0f / 32768.0f
        private const val SHORT_SCALE = 32767.0f

        fun tryCreate(assets: AssetManager, threads: Int = 1, preferFast: Boolean = false): NeuralSpeechEnhancer? {
            if (preferFast) {
                try {
                    return NeuralSpeechEnhancer(FastEnhancer.create(assets))
                } catch (e: Exception) {
                    Log.w("Vanta", "FastEnhancer unavailable; using GTCRN", e)
                } catch (e: LinkageError) {
                    Log.w("Vanta", "FastEnhancer unavailable; using GTCRN", e)
                }
            }
            return try {
                val config = OnlineSpeechDenoiserConfig(
                    model = OfflineSpeechDenoiserModelConfig(
                        gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = MODEL_ASSET_PATH),
                        numThreads = threads.coerceIn(1, 2),
                        debug = false,
                        provider = "cpu"
                    )
                )
                val gtcrn = OnlineSpeechDenoiser(assets, config)
                NeuralSpeechEnhancer(object : FloatSpeechDenoiser {
                    override fun run(input: FloatArray) = gtcrn.run(input, SAMPLE_RATE).samples
                    override fun flush(): FloatArray = gtcrn.flush().samples
                    override fun reset() = gtcrn.reset()
                    override fun close() = gtcrn.release()
                })
            } catch (e: RuntimeException) {
                Log.w("Vanta", "Neural speech enhancer unavailable", e)
                null
            } catch (e: LinkageError) {
                Log.w("Vanta", "Neural speech enhancer unavailable", e)
                null
            }
        }

        private fun floatToShort(sample: Float): Short {
            val clamped = if (sample.isFinite()) sample.coerceIn(-1.0f, 1.0f) else 0f
            return (clamped * SHORT_SCALE).roundToInt().coerceIn(-32768, 32767).toShort()
        }

        private fun floatsToShorts(samples: FloatArray, count: Int): ShortArray {
            if (count <= 0) return ShortArray(0)
            return ShortArray(count) { floatToShort(samples[it]) }
        }
    }
}
