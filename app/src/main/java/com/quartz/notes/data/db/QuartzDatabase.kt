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
    version = 4,
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()

        /** Adds page lock and two-page (spread) flags. */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN spread INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Adds the per-notebook two-page view switch (on by default). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN twoPage INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Sections, notebook index/tabs/attachment options, and archived quick notes. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN isSection INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pages ADD COLUMN sectionIcon TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN sectionImage TEXT")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN showIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN sectionTabs INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN attachAtBottom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE quick_notes ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
