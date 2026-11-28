package com.stenograf.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.model.SpeakerProfileEntity
import kotlinx.coroutines.flow.Flow

data class RecordingBookmarkCount(
    val recordingId: Long,
    val bookmarkCount: Int
)

@Dao
interface StenografDao {
    // --- Folders ---
    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, createdAtMs ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, createdAtMs ASC")
    suspend fun getAllFolders(): List<FolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: FolderEntity): Long

    @Update
    suspend fun updateFolder(folder: FolderEntity)

    @Query("UPDATE recordings SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderFromRecordings(folderId: Long)

    @Delete
    suspend fun deleteFolder(folder: FolderEntity)

    @Transaction
    suspend fun deleteFolderAndKeepRecordings(folder: FolderEntity) {
        clearFolderFromRecordings(folder.id)
        deleteFolder(folder)
    }

    // --- Recordings ---
    @Query(
        """
        SELECT r.* FROM recordings r
        WHERE (:folderId IS NULL OR (:folderId = -1 AND r.folderId IS NULL) OR r.folderId = :folderId)
          AND (:query = '' OR r.title LIKE '%' || :query || '%' ESCAPE '!'
               OR r.previewText LIKE '%' || :query || '%' ESCAPE '!'
               OR EXISTS (
                   SELECT 1 FROM paragraphs p
                   WHERE p.recordingId = r.id AND p.text LIKE '%' || :query || '%' ESCAPE '!'
               ))
        ORDER BY r.createdAtMs DESC, r.id DESC
        """
    )
    fun observeFilteredRecordings(folderId: Long?, query: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observeRecording(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecording(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE status IN ('RECORDING', 'PAUSED')")
    suspend fun getUnfinishedRecordings(): List<RecordingEntity>

    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: Long)

    @Query("UPDATE recordings SET title = :title, updatedAtMs = :now WHERE id = :id")
    suspend fun renameRecording(id: Long, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET folderId = :folderId, updatedAtMs = :now WHERE id = :id")
    suspend fun moveRecordingToFolder(id: Long, folderId: Long?, now: Long = System.currentTimeMillis())

    @Query("UPDATE recordings SET durationMs = :durationMs, fileSizeBytes = :fileSizeBytes, totalAsrBytes = :totalAsrBytes, processedAsrBytes = MAX(processedAsrBytes, :processedAsrBytes), detectedLanguage = :detectedLang, updatedAtMs = :now WHERE id = :id AND status IN ('RECORDING', 'PAUSED')")
    suspend fun updateRecordingProgress(
        id: Long,
        durationMs: Long,
        fileSizeBytes: Long,
        totalAsrBytes: Long,
        processedAsrBytes: Long,
        detectedLang: String,
        now: Long = System.currentTimeMillis()
    )

    // --- Paragraphs ---
    @Query("SELECT * FROM paragraphs WHERE recordingId = :recordingId ORDER BY orderIndex ASC, id ASC")
    fun observeParagraphs(recordingId: Long): Flow<List<ParagraphEntity>>

    @Query("SELECT recordingId, COUNT(*) AS bookmarkCount FROM paragraphs WHERE isBookmark = 1 GROUP BY recordingId")
    fun observeBookmarkCounts(): Flow<List<RecordingBookmarkCount>>

    @Query("SELECT * FROM paragraphs WHERE recordingId = :recordingId ORDER BY orderIndex ASC, id ASC")
    suspend fun getParagraphs(recordingId: Long): List<ParagraphEntity>

    @Insert
    suspend fun insertParagraph(paragraph: ParagraphEntity): Long

    @Insert
    suspend fun insertLiveParagraphs(paragraphs: List<ParagraphEntity>): List<Long>

    @Query("UPDATE recordings SET processedAsrBytes = MAX(processedAsrBytes, :processedBytes) WHERE id = :recordingId")
    suspend fun advanceTranscriptCheckpoint(recordingId: Long, processedBytes: Long)

    @Transaction
    suspend fun commitLiveTranscript(
        recordingId: Long,
        paragraphs: List<ParagraphEntity>,
        processedBytes: Long,
        updatedLast: ParagraphEntity?
    ): List<Long> {
        if (updatedLast != null) updateParagraph(updatedLast)
        val ids = if (paragraphs.isEmpty()) emptyList() else insertLiveParagraphs(paragraphs)
        advanceTranscriptCheckpoint(recordingId, processedBytes)
        return ids
    }

    @Insert
    suspend fun insertParagraphs(paragraphs: List<ParagraphEntity>)

    @Update
    suspend fun updateParagraph(paragraph: ParagraphEntity)

    @Query(
        "UPDATE paragraphs SET text = :newText, wordsJson = :wordsJson " +
            "WHERE id = :paragraphId AND recordingId = :recordingId AND text = :originalText"
    )
    suspend fun updateParagraphIfUnchanged(
        paragraphId: Long,
        recordingId: Long,
        originalText: String,
        newText: String,
        wordsJson: String
    ): Int

    @Query("UPDATE paragraphs SET isBookmark = :marked, bookmarkLabel = :label WHERE id = :paragraphId")
    suspend fun updateParagraphBookmark(paragraphId: Long, marked: Boolean, label: String?)

    @Query(
        "UPDATE recordings SET hasTranscript = :hasTranscript, wordCount = :wordCount, " +
            "previewText = :previewText, updatedAtMs = :now WHERE id = :recordingId"
    )
    suspend fun updateTranscriptSummary(
        recordingId: Long,
        hasTranscript: Boolean,
        wordCount: Int,
        previewText: String,
        now: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM paragraphs WHERE recordingId = :recordingId")
    suspend fun deleteParagraphsForRecording(recordingId: Long)

    @Query("SELECT * FROM recordings")
    suspend fun getAllRecordingsList(): List<RecordingEntity>

    @Query("DELETE FROM paragraphs")
    suspend fun deleteAllParagraphs()

    @Query("DELETE FROM recordings")
    suspend fun deleteAllRecordings()

    @Query("DELETE FROM folders")
    suspend fun deleteAllFolders()

    // --- Speaker Profiles (Voiceprints) ---
    @Query("SELECT * FROM speaker_profiles ORDER BY isAutoLearned ASC, updatedAtMs DESC")
    fun observeSpeakerProfiles(): Flow<List<SpeakerProfileEntity>>

    @Query("SELECT * FROM speaker_profiles ORDER BY isAutoLearned ASC, updatedAtMs DESC")
    suspend fun getAllSpeakerProfiles(): List<SpeakerProfileEntity>

    @Query("SELECT * FROM speaker_profiles WHERE id = :id")
    suspend fun getSpeakerProfile(id: Long): SpeakerProfileEntity?

    @Query("SELECT * FROM speaker_profiles WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findSpeakerProfileByName(name: String): SpeakerProfileEntity?

    @Insert
    suspend fun insertSpeakerProfile(profile: SpeakerProfileEntity): Long

    @Update
    suspend fun updateSpeakerProfile(profile: SpeakerProfileEntity)

    @Query("DELETE FROM speaker_profiles WHERE id = :id")
    suspend fun deleteSpeakerProfile(id: Long)

    @Query("DELETE FROM speaker_profiles")
    suspend fun deleteAllSpeakerProfiles()

    @Query("UPDATE paragraphs SET speaker = :speaker WHERE id = :paragraphId")
    suspend fun updateParagraphSpeaker(paragraphId: Long, speaker: String?)

    @Query("UPDATE paragraphs SET speaker = :newSpeaker WHERE recordingId = :recordingId AND speaker = :oldSpeaker")
    suspend fun renameRecordingSpeakerLabel(recordingId: Long, oldSpeaker: String, newSpeaker: String)

    @Query("UPDATE paragraphs SET speaker = :newSpeaker WHERE speaker = :oldSpeaker")
    suspend fun renameSpeakerAcrossAllRecordings(oldSpeaker: String, newSpeaker: String)

    @Query(
        "SELECT * FROM paragraphs WHERE speaker = :speakerName " +
            "ORDER BY CASE WHEN LENGTH(text) >= 18 THEN 0 ELSE 1 END, (endSec - startSec) DESC LIMIT 1"
    )
    suspend fun findRepresentativeParagraphForSpeaker(speakerName: String): ParagraphEntity?

    @Query(
        "SELECT * FROM paragraphs WHERE recordingId = :recordingId AND speaker = :speakerName " +
            "ORDER BY CASE WHEN LENGTH(text) >= 18 THEN 0 ELSE 1 END, (endSec - startSec) DESC LIMIT 1"
    )
    suspend fun findBestParagraphForSpeakerInRecording(recordingId: Long, speakerName: String): ParagraphEntity?

    @Transaction
    suspend fun commitOfflineTranscript(
        expected: RecordingEntity,
        original: List<ParagraphEntity>,
        replacement: List<ParagraphEntity>,
        wordCount: Int,
        preview: String,
        modelId: String,
        presetId: String,
        language: String,
        processedBytes: Long,
        completedAudioBytes: Long?
    ): Pair<RecordingEntity, List<ParagraphEntity>>? {
        val current = getRecording(expected.id) ?: return null
        if (current.statusEnum == RecordingStatus.RECORDING || current.statusEnum == RecordingStatus.PAUSED ||
            current.audioPath != expected.audioPath || current.processedAsrBytes != expected.processedAsrBytes ||
            getParagraphs(expected.id) != original
        ) return null

        deleteParagraphsForRecording(current.id)
        insertParagraphs(replacement)
        val totalBytes = completedAudioBytes ?: maxOf(current.totalAsrBytes, processedBytes)
        val updated = current.copy(
            modelId = modelId, languagePresetId = presetId, detectedLanguage = language,
            hasTranscript = replacement.any { it.text.isNotBlank() },
            wordCount = wordCount, previewText = preview, updatedAtMs = System.currentTimeMillis(),
            durationMs = if (current.durationMs <= 0L) totalBytes / 32 else current.durationMs,
            totalAsrBytes = totalBytes, processedAsrBytes = processedBytes,
            asrSpoolPath = if (completedAudioBytes != null) "" else current.asrSpoolPath.ifBlank { current.audioPath }
        )
        updateRecording(updated)
        return updated to getParagraphs(current.id)
    }

    @Transaction
    suspend fun insertCoverageResult(
        recordingId: Long,
        audioPath: String,
        gapEndMs: Long,
        original: List<ParagraphEntity>,
        planned: List<ParagraphEntity>,
        wordCount: Int,
        preview: String
    ): Boolean {
        val recording = getRecording(recordingId) ?: return false
        if (recording.audioPath != audioPath ||
            recording.statusEnum == RecordingStatus.RECORDING ||
            recording.statusEnum == RecordingStatus.PAUSED ||
            (recording.hasUnprocessedAudio && gapEndMs > recording.processedAsrBytes / 32)
        ) return false
        if (getParagraphs(recordingId) != original) return false
        val previousById = original.associateBy { it.id }
        for (paragraph in planned) {
            if (paragraph.id == 0L) insertParagraph(paragraph)
            else if (previousById[paragraph.id] != paragraph) updateParagraph(paragraph)
        }
        updateRecording(recording.copy(
            hasTranscript = true, wordCount = wordCount, previewText = preview,
            updatedAtMs = System.currentTimeMillis()
        ))
        return true
    }

    @Transaction
    suspend fun replaceReviewedAudio(
        recordingId: Long,
        original: List<ParagraphEntity>,
        reviewed: List<ParagraphEntity>,
        wordCount: Int,
        preview: String,
        processedAudioBytes: Long?,
        detectedLang: String? = null
    ): Boolean {
        val recording = getRecording(recordingId) ?: return false
        if (recording.statusEnum in setOf(RecordingStatus.RECORDING, RecordingStatus.PAUSED)) return false
        if (getParagraphs(recordingId) != original) return false
        replaceTranscript(
            recordingId, reviewed, wordCount, preview,
            detectedLang?.takeIf { it.isNotBlank() } ?: recording.detectedLanguage,
            processedAsrBytes = processedAudioBytes?.coerceAtMost(recording.totalAsrBytes)
        )
        return true
    }

    @Transaction
    suspend fun replaceTranscript(
        recordingId: Long,
        paragraphs: List<ParagraphEntity>,
        wordCount: Int,
        previewText: String,
        detectedLang: String,
        modelId: String? = null,
        processedAsrBytes: Long? = null
    ) {
        val rec = getRecording(recordingId) ?: return
        deleteParagraphsForRecording(recordingId)
        insertParagraphs(paragraphs)
        updateRecording(
            rec.copy(
                hasTranscript = paragraphs.isNotEmpty(),
                wordCount = wordCount,
                previewText = previewText,
                detectedLanguage = detectedLang,
                modelId = modelId ?: rec.modelId,
                processedAsrBytes = processedAsrBytes ?: rec.processedAsrBytes,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }
}
