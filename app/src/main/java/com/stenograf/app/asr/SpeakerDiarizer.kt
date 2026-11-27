package com.stenograf.app.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

internal class SpeakerDiarizer(assets: AssetManager, private val label: String) : Closeable {
    private val embeddingConfig = SpeakerEmbeddingExtractorConfig(model = "models/campplus.onnx")
    private val diarization = OfflineSpeakerDiarization(
        assets,
        OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                    model = "models/speaker-segmentation.onnx",
                    windowShiftRatio = 0.25f
                )
            ),
            embedding = embeddingConfig,
            clustering = FastClusteringConfig(threshold = 0.5f)
        )
    )
    private val extractor = try {
        SpeakerEmbeddingExtractor(assets, embeddingConfig)
    } catch (e: RuntimeException) {
        diarization.release()
        throw e
    } catch (e: LinkageError) {
        diarization.release()
        throw e
    }

    private data class Profile(
        val name: String,
        val vector: FloatArray,
        val observations: Int,
        val samples: List<FloatArray> = listOf(vector)
    )
    private data class Voice(val localId: Int, val vector: FloatArray?, val cleanSeconds: Float)
    private data class LabeledTurn(val start: Float, val end: Float, val name: String)
    private data class Match(val localId: Int, val name: String, val score: Float)

    private var contextPcm = ByteArray(0)
    private var contextEndByte = -1L
    private var contextTurns = emptyList<LabeledTurn>()
    private val profiles = mutableListOf<Profile>()
    private var nextSpeaker = 1

    fun seed(name: String, pcm: ByteArray) {
        nextSpeaker = maxOf(nextSpeaker, (name.substringAfterLast(' ').toIntOrNull() ?: 0) + 1)
        val vector = embed(toSamples(pcm)) ?: return
        val index = profiles.indexOfFirst { it.name == name }
        if (index < 0) profiles.add(Profile(name, vector, 1))
        else if (similarity(profiles[index], vector) >= 0.62f) updateProfile(index, vector)
    }

    fun analyze(pcm: ByteArray, offsetBytes: Long, cancelled: () -> Boolean): SpeakerTimeline {
        if (pcm.size < SAMPLE_RATE || cancelled()) return SpeakerTimeline.Empty
        val prefix = if (offsetBytes == contextEndByte) contextPcm else ByteArray(0)
        val anchors = if (prefix.isNotEmpty()) contextTurns else emptyList()
        val lead = prefix.size / (2f * SAMPLE_RATE)
        val audio = prefix + pcm
        val samples = toSamples(audio)
        val turns = diarization.process(samples).sortedBy { it.start }
        if (cancelled()) return SpeakerTimeline.Empty

        val grouped = turns.groupBy { it.speaker }
        val voices = grouped.map { (id, localTurns) ->
            if (cancelled()) return SpeakerTimeline.Empty
            val clean = cleanVoice(samples, localTurns, turns)
            Voice(id, embed(clean), clean.size / SAMPLE_RATE.toFloat())
        }
        val labels = mutableMapOf<Int, String>()
        val usedNames = mutableSetOf<String>()
        val availableLabels = profiles.map { it.name }.toSet()

        // The same audio appears in both windows. Its timing is stronger evidence
        // than comparing embeddings for short or quiet turns.
        val anchored = grouped.flatMap { (id, localTurns) ->
            anchors.groupBy { it.name }.mapNotNull { (name, prior) ->
                val shared = localTurns.sumOf { current ->
                    prior.sumOf { overlap(current.start, current.end, it.start, it.end).toDouble() }
                }.toFloat()
                if (shared >= 0.36f && name in availableLabels) Match(id, name, shared) else null
            }
        }.sortedByDescending { it.score }
        for (match in anchored) {
            if (match.localId !in labels && usedNames.add(match.name)) labels[match.localId] = match.name
        }

        val matches = voices.flatMap { voice ->
            val vector = voice.vector ?: return@flatMap emptyList()
            profiles.map { Match(voice.localId, it.name, similarity(it, vector)) }
        }.groupBy { it.localId }.mapValues { (_, values) -> values.sortedByDescending { it.score } }
        for (match in matches.values.mapNotNull { it.firstOrNull() }.sortedByDescending { it.score }) {
            val alternative = matches.getValue(match.localId).getOrNull(1)?.score ?: -1f
            val stableSingleVoice = profiles.size == 1 && voices.size == 1 && match.score >= 0.55f
            if (match.localId !in labels && (match.score >= 0.61f || stableSingleVoice) &&
                (match.score >= 0.8f || match.score - alternative >= 0.055f) && usedNames.add(match.name)
            ) {
                labels[match.localId] = match.name
            }
        }

        for (voice in voices) {
            if (cancelled()) return SpeakerTimeline.Empty
            val vector = voice.vector ?: continue
            val name = labels[voice.localId]
            if (name == null) {
                val best = profiles.maxOfOrNull { similarity(it, vector) } ?: -1f
                val enoughSpeech = voice.cleanSeconds >= if (profiles.isEmpty()) 0.75f else 1.2f
                if (enoughSpeech && best < 0.52f && profiles.size < 24) {
                    val newName = "$label ${nextSpeaker++}"
                    profiles.add(Profile(newName, vector, 1))
                    labels[voice.localId] = newName
                }
            } else if (voice.cleanSeconds >= 1.2f) {
                val index = profiles.indexOfFirst { it.name == name }
                if (similarity(profiles[index], vector) >= 0.69f) updateProfile(index, vector)
            }
        }

        val resolved = turns.map { turn ->
            if (cancelled()) return SpeakerTimeline.Empty
            val clusterLabel = labels[turn.speaker]
            val estimated = if (turn.end > lead + 0.75f && grouped.getValue(turn.speaker).size > 1) {
                refineTurn(samples, turn, turns, clusterLabel)
            } else clusterLabel
            SpeakerTurn(turn.start, turn.end, turn.speaker, estimated)
        }
        val tailBytes = minOf(audio.size, 6 * SAMPLE_RATE)
        val tailStart = (audio.size - tailBytes) / (2f * SAMPLE_RATE)
        contextPcm = audio.copyOfRange(audio.size - tailBytes, audio.size)
        contextEndByte = offsetBytes + pcm.size
        contextTurns = resolved.mapNotNull { turn ->
            val name = turn.label
            if (turn.end > tailStart && name != null) {
                LabeledTurn((turn.start - tailStart).coerceAtLeast(0f), turn.end - tailStart, name)
            } else null
        }
        return SpeakerTimeline(resolved.map { it.copy(start = it.start - lead, end = it.end - lead) })
    }

    private fun refineTurn(
        samples: FloatArray,
        turn: OfflineSpeakerDiarizationSegment,
        turns: List<OfflineSpeakerDiarizationSegment>,
        clusterLabel: String?
    ): String? {
        val clean = cleanVoice(samples, listOf(turn), turns)
        val seconds = clean.size / SAMPLE_RATE.toFloat()
        if (seconds < 1.15f) return clusterLabel
        val vector = embed(clean) ?: return clusterLabel
        val ranked = profiles.map { it.name to similarity(it, vector) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull()
        val secondScore = ranked.getOrNull(1)?.second ?: -1f
        val originalScore = ranked.firstOrNull { it.first == clusterLabel }?.second ?: -1f
        if (best != null && best.second >= 0.68f && best.second - secondScore >= 0.07f &&
            (clusterLabel == null || best.second - originalScore >= 0.13f)
        ) {
            return best.first
        }
        if (seconds >= 1.8f && (best == null || best.second < 0.47f) && profiles.size < 24) {
            val name = "$label ${nextSpeaker++}"
            profiles.add(Profile(name, vector, 1))
            return name
        }
        return clusterLabel
    }

    private fun similarity(profile: Profile, vector: FloatArray): Float =
        maxOf(cosine(profile.vector, vector), profile.samples.maxOf { cosine(it, vector) } - 0.035f)

    private fun updateProfile(index: Int, vector: FloatArray) {
        val prior = profiles[index]
        val weight = prior.observations.coerceAtMost(8)
        val mean = FloatArray(vector.size) { prior.vector[it] * weight + vector[it] }
        val samples = if (prior.samples.none { cosine(it, vector) >= 0.9f }) {
            prior.samples.take(1) + prior.samples.drop(1).takeLast(2) + listOf(vector)
        } else prior.samples
        profiles[index] = prior.copy(vector = normalized(mean) ?: prior.vector, observations = weight + 1, samples = samples)
    }

    private fun overlap(start: Float, end: Float, otherStart: Float, otherEnd: Float): Float =
        (minOf(end, otherEnd) - maxOf(start, otherStart)).coerceAtLeast(0f)

    private fun cleanVoice(
        samples: FloatArray,
        voiceTurns: List<OfflineSpeakerDiarizationSegment>,
        allTurns: List<OfflineSpeakerDiarizationSegment>
    ): FloatArray {
        val voice = FloatArray(5 * SAMPLE_RATE)
        var count = 0
        for (turn in voiceTurns.sortedByDescending { it.end - it.start }) {
            var from = turn.start
            val interruptions = allTurns.filter {
                it.speaker != turn.speaker && overlap(turn.start, turn.end, it.start, it.end) > 0f
            }.sortedBy { it.start }

            fun copyRange(start: Float, end: Float) {
                if (end - start < 0.12f) return
                val first = (start * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                val last = (end * SAMPLE_RATE).toInt().coerceIn(first, samples.size)
                val length = minOf(last - first, voice.size - count)
                samples.copyInto(voice, count, first, first + length)
                count += length
            }

            for (interruption in interruptions) {
                copyRange(from, interruption.start)
                from = maxOf(from, interruption.end)
            }
            copyRange(from, turn.end)
            if (count == voice.size) break
        }
        return voice.copyOf(count)
    }

    private fun embed(samples: FloatArray): FloatArray? {
        if (samples.size < SAMPLE_RATE * 0.75f) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) normalized(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    private fun toSamples(pcm: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get() / 32768f }
    }

    private fun normalized(vector: FloatArray): FloatArray? {
        val norm = sqrt(cosine(vector, vector))
        if (!norm.isFinite() || norm <= 0f) return null
        return FloatArray(vector.size) { vector[it] / norm }
    }

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        var score = 0f
        for (i in left.indices) score += left[i] * right[i]
        return score
    }

    override fun close() {
        extractor.release()
        diarization.release()
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
    }
}
