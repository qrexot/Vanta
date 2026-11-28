package com.stenograf.app.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.stenograf.app.data.model.SpeakerProfileEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal data class StoredVoiceprint(
    val id: Long,
    val name: String,
    val centroid: FloatArray,
    val prototypes: List<FloatArray>,
    val sampleCount: Int,
    val isAutoLearned: Boolean
)

internal data class LearnedVoiceprintUpdate(
    val profileId: Long?,
    val name: String,
    val centroid: FloatArray,
    val prototypes: List<FloatArray>,
    val sampleCount: Int,
    val isAutoLearned: Boolean,
    val addedSpeechSeconds: Float = 0f,
    val samplePcm16: ByteArray? = null
)

internal data class VoiceTrainingBundle(
    val centroid: FloatArray,
    val prototypes: List<FloatArray>,
    val activeSpeechSeconds: Float,
    val sampleCount: Int,
    val previewPcm16: ByteArray?
)

/**
 * Pure math and serialization utilities for L2-normalized neural speaker voiceprints.
 * Thread-safe and JVM-testable without native dependencies.
 */
internal object VoiceprintMath {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val floatListSerializer = ListSerializer(Float.serializer())
    private val floatMatrixSerializer = ListSerializer(floatListSerializer)

    const val EMBEDDING_MODEL_ASSET = "models/campplus.onnx"
    const val MAX_PROTOTYPES = 10
    const val PROTOTYPE_NOVELTY_MAX_COSINE = 0.94f
    const val SAMPLE_RATE = 16_000

    fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty()) return null
        val norm = sqrt(cosine(vector, vector).toDouble()).toFloat()
        if (!norm.isFinite() || norm <= 1e-6f) return null
        return FloatArray(vector.size) { vector[it] / norm }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size || left.isEmpty()) return -1f
        var score = 0f
        for (i in left.indices) {
            score += left[i] * right[i]
        }
        return if (score.isFinite()) score else -1f
    }

    fun similarity(centroid: FloatArray, prototypes: List<FloatArray>, candidate: FloatArray): Float {
        val base = cosine(centroid, candidate)
        val protoScore = if (prototypes.isEmpty()) -1f else {
            prototypes.maxOf { cosine(it, candidate) } - 0.03f
        }
        return maxOf(base, protoScore)
    }

    /**
     * Retains the anchor vector plus up to [maxCount] acoustically diverse prototypes using
     * furthest-first selection so conditions (mic distance, room, noise) aren't forgotten.
     */
    private fun selectDiversePrototypes(
        anchor: FloatArray?,
        pool: List<FloatArray>,
        maxCount: Int
    ): List<FloatArray> {
        val cleanPool = pool.mapNotNull { normalize(it) }
        if (cleanPool.isEmpty()) return anchor?.let { listOf(it) } ?: emptyList()
        val chosen = mutableListOf<FloatArray>()
        val first = anchor?.let { normalize(it) } ?: cleanPool.first()
        chosen.add(first)

        val remaining = cleanPool.filter { cosine(it, first) < PROTOTYPE_NOVELTY_MAX_COSINE }.toMutableList()
        while (chosen.size < maxCount && remaining.isNotEmpty()) {
            val nextIdx = remaining.indices.minByOrNull { idx ->
                val vec = remaining[idx]
                chosen.maxOf { cosine(it, vec) }
            } ?: break
            val nextVec = remaining.removeAt(nextIdx)
            if (chosen.maxOf { cosine(it, nextVec) } < PROTOTYPE_NOVELTY_MAX_COSINE) {
                chosen.add(nextVec)
            }
        }
        return chosen
    }

    fun mergeEmbedding(
        existingCentroid: FloatArray,
        existingPrototypes: List<FloatArray>,
        existingSamples: Int,
        newEmbedding: FloatArray,
        maxWeight: Int = 12
    ): Triple<FloatArray, List<FloatArray>, Int>? {
        val cleanNew = normalize(newEmbedding) ?: return null
        if (existingCentroid.size != cleanNew.size) return Triple(cleanNew, listOf(cleanNew), 1)
        val weight = existingSamples.coerceIn(1, maxWeight)
        val blended = FloatArray(cleanNew.size) { i ->
            existingCentroid[i] * weight + cleanNew[i]
        }
        val nextCentroid = normalize(blended) ?: existingCentroid
        val nextPrototypes = selectDiversePrototypes(
            anchor = existingPrototypes.firstOrNull() ?: nextCentroid,
            pool = existingPrototypes + listOf(cleanNew),
            maxCount = MAX_PROTOTYPES
        )
        return Triple(nextCentroid, nextPrototypes, existingSamples + 1)
    }

    fun mergeStoredProfiles(
        targetCentroid: FloatArray,
        targetPrototypes: List<FloatArray>,
        targetSamples: Int,
        sourceCentroid: FloatArray,
        sourcePrototypes: List<FloatArray>,
        sourceSamples: Int
    ): Triple<FloatArray, List<FloatArray>, Int>? {
        val tClean = normalize(targetCentroid) ?: return null
        val sClean = normalize(sourceCentroid) ?: return Triple(tClean, targetPrototypes, targetSamples)
        if (tClean.size != sClean.size) return Triple(tClean, targetPrototypes, targetSamples)

        val wT = targetSamples.coerceAtLeast(1)
        val wS = sourceSamples.coerceAtLeast(1)
        val blended = FloatArray(tClean.size) { i -> tClean[i] * wT + sClean[i] * wS }
        val nextCentroid = normalize(blended) ?: tClean

        val mergedProtos = selectDiversePrototypes(
            anchor = targetPrototypes.firstOrNull() ?: nextCentroid,
            pool = targetPrototypes + sourcePrototypes + listOf(sClean),
            maxCount = MAX_PROTOTYPES
        )
        return Triple(nextCentroid, mergedProtos.ifEmpty { listOf(nextCentroid) }, wT + wS)
    }

    fun trainingProgress(sampleCount: Int, speechSeconds: Float, prototypeCount: Int): Float {
        val effectiveSec = if (speechSeconds > 0f) speechSeconds else sampleCount * 2.5f
        val obsScore = (sampleCount.coerceAtLeast(1) / 10f).coerceAtMost(1f)
        val secScore = (effectiveSec / 36f).coerceAtMost(1f)
        val protoScore = (prototypeCount.coerceAtLeast(1) / 6f).coerceAtMost(1f)
        return (obsScore * 0.40f + secScore * 0.40f + protoScore * 0.20f).coerceIn(0.08f, 1.0f)
    }

    /** Returns 1 (Initial), 2 (Confident), or 3 (Reference) mastery level. */
    fun trainingTier(sampleCount: Int, speechSeconds: Float, prototypeCount: Int): Int {
        val score = trainingProgress(sampleCount, speechSeconds, prototypeCount)
        return when {
            score >= 0.72f && sampleCount >= 5 -> 3
            score >= 0.32f && sampleCount >= 2 -> 2
            else -> 1
        }
    }

    fun encodeEmbedding(vector: FloatArray): String {
        val list = List(vector.size) { vector[it] }
        return json.encodeToString(floatListSerializer, list)
    }

    fun decodeEmbedding(raw: String?): FloatArray? {
        if (raw.isNullOrBlank()) return null
        return try {
            val list = json.decodeFromString(floatListSerializer, raw)
            if (list.isEmpty()) null else normalize(list.toFloatArray())
        } catch (_: Throwable) {
            null
        }
    }

    fun encodePrototypes(vectors: List<FloatArray>): String {
        val matrix = vectors.map { vec -> List(vec.size) { vec[it] } }
        return json.encodeToString(floatMatrixSerializer, matrix)
    }

    fun decodePrototypes(raw: String?, fallbackCentroid: FloatArray): List<FloatArray> {
        if (raw.isNullOrBlank()) return listOf(fallbackCentroid)
        return try {
            val matrix = json.decodeFromString(floatMatrixSerializer, raw)
            val parsed = matrix.mapNotNull { row ->
                if (row.size == fallbackCentroid.size) normalize(row.toFloatArray()) else null
            }
            parsed.ifEmpty { listOf(fallbackCentroid) }
        } catch (_: Throwable) {
            listOf(fallbackCentroid)
        }
    }

    fun toStoredVoiceprint(entity: SpeakerProfileEntity): StoredVoiceprint? {
        val centroid = decodeEmbedding(entity.embeddingJson) ?: return null
        val protos = decodePrototypes(entity.prototypesJson, centroid)
        return StoredVoiceprint(
            id = entity.id,
            name = entity.name,
            centroid = centroid,
            prototypes = protos,
            sampleCount = entity.sampleCount.coerceAtLeast(1),
            isAutoLearned = entity.isAutoLearned
        )
    }
}

/**
 * Extracts normalized speaker embeddings directly from 16 kHz mono PCM16 audio using campplus.onnx.
 */
