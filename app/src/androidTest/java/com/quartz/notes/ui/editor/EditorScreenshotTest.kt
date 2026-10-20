package com.quartz.notes.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.rich.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Lays the editor out on a real device and saves screenshots (CI pushes them to the `ci-shots`
 * branch) so the look can be checked: headings, lists, tasks, alignment and selection across paragraphs.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class EditorScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sample() = RichDoc(
        listOf(
            Block(type = BlockType.HEADING, text = "Weekend plans"),
            Block(text = "A normal paragraph that is long enough to wrap onto a second line of the page.", spans = listOf(StyleSpan(2, 8, InlineStyle.BOLD), StyleSpan(9, 18, InlineStyle.HIGHLIGHT, "#FFE14D"))),
            Block(type = BlockType.SUBHEADING, text = "Shopping"),
            Block(type = BlockType.BULLET, text = "Eggs"),
            Block(type = BlockType.BULLET, text = "Bread, a long bullet item that wraps onto another line to check the indent"),
            Block(type = BlockType.BULLET, text = "Nested", indent = 1),
            Block(type = BlockType.TASK, text = "Call mom", checked = true),
            Block(type = BlockType.TASK, text = "Wash the car"),
            Block(text = ""),
            Block(text = "Centered line", align = Align.CENTER),
            Block(text = "Right line", align = Align.END),
            Block(text = "Last line"),
        ),
    )

    private fun setEditor(state: RichEditorState) {
        rule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState())) {
                    RichTextEditor(
                        state = state,
                        styles = editorStyles(),
                        contentColor = Color(0xFF111114),
                        modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("editor"),
                    )
                }
            }
        }
    }

    @Test
    fun layout() {
        val state = RichEditorState(sample())
        setEditor(state)
        save("01_layout")
        assertEquals(12, state.blocks.size)
    }

    @Test
    fun typingEnterAndLists() {
        val state = RichEditorState(RichDoc(listOf(Block(type = BlockType.BULLET, text = "First"))))
        setEditor(state)
        val field = rule.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInputSelection(TextRange(state.value.text.length))
        field.performTextInput("\nSecond")
        field.performTextInput("\n")
        field.performTextInput("\n") // empty bullet: ends the list
        field.performTextInput("Plain text after the list")
        save("02_typing")
        val doc = state.toDoc()
        assertEquals(listOf("First", "Second", "Plain text after the list"), doc.blocks.map { it.text })
        assertEquals(listOf(BlockType.BULLET, BlockType.BULLET, BlockType.BODY), doc.blocks.map { it.type })
    }

    @Test
    fun selectAcrossParagraphs() {
        val state = RichEditorState(sample())
        setEditor(state)
        val field = rule.onNode(hasSetTextAction())
        field.performClick()
        // Select from inside the first paragraph to inside the bullets.
        field.performTextInputSelection(TextRange(5, 120))
        save("03_selection")
        assertTrue(state.hasSelection)
        state.toggleStyle(InlineStyle.ITALIC)
        rule.waitForIdle()
        save("04_italic_across")
        // Select everything.
        field.performTextInputSelection(TextRange(0, state.value.text.length))
        save("05_select_all")
        assertEquals(0, state.selStart)
        assertEquals(state.text.length, state.selEnd)
    }
}
