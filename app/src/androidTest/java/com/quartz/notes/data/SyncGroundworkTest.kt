package com.quartz.notes.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sync groundwork: permanent ids, change tracking, deleted markers, and the 4 → 5 migration. */
@RunWith(AndroidJUnit4::class)
class SyncGroundworkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, QuartzDatabase::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = QuartzDatabase.build(context, name = null)
    private val repo = QuartzRepository(db, scope)
    private val raw: SupportSQLiteDatabase get() = db.openHelper.writableDatabase

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    // ---- Helpers ----

    private fun long(sql: String, vararg args: Any?): Long =
        raw.query(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    private fun string(sql: String, vararg args: Any?): String? =
        raw.query(sql, args).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun dirty(tbl: String, uid: String): Long = long("SELECT dirty FROM sync_state WHERE tbl = ? AND uid = ?", tbl, uid)

    /** Pretends the sync engine uploaded the row: it's clean and the server has it at revision [rev]. */
    private fun markSynced(tbl: String, uid: String, rev: Long = 1) {
        raw.execSQL("UPDATE sync_state SET dirty = 0, rev = ? WHERE tbl = ? AND uid = ?", arrayOf(rev, tbl, uid))
    }

    private fun waitForQueue() = runBlocking { repo.getNote(-1) }

    // ---- Migration ----

    @Test
    fun migration4To5GivesEveryRowAnIdAndMarksItForUpload() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 4).apply {
            fun insert(table: String, vararg pairs: Pair<String, Any?>): Long {
                val values = ContentValues()
                for ((k, v) in pairs) when (v) {
                    null -> values.putNull(k)
                    is Int -> values.put(k, v)
                    is Long -> values.put(k, v)
                    is Float -> values.put(k, v)
                    is String -> values.put(k, v)
                    else -> error("unsupported $v")
                }
                return insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            val shelf = insert("shelves", "name" to "Notebooks", "position" to 0, "createdAt" to 1L)
            val notebook = insert(
                "notebooks", "shelfId" to shelf, "title" to "Travel", "colorArgb" to 1, "texture" to "GLOSS", "icon" to "none",
                "position" to 0, "createdAt" to 1L, "updatedAt" to 1L, "twoPage" to 1, "showIndex" to 0, "sectionTabs" to 1,
                "attachAtBottom" to 0,
            )
            repeat(3) { i ->
                val page = insert(
                    "pages", "notebookId" to notebook, "title" to "Page $i", "position" to i, "pageType" to "LINES",
                    "backgroundArgb" to null, "texture" to "NONE", "layoutMode" to "INLINE", "content" to "", "plainText" to "",
                    "createdAt" to 1L, "updatedAt" to 1L, "locked" to 0, "spread" to 0, "isSection" to 0,
                    "sectionIcon" to null, "sectionImage" to null,
                )
                insert(
                    "page_elements", "pageId" to page, "kind" to "SHAPE", "x" to 0f, "y" to 0f, "width" to 1f, "height" to 1f,
                    "rotation" to 0f, "z" to 1, "data" to "", "createdAt" to 1L,
                )
            }
            val note = insert(
                "quick_notes", "title" to "Groceries", "content" to "", "plainText" to "milk", "colorKey" to "default",
                "pinned" to 0, "createdAt" to 1L, "updatedAt" to 1L, "archived" to 0,
            )
            insert("attachments", "ownerType" to "NOTE", "ownerId" to note, "kind" to "IMAGE", "path" to "media/a.jpg", "createdAt" to 1L)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(name, 5, true, QuartzDatabase.MIGRATION_4_5)
        fun count(sql: String) = migrated.query(sql).use { it.moveToFirst(); it.getLong(0) }
        val expected = mapOf("shelves" to 1L, "notebooks" to 1L, "pages" to 3L, "page_elements" to 3L, "quick_notes" to 1L, "attachments" to 1L)
        for ((table, rows) in expected) {
            assertEquals("$table rows kept", rows, count("SELECT COUNT(*) FROM $table"))
            assertEquals("$table ids filled in", rows, count("SELECT COUNT(DISTINCT uid) FROM $table WHERE length(uid) = 32"))
            assertEquals("$table not deleted", 0L, count("SELECT COUNT(*) FROM $table WHERE deleted = 1"))
            assertEquals("$table marked for upload", rows, count("SELECT COUNT(*) FROM sync_state WHERE tbl = '$table' AND dirty = 1 AND rev = 0"))
        }
        assertEquals(0L, count("SELECT mode FROM sync_flags WHERE id = 1"))
        // The triggers are in place: a change after the migration is tracked.
        migrated.execSQL("UPDATE sync_state SET dirty = 0")
        migrated.execSQL("UPDATE quick_notes SET title = 'Shopping'")
        assertEquals(1L, count("SELECT COUNT(*) FROM sync_state WHERE tbl = 'quick_notes' AND dirty = 1"))
        migrated.close()

        // And Room opens the migrated file.
        val reopened = QuartzDatabase.build(context, name)
        runBlocking {
            assertEquals(listOf("Shopping"), reopened.quickNoteDao().getAll().map { it.title })
            assertEquals(3, reopened.pageDao().getAll().size)
        }
        reopened.close()
        context.deleteDatabase(name)
    }

    // ---- Change tracking ----

    @Test
    fun newDatabaseStartsWithATrackedShelf() = runBlocking {
        val shelf = db.shelfDao().getAll().single()
        assertEquals(36, shelf.uid.length)
        assertEquals(1L, dirty("shelves", shelf.uid))
    }

    @Test
    fun editsMarkRowsForUploadButSyncWritesDoNot() = runBlocking {
        val id = repo.createNote()
        val uid = db.quickNoteDao().get(id)!!.uid
        assertEquals(1L, dirty("quick_notes", uid))
        markSynced("quick_notes", uid)
        assertEquals(0L, dirty("quick_notes", uid))

        repo.saveNote(id, "Title", RichDoc.fromPlainText("hello"), "default")
        waitForQueue()
        assertEquals(1L, dirty("quick_notes", uid))

        // The sync engine writing a server change doesn't mark it.
        markSynced("quick_notes", uid, rev = 2)
        db.syncDao().setMode(1)
        raw.execSQL("UPDATE quick_notes SET title = 'From the server' WHERE id = $id")
        db.syncDao().setMode(0)
        assertEquals(0L, dirty("quick_notes", uid))
        assertEquals(2L, long("SELECT rev FROM sync_state WHERE uid = ?", uid))
    }

    @Test
    fun copiesGetTheirOwnIds() = runBlocking {
        val shelf = db.shelfDao().getAll().single().id
        val a = repo.createNotebook(shelf, "A", 0, "GLOSS", "none")
        val b = repo.createNotebook(shelf, "B", 0, "GLOSS", "none")
        val page = db.pageDao().getByNotebook(a).single()
        repo.addElement(PageElementEntity(pageId = page.id, kind = "SHAPE"))
        waitForQueue()
        val copy = repo.copyPageToNotebook(page.id, b)
        val copied = db.pageDao().get(copy)!!
        assertNotEquals(page.uid, copied.uid)
        assertNotEquals(
            db.pageElementDao().getForPage(page.id).single().uid,
            db.pageElementDao().getForPage(copy).single().uid,
        )
    }

    // ---- Deleting ----

    @Test
    fun deletingSomethingNeverSyncedErasesIt() = runBlocking {
        val id = repo.createNote()
        val uid = db.quickNoteDao().get(id)!!.uid
        repo.deleteNote(id)
        waitForQueue()
        assertEquals(0L, long("SELECT COUNT(*) FROM quick_notes WHERE id = $id"))
        assertEquals(0L, long("SELECT COUNT(*) FROM sync_state WHERE uid = ?", uid))
    }

    @Test
    fun deletingSomethingSyncedLeavesAHiddenMarker() = runBlocking {
        val id = repo.createNote()
        repo.saveNote(id, "Synced", RichDoc.fromPlainText("text"), "default")
        repo.addNoteImage(id, "media/x.jpg", 10, 10)
        waitForQueue()
        val uid = db.quickNoteDao().get(id)!!.uid
        val attachment = db.attachmentDao().getFor("NOTE", id).single()
        markSynced("quick_notes", uid)
        markSynced("attachments", attachment.uid)

        repo.deleteNote(id)
        waitForQueue()
        assertEquals(1L, long("SELECT deleted FROM quick_notes WHERE id = $id"))
        assertEquals(1L, long("SELECT deleted FROM attachments WHERE id = ${attachment.id}"))
        assertEquals(1L, dirty("quick_notes", uid))
        assertNull(repo.getNote(id))
        assertTrue(repo.observeNotes().first().none { it.id == id })
        assertTrue(repo.search("Synced").notes.isEmpty())
        assertFalse("media/x.jpg" in repo.referencedMedia())
    }

    @Test
    fun anOldCopyCantBringBackADeletedRow() = runBlocking {
        val shelfDao = db.shelfDao()
        val id = repo.addShelf("Work")
        val shelf = shelfDao.getAll().first { it.id == id }
        markSynced("shelves", shelf.uid)
        repo.deleteShelf(shelf, moveNotebooksTo = shelfDao.getAll().first { it.id != id }.id)
        assertEquals(1L, long("SELECT deleted FROM shelves WHERE id = $id"))
        // A screen still holding the old shelf saves it.
        repo.renameShelf(shelf, "Work again")
        assertEquals(1L, long("SELECT deleted FROM shelves WHERE id = $id"))
        assertEquals("Work", string("SELECT name FROM shelves WHERE id = $id"))
    }

    @Test
    fun deletingANotebookDeletesItsPagesAndItems() = runBlocking {
        val shelf = db.shelfDao().getAll().single().id
        val nb = repo.createNotebook(shelf, "Trip", 0, "GLOSS", "none")
        repo.insertPage(nb, 0)
        val pages = db.pageDao().getByNotebook(nb)
        repo.addElement(PageElementEntity(pageId = pages[0].id, kind = "SHAPE"))
        waitForQueue()
        val element = db.pageElementDao().getForPage(pages[0].id).single()
        // The notebook and one page reached the server; the second page and the item didn't.
        markSynced("notebooks", db.notebookDao().get(nb)!!.uid)
        markSynced("pages", pages[0].uid)

        repo.deleteNotebook(db.notebookDao().get(nb)!!)
        assertEquals(1L, long("SELECT deleted FROM notebooks WHERE id = $nb"))
        assertEquals(1L, long("SELECT deleted FROM pages WHERE id = ${pages[0].id}"))
        assertEquals(0L, long("SELECT COUNT(*) FROM pages WHERE id = ${pages[1].id}"))
        assertEquals(0L, long("SELECT COUNT(*) FROM page_elements WHERE id = ${element.id}"))
        assertTrue(repo.observeNotebooks(shelf).first().isEmpty())
        assertTrue(repo.observeRecentPages().first().isEmpty())
    }

    // ---- Backups ----

    @Test
    fun restoringABackupKeepsIdsAndAddingAlongsideMakesNewOnes() = runBlocking {
        val id = repo.createNote()
        repo.saveNote(id, "Keep me", RichDoc.fromPlainText("text"), "default")
        waitForQueue()
        val uid = db.quickNoteDao().get(id)!!.uid
        markSynced("quick_notes", uid)
        val backup = repo.snapshot()

        // Replacing: the note comes back with the same permanent id, marked for upload.
        repo.importBackup(backup, replace = true)
        val restored = db.quickNoteDao().getAll().single()
        assertEquals(uid, restored.uid)
        assertEquals("Keep me", restored.title)
        assertEquals(1L, dirty("quick_notes", uid))
        assertEquals(0L, long("SELECT mode FROM sync_flags WHERE id = 1"))

        // Adding alongside: a second copy with its own id.
        repo.importBackup(backup, replace = false)
        val all = db.quickNoteDao().getAll()
        assertEquals(2, all.size)
        assertEquals(2, all.map { it.uid }.toSet().size)
        assertNotNull(all.firstOrNull { it.uid == uid })
    }
}
