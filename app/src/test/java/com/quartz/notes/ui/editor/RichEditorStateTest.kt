package com.quartz.notes.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.rich.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing behaviour of the single-field editor, driven the way the keyboard drives it. */
class RichEditorStateTest {

    // ---- Simulated keyboard ----

    private fun RichEditorState.type(s: String) {
        val v = value
        val sel = v.selection
        val newText = v.text.substring(0, sel.min) + s + v.text.substring(sel.max)
        onValueChange(TextFieldValue(newText, TextRange(sel.min + s.length)))
    }

    private fun RichEditorState.enter() = type("\n")

    private fun RichEditorState.backspace() {
        val v = value
        val sel = v.selection
        if (!sel.collapsed) {
            onValueChange(TextFieldValue(v.text.substring(0, sel.min) + v.text.substring(sel.max), TextRange(sel.min)))
            return
        }
        if (sel.start == 0) return
        onValueChange(TextFieldValue(v.text.removeRange(sel.start - 1, sel.start), TextRange(sel.start - 1)))
    }

    /** Selects doc range [start, end) (doc offsets, without the hidden marker). */
    private fun RichEditorState.select(start: Int, end: Int = start) {
        onValueChange(TextFieldValue(value.text, TextRange(start + 1, end + 1)))
    }

    private fun RichEditorState.paste(s: String) = type(s)

    private fun doc(vararg blocks: Block) = RichEditorState(RichDoc(blocks.toList()))

    private fun RichEditorState.texts() = toDoc().blocks.map { it.text }
    private fun RichEditorState.types() = toDoc().blocks.map { it.type }

    // ---- Tests ----

    @Test
    fun loadAndSaveKeepsEverything() {
        val image = ImageData(path = "media/a.jpg", aspect = 0.75f)
        val original = RichDoc(
            listOf(
                Block(type = BlockType.HEADING, text = "Title"),
                Block(text = "Hello world", spans = listOf(StyleSpan(0, 5, InlineStyle.BOLD))),
                Block(type = BlockType.BULLET, text = "one", indent = 1),
                Block(type = BlockType.TASK, text = "done", checked = true),
                Block(type = BlockType.IMAGE, image = image, imageWidth = 0.5f, align = Align.END),
                Block(text = "end", align = Align.CENTER),
            ),
        )
        val state = RichEditorState(original)
        assertEquals("Title\nHello world\none\ndone\n$OBJ\nend", state.text)
        val saved = state.toDoc()
        assertEquals(original.blocks.map { it.type }, saved.blocks.map { it.type })
        assertEquals(original.blocks.map { it.text }, saved.blocks.map { it.text })
        assertEquals(listOf(StyleSpan(0, 5, InlineStyle.BOLD)), saved.blocks[1].spans)
        assertEquals(1, saved.blocks[2].indent)
        assertTrue(saved.blocks[3].checked)
        assertEquals(image, saved.blocks[4].image)
        assertEquals(0.5f, saved.blocks[4].imageWidth)
        assertEquals(Align.END, saved.blocks[4].align)
        assertEquals(Align.CENTER, saved.blocks[5].align)
        assertEquals(original.blocks.map { it.id }, saved.blocks.map { it.id })
    }

    @Test
    fun typingAndEnterMakeParagraphs() {
        val s = doc(Block())
        s.type("Hello")
        s.enter()
        s.type("World")
        assertEquals(listOf("Hello", "World"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.BODY), s.types())
    }

    @Test
    fun enterInListContinuesItAndEnterOnEmptyItemEndsIt() {
        val s = doc(Block(type = BlockType.BULLET, text = "one"))
        s.select(3)
        s.enter()
        s.type("two")
        assertEquals(listOf(BlockType.BULLET, BlockType.BULLET), s.types())
        s.enter()
        s.enter() // on the empty bullet: ends the list
        assertEquals(listOf("one", "two", ""), s.texts())
        assertEquals(listOf(BlockType.BULLET, BlockType.BULLET, BlockType.BODY), s.types())
    }

    @Test
    fun enterAfterHeadingGivesPlainText() {
        val s = doc(Block(type = BlockType.HEADING, text = "Title"))
        s.select(5)
        s.enter()
        assertEquals(listOf(BlockType.HEADING, BlockType.BODY), s.types())
    }

