package com.stenograf.app.audio

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Lightweight short-burst 16 kHz PCM16 voice sample capturer used when enrolling or refining
 * a speaker voiceprint manually from Settings. Applies optional GTCRN noise suppression.
 */
internal data class VoiceCaptureProgress(
    val level: Float,
    val elapsedMs: Long,
    val activeSpeechMs: Long,
    val isSpeakingNow: Boolean
)

internal data class VoiceCaptureResult(
    val pcm16kBytes: ByteArray,
    val activeSpeechSeconds: Float
)

internal object VoiceSampleRecorder {
    private const val SAMPLE_RATE = 16_000

    @SuppressLint("MissingPermission")
    fun captureSamplePcm16(
        assets: AssetManager,
        useNeuralDenoiser: Boolean,
        maxDurationMs: Long = 12_000L,
        shouldStop: () -> Boolean,
        onLevel: (Float, Long) -> Unit
    ): ByteArray = captureSmartSamplePcm16(
        assets = assets,
        useNeuralDenoiser = useNeuralDenoiser,
        maxDurationMs = maxDurationMs,
        shouldStop = shouldStop,
        onProgress = { p -> onLevel(p.level, p.elapsedMs) }
    ).pcm16kBytes

    @SuppressLint("MissingPermission")
    fun captureSmartSamplePcm16(
        assets: AssetManager,
        useNeuralDenoiser: Boolean,
        maxDurationMs: Long = 12_000L,
        shouldStop: () -> Boolean,
        onProgress: (VoiceCaptureProgress) -> Unit
    ): VoiceCaptureResult {
        val frameShorts = 640 // 40 ms @ 16 kHz
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(frameShorts * 4)

        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        var record: AudioRecord? = null
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                record = candidate
                break
            }
            runCatching { candidate?.release() }
        }
        val active = record ?: return VoiceCaptureResult(ByteArray(0), 0f)
        val enhancer = if (useNeuralDenoiser) NeuralSpeechEnhancer.tryCreate(assets) else null
        val out = ByteArrayOutputStream(SAMPLE_RATE * 2 * ((maxDurationMs / 1000L).toInt().coerceAtLeast(5)))
        val readBuf = ShortArray(frameShorts)
        var writeBuf = ByteArray(frameShorts * 2)
        var activeSpeechMs = 0L
        var noiseFloor = 0.0045f

        fun emitShorts(samples: ShortArray, count: Int) {
            if (count <= 0) return
            val bytes = count * 2
            if (writeBuf.size < bytes) writeBuf = ByteArray(bytes)
            ByteBuffer.wrap(writeBuf, 0, bytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .put(samples, 0, count)
            out.write(writeBuf, 0, bytes)
        }

        try {
            val startMs = System.currentTimeMillis()
            active.startRecording()
            while (!shouldStop()) {
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= maxDurationMs) break
                val got = active.read(readBuf, 0, frameShorts, AudioRecord.READ_BLOCKING)
                if (got <= 0) break

                val emitted = if (enhancer != null) {
                    val denoised = enhancer.processShorts(readBuf, got)
                    emitShorts(denoised, denoised.size)
                    denoised
                } else {
                    emitShorts(readBuf, got)
                    readBuf
                }
                val rmsCount = if (enhancer != null) emitted.size else got
                var sumSq = 0.0
                for (i in 0 until rmsCount) {
                    val v = emitted[i] / 32768.0
                    sumSq += v * v
                }
                val rms = if (rmsCount > 0) sqrt(sumSq / rmsCount).toFloat() else 0f
                if (rms < noiseFloor) {
                    noiseFloor = noiseFloor * 0.85f + rms * 0.15f
                }
                val speakingNow = rms >= maxOf(0.010f, noiseFloor * 2.4f)
                if (speakingNow) {
                    activeSpeechMs += (rmsCount * 1000L) / SAMPLE_RATE
                }
                onProgress(
                    VoiceCaptureProgress(
                        level = (sqrt(rms.toDouble()).toFloat() * 1.35f).coerceIn(0.05f, 1f),
                        elapsedMs = elapsed,
                        activeSpeechMs = activeSpeechMs,
                        isSpeakingNow = speakingNow
                    )
                )
            }
            if (enhancer != null) {
                val tail = enhancer.flushShorts()
                emitShorts(tail, tail.size)
            }
        } finally {
            runCatching {
                if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
                active.release()
            }
            runCatching { enhancer?.close() }
        }
        return VoiceCaptureResult(out.toByteArray(), activeSpeechMs / 1000f)
    }
}
