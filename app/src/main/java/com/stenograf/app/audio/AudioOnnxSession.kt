package com.stenograf.app.audio

import java.io.Closeable
import java.io.File

internal data class AudioOnnxInput(val data: FloatArray, val shape: LongArray)

internal fun audioTensor(samples: FloatArray, vararg shape: Long) = AudioOnnxInput(samples, shape)

internal class AudioOnnxSession private constructor(private var handle: Long) : Closeable {
    fun run(inputs: Map<String, AudioOnnxInput>, vararg outputs: String): Array<FloatArray> {
        check(handle != 0L)
        val entries = inputs.entries.toList()
        return AudioOnnxNative.run(
            handle,
            entries.map { it.key }.toTypedArray(),
            entries.map { it.value.data }.toTypedArray(),
            entries.map { it.value.shape }.toTypedArray(),
            outputs
        )
    }

    override fun close() {
        if (handle != 0L) {
            AudioOnnxNative.release(handle)
            handle = 0L
        }
    }

    companion object {
        fun open(file: File, threads: Int = 2): AudioOnnxSession =
            AudioOnnxSession(AudioOnnxNative.create(file.absolutePath, null, threads))

        fun open(bytes: ByteArray, threads: Int): AudioOnnxSession =
            AudioOnnxSession(AudioOnnxNative.create(null, bytes, threads))
    }
}

internal object AudioOnnxNative {
    init {
        System.loadLibrary("tinyasr_jni")
    }

    external fun create(path: String?, bytes: ByteArray?, threads: Int): Long
    external fun run(
        handle: Long,
        names: Array<String>,
        inputs: Array<FloatArray>,
        shapes: Array<LongArray>,
        outputs: Array<out String>
    ): Array<FloatArray>
    external fun release(handle: Long)
}
