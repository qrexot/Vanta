package com.stenograf.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fast, bounded-memory waveform peak extractor for DetailScreen scrubber.
 * Caches normalized [0..1] amplitude bars in cacheDir/waveform_peaks.
 */
object WaveformPeakExtractor {

    private const val DEFAULT_BARS = 84

    suspend fun extractPeaks(
        context: Context,
        audioPath: String,
        barCount: Int = DEFAULT_BARS
    ): List<Float> = withContext(Dispatchers.IO) {
        val file = File(audioPath)
        if (!file.exists() || file.length() < 128L) return@withContext emptyList()

        val cacheDir = File(context.cacheDir, "waveform_peaks").apply { mkdirs() }
        val cacheFile = File(cacheDir, "${file.name}_${file.length()}_${file.lastModified()}_$barCount.peaks")
        if (cacheFile.exists()) {
            val cached = runCatching {
                cacheFile.readText().split(',').mapNotNull { it.toFloatOrNull() }
            }.getOrNull()
            if (cached != null && cached.size == barCount) return@withContext cached
        }

        val raw = runCatching {
            if (audioPath.endsWith(".wav", ignoreCase = true)) {
                extractWavPeaks(file, barCount)
            } else {
                extractCompressedPeaks(file, barCount)
            }
        }.getOrElse { emptyList() }

        val normalized = normalizePeaks(raw)
        if (normalized.isNotEmpty()) {
            runCatching {
                cacheFile.writeText(normalized.joinToString(","))
            }
        }
        normalized
    }

    private fun extractWavPeaks(file: File, barCount: Int): List<Float> {
        val headerSize = WavHelper.HEADER_SIZE.toLong()
        val totalBytes = file.length()
        if (totalBytes <= headerSize + 64L) return emptyList()
        val dataBytes = totalBytes - headerSize
        val bytesPerSample = 2L
        val totalSamples = dataBytes / bytesPerSample
        if (totalSamples < barCount) return emptyList()

        val out = FloatArray(barCount)
        val windowBytes = 512
        val buf = ByteArray(windowBytes)

        RandomAccessFile(file, "r").use { raf ->
            for (i in 0 until barCount) {
                val frac = (i + 0.5f) / barCount.toFloat()
                val byteOffset = (headerSize + ((dataBytes * frac).toLong() and -2L))
                    .coerceIn(headerSize, (totalBytes - 2L).coerceAtLeast(headerSize))
                raf.seek(byteOffset)
                val read = raf.read(buf, 0, minOf(windowBytes, (totalBytes - byteOffset).toInt()))
                if (read >= 2) {
                    val samples = read / 2
                    val sb = ByteBuffer.wrap(buf, 0, samples * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    var sumSq = 0.0
                    for (s in 0 until samples) {
                        val v = sb.get(s).toDouble() / 32768.0
                        sumSq += v * v
                    }
                    out[i] = sqrt(sumSq / samples).toFloat()
                }
            }
        }
        return out.toList()
    }

    private fun extractCompressedPeaks(file: File, barCount: Int): List<Float> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrack = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    format = f
                    break
                }
            }
            if (audioTrack < 0 || format == null) return emptyList()
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            if (durationUs <= 0L) return emptyList()

            extractor.selectTrack(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }

            val out = FloatArray(barCount)
            val info = MediaCodec.BufferInfo()
            for (i in 0 until barCount) {
                val targetUs = ((durationUs * (i + 0.5)) / barCount.toDouble()).toLong()
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()

                var rms = 0f
                var attempts = 0
                while (attempts < 16) {
                    attempts++
                    val inIdx = codec.dequeueInputBuffer(4_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: break
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            break
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 6_000L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size >= 2) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val outFmt = codec.outputFormat
                            val isFloat = outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                            if (isFloat && info.size >= 4) {
                                val fb = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                                val count = fb.remaining().coerceAtMost(512)
                                var sumSq = 0.0
                                for (s in 0 until count) {
                                    val v = abs(fb.get(s).toDouble())
                                    sumSq += v * v
                                }
                                if (count > 0) rms = sqrt(sumSq / count).toFloat()
                            } else {
                                val sb = outBuf.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val count = sb.remaining().coerceAtMost(512)
                                var sumSq = 0.0
                                for (s in 0 until count) {
                                    val v = abs(sb.get(s).toDouble() / 32768.0)
                                    sumSq += v * v
                                }
                                if (count > 0) rms = sqrt(sumSq / count).toFloat()
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (rms > 0f) break
                    }
                }
                out[i] = rms
            }
            return out.toList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun normalizePeaks(raw: List<Float>): List<Float> {
        if (raw.isEmpty()) return emptyList()
        val maxVal = raw.maxOrNull()?.coerceAtLeast(0.01f) ?: return emptyList()
        return raw.map { v ->
            val ratio = (v / maxVal).coerceIn(0f, 1f)
            (ratio.pow(0.68f)).coerceIn(0.10f, 1.0f)
        }
    }
}
