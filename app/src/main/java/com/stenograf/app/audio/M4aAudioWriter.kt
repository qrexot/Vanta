package com.stenograf.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hardware/OS AAC LC + MP4/M4A writer for compact multi-hour 48 kHz recordings.
 */
class M4aAudioWriter(
    private val outputFile: File,
    private val sampleRate: Int = 48_000,
    private val bitRate: Int = 128_000
) {
    private val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
    }

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var trackIndex = -1
    private var muxerStarted = false
    private var samplesEncoded: Long = 0L
    private var closed = false

    init {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun writePcm16(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + 750L
        while (offset < count) {
            check(SystemClock.elapsedRealtime() < deadline) { "AAC encoder stopped accepting audio" }
            val inIdx = codec.dequeueInputBuffer(10_000L)
            if (inIdx >= 0) {
                val inputBuf = checkNotNull(codec.getInputBuffer(inIdx))
                inputBuf.clear()
                val maxShorts = inputBuf.remaining() / 2
                val toWrite = minOf(maxShorts, count - offset)
                inputBuf.order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until toWrite) {
                    inputBuf.putShort(samples[offset + i])
                }
                val ptsUs = (samplesEncoded * 1_000_000L) / sampleRate
                codec.queueInputBuffer(inIdx, 0, toWrite * 2, ptsUs, 0)
                samplesEncoded += toWrite
                offset += toWrite
            }
            drain(endOfStream = false)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            val inIdx = codec.dequeueInputBuffer(20_000L)
            if (inIdx >= 0) {
                val ptsUs = (samplesEncoded * 1_000_000L) / sampleRate
                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(endOfStream = true)
            } else {
                drain(endOfStream = false)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
        }
    }

    private fun drain(endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        val deadline = SystemClock.elapsedRealtime() + 750L
        while (SystemClock.elapsedRealtime() < deadline) {
            val outIdx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIdx >= 0 -> {
                    val outBuf: ByteBuffer? = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, outBuf, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }
}
