package com.quartz.notes.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Database triggers that keep [SyncStateEntity] in step with the synced tables, whatever code path
 * writes to them:
 *
 * - inserting or changing a row marks it dirty (unless the sync engine is applying server changes);
 * - erasing a row removes its bookkeeping;
 * - a deleted row can't be brought back by an ordinary save (an old copy of the row written back
 *   after another device deleted it), only by the sync engine or a backup restore.
 */
object SyncTriggers {
    /** Every synced table. Parents come before children (the order uploads and downloads use). */
    val TABLES = listOf("shelves", "notebooks", "pages", "page_elements", "quick_notes", "attachments")

    /** The current time in ms, in SQL. */
    const val NOW_MS = "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)"

    private const val MODE = "(SELECT mode FROM sync_flags WHERE id = 1)"

    fun install(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO sync_flags (id, mode) VALUES (1, 0)")
        for (t in TABLES) {
            // No "OR IGNORE" here: a trigger's conflict handling is replaced by that of the statement
            // that fired it (Room's whole-row saves use OR ABORT), so check for the row instead.
            val mark = "INSERT INTO sync_state (tbl, uid, dirty, rev, changedAt) SELECT '$t', NEW.uid, 1, 0, $NOW_MS " +
                "WHERE NOT EXISTS (SELECT 1 FROM sync_state WHERE tbl = '$t' AND uid = NEW.uid); " +
                "UPDATE sync_state SET dirty = 1, changedAt = $NOW_MS WHERE tbl = '$t' AND uid = NEW.uid;"
            db.execSQL("DROP TRIGGER IF EXISTS sync_${t}_insert")
            db.execSQL("CREATE TRIGGER sync_${t}_insert AFTER INSERT ON $t WHEN COALESCE($MODE, 0) != 1 BEGIN $mark END")
            db.execSQL("DROP TRIGGER IF EXISTS sync_${t}_update")
            db.execSQL("CREATE TRIGGER sync_${t}_update AFTER UPDATE ON $t WHEN COALESCE($MODE, 0) != 1 BEGIN $mark END")
            db.execSQL("DROP TRIGGER IF EXISTS sync_${t}_erase")
            db.execSQL("CREATE TRIGGER sync_${t}_erase AFTER DELETE ON $t BEGIN DELETE FROM sync_state WHERE tbl = '$t' AND uid = OLD.uid; END")
            db.execSQL("DROP TRIGGER IF EXISTS sync_${t}_keep_deleted")
            db.execSQL(
                "CREATE TRIGGER sync_${t}_keep_deleted BEFORE UPDATE ON $t " +
                    "WHEN OLD.deleted = 1 AND NEW.deleted = 0 AND COALESCE($MODE, 0) = 0 BEGIN SELECT RAISE(IGNORE); END",
            )
        }
    }
}
