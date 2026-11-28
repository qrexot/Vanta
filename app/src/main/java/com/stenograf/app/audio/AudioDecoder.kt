package com.stenograf.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

data class DecodedAudioInfo(
    val durationMs: Long,
    val totalSamples16k: Long
)

/**
 * Streams any Android-supported audio/video file (WAV, MP3, M4A, OGG, OPUS, AAC, FLAC, MP4)
 * via MediaExtractor + MediaCodec directly to a 16 kHz mono PCM16 file on disk,
 * keeping RAM flat even for multi-hour lecture imports.
 */
object AudioDecoder {

    suspend fun decodeUriToPcm16kFile(
        context: Context,
        sourceUri: Uri,
        destPcm16kFile: File,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudioInfo = decodeUriInternal(
        context = context,
        sourceUri = sourceUri,
        destFile = destPcm16kFile,
        onProgress = onProgress
    )

    internal suspend fun decodeUriToPcmWindow(
        context: Context,
        uri: Uri,
        file: File,
        sampleRate: Int,
        startMs: Long,
        lengthSeconds: Int,
        onProgress: (Float) -> Unit
    ): DecodedAudioInfo {
        require(startMs >= 0L && lengthSeconds > 0)
        return decodeUriInternal(
            context, uri, file, outputSampleRate = sampleRate,
            maxSamples = lengthSeconds.toLong() * sampleRate,
            startUs = startMs * 1_000L, onProgress = onProgress
        )
    }

    internal suspend fun decodeUriToPcm16kPrefix(
        context: Context,
        uri: Uri,
        file: File,
        maxSamples: Long,
        onProgress: (Float) -> Unit
    ) {
        require(maxSamples > 0L)
        decodeUriInternal(context, uri, file, maxSamples = maxSamples, onProgress = onProgress)
    }

    internal suspend fun decodeUriToPcm48kFile(
        context: Context,
        uri: Uri,
        file: File,
        onProgress: (Float) -> Unit
    ) {
        decodeUriInternal(context, uri, file, onProgress = onProgress, outputSampleRate = 48_000)
    }

    suspend fun decodeUriToPcm16kStreaming(
        context: Context,
        sourceUri: Uri,
        destPcm16kFile: File,
        chunkBytes: Long,
        onProgress: (Float) -> Unit = {},
        onChunkDecoded: suspend (chunkStartByte: Long, chunkEndByte: Long, decodeProgress: Float) -> Unit
    ): DecodedAudioInfo = decodeUriInternal(
        context = context,
        sourceUri = sourceUri,
        destFile = destPcm16kFile,
        chunkBytes = chunkBytes,
        onChunkDecoded = onChunkDecoded,
        onProgress = onProgress
    )

    private suspend fun decodeUriInternal(
        context: Context,
        sourceUri: Uri,
        destFile: File,
        outputSampleRate: Int = 16_000,
        maxSamples: Long = Long.MAX_VALUE,
        startUs: Long = 0L,
        chunkBytes: Long = 0L,
        onChunkDecoded: (suspend (Long, Long, Float) -> Unit)? = null,
        onProgress: (Float) -> Unit
    ): DecodedAudioInfo = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            val audioTrack = requireAudioTrack(extractor)
            val trackFormat = extractor.getTrackFormat(audioTrack)
            extractor.selectTrack(audioTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val fileDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val windowStartUs = if (fileDurationUs > 0L && startUs >= fileDurationUs && maxSamples != Long.MAX_VALUE) {
                (fileDurationUs - maxSamples * 1_000_000L / outputSampleRate).coerceAtLeast(0L)
            } else startUs
            if (windowStartUs > 0L) extractor.seekTo(windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val expectedDurationUs = if (maxSamples == Long.MAX_VALUE) fileDurationUs else {
                val prefixUs = maxSamples / outputSampleRate * 1_000_000L +
                    maxSamples % outputSampleRate * 1_000_000L / outputSampleRate
                if (fileDurationUs > windowStartUs) minOf(fileDurationUs - windowStartUs, prefixUs) else prefixUs
            }
            var codec: MediaCodec? = null
            var totalSamples = 0L
            try {
                val activeCodec = MediaCodec.createDecoderByType(mime)
                codec = activeCodec
                activeCodec.configure(trackFormat, null, null, 0)
                activeCodec.start()

                var sampleRate = if (trackFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
                } else 16_000
                var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                } else 1
                var pcmEncoding = run {
                    val outFmt = runCatching { activeCodec.outputFormat }.getOrNull()
                    if (outFmt != null && outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    }
                }
                var resampler = StreamingResamplerTo16k(sampleRate, outputSampleRate)

                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                val tmpByteBuf = ByteArray(16 * 1024)
                var lastProgressEmitMs = 0L
                var lastProgressVal = 0f
                var lastChunkEndByte = 0L

                BufferedOutputStream(FileOutputStream(destFile), 64 * 1024).use { out ->
                    while (!outputDone) {
                        coroutineContext.ensureActive()
                        var fedInput = false
                        while (!inputDone) {
                            val inIdx = activeCodec.dequeueInputBuffer(0L)
                            if (inIdx < 0) break
                            fedInput = true
                            val inBuf = activeCodec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                activeCodec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                activeCodec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                                val frac = if (expectedDurationUs > 0L && pts >= 0L) {
                                    ((pts - windowStartUs).toFloat() / expectedDurationUs.toFloat()).coerceIn(0f, 0.99f)
                                } else {
                                    (1f - 1f / (1f + totalSamples / (outputSampleRate * 45f))).coerceIn(0.02f, 0.95f)
                                }
                                val nowMs = System.currentTimeMillis()
                                if (frac - lastProgressVal >= 0.02f || nowMs - lastProgressEmitMs >= 120L) {
                                    lastProgressVal = frac
                                    lastProgressEmitMs = nowMs
                                    onProgress(frac)
                                }
                            }
                        }

                        var drainedOutput = false
                        while (!outputDone) {
                            coroutineContext.ensureActive()
                            val timeoutUs = if (!fedInput && !drainedOutput) 2_000L else 0L
                            val outIdx = activeCodec.dequeueOutputBuffer(info, timeoutUs)
                            when {
                                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    drainedOutput = true
                                    val newFmt = activeCodec.outputFormat
                                    val newRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8_000)
                                    val newCh = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                                    if (newRate != sampleRate) {
                                        sampleRate = newRate
                                        resampler = StreamingResamplerTo16k(sampleRate, outputSampleRate)
                                    }
                                    channels = newCh
                                    pcmEncoding = if (newFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                        newFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    } else {
                                        android.media.AudioFormat.ENCODING_PCM_16BIT
                                    }
                                }
                                outIdx >= 0 -> {
                                    drainedOutput = true
                                    val outBuf = activeCodec.getOutputBuffer(outIdx)
                                    if (outBuf != null && info.size > 0) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)

                                        val monoShorts = extractMono16(outBuf, info.size, channels, pcmEncoding)
                                        val skip = if (windowStartUs == 0L || info.presentationTimeUs >= windowStartUs) 0 else {
                                            ((windowStartUs - info.presentationTimeUs) * sampleRate / 1_000_000L)
                                                .coerceAtMost(monoShorts.size.toLong()).toInt()
                                        }
                                        val windowSamples = if (skip == 0) monoShorts else monoShorts.copyOfRange(skip, monoShorts.size)
                                        val resampled = resampler.process(windowSamples, windowSamples.size)
                                        val count = minOf(resampled.size.toLong(), maxSamples - totalSamples).toInt()
                                        if (count > 0) {
                                            val neededBytes = count * 2
                                            val writeBuf = if (neededBytes <= tmpByteBuf.size) tmpByteBuf else ByteArray(neededBytes)
                                            ByteBuffer.wrap(writeBuf, 0, neededBytes)
                                                .order(ByteOrder.LITTLE_ENDIAN)
                                                .asShortBuffer()
                                                .put(resampled, 0, count)
                                            out.write(writeBuf, 0, neededBytes)
                                            totalSamples += count
                                            if (chunkBytes > 0L && onChunkDecoded != null) {
                                                val totalBytes = totalSamples * 2L
                                                if (totalBytes - lastChunkEndByte >= chunkBytes) {
                                                    out.flush()
                                                    onChunkDecoded(lastChunkEndByte, totalBytes, lastProgressVal)
                                                    lastChunkEndByte = totalBytes
                                                }
                                            }
                                        }
                                    }
                                    activeCodec.releaseOutputBuffer(outIdx, false)
                                    if (totalSamples >= maxSamples || (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outputDone = true
                                    }
                                }
                                else -> break
                            }
                        }
                    }
                    out.flush()
                    if (chunkBytes > 0L && onChunkDecoded != null) {
                        val totalBytes = totalSamples * 2L
                        if (totalBytes > lastChunkEndByte) {
                            onChunkDecoded(lastChunkEndByte, totalBytes, 1f)
                            lastChunkEndByte = totalBytes
                        }
                    }
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }

            onProgress(1f)

            val durationMs = (totalSamples * 1000L) / outputSampleRate
            DecodedAudioInfo(durationMs = durationMs, totalSamples16k = totalSamples)
        } finally {
            extractor.release()
        }
    }

    internal fun readAudioDurationMs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(requireAudioTrack(extractor))
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            return durationUs.coerceAtLeast(0L) / 1_000L
        } finally {
            extractor.release()
        }
    }

    private fun requireAudioTrack(extractor: MediaExtractor): Int {
        return (0 until extractor.trackCount).firstOrNull { index ->
            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error(AppLocaleManager.currentStrings().audioTrackNotFound)
    }

    private fun extractMono16(buf: ByteBuffer, byteCount: Int, channels: Int, pcmEncoding: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        return when (pcmEncoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = FloatArray(byteCount / 4)
                buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                val frames = floats.size / ch
                ShortArray(frames) { f ->
                    var sum = 0f
                    for (c in 0 until ch) sum += floats[f * ch + c]
                    ((sum / ch) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            android.media.AudioFormat.ENCODING_PCM_8BIT -> {
                val bytes = ByteArray(byteCount)
                buf.get(bytes)
                val frames = bytes.size / ch
                ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until ch) {
                        sum += ((bytes[f * ch + c].toInt() and 0xFF) - 128) shl 8
                    }
                    (sum / ch).coerceIn(-32768, 32767).toShort()
                }
            }
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val bytes = ByteArray(byteCount)
                buf.get(bytes)
                val frames = (bytes.size / 3) / ch
                ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until ch) {
                        val base = (f * ch + c) * 3
                        val sample24 = (bytes[base + 2].toInt() shl 16) or
                            ((bytes[base + 1].toInt() and 0xFF) shl 8) or
                            (bytes[base].toInt() and 0xFF)
                        sum += sample24 shr 8
                    }
                    (sum / ch).coerceIn(-32768, 32767).toShort()
                }
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> {
                val ints = IntArray(byteCount / 4)
                buf.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(ints)
                val frames = ints.size / ch
                ShortArray(frames) { f ->
                    var sum = 0L
                    for (c in 0 until ch) sum += ints[f * ch + c].toLong() shr 16
                    (sum / ch).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            else -> {
                val shorts = ShortArray(byteCount / 2)
                buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                if (ch == 1) {
                    shorts
                } else {
                    val frames = shorts.size / ch
                    ShortArray(frames) { f ->
                        var sum = 0
                        for (c in 0 until ch) sum += shorts[f * ch + c].toInt()
                        (sum / ch).coerceIn(-32768, 32767).toShort()
                    }
                }
            }
        }
    }
}
