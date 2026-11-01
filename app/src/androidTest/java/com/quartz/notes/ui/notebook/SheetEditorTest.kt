package com.quartz.notes.ui.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetGrid
import com.quartz.notes.data.sheet.SheetStyle
import com.quartz.notes.data.sheet.SheetArea
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

/** Sheet pages: showing a filled-in sheet, typing, formulas, selecting with a mouse, and the tools. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SheetEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container
    private val repo get() = container.repository

    private fun screen(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(700)
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

    private fun tool(label: String) = rule.onNode(clickLabel(label)).performSemanticsAction(SemanticsActions.OnClick)

    /** Where a cell's middle is in the grid, in pixels (default sizes, zoom 1). */
    private fun cell(r: Int, c: Int): Offset {
        val d = instrumentation.targetContext.resources.displayMetrics.density
        return Offset((44f + 100f * c + 50f) * d, (26f + 30f * r + 15f) * d)
    }

    private fun saved(pageId: Long): SheetData = runBlocking {
        SystemClock.sleep(900) // the page saves a moment after a change
        repo.getPage(-1)
        SheetData.decode(repo.getPage(pageId)!!.sheet)
    }

    @Test
    fun sheetShowsTypesAndFormats() {
        val (nb, pageId) = runBlocking {
            val shelf = repo.observeShelves().first().first().id
            val nb = repo.createNotebook(shelf, "Budget", 0xFF2F6FDE.toInt(), "GLOSS", "none", pageLayout = "SHEET")
            val page = repo.observePages(nb).first().single()
            var s = SheetData()
            listOf(
                "A1" to "Item", "B1" to "Cost", "C1" to "Due", "D1" to "Notes",
                "A2" to "Rent", "B2" to "$1,450", "C2" to "11/1/2026", "D2" to "Paid by transfer on the first of the month",
                "A3" to "Power", "B3" to "92.18", "C3" to "11/14/2026",
                "A4" to "Internet", "B4" to "60", "C4" to "11/20/2026",
                "A5" to "Total", "B5" to "=SUM(B2:B4)",
            ).forEach { (a, v) ->
                val (r, c) = com.quartz.notes.data.sheet.parseAddr(a)!!
                s = SheetGrid.typeInto(s, r, c, v)
            }
            s = SheetGrid.updateStyles(s, SheetArea(1, 1, 4, 1)) { it.copy(fmt = "currency") }
            s = SheetGrid.updateStyles(s, SheetArea(4, 0, 4, 1)) { it.copy(b = true) }
            s = SheetGrid.setBorders(s, SheetArea(4, 0, 4, 1), SheetGrid.Borders.TOP)
            s = SheetGrid.updateStyles(s, SheetArea(1, 3, 1, 3)) { it.copy(wrap = true) }
            s = s.copy(table = "blue", widths = mapOf("D" to 160f))
            repo.savePageSheet(page.id, s)
            repo.savePageTitle(page.id, "November bills")
            repo.getPage(-1)
            nb to page.id
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
        screen("s1_sheet")

        // Tap a cell, tap it again and type a formula in the bar.
        val grid = rule.onNodeWithTag("sheet-grid")
        grid.performTouchInput { click(cell(5, 1)) }
        settle()
        grid.performTouchInput { click(cell(5, 1)) }
        settle()
        rule.onNodeWithTag("sheet-formula").performTextReplacement("=AVERAGE(B2:B4")
        settle()
        screen("s2_typing_formula")
        rule.onNodeWithTag("sheet-formula").performImeAction()
        settle()
        assertEquals("=AVERAGE(B2:B4)", saved(pageId).cells["B6"]?.v)
        screen("s3_formula_in")

        // Select costs with a mouse drag: the bar shows their total.
        grid.performMouseInput {
            moveTo(cell(1, 1))
            press()
            moveTo(cell(2, 1))
            moveTo(cell(3, 1))
            release()
        }
        settle()
        rule.onNodeWithText("Sum 1602.18", substring = true).assertExists()
        screen("s4_selection_sum")
        tool("Bold")
        settle()
        assertTrue(saved(pageId).cells["B3"]?.s?.b == true)

        // Keys: type into a cell from the keyboard.
        grid.performTouchInput { click(cell(7, 0)) }
        settle()
        rule.onRoot().performKeyInput { pressKey(Key.Four) }
        settle()
        rule.onRoot().performKeyInput { pressKey(Key.Two) }
        settle()
        rule.onNodeWithTag("sheet-formula").performImeAction()
        settle()
        assertEquals("42", saved(pageId).cells["A8"]?.v)

        // A table look and a filter.
        tool("Table look")
        settle()
        rule.onNodeWithText("Green").performClick()
        settle()
        assertEquals("green", saved(pageId).table)
        screen("s5_green_table")

        grid.performTouchInput { click(cell(1, 0)) }
        settle()
        tool("Filter")
        settle()
        screen("s6_filter_dialog")
        rule.onNodeWithText("Power").performClick()
        rule.onNodeWithText("Apply").performClick()
        settle()
        assertEquals(listOf("Power"), saved(pageId).hide["A"])
        screen("s7_filtered")

        // Sort by cost, largest first (the header row stays).
        tool("Filter")
        settle()
        rule.onNodeWithText("Clear all filters").performClick()
        settle()
        grid.performMouseInput {
            moveTo(cell(1, 1))
            press()
            moveTo(cell(3, 1))
            release()
        }
        settle()
        tool("Sort")
        settle()
        rule.onNodeWithText("Z → A", substring = true).performClick()
        settle()
        val sorted = saved(pageId)
        assertEquals("Item", sorted.cells["A1"]?.v)
        assertEquals(listOf("Rent", "Power", "Internet", "Total"), (2..5).map { sorted.cells["A$it"]?.v })
        screen("s8_sorted")

        // Number formats.
        grid.performTouchInput { click(cell(2, 2)) }
        settle()
        tool("Number format")
        settle()
        screen("s9_formats_menu")
        rule.onNodeWithText("Plain text").performClick()
        settle()
        assertEquals(SheetStyle(fmt = "text"), saved(pageId).cells["C3"]?.s?.copy(b = false))
    }
}
