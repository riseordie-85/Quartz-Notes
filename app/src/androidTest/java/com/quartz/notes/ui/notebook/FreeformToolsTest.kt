package com.quartz.notes.ui.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.PageSize
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.ShapeType
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Undo and redo, groups, copy and paste, and the lasso on Freeform pages. */
@RunWith(AndroidJUnit4::class)
class FreeformToolsTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container
    private val repo get() = container.repository

    private fun screen(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle() {
        rule.waitForIdle()
        SystemClock.sleep(700)
        rule.waitForIdle()
    }

    private fun clickLabel(label: String) = SemanticsMatcher("click label is $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun freeformNotebook(title: String): Pair<Long, Long> = runBlocking {
        val shelf = repo.observeShelves().first().first().id
        val nb = repo.createNotebook(shelf, title, 0xFF2E9E5B.toInt(), "GLOSS", "none", pageLayout = "SCRAPBOOK", pageSize = PageSize.PAPER.first { it.key == PageSize.A4 })
        nb to repo.observePages(nb).first().single().id
    }

    /** Waits for every queued save. */
    private suspend fun flush() {
        repo.getPage(-1)
    }

    private suspend fun items(pageId: Long) = repo.observeElements(pageId).first()

    private fun <T> onMain(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test
    fun undoAndRedoItemChanges() = runBlocking {
        val (nb, pageId) = freeformNotebook("Undo test")
        val vm = onMain { NotebookViewModel(nb, repo, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        val id = onMain { vm.addShape(pageId, ShapeType.RECTANGLE) }
        flush()
        val shape = items(pageId).single()
        assertEquals(id, shape.id)

        // Move, then undo puts it back; redo moves it again.
        onMain { vm.moveElement(shape.copy(x = 0.6f)) }
        flush()
        assertEquals(0.6f, items(pageId).single().x)
        onMain { vm.undoItems(pageId) }
        flush()
        assertEquals(shape.x, items(pageId).single().x)
        onMain { vm.redoItems(pageId) }
        flush()
        assertEquals(0.6f, items(pageId).single().x)

        // Removing comes back with the same id; undoing the add takes it away.
        onMain { vm.deleteElement(items(pageId).single()) }
        flush()
        assertTrue(items(pageId).isEmpty())
        onMain { vm.undoItems(pageId) }
        flush()
        assertEquals(id, items(pageId).single().id)
        onMain { vm.undoItems(pageId) }
        flush()
        onMain { vm.undoItems(pageId) }
        flush()
        assertTrue("undoing the add removes the shape", items(pageId).isEmpty())

        // Style changes in quick succession are one step.
        onMain { vm.redoItems(pageId) }
        flush()
        val again = items(pageId).single()
        val d = decodePayload<ShapeData>(again.data)!!
        onMain {
            vm.updateElementData(again, d.copy(fill = 0xFF111111.toInt()).encodePayload())
            vm.updateElementData(again, d.copy(fill = 0xFF222222.toInt()).encodePayload())
            vm.updateElementData(again, d.copy(fill = 0xFF333333.toInt()).encodePayload())
        }
        flush()
        onMain { vm.undoItems(pageId) }
        flush()
        assertEquals(d.fill, decodePayload<ShapeData>(items(pageId).single().data)!!.fill)
    }

    @Test
    fun groupsMoveCopyAndPaste() = runBlocking {
        val (nb, pageId) = freeformNotebook("Group test")
        val (_, otherPage) = freeformNotebook("Paste target")
        val vm = onMain { NotebookViewModel(nb, repo, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        repo.addElement(PageElementEntity(pageId = pageId, kind = ElementKind.SHAPE, x = 0.1f, y = 0.4f, width = 0.3f, height = 0.2f, data = ShapeData().encodePayload()))
        repo.addElement(PageElementEntity(pageId = pageId, kind = ElementKind.SHAPE, x = 0.6f, y = 0.4f, width = 0.3f, height = 0.2f, data = ShapeData().encodePayload()))
        flush()
        val (a, b) = items(pageId)
        onMain { vm.addConnector(pageId, a.id, b.id) }
        onMain { vm.addStroke(pageId, listOf(0.2f, 0.8f, 0.3f, 0.9f), 0, com.quartz.notes.data.model.BrushType.PEN, 2f) }
        flush()
        val all = items(pageId)
        assertEquals(4, all.size)

        // Moving a group moves a drawing's points too.
        val stroke = all.first { it.kind == ElementKind.STROKE }
        onMain { vm.moveItems(pageId, listOf(stroke, a), 0.05f, 0.1f) }
        flush()
        val movedStroke = items(pageId).first { it.id == stroke.id }
        assertEquals(0.25f, decodePayload<StrokeData>(movedStroke.data)!!.points[0], 0.0001f)
        assertEquals(0.9f, movedStroke.y, 0.0001f)
        assertEquals(0.15f, items(pageId).first { it.id == a.id }.x, 0.0001f)

        // Copying the two shapes brings the line between them; pasting elsewhere joins the copies.
        onMain { vm.copyItems(pageId, items(pageId), setOf(a.id, b.id)) }
        assertEquals(3, ItemClipboard.items.size)
        val pasted = onMain { vm.pasteItems(otherPage, Offset(0.1f, 0.2f)) }
        flush()
        val there = items(otherPage)
        assertEquals(3, there.size)
        assertEquals(pasted.toSet(), there.map { it.id }.toSet())
        val line = there.first { it.kind == ElementKind.CONNECTOR }
        val joins = decodePayload<com.quartz.notes.data.model.ConnectorData>(line.data)!!
        assertTrue(joins.from in pasted && joins.to in pasted)
        assertEquals(0.1f, there.filter { it.kind == ElementKind.SHAPE }.minOf { it.x }, 0.0001f)

        // Duplicating, then removing the group, then undo.
        val copies = onMain { vm.duplicateItems(pageId, items(pageId), setOf(b.id)) }
        flush()
        assertEquals(5, items(pageId).size)
        onMain { vm.deleteItems(pageId, items(pageId).filter { it.id in copies || it.id == b.id }) }
        flush()
        assertNull("the line to a removed shape goes too", items(pageId).firstOrNull { it.kind == ElementKind.CONNECTOR })
        onMain { vm.undoItems(pageId) }
        flush()
        assertEquals(5, items(pageId).size)
        assertNotNull(items(pageId).firstOrNull { it.kind == ElementKind.CONNECTOR })
    }

    @Test
    fun lassoPicksItemsAndUndoBringsThemBack() {
        val (nb, pageId) = freeformNotebook("Lasso test")
        runBlocking {
            repo.addElement(PageElementEntity(pageId = pageId, kind = ElementKind.SHAPE, x = 0.15f, y = 0.45f, width = 0.3f, height = 0.2f, data = ShapeData().encodePayload()))
            repo.addElement(PageElementEntity(pageId = pageId, kind = ElementKind.SHAPE, x = 0.55f, y = 0.5f, width = 0.3f, height = 0.3f, data = ShapeData(shape = ShapeType.ELLIPSE, fill = 0xFFD6004C.toInt()).encodePayload()))
            repo.addElement(
                PageElementEntity(
                    pageId = pageId, kind = ElementKind.STROKE, x = 0.2f, y = 0.85f, width = 0.6f, height = 0.05f,
                    data = StrokeData(points = listOf(0.2f, 0.85f, 0.4f, 0.9f, 0.6f, 0.85f, 0.8f, 0.9f), level = 3).encodePayload(),
                ),
            )
            flush()
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = nb, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        settle()
        rule.onNode(clickLabel("Lasso: draw around items to pick them")).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        // A loop around most of the page.
        rule.onRoot().performTouchInput {
            val l = width * 0.06f
            val r = width * 0.94f
            val t = height * 0.26f
            val b = height * 0.78f
            down(Offset(l, t))
            var from = Offset(l, t)
            listOf(Offset(r, t), Offset(r, b), Offset(l, b), Offset(l, t + 10f)).forEach { corner ->
                for (i in 1..8) moveTo(from + (corner - from) * (i / 8f))
                from = corner
            }
            up()
        }
        settle()
        screen("g1_lasso_group")
        rule.onNodeWithContentDescription("Duplicate").assertExists()
        rule.onNodeWithContentDescription("Remove").performClick()
        settle()
        assertTrue("lasso picked and removed everything", runBlocking { flush(); items(pageId) }.isEmpty())
        screen("g2_removed")
        rule.onNode(clickLabel("Undo")).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(3, runBlocking { flush(); items(pageId) }.size)
        screen("g3_undone")
    }
}
