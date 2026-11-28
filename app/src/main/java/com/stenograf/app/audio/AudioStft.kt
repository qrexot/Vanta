package com.stenograf.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class Spectrum(val real: FloatArray, val imag: FloatArray)

/** Half-overlapped analysis with a Vorbis window, as used by libDF. */
internal class AudioStft(private val size: Int) {
    val hop = size / 2
    val bins = hop + 1
    private val fft = AudioFft(size)
    private val window = FloatArray(size) {
        val inner = sin(PI * (it + 0.5) / size)
        sin(PI / 2 * inner * inner).toFloat()
    }
    private val real = FloatArray(size)
    private val imag = FloatArray(size)

    fun analyze(audio: FloatArray, checkCancelled: () -> Unit): List<Spectrum> {
        return List((audio.size + hop - 1) / hop + 1) { frame ->
            if (frame % 32 == 0) checkCancelled()
            val start = (frame - 1) * hop
            for (i in 0 until size) real[i] = (audio.getOrNull(start + i) ?: 0f) * window[i]
            imag.fill(0f)
            val spectrum = fft.transform(real, imag)
            Spectrum(
                FloatArray(bins) { spectrum.real[it] / size },
                FloatArray(bins) { spectrum.imag[it] / size }
            )
        }
    }

    fun synthesize(spectra: List<Spectrum>, length: Int, checkCancelled: () -> Unit): FloatArray {
        val output = FloatArray(length)
        spectra.forEachIndexed { frame, spectrum ->
            if (frame % 32 == 0) checkCancelled()
            for (bin in 0 until bins) {
                real[bin] = spectrum.real[bin]
                imag[bin] = -spectrum.imag[bin]
            }
            for (bin in bins until size) {
                real[bin] = real[size - bin]
                imag[bin] = -imag[size - bin]
            }
            val signal = fft.transform(real, imag).real
            val start = (frame - 1) * hop
            for (i in maxOf(0, -start) until minOf(size, length - start)) {
                output[start + i] += signal[i] * window[i]
            }
        }
        return output
    }
}

/** Mixed-radix transform for sizes factored into 2, 3 and 5. */
private class AudioFft(private val size: Int) {
    private val cosines = FloatArray(size) { cos(-2 * PI * it / size).toFloat() }
    private val sines = FloatArray(size) { sin(-2 * PI * it / size).toFloat() }
    private val result = Spectrum(FloatArray(size), FloatArray(size))
    private val realScratch = FloatArray(5)
    private val imagScratch = FloatArray(5)

    fun transform(real: FloatArray, imag: FloatArray): Spectrum {
        transform(real, imag, source = 0, stride = 1, destination = 0, count = size)
        return result
    }

    private fun transform(
        real: FloatArray, imag: FloatArray, source: Int, stride: Int, destination: Int, count: Int
    ) {
        if (count == 1) {
            result.real[destination] = real[source]
            result.imag[destination] = imag[source]
            return
        }
        val radix = when {
            count % 2 == 0 -> 2
            count % 3 == 0 -> 3
            else -> 5
        }
        val width = count / radix
        for (branch in 0 until radix) {
            transform(real, imag, source + branch * stride, stride * radix, destination + branch * width, width)
        }
        val step = size / count
        for (bin in 0 until width) {
            for (branch in 0 until radix) {
                val slot = destination + bin + branch * width
                realScratch[branch] = result.real[slot]
                imagScratch[branch] = result.imag[slot]
            }
            for (band in 0 until radix) {
                val frequency = bin + band * width
                var re = 0f
                var im = 0f
                for (branch in 0 until radix) {
                    val index = (frequency * branch * step) % size
                    re += realScratch[branch] * cosines[index] - imagScratch[branch] * sines[index]
                    im += realScratch[branch] * sines[index] + imagScratch[branch] * cosines[index]
                }
                result.real[destination + frequency] = re
                result.imag[destination + frequency] = im
            }
        }
    }
}

/** Subband delayed prediction of late reflections. The direct path is excluded by the delay. */
internal class RoomDereverberator {
    private val stft = AudioStft(512)

    fun process(audio: FloatArray, checkCancelled: () -> Unit): FloatArray {
        val input = stft.analyze(audio, checkCancelled)
        val weights = Array(TAPS) { Spectrum(FloatArray(stft.bins), FloatArray(stft.bins)) }
        val output = input.mapIndexed { time, current ->
            if (time % 32 == 0) checkCancelled()
            if (time < DELAY + TAPS || time > input.lastIndex - 1) {
                current
            } else {
                val clean = Spectrum(FloatArray(stft.bins), FloatArray(stft.bins))
                for (bin in 0 until stft.bins) {
                    val directR = current.real[bin]
                    val directI = current.imag[bin]
                    var referencePower = 1e-10f
                    var predictedR = 0f
                    var predictedI = 0f
                    for (tap in weights.indices) {
                        val history = input[time - DELAY - tap]
                        val re = history.real[bin]
                        val im = history.imag[bin]
                        referencePower += re * re + im * im
                        predictedR += weights[tap].real[bin] * re - weights[tap].imag[bin] * im
                        predictedI += weights[tap].real[bin] * im + weights[tap].imag[bin] * re
                    }
                    val errorR = directR - predictedR
                    val errorI = directI - predictedI
                    val magnitude = directR * directR + directI * directI
                    val step = 0.12f / (referencePower + TAPS * magnitude)
                    for (tap in weights.indices) {
                        val history = input[time - DELAY - tap]
                        val re = history.real[bin]
                        val im = history.imag[bin]
                        val w = weights[tap]
                        w.real[bin] = 0.997f * w.real[bin] + step * (errorR * re + errorI * im)
                        w.imag[bin] = 0.997f * w.imag[bin] + step * (errorI * re - errorR * im)
                    }
                    val power = errorR * errorR + errorI * errorI
                    val gain = kotlin.math.sqrt((power / (magnitude + 1e-10f)).coerceIn(0.25f, 1f))
                    clean.real[bin] = directR * gain
                    clean.imag[bin] = directI * gain
                }
                clean
            }
        }
        return stft.synthesize(output, audio.size, checkCancelled)
    }

    companion object {
        private const val DELAY = 3 // 48 ms at 16 kHz
        private const val TAPS = 8
    }
}