    @Test
    fun enterAtStartOfHeadingKeepsHeadingBelow() {
        val s = doc(Block(type = BlockType.HEADING, text = "Title"))
        s.select(0)
        s.enter()
        assertEquals(listOf("", "Title"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.HEADING), s.types())
    }

    @Test
    fun backspaceAtStartOfListItemRemovesTheBulletFirst() {
        val s = doc(Block(text = "intro"), Block(type = BlockType.BULLET, text = "item"))
        s.select(6) // start of "item"
        s.backspace()
        assertEquals(listOf("intro", "item"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.BODY), s.types())
        s.backspace() // now merges
        assertEquals(listOf("introitem"), s.texts())
    }

    @Test
    fun backspaceAtVeryStartOfNoteRemovesListFormatting() {
        val s = doc(Block(type = BlockType.TASK, text = "todo"))
        s.select(0)
        s.backspace()
        assertEquals(listOf("todo"), s.texts())
        assertEquals(listOf(BlockType.BODY), s.types())
    }

    @Test
    fun selectingAcrossParagraphsAndDeletingMerges() {
        val s = doc(Block(text = "alpha"), Block(type = BlockType.BULLET, text = "beta"), Block(text = "gamma"))
        // "alpha\nbeta\ngamma": select from "al|pha" to "ga|mma"
        s.select(2, 13)
        assertTrue(s.hasSelection)
        s.backspace()
        assertEquals(listOf("almma"), s.texts())
        assertEquals(1, s.blocks.size)
    }

    @Test
    fun selectAllAndBoldCoversEveryParagraph() {
        val s = doc(Block(text = "one"), Block(text = "two"), Block(text = "three"))
        s.select(0, s.text.length)
        s.toggleStyle(InlineStyle.BOLD)
        val blocks = s.toDoc().blocks
        assertEquals(listOf(StyleSpan(0, 3, InlineStyle.BOLD)), blocks[0].spans)
        assertEquals(listOf(StyleSpan(0, 3, InlineStyle.BOLD)), blocks[1].spans)
        assertEquals(listOf(StyleSpan(0, 5, InlineStyle.BOLD)), blocks[2].spans)
        assertTrue(s.isStyleActive(InlineStyle.BOLD))
    }

    @Test
    fun listToggleAppliesToEverySelectedParagraph() {
        val s = doc(Block(text = "a"), Block(text = "b"), Block(text = "c"))
        s.select(0, 3) // "a\nb"
        s.toggleBlockType(BlockType.BULLET)
        assertEquals(listOf(BlockType.BULLET, BlockType.BULLET, BlockType.BODY), s.types())
        s.toggleBlockType(BlockType.BULLET)
        assertEquals(listOf(BlockType.BODY, BlockType.BODY, BlockType.BODY), s.types())
    }

    @Test
    fun pastingSeveralLinesMakesParagraphs() {
        val s = doc(Block(text = "start"))
        s.select(5)
        s.paste(" one\r\ntwo\nthree")
        assertEquals(listOf("start one", "two", "three"), s.texts())
    }

    @Test
    fun pendingBoldAppliesToTypedText() {
        val s = doc(Block(text = "x"))
        s.select(1)
        s.toggleStyle(InlineStyle.BOLD)
        s.type("yz")
        assertEquals(listOf(StyleSpan(1, 3, InlineStyle.BOLD)), s.toDoc().blocks[0].spans)
    }

    @Test
    fun picturesStayOnTheirOwnLine() {
        val s = doc(Block(text = "before"), Block(text = "after"))
        s.insertImage(1, ImageData(path = "p.jpg", aspect = 1f), 0.5f)
        assertEquals(listOf(BlockType.BODY, BlockType.IMAGE, BlockType.BODY), s.types())
        // Put the cursor right after the picture and type: the text goes to a new line.
        s.select(8) // "before\n" is 7 chars, the picture is at 7
        s.type("hi")
        assertEquals(listOf(BlockType.BODY, BlockType.IMAGE, BlockType.BODY, BlockType.BODY), s.types())
        assertEquals(listOf("before", "", "hi", "after"), s.texts())
    }