internal object SpeakerEmbeddingHelper {
    private const val MIN_SPEECH_SAMPLES = (VoiceprintMath.SAMPLE_RATE * 0.8f).toInt()
    private const val MAX_ENROLL_SAMPLES = VoiceprintMath.SAMPLE_RATE * 32
    private const val MAX_PREVIEW_SAMPLES = (VoiceprintMath.SAMPLE_RATE * 5.5f).toInt()

    /**
     * Strips silence and steady background noise frames so pauses never dilute speaker embeddings.
     */
    fun trimToActiveSpeech(samples: FloatArray): FloatArray {
        if (samples.size < VoiceprintMath.SAMPLE_RATE / 4) return samples
        val frameSize = 480 // 30 ms @ 16 kHz
        val nFrames = samples.size / frameSize
        if (nFrames <= 0) return samples

        val frameRms = FloatArray(nFrames) { f ->
            var sumSq = 0.0
            val base = f * frameSize
            for (i in 0 until frameSize) {
                val v = samples[base + i].toDouble()
                sumSq += v * v
            }
            sqrt(sumSq / frameSize).toFloat()
        }
        val sorted = frameRms.sorted()
        val noiseFloor = sorted[(sorted.size * 15) / 100].coerceIn(0.0005f, 0.025f)
        val speechThreshold = maxOf(0.0085f, noiseFloor * 2.3f)

        val keep = BooleanArray(nFrames)
        var lastActive = -10
        for (f in 0 until nFrames) {
            if (frameRms[f] >= speechThreshold) {
                for (b in maxOf(0, f - 2)..f) keep[b] = true
                lastActive = f
            } else if (f - lastActive <= 3) {
                keep[f] = true
            }
        }

        val keptFrames = keep.count { it }
        if (keptFrames == 0) return FloatArray(0)
        val result = FloatArray(keptFrames * frameSize)
        var outPos = 0
        for (f in 0 until nFrames) {
            if (keep[f]) {
                samples.copyInto(result, outPos, f * frameSize, (f + 1) * frameSize)
                outPos += frameSize
            }
        }
        return result
    }

