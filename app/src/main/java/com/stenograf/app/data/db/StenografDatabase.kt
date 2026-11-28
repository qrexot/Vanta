package com.stenograf.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stenograf.app.data.model.FolderEntity
import com.stenograf.app.data.model.ParagraphEntity
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.model.SpeakerProfileEntity

@Database(
    entities = [
        FolderEntity::class,
        RecordingEntity::class,
        ParagraphEntity::class,
        SpeakerProfileEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class StenografDatabase : RoomDatabase() {
    abstract fun dao(): StenografDao

    companion object {
        @Volatile
        private var INSTANCE: StenografDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE paragraphs ADD COLUMN speaker TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `speaker_profiles` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `embeddingJson` TEXT NOT NULL,
                        `prototypesJson` TEXT NOT NULL,
                        `sampleCount` INTEGER NOT NULL,
                        `isAutoLearned` INTEGER NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_speaker_profiles_name` ON `speaker_profiles` (`name`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_speaker_profiles_updatedAtMs` ON `speaker_profiles` (`updatedAtMs`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN tagOrPhone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN sampleAudioPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN sampleTranscript TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN sampleSourceTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN sampleRecordingId INTEGER")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN sampleStartSec REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE speaker_profiles ADD COLUMN speechSeconds REAL NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): StenografDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StenografDatabase::class.java,
                    "stenograf.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
