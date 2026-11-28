package com.stenograf.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.stenograf.app.i18n.AppStrings

enum class RecordingStatus {
    RECORDING,
    PAUSED,
    COMPLETED,
    INTERRUPTED
}

enum class AudioFormat(val extension: String) {
    WAV_16K("wav"),
    M4A_48K("m4a"),
    WAV_48K("wav");

    fun label(strings: AppStrings): String = when (this) {
        WAV_16K -> strings.formatWav16k
        M4A_48K -> strings.formatM4a48k
        WAV_48K -> strings.formatWav48k
    }
}

enum class RecordingSort {
    DATE_DESC,
    DATE_ASC,
    DURATION_DESC,
    TITLE_ASC
}

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorHex: String = "#3B82F6",
    val sortOrder: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "recordings",
    indices = [Index("folderId"), Index("createdAtMs"), Index("status")]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val audioPath: String = "",
    val asrSpoolPath: String = "",
    val audioFormat: String = AudioFormat.WAV_16K.name,
    val modelId: String = "en-ru-uk",
    val languagePresetId: String = "ru_en",
    val detectedLanguage: String = "ru",
    val folderId: Long? = null,
    val status: String = RecordingStatus.RECORDING.name,
    val hasTranscript: Boolean = false,
    val processedAsrBytes: Long = 0L,
    val totalAsrBytes: Long = 0L,
    val wordCount: Int = 0,
    val previewText: String = "",
    val fileSizeBytes: Long = 0L
) {
    val statusEnum: RecordingStatus
        get() = runCatching { RecordingStatus.valueOf(status) }.getOrDefault(RecordingStatus.COMPLETED)

    val audioFormatEnum: AudioFormat
        get() = runCatching { AudioFormat.valueOf(audioFormat) }.getOrDefault(AudioFormat.WAV_16K)

    val hasUnprocessedAudio: Boolean
        get() = totalAsrBytes > processedAsrBytes + 3_200L && asrSpoolPath.isNotBlank()
}

@Entity(
    tableName = "paragraphs",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId"), Index("orderIndex")]
)
data class ParagraphEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val recordingId: Long,
    val orderIndex: Int,
    val startSec: Float,
    val endSec: Float,
    val text: String,
    val wordsJson: String,
    val isBookmark: Boolean = false,
    val bookmarkLabel: String? = null,
    val speaker: String? = null
)

@Entity(
    tableName = "speaker_profiles",
    indices = [Index("name"), Index("updatedAtMs")]
)
data class SpeakerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val embeddingJson: String,
    val prototypesJson: String = "[]",
    val sampleCount: Int = 1,
    val isAutoLearned: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val tagOrPhone: String = "",
    val sampleAudioPath: String = "",
    val sampleTranscript: String = "",
    val sampleSourceTitle: String = "",
    val sampleRecordingId: Long? = null,
    val sampleStartSec: Float = 0f,
    val speechSeconds: Float = 0f
)
