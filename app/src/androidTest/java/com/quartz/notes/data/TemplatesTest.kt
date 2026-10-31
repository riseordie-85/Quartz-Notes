package com.quartz.notes.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookKind
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.OwnerType
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.templates.Starters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Page and quick-note templates, starter templates, page background pictures, database 5 → 6. */
@RunWith(AndroidJUnit4::class)
class TemplatesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, QuartzDatabase::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = QuartzDatabase.build(context, name = null)
    private val repo = QuartzRepository(db, scope)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun settle() = runBlocking { repo.getNote(-1) }

    @Test
    fun aPageSavedAsATemplateMakesNewPagesWithEverythingOnIt() = runBlocking {
        val shelf = db.shelfDao().getAll().single().id
        val nb = repo.createNotebook(shelf, "Planner", 0, "GLOSS", "none")
        val page = db.pageDao().getByNotebook(nb).single()
        val doc = RichDoc(listOf(Block(type = BlockType.HEADING, text = "Week of"), Block(type = BlockType.TASK, text = "")))
        repo.savePageContent(page.id, doc)
        repo.savePageTitle(page.id, "Weekly")
        repo.updatePageStyle(page.copy(pageType = "PLAIN", backgroundImage = "media/planner.png"))
        repo.insertElement(PageElementEntity(pageId = page.id, kind = ElementKind.SHAPE, x = 0.5f, y = 0.5f, width = 0.2f, height = 0.2f, data = "{}"))
        settle()

        val templateId = repo.savePageAsTemplate(page.id, "Weekly planner")
        val templates = repo.observePageTemplates().first()
        assertEquals(listOf("Weekly planner"), templates.map { it.templateName })
        assertEquals(templateId, templates.single().id)

        // The templates notebook stays out of sight.
        assertEquals(listOf("Planner"), repo.observeAllNotebooks().first().map { it.title })
        assertEquals(listOf("Planner"), repo.observeNotebooks(shelf).first().map { it.title })
        assertEquals(NotebookKind.TEMPLATES, db.notebookDao().templatesNotebook()!!.kind)
        assertTrue("not in search", repo.search("Week").pages.none { it.pageId == templateId })

        // A new page from it, after the first page.
        val newId = repo.insertPageFromTemplate(nb, 0, templateId)
        val pages = db.pageDao().getByNotebook(nb)
        assertEquals(listOf(page.id, newId), pages.map { it.id })
        val made = pages[1]
        assertEquals("Weekly", made.title)
        assertNull(made.templateName)
        assertEquals("media/planner.png", made.backgroundImage)
        assertEquals("PLAIN", made.pageType)
        assertEquals(doc.blocks.map { it.text }, RichDoc.decode(made.content).blocks.map { it.text })
        assertEquals(1, db.pageElementDao().getForPage(newId).size)

        // Background pictures are offered again, and new blank pages after it keep it.
        assertEquals(listOf("media/planner.png"), repo.observeBackgroundImages().first())
        val blank = repo.insertPage(nb, 1)
        assertEquals("media/planner.png", db.pageDao().get(blank)!!.backgroundImage)

        // Renaming and deleting.
        repo.renamePageTemplate(templateId, "Week")
        settle()
        assertEquals(listOf("Week"), repo.observePageTemplates().first().map { it.templateName })
        repo.deletePageTemplate(templateId)
        settle()
        assertTrue(repo.observePageTemplates().first().isEmpty())
        assertEquals("the page made from it stays", 3, db.pageDao().getByNotebook(nb).size)
    }

    @Test
    fun pagesAddedInTheTemplatesNotebookAreTemplates() = runBlocking {
        val holder = repo.templatesNotebookId()
        assertEquals("one templates notebook", holder, repo.templatesNotebookId())
        repo.insertPage(holder, -1)
        assertEquals(listOf("New template"), repo.observePageTemplates().first().map { it.templateName })
    }

    @Test
    fun aNoteSavedAsATemplateStaysOutOfTheListAndMakesNewNotes() = runBlocking {
        val id = repo.createNote()
        repo.saveNote(id, "Groceries", RichDoc(listOf(Block(type = BlockType.TASK, text = ""))), "green")
        db.attachmentDao().insert(AttachmentEntity(ownerType = OwnerType.NOTE.name, ownerId = id, kind = "IMAGE", path = "media/list.jpg"))
        settle()

        val templateId = repo.saveNoteAsTemplate(id, "Grocery list")
        assertEquals(listOf(id), repo.observeNotes().first().map { it.id })
        assertEquals(listOf("Grocery list"), repo.observeNoteTemplates().first().map { it.templateName })

        val made = repo.createNoteFromTemplate(templateId)
        val note = repo.getNote(made)!!
        assertEquals("Groceries", note.title)
        assertEquals("green", note.colorKey)
        assertNull(note.templateName)
        assertEquals(listOf("media/list.jpg"), db.attachmentDao().getFor(OwnerType.NOTE.name, made).map { it.path })
        assertEquals(setOf(id, made), repo.observeNotes().first().map { it.id }.toSet())

        // A template that's only an empty checklist isn't cleaned up as a blank note.
        val bare = repo.saveNoteAsTemplate(made, "Bare")
        repo.saveNote(bare, "", RichDoc(), "default")
        repo.discardIfBlank(bare)
        settle()
        assertNotNull(repo.getNote(bare))
    }

    @Test
    fun startersLoadAndMakePagesAndNotes() = runBlocking {
        val starters = Starters.get(context)
        assertEquals(listOf("todo", "meeting", "journal", "weekly", "sketch"), starters.pages.map { it.key })
        assertEquals(listOf("todo", "groceries", "idea", "packing"), starters.notes.map { it.key })
        val shelf = db.shelfDao().getAll().single().id
        val nb = repo.createNotebook(shelf, "Work", 0, "GLOSS", "none")
        val meeting = starters.pages.first { it.key == "meeting" }
        val pageId = repo.insertPageFromStarter(nb, 0, meeting)
        val page = db.pageDao().get(pageId)!!
        assertEquals("Meeting", page.title)
        assertTrue(RichDoc.decode(page.content).blocks.any { it.text == "Action items" })
        val journal = starters.pages.first { it.key == "journal" }
        assertEquals(0xFFFFF8E7.toInt(), journal.backgroundArgb)
        val noteId = repo.createNoteFromStarter(starters.notes.first { it.key == "groceries" })
        assertEquals("Groceries", repo.getNote(noteId)!!.title)
    }

    @Test
    fun migration5To6AddsTemplateAndBackgroundFields() {
        val name = "migration-6-test.db"
        context.deleteDatabase(name)
        helper.createDatabase(name, 5).apply {
            fun insert(table: String, vararg pairs: Pair<String, Any?>): Long {
                val values = ContentValues()
                for ((k, v) in pairs) when (v) {
                    null -> values.putNull(k)
                    is Int -> values.put(k, v)
                    is Long -> values.put(k, v)
                    is String -> values.put(k, v)
                    else -> error("unsupported $v")
                }
                return insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            val shelf = insert("shelves", "name" to "Notebooks", "position" to 0, "createdAt" to 1L, "uid" to "s1", "deleted" to 0)
            val notebook = insert(
                "notebooks", "shelfId" to shelf, "title" to "Travel", "colorArgb" to 1, "texture" to "GLOSS", "icon" to "none",
                "position" to 0, "createdAt" to 1L, "updatedAt" to 1L, "twoPage" to 1, "showIndex" to 0, "sectionTabs" to 1,
                "attachAtBottom" to 0, "uid" to "n1", "deleted" to 0,
            )
            insert(
                "pages", "notebookId" to notebook, "title" to "Day 1", "position" to 0, "pageType" to "LINES",
                "backgroundArgb" to null, "texture" to "NONE", "layoutMode" to "INLINE", "content" to "", "plainText" to "",
                "createdAt" to 1L, "updatedAt" to 1L, "locked" to 0, "spread" to 0, "isSection" to 0,
                "sectionIcon" to null, "sectionImage" to null, "uid" to "p1", "deleted" to 0,
            )
            insert(
                "quick_notes", "title" to "Groceries", "content" to "", "plainText" to "milk", "colorKey" to "default",
                "pinned" to 0, "createdAt" to 1L, "updatedAt" to 1L, "archived" to 0, "uid" to "q1", "deleted" to 0,
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 6, true, QuartzDatabase.MIGRATION_5_6)
        fun text(sql: String) = migrated.query(sql).use { it.moveToFirst(); if (it.isNull(0)) null else it.getString(0) }
        assertEquals("NOTEBOOK", text("SELECT kind FROM notebooks"))
        assertNull(text("SELECT templateName FROM pages"))
        assertNull(text("SELECT backgroundImage FROM pages"))
        assertNull(text("SELECT templateName FROM quick_notes"))
        migrated.close()

        val reopened = QuartzDatabase.build(context, name)
        runBlocking {
            assertEquals(listOf("Travel"), reopened.notebookDao().observeAll().first().map { it.title })
            assertEquals(listOf("Groceries"), reopened.quickNoteDao().observeAll().first().map { it.title })
        }
        reopened.close()
        context.deleteDatabase(name)
    }
}
