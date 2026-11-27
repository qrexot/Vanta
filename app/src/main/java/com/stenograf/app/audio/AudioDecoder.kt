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
import java.io.RandomAccessFile
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
        writeWavHeader = false,
        onProgress = onProgress
    )

    suspend fun decodeUriToWav16kFile(
        context: Context,
        sourceUri: Uri,
        destWav16kFile: File,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudioInfo = decodeUriInternal(
        context = context,
        sourceUri = sourceUri,
        destFile = destWav16kFile,
        writeWavHeader = true,
        chunkBytes = 0L,
        onChunkDecoded = null,
        onProgress = onProgress
    )

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
        writeWavHeader = false,
        chunkBytes = chunkBytes,
        onChunkDecoded = onChunkDecoded,
        onProgress = onProgress
    )

    private suspend fun decodeUriInternal(
        context: Context,
        sourceUri: Uri,
        destFile: File,
        writeWavHeader: Boolean,
        chunkBytes: Long = 0L,
        onChunkDecoded: (suspend (Long, Long, Float) -> Unit)? = null,
        onProgress: (Float) -> Unit
    ): DecodedAudioInfo = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            var audioTrack = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrack < 0 || trackFormat == null) {
                throw IllegalStateException(AppLocaleManager.currentStrings().audioTrackNotFound)
            }

            extractor.selectTrack(audioTrack)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            val expectedDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            var codec: MediaCodec? = null
            var totalSamples16k = 0L
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
                var resampler = StreamingResamplerTo16k(sampleRate)

                var inputDone = false
                var outputDone = false
                val info = MediaCodec.BufferInfo()
                val tmpByteBuf = ByteArray(16 * 1024)
                var lastProgressEmitMs = 0L
                var lastProgressVal = 0f
                var lastChunkEndByte = 0L

                BufferedOutputStream(FileOutputStream(destFile), 64 * 1024).use { out ->
                    if (writeWavHeader) {
                        out.write(WavHelper.createHeader(16_000, 1, 16, 0L))
                    }
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
                                if (expectedDurationUs > 0L && pts >= 0L) {
                                    val frac = (pts.toFloat() / expectedDurationUs.toFloat()).coerceIn(0f, 0.99f)
                                    val nowMs = System.currentTimeMillis()
                                    if (frac - lastProgressVal >= 0.02f || nowMs - lastProgressEmitMs >= 80L) {
                                        lastProgressVal = frac
                                        lastProgressEmitMs = nowMs
                                        onProgress(frac)
                                    }
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
                                    val newRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    val newCh = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    if (newRate != sampleRate) {
                                        sampleRate = newRate
                                        resampler = StreamingResamplerTo16k(sampleRate)
                                    }
                                    channels = newCh
                                }
                                outIdx >= 0 -> {
                                    drainedOutput = true
                                    val outBuf = activeCodec.getOutputBuffer(outIdx)
                                    if (outBuf != null && info.size > 0) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)
                                        val pcmEncoding = if (
                                            activeCodec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                        ) {
                                            activeCodec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                        } else {
                                            android.media.AudioFormat.ENCODING_PCM_16BIT
                                        }

                                        val monoShorts = extractMono16(outBuf, info.size, channels, pcmEncoding)
                                        val resampled = resampler.process(monoShorts, monoShorts.size)
                                        if (resampled.isNotEmpty()) {
                                            val neededBytes = resampled.size * 2
                                            val writeBuf = if (neededBytes <= tmpByteBuf.size) tmpByteBuf else ByteArray(neededBytes)
                                            ByteBuffer.wrap(writeBuf, 0, neededBytes)
                                                .order(ByteOrder.LITTLE_ENDIAN)
                                                .asShortBuffer()
                                                .put(resampled)
                                            out.write(writeBuf, 0, neededBytes)
                                            totalSamples16k += resampled.size
                                            if (chunkBytes > 0L && onChunkDecoded != null) {
                                                val totalBytes = totalSamples16k * 2L
                                                if (totalBytes - lastChunkEndByte >= chunkBytes) {
                                                    out.flush()
                                                    onChunkDecoded(lastChunkEndByte, totalBytes, lastProgressVal)
                                                    lastChunkEndByte = totalBytes
                                                }
                                            }
                                        }
                                    }
                                    activeCodec.releaseOutputBuffer(outIdx, false)
                                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        outputDone = true
                                    }
                                }
                                else -> break
                            }
                        }
                    }
                    out.flush()
                    if (chunkBytes > 0L && onChunkDecoded != null) {
                        val totalBytes = totalSamples16k * 2L
                        if (totalBytes > lastChunkEndByte) {
                            onChunkDecoded(lastChunkEndByte, totalBytes, 1f)
                            lastChunkEndByte = totalBytes
                        }
                    }
                }
                if (writeWavHeader) {
                    RandomAccessFile(destFile, "rw").use { raf ->
                        raf.seek(0L)
                        raf.write(WavHelper.createHeader(16_000, 1, 16, totalSamples16k * 2L))
                    }
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
            }

            onProgress(1f)

            val durationMs = (totalSamples16k * 1000L) / 16_000L
            DecodedAudioInfo(durationMs = durationMs, totalSamples16k = totalSamples16k)
        } finally {
            extractor.release()
        }
    }

    private fun extractMono16(buf: ByteBuffer, byteCount: Int, channels: Int, pcmEncoding: Int): ShortArray {
        val ch = channels.coerceAtLeast(1)
        return if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = FloatArray(byteCount / 4)
            buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            val frames = floats.size / ch
            ShortArray(frames) { f ->
                var sum = 0f
                for (c in 0 until ch) sum += floats[f * ch + c]
                ((sum / ch) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
        } else {
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
