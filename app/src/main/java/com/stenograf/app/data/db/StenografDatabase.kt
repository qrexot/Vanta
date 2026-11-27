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

@Database(
    entities = [FolderEntity::class, RecordingEntity::class, ParagraphEntity::class],
    version = 2,
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

        fun getInstance(context: Context): StenografDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StenografDatabase::class.java,
                    "stenograf.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
