package com.quartz.notes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ShelfEntity::class,
        NotebookEntity::class,
        PageEntity::class,
        PageElementEntity::class,
        QuickNoteEntity::class,
        AttachmentEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class QuartzDatabase : RoomDatabase() {
    abstract fun shelfDao(): ShelfDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pageElementDao(): PageElementDao

    companion object {
        fun build(context: Context): QuartzDatabase =
            Room.databaseBuilder(context, QuartzDatabase::class.java, "quartz_notes.db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL(
                            "INSERT INTO shelves (name, position, createdAt) VALUES ('Notebooks', 0, ${System.currentTimeMillis()})",
                        )
                    }
                })
                .addMigrations(MIGRATION_1_2)
                .build()

        /** Adds page lock and two-page (spread) flags. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN spread INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
