package com.stenograf.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavHelper {
    const val HEADER_SIZE = 44

    fun createHeader(sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16, dataSize: Long = 0L): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val riffSize = (36L + dataSize).coerceAtMost(0xFFFFFFFFL).toInt()
        val dataChunkSize = dataSize.coerceAtMost(0xFFFFFFFFL).toInt()

        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(riffSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM fmt chunk size
        buffer.putShort(1.toShort()) // AudioFormat = 1 (PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataChunkSize)
        return buffer.array()
    }

    fun updateHeaderSizes(file: File, dataBytes: Long) {
        if (!file.exists() || file.length() < HEADER_SIZE) return
        val riffSize = (36L + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt()
        val dataSize = dataBytes.coerceAtMost(0xFFFFFFFFL).toInt()
        RandomAccessFile(file, "rw").use { raf ->
            val intBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            intBuf.putInt(riffSize)
            raf.seek(4)
            raf.write(intBuf.array())

            intBuf.clear()
            intBuf.putInt(dataSize)
            raf.seek(40)
            raf.write(intBuf.array())
        }
    }

    fun repairHeaderFromFileLength(file: File, defaultSampleRate: Int = 16_000, channels: Int = 1): Long {
        if (!file.exists()) return 0L
        val len = file.length()
        if (len < HEADER_SIZE) return 0L
        val dataBytes = len - HEADER_SIZE
        RandomAccessFile(file, "rw").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) {
                raf.seek(0)
                raf.write(createHeader(defaultSampleRate, channels, 16, dataBytes))
            } else {
                updateHeaderSizes(file, dataBytes)
            }
        }
        return dataBytes
    }
}
