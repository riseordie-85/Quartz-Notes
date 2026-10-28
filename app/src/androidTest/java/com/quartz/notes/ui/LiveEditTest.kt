package com.quartz.notes.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.note.NoteLoad
import com.quartz.notes.ui.note.NoteViewModel
import com.quartz.notes.ui.notebook.NotebookViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An open note or page shows a newer version from another device, unless it has unsaved edits, in
 * which case the other version is kept as a copy.
 */
@RunWith(AndroidJUnit4::class)
class LiveEditTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = QuartzDatabase.build(context, name = null)
    private val repo = QuartzRepository(db, scope)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            if (runBlocking { withContext(Dispatchers.Main) { check() } }) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /** Writes a row the way sync does, then tells the app about it. */
    private fun remoteNoteEdit(id: Long, uid: String, title: String, text: String) = runBlocking {
        val doc = RichDoc.fromPlainText(text)
        db.syncDao().setMode(1)
        db.quickNoteDao().updateContent(id, title, doc.encode(), doc.plainText(), "default", System.currentTimeMillis())
        db.syncDao().setMode(0)
        repo.notifyRemoteChange("quick_notes", uid)
    }

    @Test
    fun openNoteShowsChangesOrKeepsThemAsACopy() {
        val id = runBlocking { repo.createNote() }
        repo.saveNote(id, "Shopping", RichDoc.fromPlainText("milk"), "default")
        val uid = runBlocking { repo.getNote(id)!!.uid }
        val vm = runBlocking { withContext(Dispatchers.Main) { NoteViewModel(id, repo) } }
        waitFor("the note to open") { vm.load.value is NoteLoad.Ready }
        val session = (vm.load.value as NoteLoad.Ready).session

        // Nothing unsaved here: the open note shows the other device's version.
        remoteNoteEdit(id, uid, "Shopping list", "milk\neggs")
        waitFor("the change to show") { session.title == "Shopping list" && session.editor.toDoc().plainText() == "milk\neggs" }
        assertTrue(!session.isDirty)

        // Typing here (not saved yet) when another change arrives: that change becomes a copy.
        runBlocking { withContext(Dispatchers.Main) { session.title = "Shopping (mine)" } }
        remoteNoteEdit(id, uid, "Shopping (theirs)", "bread")
        waitFor("a copy of the other version") {
            runBlocking { db.quickNoteDao().getAll() }.any { it.title == "Shopping (theirs) (conflict copy)" && it.plainText == "bread" }
        }
        assertEquals("Shopping (mine)", runBlocking { withContext(Dispatchers.Main) { session.title } })
        runBlocking { withContext(Dispatchers.Main) { vm.saveIfChanged() } }
        waitFor("the edit here to save") { runBlocking { repo.getNote(id)!!.title } == "Shopping (mine)" }
    }

    @Test
    fun openPageShowsChanges() {
        val shelf = runBlocking { db.shelfDao().getAll().single().id }
        val nb = runBlocking { repo.createNotebook(shelf, "Trip", 0, "GLOSS", "none") }
        val page = runBlocking { db.pageDao().getByNotebook(nb).single() }
        val vm = runBlocking { withContext(Dispatchers.Main) { NotebookViewModel(nb, repo, scope) } }
        val session = runBlocking { withContext(Dispatchers.Main) { vm.session(page) } }
        runBlocking {
            val doc = RichDoc.fromPlainText("from the tablet")
            db.syncDao().setMode(1)
            db.pageDao().updateContent(page.id, doc.encode(), doc.plainText(), System.currentTimeMillis())
            db.pageDao().updateTitle(page.id, "Day 1", System.currentTimeMillis())
            db.syncDao().setMode(0)
            repo.notifyRemoteChange("pages", page.uid)
        }
        waitFor("the page to show the change") { session.editor.toDoc().plainText() == "from the tablet" && session.title == "Day 1" }
        assertTrue(!session.anyDirty)
    }
}
