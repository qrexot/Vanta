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

    // --- Recordings ---
    @Query("SELECT * FROM recordings ORDER BY createdAtMs DESC")
    fun observeAllRecordings(): Flow<List<RecordingEntity>>

    @Query(
        """
        SELECT DISTINCT r.* FROM recordings r
        LEFT JOIN paragraphs p ON p.recordingId = r.id
        WHERE (:folderId IS NULL OR (:folderId = -1 AND r.folderId IS NULL) OR r.folderId = :folderId)
          AND (:query = '' OR r.title LIKE '%' || :query || '%' OR r.previewText LIKE '%' || :query || '%' OR p.text LIKE '%' || :query || '%')
        ORDER BY r.createdAtMs DESC
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

    @Query("DELETE FROM paragraphs WHERE id = :paragraphId")
    suspend fun deleteParagraphById(paragraphId: Long)

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
        deleteParagraphsForRecording(recordingId)
        insertParagraphs(paragraphs)
        val rec = getRecording(recordingId) ?: return
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
