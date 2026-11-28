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

internal class SpeakerDiarizer(
    assets: AssetManager,
    private val label: String,
    private val autoVoicePrefix: String = label,
    private val autoLearnSpeakers: Boolean = false
) : Closeable {
    private val embeddingConfig = SpeakerEmbeddingExtractorConfig(model = VoiceprintMath.EMBEDDING_MODEL_ASSET)
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
        val id: Long?,
        val name: String,
        val vector: FloatArray,
        val observations: Int,
        val samples: List<FloatArray> = listOf(vector),
        val persistent: Boolean = false,
        val isAutoLearned: Boolean = false,
        val dirty: Boolean = false,
        val persistAsName: String? = null,
        val accumulatedCleanSeconds: Float = 0f,
        val dirtyAddedSeconds: Float = 0f,
        val previewPcm16: ByteArray? = null
    )

    private data class Voice(
        val localId: Int,
        val vector: FloatArray?,
        val cleanSeconds: Float,
        val cleanSamples: FloatArray
    )
    private data class LabeledTurn(val start: Float, val end: Float, val name: String)
    private data class Match(val localId: Int, val name: String, val score: Float)

    private var contextPcm = ByteArray(0)
    private var contextEndByte = -1L
    private var contextTurns = emptyList<LabeledTurn>()
    private val profiles = mutableListOf<Profile>()
    private var nextSpeaker = 1
    private var nextAutoVoiceIndex = 1
    private var autoEnrolledThisSession = 0

    fun seedStoredProfiles(stored: List<StoredVoiceprint>) {
        val hashPrefix = "$autoVoicePrefix #"
        for (item in stored) {
            if (item.name.startsWith(hashPrefix)) {
                item.name.removePrefix(hashPrefix).toIntOrNull()?.let { n ->
                    nextAutoVoiceIndex = maxOf(nextAutoVoiceIndex, n + 1)
                }
            }
            if (profiles.none { it.name.equals(item.name, ignoreCase = true) }) {
                profiles.add(
                    Profile(
                        id = item.id,
                        name = item.name,
                        vector = item.centroid,
                        observations = item.sampleCount.coerceAtLeast(1),
                        samples = item.prototypes.ifEmpty { listOf(item.centroid) },
                        persistent = true,
                        isAutoLearned = item.isAutoLearned,
                        dirty = false
                    )
                )
            }
        }
    }

    fun drainLearnedUpdates(): List<LearnedVoiceprintUpdate> {
        if (!autoLearnSpeakers) return emptyList()
        val updates = mutableListOf<LearnedVoiceprintUpdate>()
        for (i in profiles.indices) {
            val p = profiles[i]
            if (p.persistent && p.dirty) {
                updates.add(
                    LearnedVoiceprintUpdate(
                        profileId = p.id,
                        name = p.persistAsName ?: p.name,
                        centroid = p.vector,
                        prototypes = p.samples,
                        sampleCount = p.observations,
                        isAutoLearned = p.isAutoLearned,
                        addedSpeechSeconds = p.dirtyAddedSeconds,
                        samplePcm16 = p.previewPcm16
                    )
                )
                profiles[i] = p.copy(dirty = false, dirtyAddedSeconds = 0f, previewPcm16 = null)
            }
        }
        return updates
    }

    fun seed(name: String, pcm: ByteArray) {
        if (name.startsWith("$label ")) {
            nextSpeaker = maxOf(nextSpeaker, (name.substringAfterLast(' ').toIntOrNull() ?: 0) + 1)
        }
        val samples = toSamples(pcm)
        val vector = embed(samples) ?: return
        val durationSec = samples.size / SAMPLE_RATE.toFloat()
        val index = profiles.indexOfFirst { it.name == name }
        if (index < 0) {
            profiles.add(
                Profile(
                    id = null,
                    name = name,
                    vector = vector,
                    observations = 1,
                    accumulatedCleanSeconds = durationSec
                )
            )
        } else {
            val existing = profiles[index]
            if ((!existing.persistent || autoLearnSpeakers) && similarity(existing, vector) >= 0.62f) {
                updateProfile(index, vector, durationSec)
            }
        }
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
            Voice(id, embed(clean), clean.size / SAMPLE_RATE.toFloat(), clean)
        }
        val labels = mutableMapOf<Int, String>()
        val usedNames = mutableSetOf<String>()
        val availableLabels = profiles.map { it.name }.toSet()

        // Overlap across sliding windows gives reliable continuity even for short turns.
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

        val rankedByCluster = voices.associate { voice ->
            val vector = voice.vector
            val ranked = if (vector == null) emptyList() else {
                profiles.map { Match(voice.localId, it.name, similarity(it, vector)) }
                    .sortedByDescending { it.score }
            }
            voice.localId to ranked
        }
        val matchCandidates = rankedByCluster.values.mapNotNull { it.firstOrNull() }.sortedByDescending { it.score }
        val persistentCount = profiles.count { it.persistent }
        val sessionCount = profiles.size - persistentCount

        for (match in matchCandidates) {
            val ranked = rankedByCluster.getValue(match.localId)
            val alternative = ranked.getOrNull(1)?.score ?: -1f
            val targetProfile = profiles.firstOrNull { it.name == match.name } ?: continue
            val stableSingleSessionVoice = !targetProfile.persistent && sessionCount <= 1 && persistentCount == 0 &&
                voices.size == 1 && match.score >= 0.55f
            val scoreThreshold = if (targetProfile.persistent) 0.57f else 0.60f
            val hasClearMargin = match.score >= 0.78f || (match.score - alternative) >= 0.055f

            if (match.localId !in labels &&
                (match.score >= scoreThreshold || stableSingleSessionVoice) &&
                hasClearMargin &&
                usedNames.add(match.name)
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
                if (autoLearnSpeakers &&
                    voice.cleanSeconds >= 2.6f &&
                    best < 0.45f &&
                    profiles.size < 48 &&
                    autoEnrolledThisSession < 4
                ) {
                    val autoName = "$autoVoicePrefix #${nextAutoVoiceIndex++}"
                    autoEnrolledThisSession++
                    profiles.add(
                        Profile(
                            id = null,
                            name = autoName,
                            vector = vector,
                            observations = 1,
                            samples = listOf(vector),
                            persistent = true,
                            isAutoLearned = true,
                            dirty = true,
                            accumulatedCleanSeconds = voice.cleanSeconds,
                            dirtyAddedSeconds = voice.cleanSeconds,
                            previewPcm16 = SpeakerEmbeddingHelper.floatSamplesToPcm16Bytes(voice.cleanSamples)
                        )
                    )
                    labels[voice.localId] = autoName
                } else {
                    val enoughSpeech = voice.cleanSeconds >= if (profiles.none { !it.persistent }) 0.75f else 1.2f
                    if (enoughSpeech && best < 0.52f && profiles.size < 48) {
                        val newName = "$label ${nextSpeaker++}"
                        profiles.add(
                            Profile(
                                id = null,
                                name = newName,
                                vector = vector,
                                observations = 1,
                                accumulatedCleanSeconds = voice.cleanSeconds
                            )
                        )
                        labels[voice.localId] = newName
                    }
                }
            } else {
                val index = profiles.indexOfFirst { it.name == name }
                if (index >= 0) {
                    val profile = profiles[index]
                    val ranked = rankedByCluster[voice.localId].orEmpty()
                    val sim = ranked.firstOrNull { it.name == name }?.score ?: similarity(profile, vector)
                    val alternative = ranked.firstOrNull { it.name != name }?.score ?: -1f
                    val margin = sim - alternative
                    if (profile.persistent) {
                        val strictHighMatch = voice.cleanSeconds >= 1.8f && sim >= 0.69f && margin >= 0.08f
                        val crossEnvAdaptation = voice.cleanSeconds >= 2.6f && sim >= 0.58f && margin >= 0.14f
                        if (autoLearnSpeakers && (strictHighMatch || crossEnvAdaptation)) {
                            val clip = if (profile.isAutoLearned && profile.previewPcm16 == null && voice.cleanSeconds >= 2.6f) {
                                SpeakerEmbeddingHelper.floatSamplesToPcm16Bytes(voice.cleanSamples)
                            } else null
                            updateProfile(index, vector, voice.cleanSeconds, clip)
                        }
                    } else if (voice.cleanSeconds >= 1.1f && sim >= 0.67f) {
                        updateProfile(index, vector, voice.cleanSeconds)
                        // Promote consistent multi-window session speakers to persistent store for future recordings
                        // while preserving their current-session transcript label so paragraphs never split mid-file.
                        val promoted = profiles[index]
                        if (autoLearnSpeakers &&
                            !promoted.persistent &&
                            promoted.observations >= 2 &&
                            promoted.accumulatedCleanSeconds >= 3.2f &&
                            autoEnrolledThisSession < 4
                        ) {
                            autoEnrolledThisSession++
                            profiles[index] = promoted.copy(
                                persistent = true,
                                isAutoLearned = true,
                                dirty = true,
                                persistAsName = "$autoVoicePrefix #${nextAutoVoiceIndex++}",
                                dirtyAddedSeconds = promoted.accumulatedCleanSeconds,
                                previewPcm16 = promoted.previewPcm16
                                    ?: SpeakerEmbeddingHelper.floatSamplesToPcm16Bytes(voice.cleanSamples)
                            )
                        }
                    }
                }
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
        val minScore = if (clusterLabel == null) 0.64f else 0.68f
        if (best != null && best.second >= minScore && best.second - secondScore >= 0.06f &&
            (clusterLabel == null || best.second - originalScore >= 0.13f)
        ) {
            return best.first
        }
        if (seconds >= 2.0f && (best == null || best.second < 0.42f) && profiles.size < 48) {
            val name = "$label ${nextSpeaker++}"
            profiles.add(Profile(id = null, name = name, vector = vector, observations = 1, accumulatedCleanSeconds = seconds))
            return name
        }
        return clusterLabel
    }

    private fun similarity(profile: Profile, vector: FloatArray): Float =
        VoiceprintMath.similarity(profile.vector, profile.samples, vector)

    private fun updateProfile(
        index: Int,
        vector: FloatArray,
        cleanSeconds: Float = 0f,
        fallbackClip: ByteArray? = null
    ) {
        val prior = profiles[index]
        val maxWeight = if (prior.persistent) 12 else 8
        val merged = VoiceprintMath.mergeEmbedding(
            existingCentroid = prior.vector,
            existingPrototypes = prior.samples,
            existingSamples = prior.observations,
            newEmbedding = vector,
            maxWeight = maxWeight
        ) ?: return
        profiles[index] = prior.copy(
            vector = merged.first,
            samples = merged.second,
            observations = merged.third,
            dirty = prior.persistent,
            accumulatedCleanSeconds = prior.accumulatedCleanSeconds + cleanSeconds,
            dirtyAddedSeconds = if (prior.persistent) prior.dirtyAddedSeconds + cleanSeconds else prior.dirtyAddedSeconds,
            previewPcm16 = prior.previewPcm16 ?: fallbackClip
        )
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
        var sumSq = 0.0
        for (s in samples) sumSq += s.toDouble() * s.toDouble()
        val rms = kotlin.math.sqrt(sumSq / samples.size)
        if (rms < 0.0045) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) VoiceprintMath.normalize(extractor.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    private fun toSamples(pcm: ByteArray): FloatArray {
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(shorts.remaining()) { shorts.get() / 32768f }
    }

    override fun close() {
        extractor.release()
        diarization.release()
    }

    companion object {
        private const val SAMPLE_RATE = VoiceprintMath.SAMPLE_RATE
    }
}
