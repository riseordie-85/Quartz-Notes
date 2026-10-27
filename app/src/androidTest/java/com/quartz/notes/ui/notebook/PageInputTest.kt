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

        mouseClick(onScreen(field, Offset(width - 20f, y1)))
        note("mouse click right of line 2: selection=${selection(field)} expected end of line 2 = ${paragraphEnd(1) + 1}")
        screen("10_mouse_click_line")

        fingerTap(onScreen(field, Offset(width - 20f, (l.getLineTop(0) + l.getLineBottom(0)) / 2)))
        note("finger tap right of line 1: selection=${selection(field)} expected ${paragraphEnd(0) + 1}")

        // Left margin of the page, beside line 4.
        val line3 = l.getLineForOffset(paragraphEnd(2) + 2)
        mouseClick(onScreen(field, Offset(-10f, (l.getLineTop(line3) + l.getLineBottom(line3)) / 2)))
        note("mouse click in left margin of line 4: selection=${selection(field)} expected start of line 4 = ${paragraphEnd(2) + 2}")
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
        screen("14_${label}_mouse_drag")
        // Now try moving the end of that selection with a finger on its handle (if handles show).
        val sel = selection(field) ?: return
        val l2 = layout(field)
        val rect = l2.getCursorRect((sel.max - 1).coerceIn(0, l2.layoutInput.text.length))
        val handle = onScreen(field, Offset(rect.left + 12f, rect.bottom + 24f))
        fingerDrag(handle, handle + Offset(0f, l2.getLineBottom(1) - l2.getLineTop(0)))
        note("$label after finger drag on end handle: selection=${selection(field)} (was $sel)")
        screen("15_${label}_handle_drag")
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
}
