package com.quartz.notes.ui.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.BrushType
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.PageSize
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pen, pencil, marker and highlighter; brush sizes; two-finger tap to undo. */
@RunWith(AndroidJUnit4::class)
class BrushTest {
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

    private fun tap(label: String) {
        rule.onNode(clickLabel(label)).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private suspend fun strokes(pageId: Long) = repo.getPage(-1).let { repo.observeElements(pageId).first().filter { it.kind == ElementKind.STROKE } }

    @Test
    fun brushesSizesAndTwoFingerUndo() {
        runBlocking { container.settings.setFingerDraw(true) }
        val (nb, pageId) = runBlocking {
            val shelf = repo.observeShelves().first().first().id
            val nb = repo.createNotebook(shelf, "Brush test", 0xFF6A3FD0.toInt(), "GLOSS", "none", pageLayout = "SCRAPBOOK", pageSize = PageSize.PAPER.first { it.key == PageSize.A5 })
            val page = repo.observePages(nb).first().single().id
            // One line of each kind, for the picture.
            BrushType.ALL.forEachIndexed { i, t ->
                val y = 0.35f + i * 0.12f
                repo.addElement(
                    PageElementEntity(
                        pageId = page, kind = ElementKind.STROKE, x = 0.1f, y = y, width = 0.8f, height = 0.04f,
                        data = StrokeData(
                            points = listOf(0.1f, y, 0.3f, y + 0.04f, 0.5f, y, 0.7f, y + 0.04f, 0.9f, y),
                            color = if (t == BrushType.HIGHLIGHTER) 0xFFFFE14D.toInt() else 0xFF1A3D8F.toInt(),
                            tool = t,
                            size = BrushType.defaultSize(t) * 1.5f,
                        ).encodePayload(),
                    ),
                )
            }
            repo.getPage(-1)
            nb to page
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
        screen("b1_four_brushes")

        // Brush → Highlighter, a big size, then draw across the page.
        tap("Brush")
        tap("Highlighter")
        tap("Size 12")
        screen("b2_brush_bar")
        rule.onRoot().performTouchInput {
            val y = height * 0.33f
            down(Offset(width * 0.2f, y))
            for (i in 1..12) moveTo(Offset(width * (0.2f + 0.05f * i), y + (if (i % 2 == 0) 8f else -8f)))
            up()
        }
        settle()
        val drawn = runBlocking { strokes(pageId) }
        assertEquals(5, drawn.size)
        val last = decodePayload<StrokeData>(drawn.maxBy { it.id }.data)!!
        assertEquals(BrushType.HIGHLIGHTER, last.tool)
        assertEquals(12f, last.size!!, 0.001f)
        screen("b3_highlighted")

        // Two fingers tapped together undo the last line.
        rule.onRoot().performTouchInput {
            down(0, Offset(width * 0.4f, height * 0.5f))
            down(1, Offset(width * 0.6f, height * 0.5f))
            up(0)
            up(1)
        }
        settle()
        assertEquals(4, runBlocking { strokes(pageId) }.size)
        // Three fingers redo it.
        rule.onRoot().performTouchInput {
            down(0, Offset(width * 0.3f, height * 0.5f))
            down(1, Offset(width * 0.5f, height * 0.5f))
            down(2, Offset(width * 0.7f, height * 0.5f))
            up(0)
            up(1)
            up(2)
        }
        settle()
        assertEquals(5, runBlocking { strokes(pageId) }.size)
        // Each brush keeps its own size.
        tap("Pencil")
        val saved = runBlocking { SystemClock.sleep(800); container.settings.settings.first() }
        assertEquals(12f, saved.brushSizes[BrushType.HIGHLIGHTER]!!, 0.001f)
        assertTrue(saved.brushSizes[BrushType.PENCIL] == null || saved.brushSizes[BrushType.PENCIL] == BrushType.defaultSize(BrushType.PENCIL))
        tap("Done")
    }
}
