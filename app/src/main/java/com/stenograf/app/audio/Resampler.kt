package com.stenograf.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Converts microphone or decoded audio to 16 kHz PCM, rejecting frequencies that would alias into speech. */
class StreamingResamplerTo16k(
    private val inputSampleRate: Int,
    private val outputSampleRate: Int = 16_000
) {
    private val ratio: Double = inputSampleRate.toDouble() / outputSampleRate
    private var t: Double = 0.0
    private val filter = if (ratio > 1.0) {
        val half = 15
        val cutoff = (0.425 * outputSampleRate) / inputSampleRate
        val taps = DoubleArray(2 * half + 1) { index ->
            val offset = index - half
            val sinc = if (offset == 0) 2 * cutoff else sin(2 * PI * cutoff * offset) / (PI * offset)
            sinc * (0.54 + 0.46 * cos(PI * offset / half))
        }
        val gain = taps.sum()
        FloatArray(taps.size) { (taps[it] / gain).toFloat() }
    } else {
        FloatArray(0)
    }
    private val history = FloatArray(filter.size)
    private var filteredScratch = FloatArray(0)
    private var writeIndex = 0
    private var prevFiltered: Float = 0f

    fun process(input: ShortArray, count: Int): ShortArray {
        if (count <= 0) return ShortArray(0)
        if (inputSampleRate == outputSampleRate) {
            return if (count == input.size) input.copyOf() else input.copyOfRange(0, count)
        }

        if (filteredScratch.size < count) {
            filteredScratch = FloatArray(count)
        }
        val filtered = filteredScratch
        for (j in 0 until count) {
            val sample = input[j].toFloat()
            if (filter.isEmpty()) {
                filtered[j] = sample
                continue
            }
            history[writeIndex] = sample
            var sum = 0f
            var cursor = writeIndex
            for (tap in filter) {
                sum += tap * history[cursor]
                cursor = if (cursor == 0) history.lastIndex else cursor - 1
            }
            filtered[j] = sum
            writeIndex = if (writeIndex == history.lastIndex) 0 else writeIndex + 1
        }

        val maxOut = ((count / ratio) + 4).toInt().coerceAtLeast(1)
        val out = ShortArray(maxOut)
        var outLen = 0
        var pos = t
        while (pos < count - 1) {
            val k = kotlin.math.floor(pos).toInt()
            val f = (pos - k).toFloat()
            val a = if (k < 0) prevFiltered else filtered[k]
            val b = filtered[k + 1]
            val sample = (a + (b - a) * f).toInt().coerceIn(-32768, 32767)
            if (outLen < out.size) {
                out[outLen++] = sample.toShort()
            }
            pos += ratio
        }
        t = pos - count
        prevFiltered = filtered[count - 1]
        return if (outLen == out.size) out else out.copyOf(outLen)
    }

    fun reset() {
        t = 0.0
        history.fill(0f)
        writeIndex = 0
        prevFiltered = 0f
    }
}
