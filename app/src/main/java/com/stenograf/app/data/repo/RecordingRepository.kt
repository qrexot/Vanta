package com.stenograf.app.data.repo

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.room.withTransaction
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.AsrTextFormatter
import com.stenograf.app.asr.LearnedVoiceprintUpdate
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.SpeakerEmbeddingHelper
import com.stenograf.app.asr.StoredVoiceprint
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.asr.SpeechGap
import com.stenograf.app.asr.planGapInsertion
import com.stenograf.app.asr.VoiceprintMath
import com.stenograf.app.audio.AudioDecoder
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.service.preserveTranscriptAnnotations
import com.stenograf.app.data.db.RecordingBookmarkCount
import com.stenograf.app.data.db.StenografDatabase
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.model.SpeakerProfileEntity
import com.stenograf.app.data.prefs.AppPreferences
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class StorageBreakdown(
    val audioBytes: Long,
    val modelsBytes: Long,
    val cacheAndDownloadsBytes: Long,
    val databaseBytes: Long,
    val freeDeviceBytes: Long
) {
    val totalAppBytes: Long
        get() = audioBytes + modelsBytes + cacheAndDownloadsBytes + databaseBytes
}

class RecordingRepository(
    private val context: Context,
    private val database: StenografDatabase
) {
    private val dao = database.dao()
    val recordingsDir: File = File(context.filesDir, "recordings").apply { mkdirs() }
    val voiceprintsDir: File = File(context.filesDir, "voiceprints").apply { mkdirs() }
    val exportsDir: File = File(context.cacheDir, "exports").apply { mkdirs() }
    private val speakerProfileMutex = Mutex()

    private fun writeProfileSampleWav(profileId: Long, pcm16kBytes: ByteArray?): String {
        if (pcm16kBytes == null || pcm16kBytes.size < 16_000) return ""
        val target = File(voiceprintsDir, "vp_$profileId.wav")
        return try {
            FileOutputStream(target).use { out ->
                out.write(WavHelper.createHeader(16_000, 1, 16, pcm16kBytes.size.toLong()))
                out.write(pcm16kBytes)
            }
            target.absolutePath
        } catch (_: Throwable) {
            ""
        }
    }

    private fun deleteProfileSampleWav(samplePath: String) {
        if (samplePath.isNotBlank()) {
            runCatching { File(samplePath).delete() }
        }
    }

    private suspend fun buildQuoteMetadataLocked(speakerName: String, preferRecordingId: Long? = null): Triple<String, String, Pair<Long?, Float>>? {
        val para = (preferRecordingId?.let { dao.findBestParagraphForSpeakerInRecording(it, speakerName) })
            ?: dao.findRepresentativeParagraphForSpeaker(speakerName)
            ?: return null
        val rec = dao.getRecording(para.recordingId) ?: return null
        val quote = para.text.trim().take(140)
        val source = "${rec.title} · ${AsrTextFormatter.formatTimestampSec(para.startSec)}"
        return Triple(quote, source, rec.id to para.startSec)
    }

    fun observeSpeakerProfiles(): Flow<List<SpeakerProfileEntity>> = dao.observeSpeakerProfiles()

    suspend fun getAllSpeakerProfiles(): List<SpeakerProfileEntity> = withContext(Dispatchers.IO) {
        dao.getAllSpeakerProfiles()
    }

    internal suspend fun getAllStoredVoiceprints(): List<StoredVoiceprint> = withContext(Dispatchers.IO) {
        dao.getAllSpeakerProfiles().mapNotNull { VoiceprintMath.toStoredVoiceprint(it) }
    }

    suspend fun enrollOrReinforceFromPcm16(
        name: String,
        tagOrPhone: String,
        pcm16kBytes: ByteArray,
        existingProfileId: Long? = null,
        sourceTitle: String = ""
    ): SpeakerProfileEntity? = withContext(Dispatchers.IO) {
        val bundle = SpeakerEmbeddingHelper.extractStrictTrainingBundle(
            assets = context.assets,
            pcm16kBytes = pcm16kBytes,
            minSpeechSec = 1.2f
        ) ?: return@withContext null

        val cleanTag = tagOrPhone.trim()
        speakerProfileMutex.withLock {
            val now = System.currentTimeMillis()
            val targetById = existingProfileId?.let { dao.getSpeakerProfile(it) }
            val cleanName = name.trim().ifEmpty { targetById?.name.orEmpty() }
            if (cleanName.isEmpty()) return@withContext null
            val existing = targetById ?: dao.findSpeakerProfileByName(cleanName)

            if (existing == null) {
                val baseEntity = SpeakerProfileEntity(
                    name = cleanName,
                    embeddingJson = VoiceprintMath.encodeEmbedding(bundle.centroid),
                    prototypesJson = VoiceprintMath.encodePrototypes(bundle.prototypes),
                    sampleCount = bundle.sampleCount.coerceAtLeast(1),
                    isAutoLearned = false,
                    createdAtMs = now,
                    updatedAtMs = now,
                    tagOrPhone = cleanTag,
                    sampleSourceTitle = sourceTitle,
                    speechSeconds = bundle.activeSpeechSeconds
                )
                val newId = dao.insertSpeakerProfile(baseEntity)
                val wavPath = writeProfileSampleWav(newId, bundle.previewPcm16)
                val saved = if (wavPath.isNotEmpty()) {
                    baseEntity.copy(id = newId, sampleAudioPath = wavPath).also { dao.updateSpeakerProfile(it) }
                } else {
                    baseEntity.copy(id = newId)
                }
                return@withContext saved
            }

            val currentStored = VoiceprintMath.toStoredVoiceprint(existing)
            val merged = if (currentStored != null) {
                VoiceprintMath.mergeStoredProfiles(
                    targetCentroid = currentStored.centroid,
                    targetPrototypes = currentStored.prototypes,
                    targetSamples = currentStored.sampleCount,
                    sourceCentroid = bundle.centroid,
                    sourcePrototypes = bundle.prototypes,
                    sourceSamples = bundle.sampleCount
                )
            } else null

            val nextCentroid = merged?.first ?: bundle.centroid
            val nextProtos = merged?.second ?: bundle.prototypes
            val nextCount = merged?.third ?: (existing.sampleCount + bundle.sampleCount)
            val needsClip = existing.sampleAudioPath.isBlank() || !File(existing.sampleAudioPath).exists() ||
                (bundle.previewPcm16?.size ?: 0) >= 38_400
            val wavPath = if (needsClip) {
                writeProfileSampleWav(existing.id, bundle.previewPcm16).ifEmpty { existing.sampleAudioPath }
            } else {
                existing.sampleAudioPath
            }
            val updated = existing.copy(
                name = cleanName,
                embeddingJson = VoiceprintMath.encodeEmbedding(nextCentroid),
                prototypesJson = VoiceprintMath.encodePrototypes(nextProtos),
                sampleCount = nextCount,
                isAutoLearned = false,
                updatedAtMs = now,
                tagOrPhone = cleanTag.ifEmpty { existing.tagOrPhone },
                sampleAudioPath = wavPath,
                sampleSourceTitle = existing.sampleSourceTitle.ifBlank { sourceTitle },
                speechSeconds = existing.speechSeconds + bundle.activeSpeechSeconds
            )
            dao.updateSpeakerProfile(updated)
            updated
        }
    }

    suspend fun enrollOrUpdateSpeakerVoiceprint(
        name: String,
        embedding: FloatArray,
        isAutoLearned: Boolean = false
    ): SpeakerProfileEntity? = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val normalized = VoiceprintMath.normalize(embedding) ?: return@withContext null
        if (cleanName.isEmpty()) return@withContext null

        speakerProfileMutex.withLock {
            val now = System.currentTimeMillis()
            val existing = dao.findSpeakerProfileByName(cleanName)
            if (existing == null) {
                val entity = SpeakerProfileEntity(
                    name = cleanName,
                    embeddingJson = VoiceprintMath.encodeEmbedding(normalized),
                    prototypesJson = VoiceprintMath.encodePrototypes(listOf(normalized)),
                    sampleCount = 1,
                    isAutoLearned = isAutoLearned,
                    createdAtMs = now,
                    updatedAtMs = now,
                    speechSeconds = 3.0f
                )
                val id = dao.insertSpeakerProfile(entity)
                return@withContext entity.copy(id = id)
            }
            val currentStored = VoiceprintMath.toStoredVoiceprint(existing)
            val updated = if (currentStored != null) {
                val merged = VoiceprintMath.mergeEmbedding(
                    existingCentroid = currentStored.centroid,
                    existingPrototypes = currentStored.prototypes,
                    existingSamples = currentStored.sampleCount,
                    newEmbedding = normalized
                )
                if (merged != null) {
                    existing.copy(
                        name = cleanName,
                        embeddingJson = VoiceprintMath.encodeEmbedding(merged.first),
                        prototypesJson = VoiceprintMath.encodePrototypes(merged.second),
                        sampleCount = merged.third,
                        isAutoLearned = existing.isAutoLearned && isAutoLearned,
                        updatedAtMs = now,
                        speechSeconds = existing.speechSeconds + 3.0f
                    )
                } else existing
            } else {
                existing.copy(
                    name = cleanName,
                    embeddingJson = VoiceprintMath.encodeEmbedding(normalized),
                    prototypesJson = VoiceprintMath.encodePrototypes(listOf(normalized)),
                    sampleCount = existing.sampleCount + 1,
                    isAutoLearned = existing.isAutoLearned && isAutoLearned,
                    updatedAtMs = now,
                    speechSeconds = existing.speechSeconds + 3.0f
                )
            }
            dao.updateSpeakerProfile(updated)
            updated
        }
    }

    internal suspend fun persistLearnedVoiceprints(updates: List<LearnedVoiceprintUpdate>) = withContext(Dispatchers.IO) {
        if (updates.isEmpty()) return@withContext
        speakerProfileMutex.withLock {
            val now = System.currentTimeMillis()
            var existingCount = dao.getAllSpeakerProfiles().size
            for (u in updates) {
                val cleanName = u.name.trim()
                if (cleanName.isEmpty()) continue
                val existing = u.profileId?.let { dao.getSpeakerProfile(it) } ?: dao.findSpeakerProfileByName(cleanName)
                if (existing != null) {
                    val stored = VoiceprintMath.toStoredVoiceprint(existing)
                    val merged = if (stored != null) {
                        VoiceprintMath.mergeStoredProfiles(
                            targetCentroid = stored.centroid,
                            targetPrototypes = stored.prototypes,
                            targetSamples = stored.sampleCount,
                            sourceCentroid = u.centroid,
                            sourcePrototypes = u.prototypes,
                            sourceSamples = (u.sampleCount - stored.sampleCount).coerceAtLeast(1)
                        )
                    } else null
                    val nextCentroid = merged?.first ?: u.centroid
                    val nextProtos = merged?.second ?: u.prototypes
                    val nextCount = maxOf(existing.sampleCount + 1, u.sampleCount)
                    val wavPath = if (existing.sampleAudioPath.isBlank() || !File(existing.sampleAudioPath).exists()) {
                        writeProfileSampleWav(existing.id, u.samplePcm16).ifEmpty { existing.sampleAudioPath }
                    } else {
                        existing.sampleAudioPath
                    }
                    val quoteMeta = if (existing.sampleTranscript.isBlank()) buildQuoteMetadataLocked(cleanName) else null
                    dao.updateSpeakerProfile(
                        existing.copy(
                            embeddingJson = VoiceprintMath.encodeEmbedding(nextCentroid),
                            prototypesJson = VoiceprintMath.encodePrototypes(nextProtos),
                            sampleCount = nextCount,
                            updatedAtMs = now,
                            sampleAudioPath = wavPath,
                            sampleTranscript = quoteMeta?.first ?: existing.sampleTranscript,
                            sampleSourceTitle = quoteMeta?.second ?: existing.sampleSourceTitle,
                            sampleRecordingId = quoteMeta?.third?.first ?: existing.sampleRecordingId,
                            sampleStartSec = quoteMeta?.third?.second ?: existing.sampleStartSec,
                            speechSeconds = existing.speechSeconds + u.addedSpeechSeconds.coerceAtLeast(0f)
                        )
                    )
                } else if (existingCount < 48) {
                    val entity = SpeakerProfileEntity(
                        name = cleanName,
                        embeddingJson = VoiceprintMath.encodeEmbedding(u.centroid),
                        prototypesJson = VoiceprintMath.encodePrototypes(u.prototypes),
                        sampleCount = u.sampleCount.coerceAtLeast(1),
                        isAutoLearned = u.isAutoLearned,
                        createdAtMs = now,
                        updatedAtMs = now,
                        speechSeconds = u.addedSpeechSeconds.coerceAtLeast(2.5f)
                    )
                    val newId = dao.insertSpeakerProfile(entity)
                    val wavPath = writeProfileSampleWav(newId, u.samplePcm16)
                    val quoteMeta = buildQuoteMetadataLocked(cleanName)
                    if (wavPath.isNotEmpty() || quoteMeta != null) {
                        dao.updateSpeakerProfile(
                            entity.copy(
                                id = newId,
                                sampleAudioPath = wavPath,
                                sampleTranscript = quoteMeta?.first ?: "",
                                sampleSourceTitle = quoteMeta?.second ?: "",
                                sampleRecordingId = quoteMeta?.third?.first,
                                sampleStartSec = quoteMeta?.third?.second ?: 0f
                            )
                        )
                    }
                    existingCount++
                }
            }
        }
    }

    suspend fun backfillLearnedVoiceSamples(recordingId: Long) = withContext(Dispatchers.IO) {
        val rec = dao.getRecording(recordingId) ?: return@withContext
        val profiles = dao.getAllSpeakerProfiles()
        for (p in profiles) {
            if (p.sampleAudioPath.isNotBlank() && File(p.sampleAudioPath).exists() && p.sampleTranscript.isNotBlank()) {
                continue
            }
            val para = dao.findBestParagraphForSpeakerInRecording(recordingId, p.name) ?: continue
            ensureProfileAudioSampleFromParagraph(p.id, rec, para)
        }
    }

    suspend fun ensureProfileAudioSample(profileId: Long): SpeakerProfileEntity? = withContext(Dispatchers.IO) {
        val profile = dao.getSpeakerProfile(profileId) ?: return@withContext null
        val hasAudio = profile.sampleAudioPath.isNotBlank() && File(profile.sampleAudioPath).let {
            it.exists() && it.length() > WavHelper.HEADER_SIZE + 16_000L
        }
        if (hasAudio && profile.sampleTranscript.isNotBlank()) {
            return@withContext profile
        }
        val para = (profile.sampleRecordingId?.let { dao.findBestParagraphForSpeakerInRecording(it, profile.name) })
            ?: dao.findRepresentativeParagraphForSpeaker(profile.name)
            ?: return@withContext profile
        val rec = dao.getRecording(para.recordingId) ?: return@withContext profile
        ensureProfileAudioSampleFromParagraph(profile.id, rec, para)
    }

    private suspend fun ensureProfileAudioSampleFromParagraph(
        profileId: Long,
        rec: RecordingEntity,
        para: ParagraphEntity
    ): SpeakerProfileEntity? {
        var tempPcm: File? = null
        val extractedPcm = try {
            val spoolFile = File(rec.asrSpoolPath)
            val audioFile = File(rec.audioPath)
            val (pcmFile, baseOffset) = when {
                spoolFile.exists() && rec.asrSpoolPath != rec.audioPath && spoolFile.length() > 3200L ->
                    spoolFile to 0L
                WavHelper.monoPcm16SampleRate(audioFile) == 16_000 ->
                    audioFile to WavHelper.HEADER_SIZE.toLong()
                audioFile.exists() -> {
                    val tmp = File.createTempFile("vpclip_${profileId}_", ".pcm", context.cacheDir).also { tempPcm = it }
                    AudioDecoder.decodeUriToPcm16kFile(context, Uri.fromFile(audioFile), tmp)
                    tmp to 0L
                }
                else -> null
            } ?: return dao.getSpeakerProfile(profileId)

            val durSec = (para.endSec - para.startSec).coerceIn(1.2f, 5.5f)
            val startByte = baseOffset + ((para.startSec.coerceAtLeast(0f) * 32_000f).toLong() and -2L)
            val maxLen = (durSec * 32_000f).toInt() and -2
            val available = (pcmFile.length() - startByte).coerceAtLeast(0L)
            val toRead = minOf(available, maxLen.toLong()).toInt() and -2
            if (toRead >= 16_000) {
                ByteArray(toRead).also { buf ->
                    RandomAccessFile(pcmFile, "r").use { raf ->
                        raf.seek(startByte)
                        raf.readFully(buf)
                    }
                }
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            tempPcm?.delete()
        }

        return speakerProfileMutex.withLock {
            val current = dao.getSpeakerProfile(profileId) ?: return@withLock null
            val hasWav = current.sampleAudioPath.isNotBlank() && File(current.sampleAudioPath).exists()
            val wavPath = if (!hasWav) writeProfileSampleWav(profileId, extractedPcm).ifEmpty { current.sampleAudioPath } else current.sampleAudioPath
            val quote = current.sampleTranscript.ifBlank { para.text.trim().take(140) }
            val sourceTitle = current.sampleSourceTitle.ifBlank {
                "${rec.title} · ${AsrTextFormatter.formatTimestampSec(para.startSec)}"
            }
            val updated = current.copy(
                sampleAudioPath = wavPath,
                sampleTranscript = quote,
                sampleSourceTitle = sourceTitle,
                sampleRecordingId = current.sampleRecordingId ?: rec.id,
                sampleStartSec = if (current.sampleRecordingId == null) para.startSec else current.sampleStartSec
            )
            dao.updateSpeakerProfile(updated)
            updated
        }
    }

    suspend fun updateSpeakerProfileDetails(
        profileId: Long,
        newName: String,
        tagOrPhone: String,
        renameInRecordings: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val clean = newName.trim()
        val cleanTag = tagOrPhone.trim()
        if (clean.isEmpty()) return@withContext
        speakerProfileMutex.withLock {
            val source = dao.getSpeakerProfile(profileId) ?: return@withContext
            val oldName = source.name
            val collision = dao.findSpeakerProfileByName(clean)
            if (collision != null && collision.id != source.id) {
                val targetStored = VoiceprintMath.toStoredVoiceprint(collision)
                val sourceStored = VoiceprintMath.toStoredVoiceprint(source)
                if (targetStored != null && sourceStored != null) {
                    val merged = VoiceprintMath.mergeStoredProfiles(
                        targetCentroid = targetStored.centroid,
                        targetPrototypes = targetStored.prototypes,
                        targetSamples = targetStored.sampleCount,
                        sourceCentroid = sourceStored.centroid,
                        sourcePrototypes = sourceStored.prototypes,
                        sourceSamples = sourceStored.sampleCount
                    )
                    if (merged != null) {
                        val keepWav = collision.sampleAudioPath.takeIf { it.isNotBlank() && File(it).exists() }
                            ?: source.sampleAudioPath.takeIf { it.isNotBlank() && File(it).exists() }
                            ?: ""
                        dao.updateSpeakerProfile(
                            collision.copy(
                                name = clean,
                                embeddingJson = VoiceprintMath.encodeEmbedding(merged.first),
                                prototypesJson = VoiceprintMath.encodePrototypes(merged.second),
                                sampleCount = merged.third,
                                isAutoLearned = false,
                                updatedAtMs = System.currentTimeMillis(),
                                tagOrPhone = cleanTag.ifEmpty { collision.tagOrPhone.ifEmpty { source.tagOrPhone } },
                                sampleAudioPath = keepWav,
                                sampleTranscript = collision.sampleTranscript.ifBlank { source.sampleTranscript },
                                sampleSourceTitle = collision.sampleSourceTitle.ifBlank { source.sampleSourceTitle },
                                sampleRecordingId = collision.sampleRecordingId ?: source.sampleRecordingId,
                                sampleStartSec = if (collision.sampleRecordingId != null) collision.sampleStartSec else source.sampleStartSec,
                                speechSeconds = collision.speechSeconds + source.speechSeconds
                            )
                        )
                        if (keepWav != source.sampleAudioPath) deleteProfileSampleWav(source.sampleAudioPath)
                    }
                }
                dao.deleteSpeakerProfile(source.id)
            } else {
                dao.updateSpeakerProfile(
                    source.copy(
                        name = clean,
                        tagOrPhone = cleanTag,
                        isAutoLearned = false,
                        updatedAtMs = System.currentTimeMillis()
                    )
                )
            }
            if (renameInRecordings && oldName != clean) {
                dao.renameSpeakerAcrossAllRecordings(oldName, clean)
            }
        }
    }

    suspend fun renameSpeakerProfile(
        profileId: Long,
        newName: String,
        renameInRecordings: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val current = dao.getSpeakerProfile(profileId) ?: return@withContext
        updateSpeakerProfileDetails(profileId, newName, current.tagOrPhone, renameInRecordings)
    }

    suspend fun mergeSpeakerProfiles(
        targetId: Long,
        sourceId: Long,
        renameInRecordings: Boolean = true
    ) = withContext(Dispatchers.IO) {
        if (targetId == sourceId) return@withContext
        speakerProfileMutex.withLock {
            val target = dao.getSpeakerProfile(targetId) ?: return@withContext
            val source = dao.getSpeakerProfile(sourceId) ?: return@withContext
            val targetStored = VoiceprintMath.toStoredVoiceprint(target)
            val sourceStored = VoiceprintMath.toStoredVoiceprint(source)
            if (targetStored != null && sourceStored != null) {
                val merged = VoiceprintMath.mergeStoredProfiles(
                    targetCentroid = targetStored.centroid,
                    targetPrototypes = targetStored.prototypes,
                    targetSamples = targetStored.sampleCount,
                    sourceCentroid = sourceStored.centroid,
                    sourcePrototypes = sourceStored.prototypes,
                    sourceSamples = sourceStored.sampleCount
                )
                if (merged != null) {
                    val keepWav = target.sampleAudioPath.takeIf { it.isNotBlank() && File(it).exists() }
                        ?: source.sampleAudioPath.takeIf { it.isNotBlank() && File(it).exists() }
                        ?: ""
                    dao.updateSpeakerProfile(
                        target.copy(
                            embeddingJson = VoiceprintMath.encodeEmbedding(merged.first),
                            prototypesJson = VoiceprintMath.encodePrototypes(merged.second),
                            sampleCount = merged.third,
                            isAutoLearned = false,
                            updatedAtMs = System.currentTimeMillis(),
                            tagOrPhone = target.tagOrPhone.ifBlank { source.tagOrPhone },
                            sampleAudioPath = keepWav,
                            sampleTranscript = target.sampleTranscript.ifBlank { source.sampleTranscript },
                            sampleSourceTitle = target.sampleSourceTitle.ifBlank { source.sampleSourceTitle },
                            sampleRecordingId = target.sampleRecordingId ?: source.sampleRecordingId,
                            sampleStartSec = if (target.sampleRecordingId != null) target.sampleStartSec else source.sampleStartSec,
                            speechSeconds = target.speechSeconds + source.speechSeconds
                        )
                    )
                    if (keepWav != source.sampleAudioPath) deleteProfileSampleWav(source.sampleAudioPath)
                }
            }
            dao.deleteSpeakerProfile(source.id)
            if (renameInRecordings && source.name != target.name) {
                dao.renameSpeakerAcrossAllRecordings(source.name, target.name)
            }
        }
    }

    suspend fun deleteSpeakerProfile(id: Long) = withContext(Dispatchers.IO) {
        speakerProfileMutex.withLock {
            val existing = dao.getSpeakerProfile(id)
            dao.deleteSpeakerProfile(id)
            existing?.let { deleteProfileSampleWav(it.sampleAudioPath) }
        }
    }

    /**
     * Reassigns a paragraph's (or all matching speaker paragraphs') speaker label in [recordingId],
     * extracts multi-segment acoustic voiceprints from those intervals, and saves sample clip + quote.
     */
    suspend fun assignRecordingSpeakerAndLearn(
        recordingId: Long,
        targetParagraph: ParagraphEntity?,
        oldSpeaker: String?,
        newName: String,
        applyToAllMatching: Boolean,
        enrollVoiceprint: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return@withContext false

        val allParagraphs = dao.getParagraphs(recordingId)
        val matchedParagraphs = when {
            applyToAllMatching && !oldSpeaker.isNullOrBlank() ->
                allParagraphs.filter { it.speaker == oldSpeaker }
            targetParagraph != null -> listOf(targetParagraph)
            else -> emptyList()
        }
        if (matchedParagraphs.isEmpty()) return@withContext false

        if (applyToAllMatching && !oldSpeaker.isNullOrBlank()) {
            dao.renameRecordingSpeakerLabel(recordingId, oldSpeaker, cleanName)
        } else if (targetParagraph != null) {
            dao.updateParagraphSpeaker(targetParagraph.id, cleanName)
        }
        dao.getRecording(recordingId)?.let { rec ->
            dao.updateRecording(rec.copy(updatedAtMs = System.currentTimeMillis()))
        }

        if (!enrollVoiceprint) return@withContext true

        val rec = dao.getRecording(recordingId) ?: return@withContext true
        val intervals = matchedParagraphs.map { it.startSec to it.endSec }
        val repPara = targetParagraph ?: matchedParagraphs.maxByOrNull { it.endSec - it.startSec } ?: matchedParagraphs.first()
        var tempPcm: File? = null
        try {
            val spoolFile = File(rec.asrSpoolPath)
            val audioFile = File(rec.audioPath)
            val (pcmFile, baseOffset) = when {
                spoolFile.exists() && rec.asrSpoolPath != rec.audioPath && spoolFile.length() > 3200L ->
                    spoolFile to 0L
                WavHelper.monoPcm16SampleRate(audioFile) == 16_000 ->
                    audioFile to WavHelper.HEADER_SIZE.toLong()
                audioFile.exists() -> {
                    val tmp = File.createTempFile("vprint_${recordingId}_", ".pcm", context.cacheDir).also { tempPcm = it }
                    AudioDecoder.decodeUriToPcm16kFile(context, Uri.fromFile(audioFile), tmp)
                    tmp to 0L
                }
                else -> return@withContext true
            }
            val bundle = SpeakerEmbeddingHelper.extractTrainingBundleFromIntervals(context.assets, pcmFile, baseOffset, intervals)
            if (bundle != null) {
                val quote = repPara.text.trim().take(140)
                val sourceTitle = "${rec.title} · ${AsrTextFormatter.formatTimestampSec(repPara.startSec)}"
                speakerProfileMutex.withLock {
                    val now = System.currentTimeMillis()
                    val existing = dao.findSpeakerProfileByName(cleanName)
                    if (existing == null) {
                        val baseEntity = SpeakerProfileEntity(
                            name = cleanName,
                            embeddingJson = VoiceprintMath.encodeEmbedding(bundle.centroid),
                            prototypesJson = VoiceprintMath.encodePrototypes(bundle.prototypes),
                            sampleCount = bundle.sampleCount,
                            isAutoLearned = false,
                            createdAtMs = now,
                            updatedAtMs = now,
                            sampleTranscript = quote,
                            sampleSourceTitle = sourceTitle,
                            sampleRecordingId = rec.id,
                            sampleStartSec = repPara.startSec,
                            speechSeconds = bundle.activeSpeechSeconds
                        )
                        val id = dao.insertSpeakerProfile(baseEntity)
                        val wavPath = writeProfileSampleWav(id, bundle.previewPcm16)
                        if (wavPath.isNotEmpty()) {
                            dao.updateSpeakerProfile(baseEntity.copy(id = id, sampleAudioPath = wavPath))
                        }
                    } else {
                        val stored = VoiceprintMath.toStoredVoiceprint(existing)
                        val merged = if (stored != null) {
                            VoiceprintMath.mergeStoredProfiles(
                                targetCentroid = stored.centroid,
                                targetPrototypes = stored.prototypes,
                                targetSamples = stored.sampleCount,
                                sourceCentroid = bundle.centroid,
                                sourcePrototypes = bundle.prototypes,
                                sourceSamples = bundle.sampleCount
                            )
                        } else null
                        val wavPath = if (existing.sampleAudioPath.isBlank() || !File(existing.sampleAudioPath).exists()) {
                            writeProfileSampleWav(existing.id, bundle.previewPcm16).ifEmpty { existing.sampleAudioPath }
                        } else existing.sampleAudioPath
                        dao.updateSpeakerProfile(
                            existing.copy(
                                embeddingJson = VoiceprintMath.encodeEmbedding(merged?.first ?: bundle.centroid),
                                prototypesJson = VoiceprintMath.encodePrototypes(merged?.second ?: bundle.prototypes),
                                sampleCount = merged?.third ?: (existing.sampleCount + bundle.sampleCount),
                                isAutoLearned = false,
                                updatedAtMs = now,
                                sampleAudioPath = wavPath,
                                sampleTranscript = existing.sampleTranscript.ifBlank { quote },
                                sampleSourceTitle = existing.sampleSourceTitle.ifBlank { sourceTitle },
                                sampleRecordingId = existing.sampleRecordingId ?: rec.id,
                                sampleStartSec = if (existing.sampleRecordingId == null) repPara.startSec else existing.sampleStartSec,
                                speechSeconds = existing.speechSeconds + bundle.activeSpeechSeconds
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            // Paragraph label change already committed; ignore transient audio extraction failure
        } finally {
            tempPcm?.delete()
        }
        true
    }

    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    fun observeRecordings(folderId: Long?, query: String): Flow<List<RecordingEntity>> {
        val escaped = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_")
        return dao.observeFilteredRecordings(folderId, escaped)
    }

    fun observeRecording(id: Long): Flow<RecordingEntity?> = dao.observeRecording(id)

    fun observeParagraphs(recordingId: Long): Flow<List<ParagraphEntity>> = dao.observeParagraphs(recordingId)

    fun observeBookmarkCounts(): Flow<List<RecordingBookmarkCount>> = dao.observeBookmarkCounts()

    suspend fun getRecording(id: Long): RecordingEntity? = dao.getRecording(id)

    suspend fun getParagraphs(recordingId: Long): List<ParagraphEntity> = dao.getParagraphs(recordingId)

    suspend fun ensureDefaultFolders() {
        val strings = AppLocaleManager.currentStrings()
        if (dao.getAllFolders().isEmpty()) {
            dao.insertFolder(FolderEntity(name = strings.defaultFolderLectures, colorHex = "#3B82F6", sortOrder = 0))
            dao.insertFolder(FolderEntity(name = strings.defaultFolderMeetings, colorHex = "#10B981", sortOrder = 1))
            dao.insertFolder(FolderEntity(name = strings.defaultFolderNotes, colorHex = "#F59E0B", sortOrder = 2))
        }
    }

    suspend fun createFolder(name: String, colorHex: String = "#3B82F6"): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return 0L
        val order = dao.getAllFolders().size
        return dao.insertFolder(FolderEntity(name = clean, colorHex = colorHex, sortOrder = order))
    }

    suspend fun updateFolder(folder: FolderEntity, newName: String, colorHex: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            dao.updateFolder(folder.copy(name = clean, colorHex = colorHex))
        }
    }

    suspend fun toggleParagraphBookmark(paragraph: ParagraphEntity, label: String?) {
        val nextBookmark = if (label != null) true else !paragraph.isBookmark
        val resolvedLabel = if (nextBookmark) {
            label?.trim()?.takeIf { it.isNotEmpty() } ?: paragraph.bookmarkLabel ?: AppLocaleManager.currentStrings().bookmarkDefaultLabel
        } else {
            null
        }
        dao.updateParagraphBookmark(paragraph.id, nextBookmark, resolvedLabel)
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        dao.deleteFolderAndKeepRecordings(folder)
    }

    suspend fun createNewRecordingSession(
        format: AudioFormat,
        modelId: String,
        languagePresetId: String,
        startLang: String,
        folderId: Long? = null
    ): RecordingEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val strings = AppLocaleManager.currentStrings()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val titleStamp = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(now))
        val stem = "rec_${stamp}_${UUID.randomUUID()}"
        val audioFile = File(recordingsDir, "$stem.${format.extension}")
        val spoolFile = File(recordingsDir, "$stem.asr16k.pcm")

        val initial = RecordingEntity(
            title = "${strings.defaultRecordingPrefix} $titleStamp",
            createdAtMs = now,
            updatedAtMs = now,
            audioPath = audioFile.absolutePath,
            asrSpoolPath = spoolFile.absolutePath,
            audioFormat = format.name,
            modelId = modelId,
            languagePresetId = languagePresetId,
            detectedLanguage = startLang,
            folderId = folderId,
            status = RecordingStatus.RECORDING.name
        )
        val id = dao.insertRecording(initial)
        initial.copy(id = id)
    }

    suspend fun createImportedRecording(
        title: String,
        audioFile: File,
        asrSpoolFile: File,
        durationMs: Long,
        format: AudioFormat,
        modelId: String,
        languagePresetId: String,
        startLang: String,
        folderId: Long? = null
    ): RecordingEntity = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val rec = RecordingEntity(
            title = title,
            createdAtMs = now,
            updatedAtMs = now,
            durationMs = durationMs,
            audioPath = audioFile.absolutePath,
            asrSpoolPath = asrSpoolFile.absolutePath,
            audioFormat = format.name,
            modelId = modelId,
            languagePresetId = languagePresetId,
            detectedLanguage = startLang,
            folderId = folderId,
            status = RecordingStatus.COMPLETED.name,
            totalAsrBytes = if (asrSpoolFile == audioFile) durationMs.coerceAtLeast(0L) * 32L
                else asrSpoolFile.length() and -2L,
            processedAsrBytes = 0L,
            fileSizeBytes = audioFile.length()
        )
        val id = dao.insertRecording(rec)
        rec.copy(id = id)
    }

    suspend fun updateRecording(recording: RecordingEntity) {
        dao.updateRecording(recording)
    }

    internal suspend fun completeCapture(
        recordingId: Long,
        bookmarks: List<ParagraphEntity>,
        finish: (RecordingEntity) -> RecordingEntity
    ): Boolean = database.withTransaction {
        val current = dao.getRecording(recordingId) ?: return@withTransaction false
        if (bookmarks.isNotEmpty()) dao.insertParagraphs(bookmarks)
        val paragraphs = dao.getParagraphs(recordingId)
        val (count, preview) = summarizeTranscript(paragraphs)
        dao.updateRecording(
            finish(current).copy(
                hasTranscript = paragraphs.any { it.text.isNotBlank() },
                wordCount = count,
                previewText = preview,
                updatedAtMs = System.currentTimeMillis()
            )
        )
        true
    }

    suspend fun updateRecordingProgress(
        id: Long,
        durationMs: Long,
        fileSizeBytes: Long,
        totalAsrBytes: Long,
        processedAsrBytes: Long,
        detectedLang: String
    ) {
        dao.updateRecordingProgress(id, durationMs, fileSizeBytes, totalAsrBytes, processedAsrBytes, detectedLang)
    }

    suspend fun commitLiveTranscript(
        recordingId: Long,
        paragraphs: List<ParagraphEntity>,
        processedBytes: Long,
        updatedLast: ParagraphEntity?
    ): List<Long> = dao.commitLiveTranscript(recordingId, paragraphs, processedBytes, updatedLast)

    suspend fun insertParagraph(paragraph: ParagraphEntity): Long = dao.insertParagraph(paragraph)

    suspend fun updateParagraph(paragraph: ParagraphEntity) = dao.updateParagraph(paragraph)

    suspend fun updateParagraphTextAndRefreshSummary(paragraph: ParagraphEntity, newText: String): Boolean {
        return saveParagraphText(paragraph, paragraphWithEditedText(paragraph, newText))
    }

    private suspend fun saveParagraphText(original: ParagraphEntity, updated: ParagraphEntity): Boolean =
        database.withTransaction {
            val changed = dao.updateParagraphIfUnchanged(
                original.id, original.recordingId, original.text, updated.text, updated.wordsJson
            ) > 0
            if (changed) refreshRecordingTranscriptSummary(original.recordingId)
            changed
        }

    internal suspend fun commitOfflineTranscript(
        expected: RecordingEntity,
        original: List<ParagraphEntity>,
        replacement: List<ParagraphEntity>,
        modelId: String,
        presetId: String,
        language: String,
        processedBytes: Long,
        completedAudioBytes: Long?,
        reuseParagraphIds: Boolean
    ): Pair<RecordingEntity, List<ParagraphEntity>>? {
        val knownIds = if (reuseParagraphIds) original.associateBy { it.orderIndex } else emptyMap()
        val stable = replacement.map { paragraph ->
            val existing = knownIds[paragraph.orderIndex]
            if (paragraph.id == 0L && existing != null) paragraph.copy(id = existing.id) else paragraph
        }
        val (count, preview) = summarizeTranscript(stable)
        val commit = dao.commitOfflineTranscript(
            expected, original, stable, count, preview, modelId, presetId, language,
            processedBytes, completedAudioBytes
        )
        if (commit != null && completedAudioBytes != null && expected.asrSpoolPath.isNotBlank()) {
            val spool = File(expected.asrSpoolPath)
            safeDeleteSpool(spool, File(expected.audioPath))
        }
        return commit
    }

    private fun safeDeleteSpool(spool: File, audio: File) {
        runCatching {
            val actual = spool.canonicalFile
            if (actual != audio.canonicalFile && actual.parentFile == recordingsDir.canonicalFile &&
                actual.name.endsWith(".asr16k.pcm")
            ) actual.delete()
        }
    }

    internal suspend fun insertRecoveredSpeech(
        recording: RecordingEntity,
        gap: SpeechGap,
        words: List<TranscriptWord>
    ): Boolean = withContext(Dispatchers.Default) {
        val original = dao.getParagraphs(recording.id)
        val result = planGapInsertion(recording.id, original, gap, words) ?: return@withContext false
        val (count, preview) = summarizeTranscript(result)
        dao.insertCoverageResult(
            recording.id, recording.audioPath, gap.endMs, original, result, count, preview
        )
    }

    suspend fun applyTranscriptReview(
        paragraph: ParagraphEntity,
        newText: String,
        alignedWords: List<TranscriptWord>? = null
    ): Boolean {
        val updated = if (!alignedWords.isNullOrEmpty()) {
            paragraph.copy(
                text = newText.trim(),
                wordsJson = AsrJsonParser.encodeWords(alignedWords)
            )
        } else {
            paragraphWithEditedText(paragraph, newText, preserveWordTimings = false)
        }
        return saveParagraphText(paragraph, updated)
    }

    internal suspend fun applyFullAudioReview(
        recordingId: Long,
        original: List<ParagraphEntity>,
        reviewed: List<ParagraphEntity>,
        processedAudioBytes: Long?,
        detectedLanguage: String? = null
    ): Boolean {
        if (reviewed.isEmpty()) return false
        val merged = preserveTranscriptAnnotations(original, reviewed)
        val (count, preview) = summarizeTranscript(reviewed)
        return dao.replaceReviewedAudio(recordingId, original, merged, count, preview, processedAudioBytes, detectedLanguage)
    }

    internal fun paragraphWithEditedText(
        paragraph: ParagraphEntity,
        newText: String,
        preserveWordTimings: Boolean = true
    ): ParagraphEntity = buildEditedParagraph(paragraph, newText, preserveWordTimings)

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")

        internal fun summarizeTranscript(paragraphs: List<ParagraphEntity>): Pair<Int, String> {
            val wordCount = paragraphs.sumOf { p ->
                p.text.split(WHITESPACE_REGEX).count { it.isNotBlank() }
            }
            val preview = paragraphs.asSequence()
                .map { it.text }
                .filter { it.isNotBlank() }
                .take(20)
                .joinToString(" ")
                .take(220)
            return wordCount to preview
        }

        internal fun buildEditedParagraph(
            paragraph: ParagraphEntity,
            newText: String,
            preserveWordTimings: Boolean = true
        ): ParagraphEntity {
            val clean = newText.trim()
            if (!preserveWordTimings) {
                return paragraph.copy(text = clean, wordsJson = "")
            }
            val existingWords = AsrJsonParser.parseWords(paragraph.wordsJson)
            val tokens = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val encodedWords = if (existingWords.isNotEmpty() && tokens.size == existingWords.size) {
                AsrJsonParser.encodeWords(tokens.mapIndexed { idx, w -> existingWords[idx].copy(text = w) })
            } else {
                ""
            }
            return paragraph.copy(
                text = clean,
                wordsJson = encodedWords
            )
        }
    }

    suspend fun refreshRecordingTranscriptSummary(recordingId: Long) {
        val paragraphs = dao.getParagraphs(recordingId)
        val (wordCount, preview) = summarizeTranscript(paragraphs)
        dao.updateTranscriptSummary(recordingId, paragraphs.any { it.text.isNotBlank() }, wordCount, preview)
    }

    suspend fun replaceTranscriptSummary(
        recordingId: Long,
        paragraphs: List<ParagraphEntity>,
        detectedLang: String,
        modelId: String? = null,
        processedAsrBytes: Long? = null
    ) {
        val (wordCount, preview) = summarizeTranscript(paragraphs)
        dao.replaceTranscript(recordingId, paragraphs, wordCount, preview, detectedLang, modelId, processedAsrBytes)
    }

    suspend fun replaceTranscript(
        recordingId: Long,
        paragraphs: List<ParagraphEntity>,
        wordCount: Int,
        previewText: String,
        detectedLang: String,
        modelId: String? = null,
        processedAsrBytes: Long? = null
    ) {
        dao.replaceTranscript(recordingId, paragraphs, wordCount, previewText, detectedLang, modelId, processedAsrBytes)
    }

    suspend fun renameRecording(id: Long, title: String) {
        val clean = title.trim()
        if (clean.isNotEmpty()) dao.renameRecording(id, clean)
    }

    suspend fun moveRecordingToFolder(id: Long, folderId: Long?) {
        dao.moveRecordingToFolder(id, folderId)
    }

    suspend fun deleteRecording(recording: RecordingEntity) = withContext(Dispatchers.IO) {
        val current = dao.getRecording(recording.id) ?: return@withContext
        check(current.statusEnum != RecordingStatus.RECORDING && current.statusEnum != RecordingStatus.PAUSED)
        dao.deleteRecordingById(current.id)
        if (current.asrSpoolPath.isNotBlank()) safeDeleteSpool(File(current.asrSpoolPath), File(current.audioPath))
        if (current.audioPath.isNotBlank()) File(current.audioPath).delete()
        Unit
    }

    internal suspend fun discardEmptyCapture(recording: RecordingEntity) = withContext(Dispatchers.IO) {
        dao.deleteRecordingById(recording.id)
        val audio = File(recording.audioPath)
        if (recording.asrSpoolPath.isNotBlank()) safeDeleteSpool(File(recording.asrSpoolPath), audio)
        if (audio.isFile) audio.delete()
        Unit
    }

    /** Repairs files and checkpoints left behind when capture was interrupted. */
    suspend fun recoverUnfinishedRecordings(): List<RecordingEntity> = withContext(Dispatchers.IO) {
        val unfinished = dao.getUnfinishedRecordings()
        val recovered = mutableListOf<RecordingEntity>()
        for (rec in unfinished) {
            val audioFile = File(rec.audioPath)
            val spoolFile = File(rec.asrSpoolPath)
            val spoolBytes = if (rec.asrSpoolPath != rec.audioPath && spoolFile.isFile) spoolFile.length() and -2L else 0L
            var durationMs = rec.durationMs
            if (audioFile.exists() && rec.audioFormatEnum != AudioFormat.M4A_48K) {
                val sampleRate = WavHelper.monoPcm16SampleRate(audioFile)
                    ?: if (rec.audioFormatEnum == AudioFormat.WAV_48K) 48_000 else 16_000
                val dataBytes = WavHelper.repairHeaderFromFileLength(audioFile, sampleRate, 1)
                if (dataBytes > 0) {
                    durationMs = (dataBytes * 1000L) / (sampleRate * 2L)
                }
            }
            durationMs = maxOf(durationMs, spoolBytes / 32L)

            val audioBytes = if (audioFile.isFile) audioFile.length() else 0L
            val hasAudio = if (rec.audioFormatEnum == AudioFormat.M4A_48K) {
                audioBytes > 0L
            } else audioBytes > WavHelper.HEADER_SIZE
            if (!hasAudio && spoolBytes == 0L) {
                discardEmptyCapture(rec)
                continue
            }
            val totalAsrBytes = maxOf(spoolBytes, rec.totalAsrBytes, durationMs * 32L)
            val paragraphs = dao.getParagraphs(rec.id)
            val (wordCount, preview) = summarizeTranscript(paragraphs)
            val fixed = rec.copy(
                status = RecordingStatus.INTERRUPTED.name,
                durationMs = durationMs,
                fileSizeBytes = audioBytes,
                totalAsrBytes = totalAsrBytes,
                processedAsrBytes = rec.processedAsrBytes.coerceIn(0L, totalAsrBytes),
                asrSpoolPath = if (spoolBytes > 0L || !hasAudio) rec.asrSpoolPath else rec.audioPath,
                hasTranscript = paragraphs.any { it.text.isNotBlank() },
                wordCount = wordCount,
                previewText = preview,
                updatedAtMs = System.currentTimeMillis()
            )
            dao.updateRecording(fixed)
            recovered.add(fixed)
        }
        recovered
    }

    /**
     * Cleans orphaned files inside isolated app storage:
     * - Completed .asr16k.pcm spools already fully transcribed
     * - Audio/temp files in recordingsDir not linked to any DB row
     * - Temporary re-transcribe PCM files and stale export cache files
     */
    suspend fun cleanupOrphanedFiles(modelManager: ModelManager) = withContext(Dispatchers.IO) {
        val allRecs = dao.getAllRecordingsList()
        val knownPaths = mutableSetOf<String>()
        for (r in allRecs) {
            if (r.audioPath.isNotBlank()) knownPaths += File(r.audioPath).absolutePath
            if (r.asrSpoolPath.isNotBlank()) {
                val spoolFile = File(r.asrSpoolPath)
                val isCompleted = r.statusEnum == RecordingStatus.COMPLETED
                val fullyProcessed = r.totalAsrBytes > 0L && !r.hasUnprocessedAudio
                val audio = File(r.audioPath)
                if (isCompleted && fullyProcessed && spoolFile.isFile && audio.isFile &&
                    audio.length() > WavHelper.HEADER_SIZE && r.asrSpoolPath != r.audioPath
                ) {
                    safeDeleteSpool(spoolFile, audio)
                    dao.updateRecording(r.copy(asrSpoolPath = ""))
                } else {
                    knownPaths += spoolFile.absolutePath
                }
            }
        }

        val now = System.currentTimeMillis()
        recordingsDir.listFiles()?.forEach { f ->
            if (f.absolutePath !in knownPaths && (now - f.lastModified()) > 15L * 60L * 1000L) {
                f.delete()
            }
        }

        val staleAfterMs = 12L * 3600L * 1000L
        temporaryWorkFiles().forEach { file ->
            if (now - file.lastModified() > staleAfterMs) file.delete()
        }
        exportsDir.listFiles()?.forEach { file ->
            if (now - file.lastModified() > staleAfterMs) file.deleteRecursively()
        }
        modelManager.cleanupOrphanedDownloads()
    }

    fun getRecordingsTotalBytes(): Long {
        return recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getExportsTotalBytes(): Long {
        return exportsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun getDatabaseTotalBytes(): Long {
        val dbDir = context.getDatabasePath("stenograf.db").parentFile ?: return 0L
        return dbDir.listFiles()
            ?.filter { it.name.startsWith("stenograf.db") }
            ?.sumOf { it.length() } ?: 0L
    }

    fun getCacheAndDownloadsBytes(modelManager: ModelManager): Long {
        val looseTemp = temporaryWorkFiles().sumOf { it.length() }
        return getExportsTotalBytes() + looseTemp + modelManager.getDownloadsCacheBytes()
    }

    private fun temporaryWorkFiles(): List<File> {
        val local = context.cacheDir.listFiles().orEmpty().filter { file ->
            file.isFile && (
                file.name.startsWith("export_") ||
                    (file.extension == "pcm" && listOf("retrans_", "stream_imp_", "stream_norm_", "audio-tools-")
                        .any(file.name::startsWith))
                )
        }
        return local + File(context.cacheDir, "audio_previews").listFiles().orEmpty().filter(File::isFile)
    }

    fun getStorageBreakdown(modelManager: ModelManager): StorageBreakdown {
        return StorageBreakdown(
            audioBytes = getRecordingsTotalBytes(),
            modelsBytes = modelManager.getModelsTotalBytes(),
            cacheAndDownloadsBytes = getCacheAndDownloadsBytes(modelManager),
            databaseBytes = getDatabaseTotalBytes(),
            freeDeviceBytes = getAvailableInternalStorageBytes()
        )
    }

    suspend fun clearTemporaryCache(modelManager: ModelManager) = withContext(Dispatchers.IO) {
        exportsDir.listFiles()?.forEach { it.deleteRecursively() }
        File(context.cacheDir, "waveform_peaks").listFiles()?.forEach { it.delete() }
        modelManager.clearDownloadCache()
    }

    suspend fun resetAllAppData(modelManager: ModelManager, preferences: AppPreferences) = withContext(Dispatchers.IO) {
        modelManager.wipeAllModelsAndDownloads()
        dao.deleteAllParagraphs()
        dao.deleteAllRecordings()
        dao.deleteAllFolders()
        dao.deleteAllSpeakerProfiles()
        voiceprintsDir.listFiles()?.forEach { it.delete() }
        recordingsDir.listFiles()?.forEach { it.delete() }
        exportsDir.listFiles()?.forEach { it.deleteRecursively() }
        File(context.cacheDir, "waveform_peaks").listFiles()?.forEach { it.delete() }
        temporaryWorkFiles().forEach { it.delete() }
        preferences.clearAll()
        AppLocaleManager.applyPreference("system")
        ensureDefaultFolders()
    }

    fun getAvailableInternalStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Throwable) {
            Long.MAX_VALUE
        }
    }
}