    fun floatSamplesToPcm16Bytes(samples: FloatArray, maxSamples: Int = MAX_PREVIEW_SAMPLES): ByteArray {
        val n = minOf(samples.size, maxSamples)
        if (n <= 0) return ByteArray(0)
        val out = ByteArray(n * 2)
        val sb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until n) {
            sb.put((samples[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        return out
    }

    fun extractFromPcm16(assets: AssetManager, pcm16kBytes: ByteArray): FloatArray? =
        extractStrictTrainingBundle(assets, pcm16kBytes, minSpeechSec = 0.8f)?.centroid

    fun extractStrictTrainingBundle(
        assets: AssetManager,
        pcm16kBytes: ByteArray,
        minSpeechSec: Float = 1.2f
    ): VoiceTrainingBundle? {
        val shorts = ByteBuffer.wrap(pcm16kBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val sampleCount = minOf(shorts.remaining(), MAX_ENROLL_SAMPLES)
        if (sampleCount < (VoiceprintMath.SAMPLE_RATE * minSpeechSec).toInt()) return null
        val samples = FloatArray(sampleCount) { shorts.get() / 32768f }
        return extractBundleFromFloatSamples(assets, samples, minSpeechSec)
    }

    fun extractFromPcm16Intervals(
        assets: AssetManager,
        pcmFile: File,
        baseOffsetBytes: Long,
        intervalsSec: List<Pair<Float, Float>>
    ): FloatArray? = extractTrainingBundleFromIntervals(assets, pcmFile, baseOffsetBytes, intervalsSec)?.centroid

    fun extractTrainingBundleFromIntervals(
        assets: AssetManager,
        pcmFile: File,
        baseOffsetBytes: Long,
        intervalsSec: List<Pair<Float, Float>>
    ): VoiceTrainingBundle? {
        if (!pcmFile.exists() || intervalsSec.isEmpty()) return null
        val maxSamples = MAX_ENROLL_SAMPLES
        val collected = FloatArray(maxSamples)
        var count = 0
        val fileLen = pcmFile.length()
        val scratch = ByteArray(16_384)

        // Prioritize the longest clean intervals across the recording up to 32s total speech
        val sorted = intervalsSec
            .filter { (s, e) -> e > s + 0.5f }
            .sortedByDescending { (s, e) -> e - s }

        RandomAccessFile(pcmFile, "r").use { raf ->
            for ((startSec, endSec) in sorted) {
                if (count >= maxSamples) break
                val startByte = baseOffsetBytes + ((startSec * VoiceprintMath.SAMPLE_RATE * 2L).toLong() and -2L)
                val endByte = minOf(
                    fileLen,
                    baseOffsetBytes + ((endSec * VoiceprintMath.SAMPLE_RATE * 2L).toLong() and -2L)
                )
                if (endByte <= startByte) continue
                raf.seek(startByte.coerceAtLeast(baseOffsetBytes))
                var remainingBytes = endByte - startByte
                while (remainingBytes >= 2L && count < maxSamples) {
                    val maxBytes = minOf(remainingBytes, scratch.size.toLong(), ((maxSamples - count) * 2L)).toInt() and -2
                    if (maxBytes < 2) break
                    val got = raf.read(scratch, 0, maxBytes)
                    if (got < 2) break
                    val nShorts = got / 2
                    val sb = ByteBuffer.wrap(scratch, 0, nShorts * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    for (i in 0 until nShorts) {
                        collected[count++] = sb.get() / 32768f
                    }
                    remainingBytes -= got
                }
            }
        }
        if (count < MIN_SPEECH_SAMPLES) return null
        return extractBundleFromFloatSamples(assets, collected.copyOf(count), minSpeechSec = 0.8f)
    }

    private fun extractBundleFromFloatSamples(
        assets: AssetManager,
        rawSamples: FloatArray,
        minSpeechSec: Float
    ): VoiceTrainingBundle? {
        val active = trimToActiveSpeech(rawSamples).let { trimmed ->
            if (trimmed.size >= (VoiceprintMath.SAMPLE_RATE * minSpeechSec).toInt()) trimmed
            else rawSamples
        }
        val minSamples = maxOf(MIN_SPEECH_SAMPLES, (VoiceprintMath.SAMPLE_RATE * minSpeechSec).toInt())
        if (active.size < minSamples) return null
        val activeSec = active.size / VoiceprintMath.SAMPLE_RATE.toFloat()

        return try {
            val extractor = SpeakerEmbeddingExtractor(
                assets,
                SpeakerEmbeddingExtractorConfig(model = VoiceprintMath.EMBEDDING_MODEL_ASSET)
            )
            try {
                fun runWindow(slice: FloatArray): FloatArray? {
                    val stream = extractor.createStream()
                    return try {
                        stream.acceptWaveform(slice, VoiceprintMath.SAMPLE_RATE)
                        stream.inputFinished()
                        if (extractor.isReady(stream)) VoiceprintMath.normalize(extractor.compute(stream)) else null
                    } finally {
                        stream.release()
                    }
                }

                val full = runWindow(active) ?: return null
                val windows = mutableListOf<FloatArray>()
                if (activeSec >= 4.2f) {
                    val winSize = (VoiceprintMath.SAMPLE_RATE * 3.0f).toInt().coerceAtMost(active.size)
                    val steps = minOf(4, ((active.size - winSize) / (VoiceprintMath.SAMPLE_RATE * 1.8f)).toInt() + 2)
                    val maxOffset = (active.size - winSize).coerceAtLeast(1)
                    for (k in 0 until steps) {
                        val start = if (steps <= 1) 0 else (k.toLong() * maxOffset / (steps - 1)).toInt()
                        val sub = runWindow(active.copyOfRange(start, minOf(active.size, start + winSize)))
                        // Strict consensus check rejects coughs / accidental crosstalk slices
                        if (sub != null && VoiceprintMath.cosine(full, sub) >= 0.54f) {
                            windows.add(sub)
                        }
                    }
                }

                val accepted = if (windows.isNotEmpty()) windows else listOf(full)
                val sum = FloatArray(full.size) { idx ->
                    var acc = full[idx]
                    for (w in windows) acc += w[idx]
                    acc
                }
                val centroid = VoiceprintMath.normalize(sum) ?: full
                var protos = listOf(full)
                for (w in accepted) {
                    if (protos.none { VoiceprintMath.cosine(it, w) >= VoiceprintMath.PROTOTYPE_NOVELTY_MAX_COSINE }) {
                        protos = (protos + listOf(w)).take(VoiceprintMath.MAX_PROTOTYPES)
                    }
                }
                VoiceTrainingBundle(
                    centroid = centroid,
                    prototypes = protos,
                    activeSpeechSeconds = activeSec,
                    sampleCount = accepted.size,
                    previewPcm16 = floatSamplesToPcm16Bytes(active, MAX_PREVIEW_SAMPLES)
                )
            } finally {
                extractor.release()
            }
        } catch (e: RuntimeException) {
            Log.w("Vanta", "Speaker embedding extraction failed", e)
            null
        } catch (e: LinkageError) {
            Log.w("Vanta", "Speaker embedding extraction failed", e)
            null
        }
    }
}
