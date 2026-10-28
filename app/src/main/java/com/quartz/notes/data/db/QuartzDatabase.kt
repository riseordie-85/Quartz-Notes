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
        SyncStateEntity::class,
        SyncFlagsEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class QuartzDatabase : RoomDatabase() {
    abstract fun shelfDao(): ShelfDao
    abstract fun notebookDao(): NotebookDao
    abstract fun pageDao(): PageDao
    abstract fun quickNoteDao(): QuickNoteDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun pageElementDao(): PageElementDao
    abstract fun syncDao(): SyncDao

    companion object {
        /** The app's database. A null [name] makes a throwaway in-memory one (for tests). */
        fun build(context: Context, name: String? = "quartz_notes.db"): QuartzDatabase =
            (
                if (name == null) Room.inMemoryDatabaseBuilder(context, QuartzDatabase::class.java)
                else Room.databaseBuilder(context, QuartzDatabase::class.java, name)
                )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        SyncTriggers.install(db)
                        db.execSQL(
                            "INSERT INTO shelves (name, position, createdAt, uid, deleted) " +
                                "VALUES ('Notebooks', 0, ${System.currentTimeMillis()}, '${newUid()}', 0)",
                        )
                    }
                })
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        val ALL_MIGRATIONS get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

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

        /**
         * Sync groundwork: a permanent id and a deleted marker on every synced row, plus the sync
         * bookkeeping tables and triggers. Every existing row starts out marked for upload.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_state` (`tbl` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                        "`dirty` INTEGER NOT NULL, `rev` INTEGER NOT NULL, `changedAt` INTEGER NOT NULL, PRIMARY KEY(`tbl`, `uid`))",
                )
                db.execSQL("CREATE TABLE IF NOT EXISTS `sync_flags` (`id` INTEGER NOT NULL, `mode` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                for (t in SyncTriggers.TABLES) {
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$t` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
                    // randomblob() runs once per row, so every row gets its own id.
                    db.execSQL("UPDATE `$t` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${t}_uid` ON `$t` (`uid`)")
                    db.execSQL(
                        "INSERT OR IGNORE INTO sync_state (tbl, uid, dirty, rev, changedAt) " +
                            "SELECT '$t', uid, 1, 0, ${SyncTriggers.NOW_MS} FROM `$t`",
                    )
                }
                SyncTriggers.install(db)
            }
        }
    }
}
