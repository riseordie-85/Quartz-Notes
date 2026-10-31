package com.quartz.notes.ui.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives a real notebook page with a mouse and a finger, the way a person with a keyboard, mouse
 * or pen would, and records where the cursor lands. Results and full-screen screenshots go to the
 * shots folder (published by the UI check workflow).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PageInputTest {

    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val report = StringBuilder()

    private fun note(line: String) {
        report.appendLine(line)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "input_report.txt").appendText(line + "\n")
    }

    /** Full-screen screenshot, including popups such as selection handles. */
    private fun screen(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val paragraphs = listOf(
        "Short line",
        "Another short one",
        "A third line that is a little longer than the others",
        "Fourth",
        "Fifth and last",
    )

    private fun openPage() {
        val app = ApplicationProvider.getApplicationContext<QuartzApp>()
        val container = app.container
        val notebookId = runBlocking {
            val repo = container.repository
            val shelf = repo.observeShelves().first().first()
            val id = repo.createNotebook(shelf.id, "Input test", 0xFF5B6CFF.toInt(), "GLOSS", "none")
            val page = repo.observePages(id).first().first()
            repo.savePageContent(page.id, RichDoc(paragraphs.map { Block(type = BlockType.BODY, text = it) }))
            repo.getPage(page.id) // waits for the save
            id
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalFontResolver provides FontResolver(BuiltInFonts),
            ) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = notebookId, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        rule.waitForIdle()
        SystemClock.sleep(500)
        rule.waitForIdle()
    }

    private fun editor(): SemanticsNodeInteraction = rule.onNode(hasSetTextAction() and hasText(paragraphs[1], substring = true))

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first()
    }

    private fun selection(node: SemanticsNodeInteraction): TextRange? =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    /** Screen position of a point given in the text field's own coordinates. */
    private fun onScreen(node: SemanticsNodeInteraction, local: Offset): Offset =
        node.fetchSemanticsNode().positionOnScreen + local

    /** Doc offset (without the hidden leading line break) where paragraph [i] ends. */
    private fun paragraphEnd(i: Int): Int = paragraphs.take(i + 1).sumOf { it.length } + i

    private fun inject(action: Int, at: Offset, source: Int, downTime: Long, button: Int = 0) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = at.x
            y = at.y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords),
            0, button, 1f, 1f, 0, 0, source, 0,
        )
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun mouseClick(at: Offset) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, at, InputDevice.SOURCE_MOUSE, down, MotionEvent.BUTTON_PRIMARY)
        inject(MotionEvent.ACTION_BUTTON_PRESS, at, InputDevice.SOURCE_MOUSE, down, MotionEvent.BUTTON_PRIMARY)
        SystemClock.sleep(60)
        inject(MotionEvent.ACTION_BUTTON_RELEASE, at, InputDevice.SOURCE_MOUSE, down, 0)
        inject(MotionEvent.ACTION_UP, at, InputDevice.SOURCE_MOUSE, down, 0)
        rule.waitForIdle()
    }

    private fun mouseDrag(from: Offset, to: Offset) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, from, InputDevice.SOURCE_MOUSE, down, MotionEvent.BUTTON_PRIMARY)
        inject(MotionEvent.ACTION_BUTTON_PRESS, from, InputDevice.SOURCE_MOUSE, down, MotionEvent.BUTTON_PRIMARY)
        for (i in 1..12) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, from + (to - from) * (i / 12f), InputDevice.SOURCE_MOUSE, down, MotionEvent.BUTTON_PRIMARY)
        }
        inject(MotionEvent.ACTION_BUTTON_RELEASE, to, InputDevice.SOURCE_MOUSE, down, 0)
        inject(MotionEvent.ACTION_UP, to, InputDevice.SOURCE_MOUSE, down, 0)
        rule.waitForIdle()
    }

    private fun fingerTap(at: Offset) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, at, InputDevice.SOURCE_TOUCHSCREEN, down)
        SystemClock.sleep(50)
        inject(MotionEvent.ACTION_UP, at, InputDevice.SOURCE_TOUCHSCREEN, down)
        rule.waitForIdle()
    }

    private fun fingerDrag(from: Offset, to: Offset) {
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, from, InputDevice.SOURCE_TOUCHSCREEN, down)
        for (i in 1..20) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, from + (to - from) * (i / 20f), InputDevice.SOURCE_TOUCHSCREEN, down)
        }
        inject(MotionEvent.ACTION_UP, to, InputDevice.SOURCE_TOUCHSCREEN, down)
        rule.waitForIdle()
    }

    /**
     * How selecting behaves with real (injected) input, like a person's finger, pen or mouse:
     * double tap, long hold (with the small wobble a real finger has), hold then drag, and double /
     * triple click. Records what got selected; checks the basics every app has.
     */
    private fun selectionGestures(field: SemanticsNodeInteraction, label: String) {
        val l = layout(field)
        fun lineY(i: Int) = (l.getLineTop(i) + l.getLineBottom(i)) / 2
        // Middle of "Another" (line 2) and of "Fourth" (line 4), in the field's own coordinates.
        val anotherStart = paragraphEnd(0) + 2
        val another = Offset((l.getHorizontalPosition(anotherStart - 1, true) + l.getHorizontalPosition(anotherStart - 1 + "Another".length, true)) / 2, lineY(1))
        val fourthStart = paragraphEnd(2) + 2
        val fourthLine = l.getLineForOffset(fourthStart - 1)
        val fourth = Offset(l.getHorizontalPosition(fourthStart - 1 + 3, true), lineY(fourthLine))
        val rest = onScreen(field, Offset(10f, lineY(l.lineCount - 1)))
        val anotherWord = TextRange(anotherStart, anotherStart + "Another".length)
        val results = LinkedHashMap<String, TextRange?>()
        fun reset() {
            fingerTap(rest)
            SystemClock.sleep(400)
        }
        fun hold(at: Offset, millis: Long, source: Int, stylus: Boolean, dragTo: Offset? = null) {
            val down = SystemClock.uptimeMillis()
            injectTool(MotionEvent.ACTION_DOWN, at, source, down, stylus)
            var t = 0L
            var i = 0
            while (t < millis) {
                SystemClock.sleep(40)
                t += 40
                i++
                // A real finger wobbles a little while held.
                injectTool(MotionEvent.ACTION_MOVE, at + Offset(if (i % 2 == 0) 1f else -1f, 0f), source, down, stylus)
            }
            var last = at
            if (dragTo != null) {
                for (k in 1..15) {
                    SystemClock.sleep(20)
                    last = at + (dragTo - at) * (k / 15f)
                    injectTool(MotionEvent.ACTION_MOVE, last, source, down, stylus)
                }
            }
            injectTool(MotionEvent.ACTION_UP, last, source, down, stylus)
            rule.waitForIdle()
        }
        val touch = InputDevice.SOURCE_TOUCHSCREEN
        val pen = InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN

        reset()
        fingerTap(onScreen(field, another))
        SystemClock.sleep(90)
        fingerTap(onScreen(field, another))
        SystemClock.sleep(300)
        rule.waitForIdle()
        results["finger double tap"] = selection(field)
        screen("30_${label}_double_tap")

        reset()
        hold(onScreen(field, another), 800, touch, stylus = false)
        results["finger hold"] = selection(field)
        screen("31_${label}_hold")

        reset()
        hold(onScreen(field, another), 800, touch, stylus = false, dragTo = onScreen(field, fourth))
        results["finger hold then drag"] = selection(field)
        screen("32_${label}_hold_drag")

        reset()
        hold(onScreen(field, another), 800, pen, stylus = true)
        results["pen hold"] = selection(field)

        reset()
        mouseClick(onScreen(field, another))
        SystemClock.sleep(60)
        mouseClick(onScreen(field, another))
        rule.waitForIdle()
        results["mouse double click"] = selection(field)

        reset()
        repeat(3) {
            mouseClick(onScreen(field, another))
            SystemClock.sleep(60)
        }
        rule.waitForIdle()
        results["mouse triple click"] = selection(field)
        screen("33_${label}_triple_click")

        results.forEach { (k, v) -> note("$label $k: selection=$v") }
        note("$label expected word \"Another\" = $anotherWord, line 2 = ${TextRange(anotherStart, paragraphEnd(1) + 1)}")
        assertEquals("finger double tap selects the word", anotherWord, results["finger double tap"])
        assertEquals("finger hold selects the word", anotherWord, results["finger hold"])
        assertTrue("finger hold then drag selects down to line 4", (results["finger hold then drag"]?.max ?: 0) > paragraphEnd(2))
        assertEquals("pen hold selects the word", anotherWord, results["pen hold"])
        assertEquals("mouse double click selects the word", anotherWord, results["mouse double click"])
        assertEquals("mouse triple click selects the paragraph", TextRange(anotherStart, paragraphEnd(1) + 1), results["mouse triple click"])
    }

    @Test
    fun selectingTextOnPage() {
        openPage()
        selectionGestures(editor(), "page")
    }

    @Test
    fun selectingTextOnBareEditor() {
        val state = com.quartz.notes.ui.editor.RichEditorState(RichDoc(paragraphs.map { Block(type = BlockType.BODY, text = it) }))
        rule.setContent {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White),
                ) {
                    com.quartz.notes.ui.editor.RichTextEditor(
                        state = state,
                        styles = com.quartz.notes.ui.editor.editorStyles(),
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                        modifier = androidx.compose.ui.Modifier.padding(top = 80.dp, start = 20.dp, end = 20.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        selectionGestures(editor(), "bare")
    }

    @Test
    fun clickAnywhereOnALine() {
        openPage()
        val field = editor()
        val l = layout(field)
        val width = field.fetchSemanticsNode().size.width.toFloat()
        // Line of paragraph 1 (the field's text starts with a hidden line break, so offsets shift by one).
        val start1 = paragraphEnd(0) + 1 + 1
        val line1 = l.getLineForOffset(start1 - 1)
        val y1 = (l.getLineTop(line1) + l.getLineBottom(line1)) / 2

        fingerTap(onScreen(field, Offset(width - 20f, (l.getLineTop(0) + l.getLineBottom(0)) / 2)))
        note("finger tap right of line 1: selection=${selection(field)} expected ${paragraphEnd(0) + 1}")
        assertEquals(TextRange(paragraphEnd(0) + 1), selection(field))
        screen("10_tap_right_of_line")

        // Left margin of the page, beside line 4.
        val line3 = l.getLineForOffset(paragraphEnd(2) + 2)
        mouseClick(onScreen(field, Offset(-10f, (l.getLineTop(line3) + l.getLineBottom(line3)) / 2)))
        note("mouse click in left margin of line 4: selection=${selection(field)} expected start of line 4 = ${paragraphEnd(2) + 2}")
        assertEquals(TextRange(paragraphEnd(2) + 2), selection(field))
    }

    /** Long-press a word in line 2, then drag the selection's end handle down two lines. */
    private fun handleChecks(field: SemanticsNodeInteraction, label: String, stylus: Boolean) {
        val l = layout(field)
        fun lineY(i: Int) = (l.getLineTop(i) + l.getLineBottom(i)) / 2
        val source = if (stylus) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_TOUCHSCREEN
        // Long-press through Compose's own touch input (a raw injected hold doesn't register as a long press).
        field.performTouchInput { longClick(Offset(120f, lineY(1))) }
        rule.waitForIdle()
        val selected = selection(field)
        note("$label ${if (stylus) "pen" else "finger"} long press on line 2: selection=$selected")
        assertEquals(TextRange(paragraphEnd(0) + 2, paragraphEnd(0) + 2 + "Another".length), selected)
        screen("16_${label}_${if (stylus) "pen" else "finger"}_selected")
        val sel = selected ?: return
        if (sel.collapsed) return
        val l2 = layout(field)
        val end = (sel.max - 1).coerceIn(0, l2.layoutInput.text.length)
        val rect = l2.getCursorRect(end)
        // The end handle hangs just below the end of the selection.
        val handle = onScreen(field, Offset(rect.left + 14f, rect.bottom + 20f))
        val target = handle + Offset(0f, (l2.getLineBottom(3) - l2.getLineBottom(1)))
        val d2 = SystemClock.uptimeMillis()
        injectTool(MotionEvent.ACTION_DOWN, handle, source, d2, stylus)
        for (i in 1..20) {
            SystemClock.sleep(16)
            injectTool(MotionEvent.ACTION_MOVE, handle + (target - handle) * (i / 20f), source, d2, stylus)
        }
        injectTool(MotionEvent.ACTION_UP, target, source, d2, stylus)
        rule.waitForIdle()
        note("$label ${if (stylus) "pen" else "finger"} drag end handle down 2 lines: selection=${selection(field)} (was $sel)")
        assertTrue("end handle should move down", (selection(field)?.max ?: 0) > paragraphEnd(2))
        screen("17_${label}_${if (stylus) "pen" else "finger"}_handle_moved")
    }

    private fun injectTool(action: Int, at: Offset, source: Int, downTime: Long, stylus: Boolean) {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = at.x
            y = at.y
            pressure = 1f
            size = 1f
        }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, source, 0)
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    @Test
    fun handlesOnPage() {
        openPage()
        handleChecks(editor(), "page", stylus = false)
    }

    @Test
    fun penHandlesOnPage() {
        openPage()
        handleChecks(editor(), "page", stylus = true)
    }

    private fun composeMouseChecks(field: SemanticsNodeInteraction, label: String) {
        val l = layout(field)
        val width = field.fetchSemanticsNode().size.width.toFloat()
        fun lineY(i: Int) = (l.getLineTop(i) + l.getLineBottom(i)) / 2
        field.performMouseInput {
            moveTo(Offset(width - 20f, lineY(1)))
            press()
            release()
        }
        rule.waitForIdle()
        note("$label compose mouse click right of line 2: selection=${selection(field)} expected ${paragraphEnd(1) + 1}")
        assertEquals(TextRange(paragraphEnd(1) + 1), selection(field))
        field.performMouseInput {
            moveTo(Offset(40f, lineY(0)))
            press()
            release()
        }
        rule.waitForIdle()
        note("$label compose mouse click in line 1: selection=${selection(field)}")
        field.performMouseInput {
            moveTo(Offset(5f, lineY(0)))
            press()
            moveTo(Offset(100f, lineY(2)))
            moveTo(Offset(200f, lineY(3)))
            release()
        }
        rule.waitForIdle()
        note("$label compose mouse drag select lines 1-4: selection=${selection(field)}")
        assertTrue((selection(field)?.length ?: 0) > paragraphEnd(1))
        screen("14_${label}_mouse_drag")
    }

    @Test
    fun composeMouseOnPage() {
        openPage()
        composeMouseChecks(editor(), "page")
    }

    @Test
    fun composeMouseOnBareEditor() {
        val state = com.quartz.notes.ui.editor.RichEditorState(RichDoc(paragraphs.map { Block(type = BlockType.BODY, text = it) }))
        rule.setContent {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White),
                ) {
                    com.quartz.notes.ui.editor.RichTextEditor(
                        state = state,
                        styles = com.quartz.notes.ui.editor.editorStyles(),
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                        modifier = androidx.compose.ui.Modifier.padding(top = 80.dp, start = 20.dp, end = 20.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        composeMouseChecks(editor(), "bare")
    }

    @Test
    fun handlesOnBareEditor() {
        val state = com.quartz.notes.ui.editor.RichEditorState(RichDoc(paragraphs.map { Block(type = BlockType.BODY, text = it) }))
        rule.setContent {
            androidx.compose.material3.MaterialTheme {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White),
                ) {
                    com.quartz.notes.ui.editor.RichTextEditor(
                        state = state,
                        styles = com.quartz.notes.ui.editor.editorStyles(),
                        contentColor = androidx.compose.ui.graphics.Color.Black,
                        modifier = androidx.compose.ui.Modifier.padding(top = 80.dp, start = 20.dp, end = 20.dp),
                    )
                }
            }
        }
        rule.waitForIdle()
        handleChecks(editor(), "bare", stylus = false)
    }

    @Test
    fun pageButtonsAndTimeline() {
        val app = ApplicationProvider.getApplicationContext<QuartzApp>()
        val container = app.container
        val notebookId = runBlocking {
            val repo = container.repository
            val shelf = repo.observeShelves().first().first()
            val id = repo.createNotebook(shelf.id, "Timeline", 0xFFD6006A.toInt(), "GLOSS", "none")
            val first = repo.observePages(id).first().first()
            repo.savePageContent(first.id, RichDoc(listOf(Block(text = "First page"), Block(type = BlockType.BULLET, text = "an item"))))
            repo.insertPage(id, 0)
            val sectionId = repo.insertSection(id, 2, 0xFFFF9F43.toInt())
            repo.saveSectionText(sectionId, "Travel", "Trips and plans")
            repo.insertPage(id, 2)
            repo.getPage(first.id)
            id
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalFontResolver provides FontResolver(BuiltInFonts),
            ) {
                QuartzTheme(settings = settings, dark = true) {
                    NotebookScreen(notebookId = notebookId, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        rule.waitForIdle()
        SystemClock.sleep(500)
        screen("20_page_buttons")
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Next page")).performClick()
        rule.waitForIdle()
        SystemClock.sleep(600)
        rule.onNode(androidx.compose.ui.test.hasText("2/4")).assertExists()
        // Wait for the notebook's pages to load before using the buttons.
        rule.waitUntil(10_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pages") and androidx.compose.ui.test.hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Pages") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        SystemClock.sleep(700)
        screen("21_page_timeline")
        rule.onNode(androidx.compose.ui.test.hasText("Travel") and !hasSetTextAction() and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        SystemClock.sleep(700)
        rule.onNode(androidx.compose.ui.test.hasText("3/4")).assertExists()
        screen("22_after_timeline_jump")
        // Alt + Left goes back a page.
        rule.onRoot().performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.AltLeft)
            keyDown(androidx.compose.ui.input.key.Key.DirectionLeft)
            keyUp(androidx.compose.ui.input.key.Key.DirectionLeft)
            keyUp(androidx.compose.ui.input.key.Key.AltLeft)
        }
        rule.waitForIdle()
        SystemClock.sleep(600)
        rule.onNode(androidx.compose.ui.test.hasText("2/4")).assertExists()
    }

    @Test
    fun mouseWheelInTimeline() {
        val app = ApplicationProvider.getApplicationContext<QuartzApp>()
        val container = app.container
        val notebookId = runBlocking {
            val repo = container.repository
            val shelf = repo.observeShelves().first().first()
            val id = repo.createNotebook(shelf.id, "Wheel", 0xFF2E86DE.toInt(), "GLOSS", "none")
            repeat(11) { repo.insertPage(id, it) }
            repo.observePages(id).first()
            id
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalFontResolver provides FontResolver(BuiltInFonts),
            ) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = notebookId, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        rule.waitForIdle()
        // Wait for the notebook's pages to load before using the buttons.
        rule.waitUntil(10_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pages") and androidx.compose.ui.test.hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Pages") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        SystemClock.sleep(700)
        // A plain up/down wheel over the pages scrolls them sideways.
        rule.onNode(androidx.compose.ui.test.hasText("Reorder & delete")).assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("1")).onFirst().assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasScrollAction() and androidx.compose.ui.test.hasAnyChild(androidx.compose.ui.test.hasText("Untitled", substring = true)))
            .performMouseInput { moveTo(center); repeat(20) { scroll(1f) } }
        rule.waitForIdle()
        SystemClock.sleep(800)
        screen("23_wheel_scrolled")
        rule.onNode(androidx.compose.ui.test.hasText("Reorder & delete")).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText("12")).assertIsDisplayed()
        // A wheel over the sheet's heading must never leave the app stuck.
        rule.onNode(androidx.compose.ui.test.hasText("Reorder & delete")).performMouseInput { repeat(10) { scroll(-1f) } }
        rule.waitForIdle()
        SystemClock.sleep(800)
        rule.onNode(androidx.compose.ui.test.hasText("Reorder & delete")).performMouseInput { repeat(10) { scroll(1f) } }
        rule.waitForIdle()
        SystemClock.sleep(1200)
        screen("24_after_wheel_on_heading")
        val sheetOpen = rule.onAllNodes(androidx.compose.ui.test.hasText("Reorder & delete")).fetchSemanticsNodes().isNotEmpty()
        note("after wheel on heading: sheet open=$sheetOpen")
        if (sheetOpen) {
            // Still open and still working: the last page opens from it.
            rule.onNode(androidx.compose.ui.test.hasText("12") and androidx.compose.ui.test.hasClickAction()).performClick()
            rule.waitForIdle()
            SystemClock.sleep(800)
            rule.onNode(androidx.compose.ui.test.hasText("12/12")).assertExists()
        } else {
            // Closed: the notebook must still respond.
            rule.onNode(androidx.compose.ui.test.hasContentDescription("Next page")).performClick()
            rule.waitForIdle()
            SystemClock.sleep(600)
            rule.onNode(androidx.compose.ui.test.hasText("2/12")).assertExists()
        }
    }

    /** Opens a fresh notebook with [pages] pages. */
    private fun openNotebook(name: String, pages: Int, toolbarOnTop: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<QuartzApp>()
        val container = app.container
        val notebookId = runBlocking {
            val repo = container.repository
            val shelf = repo.observeShelves().first().first()
            val id = repo.createNotebook(shelf.id, name, 0xFF1ABC9C.toInt(), "GLOSS", "none")
            repeat(pages - 1) { repo.insertPage(id, it) }
            repo.observePages(id).first()
            id
        }
        runBlocking { container.settings.setToolbarOnTop(toolbarOnTop) }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalFontResolver provides FontResolver(BuiltInFonts),
            ) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = notebookId, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        rule.waitForIdle()
        SystemClock.sleep(500)
    }

    @Test
    fun rightClickThumbnailToDelete() {
        openNotebook("Menu", 4)
        rule.onNode(androidx.compose.ui.test.hasText("1/4")).assertExists()
        // Wait for the notebook's pages to load before using the buttons.
        rule.waitUntil(10_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pages") and androidx.compose.ui.test.hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Pages") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        SystemClock.sleep(700)
        rule.onNode(androidx.compose.ui.test.hasText("2") and androidx.compose.ui.test.hasClickAction()).performMouseInput { rightClick(center) }
        rule.waitForIdle()
        SystemClock.sleep(400)
        screen("25_thumbnail_menu")
        rule.onNode(androidx.compose.ui.test.hasText("Delete") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        SystemClock.sleep(400)
        screen("26_delete_confirm")
        rule.onNode(androidx.compose.ui.test.hasText("Delete page 2?")).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText("Delete") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        SystemClock.sleep(800)
        rule.onNode(androidx.compose.ui.test.hasText("1/3")).assertExists()
    }

    @Test
    fun toolbarOnTop() {
        openNotebook("Top bar", 3, toolbarOnTop = true)
        screen("27_toolbar_on_top")
        val bar = rule.onNode(androidx.compose.ui.test.hasContentDescription("Next page") and androidx.compose.ui.test.hasClickAction()).fetchSemanticsNode()
        val page = editorAny().fetchSemanticsNode()
        assertTrue("page buttons should sit above the page", bar.positionInRoot.y < page.positionInRoot.y)
        runBlocking { ApplicationProvider.getApplicationContext<QuartzApp>().container.settings.setToolbarOnTop(false) }
    }

    private fun editorAny(): SemanticsNodeInteraction = rule.onAllNodes(hasSetTextAction()).onLast()
}