    @Test
    fun backspaceAfterPictureDoesNotMergeText() {
        val s = doc(Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f)), Block(text = "text"))
        s.select(2) // start of "text"
        s.backspace()
        assertEquals(listOf(BlockType.IMAGE, BlockType.BODY), s.types())
        assertEquals("text", s.texts()[1])
    }

    @Test
    fun deletingThePictureCharacterRemovesThePicture() {
        val s = doc(Block(text = "a"), Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f)), Block(text = "b"))
        s.select(2, 3) // the picture
        s.backspace()
        assertFalse(s.toDoc().blocks.any { it.type == BlockType.IMAGE })
        assertEquals(s.text.count { it == '\n' } + 1, s.blocks.size)
    }

    @Test
    fun removeBlockAndFocusEnd() {
        val s = doc(Block(text = "a"))
        val id = s.insertImage(1, ImageData(path = "p.jpg", aspect = 1f), 0.5f)
        assertEquals(3, s.blocks.size) // text, picture, empty line after
        s.removeBlock(id)
        assertEquals(listOf("a", ""), s.texts())
        s.focusEnd()
        assertEquals(s.text.length, s.selEnd)
    }

    @Test
    fun blocksAlwaysMatchLines() {
        val s = doc(Block(text = "one"), Block(type = BlockType.TASK, text = "two"))
        s.select(1, 5)
        s.paste("X\nY\nZ")
        s.enter()
        s.backspace()
        s.backspace()
        assertEquals(s.text.count { it == '\n' } + 1, s.blocks.size)
        assertEquals(s.texts().size, s.blocks.size)
    }

    @Test
    fun deletingAnEmptyLineKeepsTheNextLinesLook() {
        val s = doc(Block(text = "a"), Block(text = ""), Block(type = BlockType.HEADING, text = "Title"))
        s.select(2) // on the empty line
        s.backspace()
        assertEquals(listOf("a", "Title"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.HEADING), s.types())
    }

    @Test
    fun backspaceAtStartWhenNoteStartsWithEmptyLineDoesNothing() {
        val s = doc(Block(text = ""), Block(type = BlockType.TASK, text = "todo"))
        s.select(0)
        s.backspace()
        assertEquals(listOf("", "todo"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.TASK), s.types())
    }

    @Test
    fun typingInFrontOfAPictureMakesOneLineAboveIt() {
        val s = doc(Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f)), Block(text = "after"))
        s.select(0)
        s.type("h")
        s.type("i")
        assertEquals(listOf("hi", "", "after"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.IMAGE, BlockType.BODY), s.types())
    }

    @Test
    fun enterInFrontOfAPictureAddsAPlainLineAndKeepsThePicture() {
        val s = doc(Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f), align = Align.CENTER), Block(text = "x"))
        val id = s.blocks[0].id
        s.select(0)
        s.enter()
        assertEquals(listOf(BlockType.BODY, BlockType.IMAGE, BlockType.BODY), s.types())
        assertEquals(id, s.blocks[1].id)
        assertEquals(Align.START, s.blocks[0].align)
    }

    @Test
    fun deletingUpToAPictureKeepsThePicture() {
        val s = doc(Block(text = "ab"), Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f)), Block(text = "c"))
        s.select(1, 3) // "b\n"
        s.backspace()
        assertEquals(listOf("a", "", "c"), s.texts())
        assertEquals(listOf(BlockType.BODY, BlockType.IMAGE, BlockType.BODY), s.types())
    }

    @Test
    fun deletingTextBetweenTwoPicturesKeepsBoth() {
        val pic = ImageData(path = "p.jpg", aspect = 1f)
        val s = doc(Block(type = BlockType.IMAGE, image = pic), Block(text = "x"), Block(type = BlockType.IMAGE, image = pic), Block(text = ""))
        s.select(1, 4) // "\nx\n"
        s.backspace()
        assertEquals(listOf(BlockType.IMAGE, BlockType.IMAGE, BlockType.BODY), s.types())
    }

    @Test
    fun enterAfterACenteredPictureStartsALeftAlignedLine() {
        val s = doc(Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f), align = Align.CENTER))
        s.select(1)
        s.enter()
        assertEquals(listOf(BlockType.IMAGE, BlockType.BODY), s.types())
        assertEquals(Align.START, s.blocks[1].align)
    }

    @Test
    fun undoAndRedoTypingAndFormatting() {
        val s = doc(Block(text = "one"))
        s.select(3)
        s.type(" two")
        s.select(0, 3)
        s.toggleStyle(InlineStyle.BOLD)
        s.toggleBlockType(BlockType.BULLET)
        assertEquals(BlockType.BULLET, s.types()[0])
        s.undo() // bullet
        assertEquals(BlockType.BODY, s.types()[0])
        s.undo() // bold
        assertTrue(s.toDoc().blocks[0].spans.isEmpty())
        s.undo() // typing
        assertEquals(listOf("one"), s.texts())
        s.redo()
        assertEquals(listOf("one two"), s.texts())
        assertTrue(s.canUndo)
        assertTrue(s.canRedo)
    }

    @Test
    fun indentAndOutdent() {
        val s = doc(Block(type = BlockType.BULLET, text = "a"), Block(type = BlockType.BULLET, text = "b"))
        s.select(2)
        s.indent(1)
        assertEquals(listOf(0, 1), s.toDoc().blocks.map { it.indent })
        s.indent(-1)
        s.indent(-1)
        assertEquals(listOf(0, 0), s.toDoc().blocks.map { it.indent })
    }

    @Test
    fun selectedTextAndInsertText() {
        val s = doc(Block(text = "hello"), Block(type = BlockType.IMAGE, image = ImageData(path = "p.jpg", aspect = 1f)), Block(text = "world"))
        s.selectAll()
        assertEquals("hello\n\nworld", s.selectedText())
        s.select(0, 5)
        s.insertText("bye")
        assertEquals("bye", s.texts()[0])
    }

    @Test
    fun checkingSelectedTasks() {
        val s = doc(Block(type = BlockType.TASK, text = "a"), Block(type = BlockType.TASK, text = "b", checked = true))
        s.select(0, 3)
        s.toggleSelectedTasks()
        assertTrue(s.toDoc().blocks.all { it.checked })
        s.toggleSelectedTasks()
        assertTrue(s.toDoc().blocks.none { it.checked })
    }

    @Test
    fun dividerGoesBelowTheLineWithALineAfterIt() {
        val s = doc(Block(text = "one"), Block(text = "two"))
        s.select(1)
        s.insertDivider()
        assertEquals(listOf(BlockType.BODY, BlockType.DIVIDER, BlockType.BODY, BlockType.BODY), s.types())
        assertEquals(listOf("one", "", "", "two"), s.texts())
        // The cursor is on the new empty line under the divider.
        s.type("x")
        assertEquals(listOf("one", "", "x", "two"), s.texts())
    }

    @Test
    fun dividerOnAnEmptyLineReplacesIt() {
        val s = doc(Block(text = "one"), Block(text = ""), Block(text = "two"))
        s.select(4)
        s.insertDivider()
        assertEquals(listOf(BlockType.BODY, BlockType.DIVIDER, BlockType.BODY), s.types())
        assertEquals(listOf("one", "", "two"), s.texts())
        assertEquals(s.paragraphStarts()[2], s.selStart)
    }

    @Test
    fun dividerAtTheEndAddsALineAndSurvivesSaving() {
        val s = doc(Block(text = "one"))
        s.select(3)
        s.insertDivider()
        assertEquals(listOf(BlockType.BODY, BlockType.DIVIDER, BlockType.BODY), s.types())
        val again = RichEditorState(s.toDoc())
        assertEquals(listOf(BlockType.BODY, BlockType.DIVIDER, BlockType.BODY), again.types())
        // Backspace just after the divider removes it and keeps the text.
        again.select(again.paragraphStarts()[2])
        again.type("z")
        again.select(again.paragraphStarts()[2])
        again.backspace()
        assertEquals(listOf(BlockType.BODY, BlockType.BODY), again.types())
        assertEquals(listOf("one", "z"), again.texts())
        assertEquals(4, again.selStart)
    }

    @Test
    fun deletingTheDividerAndUndo() {
        val s = doc(Block(text = "one"), Block(type = BlockType.DIVIDER), Block(text = "two"))
        s.select(3, 6)
        s.backspace()
        assertTrue(BlockType.DIVIDER !in s.types())
        s.undo()
        assertEquals(listOf(BlockType.BODY, BlockType.DIVIDER, BlockType.BODY), s.types())
    }
}
