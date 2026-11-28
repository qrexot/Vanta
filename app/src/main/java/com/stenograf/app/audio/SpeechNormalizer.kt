package com.stenograf.app.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

data class SpeechAnalysisStats(
    val noiseFloorRms: Float,
    val speechRms: Float,
    val recommendedGain: Float
)

/**
 * Noise-floor-aware speech normalizer (DC/80Hz rumble HPF + gated adaptive AGC + soft-knee limiter)
 * tuned for 16 kHz ASR (Silero VAD v5 + TinyASR Zipformer2).
 *
 * Boosts quiet or distant lecturers smoothly without amplifying constant room silence or clipping loud peaks.
 */
class SpeechNormalizer(
    sampleRate: Int = 16_000,
    private val targetSpeechRms: Float = DEFAULT_TARGET_RMS,
    private val minGain: Float = DEFAULT_MIN_GAIN,
    private val maxGain: Float = DEFAULT_MAX_GAIN,
    initialGain: Float = 1.0f,
    initialNoiseFloor: Float = 0.002f
) {
    private val hpCoeff: Float = kotlin.math.exp(-2.0 * Math.PI * 80.0 / sampleRate.coerceAtLeast(8_000)).toFloat()
    private var prevRaw: Float = 0f
    private var prevFiltered: Float = 0f

    private var currentGain: Float = initialGain.coerceIn(minGain, maxGain)
    private var noiseFloorRms: Float = initialNoiseFloor.coerceIn(0.0004f, 0.035f)
    private var silenceHangoverFrames: Int = 0
    private var floatScratch = FloatArray(1024)

    fun reset(initialGain: Float = 1.0f, initialNoiseFloor: Float = 0.002f) {
        prevRaw = 0f
        prevFiltered = 0f
        currentGain = initialGain.coerceIn(minGain, maxGain)
        noiseFloorRms = initialNoiseFloor.coerceIn(0.0004f, 0.035f)
        silenceHangoverFrames = 0
    }

    /**
     * Normalizes [count] mono 16 kHz PCM16 samples in-place in [samples].
     */
    fun processInPlace(samples: ShortArray, count: Int = samples.size) {
        if (count <= 0) return
        val n = minOf(count, samples.size)
        if (floatScratch.size < n) {
            floatScratch = FloatArray(n)
        }

        // 1. Apply 80 Hz DC/rumble blocker and collect frame RMS + peak
        var sumSq = 0.0
        var peak = 0f
        val coeff = hpCoeff
        var xPrev = prevRaw
        var yPrev = prevFiltered

        for (i in 0 until n) {
            val x = samples[i].toFloat() * INV_SHORT_MAX
            val y = x - xPrev + coeff * yPrev
            xPrev = x
            yPrev = y
            floatScratch[i] = y
            sumSq += (y * y).toDouble()
            val ay = abs(y)
            if (ay > peak) peak = ay
        }
        prevRaw = xPrev
        prevFiltered = yPrev

        val frameRms = sqrt(sumSq / n).toFloat()

        // 2. Update adaptive ambient noise floor (ignores near-zero digital silence)
        if (frameRms > 1e-5f) {
            if (frameRms < noiseFloorRms * 0.6f) {
                // Fast downward step when entering silence or after neural speech enhancement removes steady noise.
                noiseFloorRms = noiseFloorRms * 0.72f + frameRms * 0.28f
            } else if (frameRms < noiseFloorRms) {
                noiseFloorRms = noiseFloorRms * 0.90f + frameRms * 0.10f
            } else if (frameRms < noiseFloorRms * 2.2f) {
                noiseFloorRms = noiseFloorRms * 0.992f + frameRms * 0.008f
            }
            noiseFloorRms = noiseFloorRms.coerceIn(0.0004f, 0.035f)
        }

        // 3. Speech-gated target gain with hangover and peak-safe headroom
        val speechThreshold = maxOf(0.0016f, noiseFloorRms * 2.05f)
        val isSpeech = frameRms > speechThreshold
        val peakSafeMax = if (peak > 1e-4f) (0.92f / peak) else maxGain

        val startGain = currentGain
        val endGain = if (isSpeech) {
            silenceHangoverFrames = HANGOVER_FRAMES
            val desired = (targetSpeechRms / frameRms).coerceIn(minGain, minOf(maxGain, peakSafeMax))
            val alpha = if (desired < startGain) ATTACK_ALPHA else RELEASE_UP_ALPHA
            startGain + (desired - startGain) * alpha
        } else if (silenceHangoverFrames > 0) {
            silenceHangoverFrames--
            minOf(startGain, peakSafeMax).coerceIn(minGain, maxGain)
        } else {
            val idleCap = 1.4f.coerceIn(minGain, maxGain)
            val desired = minOf(idleCap, peakSafeMax).coerceAtLeast(minGain)
            startGain + (desired - startGain) * IDLE_RELAX_ALPHA
        }.coerceIn(minGain, maxGain)

        currentGain = endGain

        // 4. Sample-interpolated gain ramp + symmetric soft-knee limiter
        val denom = (n - 1).coerceAtLeast(1).toFloat()
        val gainDelta = endGain - startGain
        for (i in 0 until n) {
            val g = startGain + gainDelta * (i / denom)
            val scaled = softLimit(floatScratch[i] * g)
            samples[i] = (scaled * SHORT_SCALE).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    companion object {
        const val DEFAULT_TARGET_RMS = 0.12f
        const val DEFAULT_MIN_GAIN = 0.75f
        const val DEFAULT_MAX_GAIN = 9.0f

        private const val INV_SHORT_MAX = 1.0f / 32768.0f
        private const val SHORT_SCALE = 32767.0f
        private const val LIMITER_KNEE = 0.80f
        private const val LIMITER_HEADROOM = 0.19f
        private const val ATTACK_ALPHA = 0.36f
        private const val RELEASE_UP_ALPHA = 0.22f
        private const val IDLE_RELAX_ALPHA = 0.04f
        private const val HANGOVER_FRAMES = 8

        internal fun softLimit(x: Float): Float {
            val ax = abs(x)
            if (ax <= LIMITER_KNEE) return x
            val compressed = LIMITER_KNEE + LIMITER_HEADROOM * tanh(((ax - LIMITER_KNEE) / LIMITER_HEADROOM).toDouble()).toFloat()
            return if (x >= 0f) compressed else -compressed
        }

        /**
         * Fast Pass-1 scan over a 16 kHz mono PCM16 file slice to estimate ambient noise floor
         * and speech RMS so Pass-2 offline decoding boosts even the very first quiet syllable.
         */
        fun analyzePcm16File(
            pcmFile: File,
            startByte: Long,
            endByte: Long,
            onCheckCancelled: () -> Unit = {}
        ): SpeechAnalysisStats {
            val total = (endByte - startByte).coerceAtLeast(0L)
            if (total < 640L) return SpeechAnalysisStats(0.002f, DEFAULT_TARGET_RMS, 1.0f)

            val frameShorts = 320 // 20 ms @ 16 kHz
            val frameBytes = frameShorts * 2
            val buf = ByteArray(frameBytes)
            val hpCoeff = kotlin.math.exp(-2.0 * Math.PI * 80.0 / 16_000.0).toFloat()

            // Sample up to ~2500 frames across long lectures so analysis stays < ~30 ms and constant RAM
            val totalFrames = total / frameBytes
            val stepMultiplier = (totalFrames / 2500L).coerceAtLeast(1L)
            val strideBytes = frameBytes * stepMultiplier
            val frameRmsList = ArrayList<Float>(minOf(2500, totalFrames.toInt().coerceAtLeast(1)))

            var xPrev = 0f
            var yPrev = 0f
            RandomAccessFile(pcmFile, "r").use { raf ->
                var pos = startByte and -2L
                while (pos + frameBytes <= endByte) {
                    onCheckCancelled()
                    raf.seek(pos)
                    val got = raf.read(buf, 0, frameBytes)
                    if (got < frameBytes) break
                    var sumSq = 0.0
                    var i = 0
                    while (i < frameBytes) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toFloat() * INV_SHORT_MAX
                        val y = s - xPrev + hpCoeff * yPrev
                        xPrev = s
                        yPrev = y
                        sumSq += (y * y).toDouble()
                        i += 2
                    }
                    val rms = sqrt(sumSq / frameShorts).toFloat()
                    if (rms > 1e-5f) frameRmsList.add(rms)
                    if (stepMultiplier > 1L) {
                        xPrev = 0f
                        yPrev = 0f
                    }
                    pos += strideBytes
                }
            }

            if (frameRmsList.isEmpty()) return SpeechAnalysisStats(0.002f, DEFAULT_TARGET_RMS, 1.0f)
            frameRmsList.sort()
            val noiseFloor = frameRmsList[(frameRmsList.size * 0.18f).toInt().coerceIn(0, frameRmsList.lastIndex)]
                .coerceIn(0.0004f, 0.035f)
            val speechGate = maxOf(0.0022f, noiseFloor * 2.15f)
            val speechFrames = frameRmsList.filter { it > speechGate }
            val speechRms = if (speechFrames.isNotEmpty()) {
                speechFrames[(speechFrames.size * 0.72f).toInt().coerceIn(0, speechFrames.lastIndex)]
            } else {
                frameRmsList[(frameRmsList.size * 0.85f).toInt().coerceIn(0, frameRmsList.lastIndex)]
            }
            val recommended = if (speechRms > 0.0005f) {
                (DEFAULT_TARGET_RMS / speechRms).coerceIn(DEFAULT_MIN_GAIN, DEFAULT_MAX_GAIN)
            } else 1.0f

            return SpeechAnalysisStats(noiseFloor, speechRms, recommended)
        }

        /**
         * Streams [startByte .. endByte) of [srcPcm] through optional neural noise suppression ([enhancer])
         * followed by [SpeechNormalizer] into [dstPcm], preserving exact sample count and durations for ASR alignment.
         */
        internal fun writeNormalizedPcm16Window(
            srcPcm: File,
            startByte: Long,
            endByte: Long,
            dstPcm: File,
            stats: SpeechAnalysisStats,
            onCheckCancelled: () -> Unit = {},
            enhancer: NeuralSpeechEnhancer? = null,
            applyNormalizer: Boolean = true
        ): Long {
            val chunkShorts = 640 // 40 ms
            val chunkBytes = chunkShorts * 2
            val targetSamples = ((endByte - startByte).coerceAtLeast(0L) / 2L)
            if (targetSamples <= 0L) {
                FileOutputStream(dstPcm).close()
                return 0L
            }
            val byteBuf = ByteArray(chunkBytes)
            val shortBuf = ShortArray(chunkShorts)
            val effectiveInitialNoiseFloor = if (enhancer != null) {
                // GTCRN removes broadband stationary noise before normalizer sees the samples;
                // clamp raw pre-enhancer noise floor so early post-GTCRN speech is never gated out.
                minOf(stats.noiseFloorRms, 0.0022f)
            } else {
                stats.noiseFloorRms
            }
            val normalizer = if (applyNormalizer) {
                SpeechNormalizer(
                    initialGain = stats.recommendedGain,
                    initialNoiseFloor = effectiveInitialNoiseFloor
                )
            } else null

            enhancer?.reset()
            var writtenSamples = 0L
            var writeBytesBuf = ByteArray(chunkBytes)

            fun emitSamples(out: BufferedOutputStream, samples: ShortArray, available: Int) {
                val toWrite = minOf(available.toLong(), targetSamples - writtenSamples).toInt()
                if (toWrite <= 0) return
                normalizer?.processInPlace(samples, toWrite)
                val bytesNeeded = toWrite * 2
                if (writeBytesBuf.size < bytesNeeded) {
                    writeBytesBuf = ByteArray(bytesNeeded)
                }
                ByteBuffer.wrap(writeBytesBuf, 0, bytesNeeded)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()
                    .put(samples, 0, toWrite)
                out.write(writeBytesBuf, 0, bytesNeeded)
                writtenSamples += toWrite
            }

            RandomAccessFile(srcPcm, "r").use { raf ->
                BufferedOutputStream(FileOutputStream(dstPcm), 64 * 1024).use { out ->
                    var pos = startByte and -2L
                    raf.seek(pos)
                    while (pos < endByte && writtenSamples < targetSamples) {
                        onCheckCancelled()
                        val toRead = minOf(chunkBytes.toLong(), endByte - pos).toInt() and -2
                        if (toRead < 2) break
                        val got = raf.read(byteBuf, 0, toRead)
                        if (got < 2) break
                        val usableBytes = got and -2
                        val nSamples = usableBytes / 2
                        ByteBuffer.wrap(byteBuf, 0, usableBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer()
                            .get(shortBuf, 0, nSamples)

                        if (enhancer != null) {
                            val denoised = enhancer.processShorts(shortBuf, nSamples)
                            emitSamples(out, denoised, denoised.size)
                        } else {
                            emitSamples(out, shortBuf, nSamples)
                        }
                        if (usableBytes < got) {
                            raf.seek(pos + usableBytes)
                        }
                        pos += usableBytes
                    }
                    if (enhancer != null && writtenSamples < targetSamples) {
                        val tail = enhancer.flushShorts()
                        emitSamples(out, tail, tail.size)
                    }
                    // Pad trailing sample remainder (<1 frame shift) if denoiser produced fewer samples at boundary
                    if (writtenSamples < targetSamples) {
                        val rem = (targetSamples - writtenSamples).toInt()
                        emitSamples(out, ShortArray(rem), rem)
                    }
                    out.flush()
                }
            }
            enhancer?.reset()
            return writtenSamples * 2L
        }
    }
}
