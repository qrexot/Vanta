package com.stenograf.app.audio

import android.content.res.AssetManager
import android.util.Log
import java.io.Closeable

internal interface FloatSpeechDenoiser : Closeable {
    fun run(input: FloatArray): FloatArray
    fun flush(): FloatArray
    fun reset()
}

internal class FastEnhancer private constructor(private val session: AudioOnnxSession) : FloatSpeechDenoiser {
    private val input = FloatArray(HOP)
    private var buffered = 0
    private var previous: FloatArray? = null
    private val caches = Array(cacheShapes.size) { i ->
        FloatArray(cacheShapes[i].fold(1L, Long::times).toInt())
    }
    private var failed = false

    override fun run(input: FloatArray): FloatArray {
        val out = FloatArray(((buffered + input.size) / HOP) * HOP)
        var written = 0
        for (sample in input) {
            this.input[buffered++] = sample
            if (buffered == HOP) {
                feedHop()?.copyInto(out, written)?.let { written += HOP }
            }
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    private fun feedHop(): FloatArray? {
        val raw = previous
        val enhanced = if (failed) null else try {
            inference()
        } catch (e: Exception) {
            Log.w("Vanta", "FastEnhancer failed; passing through aligned audio", e)
            failed = true
            null
        }
        previous = input.copyOf()
        buffered = 0
        // The waveform graph has one hop of algorithmic delay (FFT 512, hop 256).
        return raw?.let { enhanced ?: it }
    }

    private fun inference(): FloatArray {
        val tensors = LinkedHashMap<String, AudioOnnxInput>(caches.size + 1)
        tensors["wav_in"] = audioTensor(input, 1, HOP.toLong())
        caches.forEachIndexed { i, data -> tensors["cache_in_$i"] = audioTensor(data, *cacheShapes[i]) }
        val result = session.run(tensors, *outputNames)
        val out = result[0]
        check(out.size == HOP && out.all { it.isFinite() })
        caches.indices.forEach { i ->
            check(result[i + 1].size == caches[i].size)
            caches[i] = result[i + 1]
        }
        return out
    }

    override fun flush(): FloatArray {
        val count = buffered
        val hadPrevious = previous != null
        if (!hadPrevious && count == 0) return FloatArray(0)
        input.fill(0f, count)
        val first = feedHop()
        val out = FloatArray((if (hadPrevious) HOP else 0) + count)
        if (first != null) first.copyInto(out)
        if (count > 0) {
            input.fill(0f)
            requireNotNull(feedHop()).copyInto(out, if (hadPrevious) HOP else 0, 0, count)
        }
        reset()
        return out
    }

    override fun reset() {
        buffered = 0
        previous = null
        caches.forEach { it.fill(0f) }
    }

    override fun close() = session.close()

    companion object {
        private const val HOP = 256
        private val cacheShapes = arrayOf(
            longArrayOf(1, 256), longArrayOf(1, 256),
            longArrayOf(1, 24, 36), longArrayOf(1, 24, 36), longArrayOf(1, 24, 36)
        )

        private val outputNames = arrayOf("wav_out", *Array(cacheShapes.size) { "cache_out_$it" })

        fun create(assets: AssetManager): FastEnhancer {
            val model = assets.open("models/fastenhancer_b.onnx").use { it.readBytes() }
            return FastEnhancer(AudioOnnxSession.open(model, threads = 1))
        }
    }
}
