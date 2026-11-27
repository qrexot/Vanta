package com.stenograf.app.data.repo

import android.content.Context
import android.os.StatFs
import com.stenograf.app.asr.AsrJsonParser
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.asr.TranscriptWord
import com.stenograf.app.audio.WavHelper
import com.stenograf.app.data.db.RecordingBookmarkCount
import com.stenograf.app.data.db.StenografDao
import com.stenograf.app.data.model.AudioFormat
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.RecordingStatus
import com.stenograf.app.data.prefs.AppPreferences
import com.stenograf.app.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val dao: StenografDao
) {
    val recordingsDir: File = File(context.filesDir, "recordings").apply { mkdirs() }
    val exportsDir: File = File(context.cacheDir, "exports").apply { mkdirs() }

    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    fun observeRecordings(folderId: Long?, query: String): Flow<List<RecordingEntity>> =
        dao.observeFilteredRecordings(folderId, query.trim())

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

    suspend fun renameFolder(folder: FolderEntity, newName: String) {
        val clean = newName.trim()
        if (clean.isNotEmpty()) {
            dao.updateFolder(folder.copy(name = clean))
        }
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
        dao.updateParagraph(
            paragraph.copy(
                isBookmark = nextBookmark,
                bookmarkLabel = resolvedLabel
            )
        )
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        dao.clearFolderFromRecordings(folder.id)
        dao.deleteFolder(folder)
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
        val audioFile = File(recordingsDir, "rec_$stamp.${format.extension}")
        val spoolFile = File(recordingsDir, "rec_$stamp.asr16k.pcm")

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
            totalAsrBytes = if (asrSpoolFile == audioFile && format == AudioFormat.WAV_16K) {
                (asrSpoolFile.length() - WavHelper.HEADER_SIZE).coerceAtLeast(0L)
            } else asrSpoolFile.length(),
            processedAsrBytes = 0L,
            fileSizeBytes = audioFile.length()
        )
        val id = dao.insertRecording(rec)
        rec.copy(id = id)
    }

    suspend fun updateRecording(recording: RecordingEntity) {
        dao.updateRecording(recording)
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

    suspend fun updateParagraphTextAndRefreshSummary(paragraph: ParagraphEntity, newText: String) {
        dao.updateParagraph(paragraphWithEditedText(paragraph, newText))
        refreshRecordingTranscriptSummary(paragraph.recordingId)
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
        val changed = dao.updateParagraphIfUnchanged(
            paragraph.id, paragraph.recordingId, paragraph.text, updated.text, updated.wordsJson
        ) > 0
        if (changed) refreshRecordingTranscriptSummary(paragraph.recordingId)
        return changed
    }

    internal suspend fun applyFullAudioReview(
        recordingId: Long,
        original: List<ParagraphEntity>,
        reviewed: List<ParagraphEntity>,
        processedAudioBytes: Long?,
        detectedLanguage: String? = null
    ): Boolean {
        if (reviewed.isEmpty()) return false
        val markers = original.filter { it.isBookmark }.map {
            it.copy(endSec = it.startSec, text = "", wordsJson = "", speaker = null)
        }
        val merged = (reviewed + markers).sortedBy { it.startSec }
            .mapIndexed { index, paragraph -> paragraph.copy(orderIndex = index) }
        val preview = reviewed.asSequence().map { it.text }.take(20).joinToString(" ").take(220)
        val count = reviewed.sumOf { it.text.split(Regex("\\s+")).count(String::isNotBlank) }
        return dao.replaceReviewedAudio(recordingId, original, merged, count, preview, processedAudioBytes, detectedLanguage)
    }

    internal fun paragraphWithEditedText(
        paragraph: ParagraphEntity,
        newText: String,
        preserveWordTimings: Boolean = true
    ): ParagraphEntity = buildEditedParagraph(paragraph, newText, preserveWordTimings)

    companion object {
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
        val rec = dao.getRecording(recordingId) ?: return
        val paragraphs = dao.getParagraphs(recordingId)
        val wordCount = paragraphs.sumOf { p ->
            p.text.split(Regex("\\s+")).count { it.isNotBlank() }
        }
        val preview = paragraphs.joinToString(" ") { it.text }.take(220)
        dao.updateRecording(
            rec.copy(
                hasTranscript = paragraphs.isNotEmpty(),
                wordCount = wordCount,
                previewText = preview,
                updatedAtMs = System.currentTimeMillis()
            )
        )
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
        dao.deleteRecordingById(recording.id)
        if (recording.audioPath.isNotBlank()) File(recording.audioPath).delete()
        if (recording.asrSpoolPath.isNotBlank()) File(recording.asrSpoolPath).delete()
    }

    /**
     * Repairs recordings that were active when the OS or battery killer terminated the process.
     */
    suspend fun recoverUnfinishedRecordings(): List<RecordingEntity> = withContext(Dispatchers.IO) {
        val unfinished = dao.getUnfinishedRecordings()
        val recovered = mutableListOf<RecordingEntity>()
        for (rec in unfinished) {
            val audioFile = File(rec.audioPath)
            val spoolFile = File(rec.asrSpoolPath)
            var durationMs = rec.durationMs
            if (audioFile.exists() && rec.audioFormatEnum != AudioFormat.M4A_48K) {
                val sampleRate = if (rec.audioFormatEnum == AudioFormat.WAV_48K) 48_000 else 16_000
                val dataBytes = WavHelper.repairHeaderFromFileLength(audioFile, sampleRate, 1)
                if (dataBytes > 0) {
                    durationMs = (dataBytes * 1000L) / (sampleRate * 2L)
                }
            } else if (spoolFile.exists() && spoolFile.length() > 0) {
                durationMs = (spoolFile.length() * 1000L) / (16_000L * 2L)
            }

            val paragraphs = dao.getParagraphs(rec.id)
            val wordCount = paragraphs.sumOf { p -> p.text.split(Regex("\\s+")).count { it.isNotBlank() } }
            val preview = paragraphs.joinToString(" ") { it.text }.take(220)
            val fixed = rec.copy(
                status = RecordingStatus.INTERRUPTED.name,
                durationMs = durationMs,
                fileSizeBytes = if (audioFile.exists()) audioFile.length() else 0L,
                totalAsrBytes = if (spoolFile.exists()) spoolFile.length() else rec.totalAsrBytes,
                hasTranscript = paragraphs.isNotEmpty(),
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
                val isCompleted = r.statusEnum == RecordingStatus.COMPLETED || r.statusEnum == RecordingStatus.INTERRUPTED
                val fullyProcessed = r.processedAsrBytes >= (r.totalAsrBytes - 3200L).coerceAtLeast(0L)
                if (isCompleted && fullyProcessed && spoolFile.exists() && r.asrSpoolPath != r.audioPath) {
                    spoolFile.delete()
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

        // Remove leftover re-transcribe PCM temp files & stale cached exports (> 12h old)
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("retrans_") && f.name.endsWith(".pcm")) {
                f.delete()
            }
        }
        exportsDir.listFiles()?.forEach { f ->
            if (now - f.lastModified() > 12L * 3600L * 1000L) {
                f.delete()
            }
        }
        modelManager.cleanupOrphanedDownloads()
    }

    fun getRecordingsTotalBytes(): Long {
        return recordingsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getExportsTotalBytes(): Long {
        return exportsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun getDatabaseTotalBytes(): Long {
        val dbDir = context.getDatabasePath("stenograf.db").parentFile ?: return 0L
        return dbDir.listFiles()
            ?.filter { it.name.startsWith("stenograf.db") }
            ?.sumOf { it.length() } ?: 0L
    }

    fun getCacheAndDownloadsBytes(modelManager: ModelManager): Long {
        val looseTemp = context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("retrans_") }
            ?.sumOf { it.length() } ?: 0L
        return getExportsTotalBytes() + looseTemp + modelManager.getDownloadsCacheBytes()
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
        exportsDir.listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "waveform_peaks").listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("retrans_")) f.delete()
        }
        modelManager.clearDownloadCache()
    }

    suspend fun resetAllAppData(modelManager: ModelManager, preferences: AppPreferences) = withContext(Dispatchers.IO) {
        dao.deleteAllParagraphs()
        dao.deleteAllRecordings()
        dao.deleteAllFolders()
        recordingsDir.listFiles()?.forEach { it.delete() }
        exportsDir.listFiles()?.forEach { it.delete() }
        File(context.cacheDir, "waveform_peaks").listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("retrans_")) f.delete()
        }
        modelManager.wipeAllModelsAndDownloads()
        preferences.clearAll()
        AppLocaleManager.applyPreference("system")
        ensureDefaultFolders()
    }

    fun clearExportsCache() {
        exportsDir.listFiles()?.forEach { it.delete() }
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
