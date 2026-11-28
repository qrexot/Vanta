package com.stenograf.app.audio

import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Full-band batch inference for the combined DeepFilterNet2/3 exports from deepfilter-rt. */
internal class DeepFilterAudio(private val session: AudioOnnxSession) {
    private val stft = AudioStft(960)
    private val bandWidths = erbWidths()
    private val binBands = IntArray(stft.bins).also { map ->
        var offset = 0
        bandWidths.forEachIndexed { band, width ->
            map.fill(band, offset, offset + width)
            offset += width
        }
    }

    fun process(audio: FloatArray, checkCancelled: () -> Unit): FloatArray {
        val spectra = stft.analyze(audio, checkCancelled)
        val frames = spectra.size
        val erb = FloatArray(frames * ERB_BANDS)
        val spec = FloatArray(2 * frames * DF_BINS)
        val mean = FloatArray(ERB_BANDS) { -60f - 30f * it / (ERB_BANDS - 1) }
        val unit = FloatArray(DF_BINS) { 0.001f - 0.0009f * it / (DF_BINS - 1) }
        spectra.forEachIndexed { time, frame ->
            var offset = 0
            for (band in bandWidths.indices) {
                val width = bandWidths[band]
                var power = 0f
                for (bin in offset until offset + width) {
                    power += frame.real[bin] * frame.real[bin] + frame.imag[bin] * frame.imag[bin]
                }
                offset += width
                val db = 10f * log10(power / width + 1e-10f)
                mean[band] = ALPHA * mean[band] + (1f - ALPHA) * db
                if (time >= LOOKAHEAD) erb[(time - LOOKAHEAD) * ERB_BANDS + band] = (db - mean[band]) / 40f
            }
            for (bin in 0 until DF_BINS) {
                val real = frame.real[bin]
                val imag = frame.imag[bin]
                unit[bin] = ALPHA * unit[bin] + (1f - ALPHA) * sqrt(real * real + imag * imag)
                val scale = sqrt(unit[bin]).coerceAtLeast(1e-8f)
                if (time >= LOOKAHEAD) {
                    val index = (time - LOOKAHEAD) * DF_BINS + bin
                    spec[index] = real / scale
                    spec[frames * DF_BINS + index] = imag / scale
                }
            }
        }
        checkCancelled()
        val (gains, coefficients) = session.run(
            mapOf(
                "feat_erb" to audioTensor(erb, 1, 1, frames.toLong(), ERB_BANDS.toLong()),
                "feat_spec" to audioTensor(spec, 1, 2, frames.toLong(), DF_BINS.toLong())
            ),
            "m", "coefs"
        )
        check(gains.size == erb.size && coefficients.size == frames * DF_BINS * TAPS * 2)
        val clean = spectra.mapIndexed { time, frame ->
            if (time % 32 == 0) checkCancelled()
            val filtered = Spectrum(FloatArray(stft.bins), FloatArray(stft.bins))
            for (bin in 0 until DF_BINS) {
                var real = 0f
                var imag = 0f
                val firstCoefficient = (time * DF_BINS + bin) * TAPS * 2
                for (tap in 0 until TAPS) {
                    val src = spectra.getOrNull(time + tap + LOOKAHEAD - (TAPS - 1)) ?: continue
                    val cr = coefficients[firstCoefficient + tap * 2]
                    val ci = coefficients[firstCoefficient + tap * 2 + 1]
                    real += src.real[bin] * cr - src.imag[bin] * ci
                    imag += src.real[bin] * ci + src.imag[bin] * cr
                }
                filtered.real[bin] = real
                filtered.imag[bin] = imag
            }
            for (bin in DF_BINS until stft.bins) {
                val gain = gains[time * ERB_BANDS + binBands[bin]]
                filtered.real[bin] = frame.real[bin] * gain
                filtered.imag[bin] = frame.imag[bin] * gain
            }
            filtered
        }
        return stft.synthesize(clean, audio.size, checkCancelled)
    }

    private fun erbWidths(): IntArray {
        val bandwidth = 24.7 * 9.265
        val maxErb = ln1p(24_000.0 / bandwidth)
        var previous = 0
        var carried = 0
        val widths = IntArray(ERB_BANDS) { band ->
            val boundary = (bandwidth * (exp(maxErb * (band + 1) / ERB_BANDS) - 1) / 50).roundToInt()
            val width = boundary - previous - carried
            previous = boundary
            carried = (2 - width).coerceAtLeast(0)
            width + carried
        }
        widths[widths.lastIndex] += stft.bins - widths.sum()
        return widths
    }

    companion object {
        private const val ALPHA = 0.99f
        private const val ERB_BANDS = 32
        private const val DF_BINS = 96
        private const val TAPS = 5
        private const val LOOKAHEAD = 2
    }
}
