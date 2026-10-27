package com.quartz.notes.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.rich.SpanOps
import com.quartz.notes.data.rich.StyleSpan
import com.quartz.notes.data.rich.newBlockId

/**
 * The text field's text starts with a hidden line break (the visual transformation removes it from
 * display). When the keyboard deletes it, we know backspace was pressed at the very start of the note,
 * which soft keyboards don't otherwise report, so a list or heading there can turn back into plain text.
 * It also tells the keyboard the note starts a new sentence, so auto-capitalization works.
 */
internal const val MARK = "\n"

/** Stands in for an in-line picture: a picture is a paragraph holding just this character. */
internal const val OBJ = '￼'

private val ListTypes = setOf(BlockType.BULLET, BlockType.TASK)
private const val MaxIndent = 6
private const val MaxUndo = 200
private val HeadingTypes = setOf(BlockType.HEADING, BlockType.SUBHEADING)

/**
 * How one paragraph looks: its type (heading, list item, task, picture), alignment, indent and, for a
 * picture, the picture itself. The paragraph's words live in the editor's single text.
 */
@Stable
class BlockState internal constructor(
    val id: String,
    type: BlockType,
    align: Align,
    checked: Boolean,
    indent: Int,
    image: ImageData? = null,
    imageWidth: Float = 0.7f,
) {
    var type by mutableStateOf(type)
    var align by mutableStateOf(align)
    var checked by mutableStateOf(checked)
    var indent by mutableIntStateOf(indent)

    /** The picture of an in-line image paragraph. */
    var image by mutableStateOf(image)
    var imageWidth by mutableFloatStateOf(imageWidth)
    val isImage: Boolean get() = type == BlockType.IMAGE

    /** A picture or a divider: a line of its own holding just the [OBJ] placeholder, with no words. */
    val isObject: Boolean get() = type == BlockType.IMAGE || type == BlockType.DIVIDER

    internal fun copyNew() = BlockState(newBlockId(), type, align, checked, indent, image, imageWidth)

    companion object {
        fun from(b: Block): BlockState {
            val isPicture = b.type == BlockType.IMAGE && b.image != null
            return BlockState(
                id = b.id,
                type = if (b.type == BlockType.IMAGE && !isPicture) BlockType.BODY else b.type,
                align = b.align,
                checked = b.checked,
                indent = b.indent,
                image = if (isPicture) b.image else null,
                imageWidth = b.imageWidth,
            )
        }

        fun empty(type: BlockType = BlockType.BODY, align: Align = Align.START, indent: Int = 0) =
            BlockState(newBlockId(), type, align, false, indent)
    }
}

/** How a paragraph looked, for undo. */
private data class BlockSnap(
    val id: String,
    val type: BlockType,
    val align: Align,
    val checked: Boolean,
    val indent: Int,
    val image: ImageData?,
    val imageWidth: Float,
)

/** The whole document at one moment, for undo and redo. */
private data class Snapshot(val value: TextFieldValue, val spans: List<StyleSpan>, val blocks: List<BlockSnap>)

/**
 * Editing state for one rich text document, edited as one continuous text so selection, select all,
 * copy and paste work across paragraphs. Paragraphs are the text's lines; [blocks] holds how each one
 * looks, and [spans] the character styles (bold, color, ...) by position in the whole text.
 */
@Stable
class RichEditorState(doc: RichDoc) {
    /** One entry per paragraph (line of [text]), in order. */
    val blocks = mutableStateListOf<BlockState>()

    /** The field's value, including the leading [MARK]. Never-equal so rejected edits still resync the field. */
    var value by mutableStateOf(TextFieldValue(MARK), neverEqualPolicy())
        private set

    /** Character styles, by offset in [text]. */
    var spans by mutableStateOf<List<StyleSpan>>(emptyList())
        private set

    /** Whether the text has the cursor. */
    var focused by mutableStateOf(false)
        private set

    /** Bumped to ask the field to take focus. */
    var focusRequest by mutableLongStateOf(0L)
        private set

    /** Bumped on every content change; observe it to autosave. */
    var version by mutableIntStateOf(0)
        private set

    val focusRequester = FocusRequester()

    /** Where the field is and how its text is laid out (set by the editor), for placing the cursor from a tap. */
    internal var fieldCoords: LayoutCoordinates? = null
    internal var fieldLayout: TextLayoutResult? = null

    // Undo / redo history.
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var lastRecordAt = 0L
    private var lastRecordWasTyping = false

    /** Whether there's something to undo / redo (observable). */
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    // Styles toggled with a collapsed cursor, applied to the next typed characters.
    private var pendingOn by mutableStateOf<Map<InlineStyle, String?>>(emptyMap())
    private var pendingOff by mutableStateOf<Set<InlineStyle>>(emptySet())

    init {
        load(doc)
    }

    /** The document's text: paragraphs separated by line breaks, pictures as [OBJ]. */
    val text: String get() = value.text.removePrefix(MARK)
    val selStart: Int get() = (value.selection.min - MARK.length).coerceIn(0, text.length)
    val selEnd: Int get() = (value.selection.max - MARK.length).coerceIn(0, text.length)
    val hasSelection: Boolean get() = selStart < selEnd

    /** Paragraph the cursor (or the start of the selection) is in. */
    private val currentIndex: Int get() = paragraphAt(text, selStart).coerceIn(0, blocks.lastIndex)

    val currentType: BlockType get() = blocks.getOrNull(currentIndex)?.type ?: BlockType.BODY
    val currentAlign: Align get() = blocks.getOrNull(currentIndex)?.align ?: Align.START

    // ---- Loading and saving ----

    private fun load(doc: RichDoc) {
        val source = doc.blocks.ifEmpty { listOf(Block()) }
        val sb = StringBuilder()
        val all = ArrayList<StyleSpan>()
        val metas = ArrayList<BlockState>()
        source.forEachIndexed { i, b ->
            if (i > 0) sb.append('\n')
            val meta = BlockState.from(b)
            if (meta.isObject) {
                sb.append(OBJ)
            } else {
                val clean = b.text.replace('\n', ' ').replace(OBJ.toString(), "")
                val start = sb.length
                sb.append(clean)
                all += SpanOps.shift(SpanOps.clip(b.spans, clean.length), start)
            }
            metas += meta
        }
        blocks.clear()
        blocks.addAll(metas)
        value = TextFieldValue(MARK + sb, TextRange(MARK.length + sb.length))
        spans = SpanOps.normalize(all)
    }

    fun toDoc(): RichDoc {
        val t = text
        val parts = t.split('\n')
        var offset = 0
        val out = parts.mapIndexed { i, part ->
            val start = offset
            offset += part.length + 1
            val meta = blocks.getOrNull(i) ?: BlockState.empty()
            if (meta.isImage && meta.image != null) {
                Block(id = meta.id, type = BlockType.IMAGE, text = "", align = meta.align, image = meta.image, imageWidth = meta.imageWidth)
            } else if (meta.type == BlockType.DIVIDER) {
                Block(id = meta.id, type = BlockType.DIVIDER, text = "")
            } else {
                val words = part.replace(OBJ.toString(), "")
                Block(
                    id = meta.id,
                    type = if (meta.isObject) BlockType.BODY else meta.type,
                    text = words,
                    spans = if (words.length == part.length) SpanOps.normalize(SpanOps.slice(spans, start, start + part.length)) else emptyList(),
                    align = meta.align,
                    checked = meta.checked,
                    indent = meta.indent,
                )
            }
        }
        return RichDoc(out)
    }

    fun isBlank(): Boolean = blocks.none { it.isObject } && text.isBlank()

    private fun changed() {
        version++
    }

    internal fun onFocusChanged(isFocused: Boolean) {
        if (focused != isFocused) focused = isFocused
    }

    private fun requestFocus() {
        focusRequest++
    }

    /** Replaces the whole document (e.g. when a page is cleared). */
    fun reset(doc: RichDoc = RichDoc()) {
        undoStack.clear()
        redoStack.clear()
        updateHistoryFlags()
        load(doc)
        clearPending()
        changed()
    }

    /** Places the cursor at the end of the document. */
    fun focusEnd() {
        if (blocks.lastOrNull()?.isObject == true) {
            record()
            // There's nowhere to type after a picture, so add a line.
            replace(text.length, text.length, "\n")
            blocks.add(BlockState.empty())
            changed()
        }
        setCursor(text.length)
        requestFocus()
    }

    private fun setCursor(docOffset: Int) {
        val c = docOffset.coerceIn(0, text.length) + MARK.length
        value = TextFieldValue(value.text, TextRange(c))
    }

    // ---- Paragraph helpers ----

    /** Start offset of each paragraph in [t]. */
    internal fun paragraphStarts(t: String = text): IntArray {
        val starts = ArrayList<Int>(blocks.size)
        starts += 0
        t.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return starts.toIntArray()
    }

    private fun paragraphText(t: String, index: Int): String = t.split('\n').getOrElse(index) { "" }

    /**
     * Replaces doc text [start, end) with [insert], keeping spans and the selection in step. Paragraph
     * entries ([blocks]) are the caller's job.
     */
    private fun replace(start: Int, end: Int, insert: String) {
        val t = text
        val newText = t.substring(0, start) + insert + t.substring(end)
        spans = SpanOps.normalize(SpanOps.remap(spans, start, end, insert.length))
        fun move(x: Int): Int {
            val d = x - MARK.length
            val moved = when {
                d >= end -> d + insert.length - (end - start)
                d > start -> start + insert.length
                else -> d
            }
            return moved + MARK.length
        }
        val sel = value.selection
        value = TextFieldValue(MARK + newText, TextRange(move(sel.start), move(sel.end)))
    }

    // ---- In-line pictures ----

    /** Inserts a picture paragraph before paragraph [index] (or at the end), with a line after it if needed. */
    fun insertImage(index: Int, data: ImageData, width: Float, align: Align = Align.CENTER): String {
        record()
        val at = index.coerceIn(0, blocks.size)
        val meta = BlockState(newBlockId(), BlockType.IMAGE, align, false, 0, data, width.coerceIn(0.25f, 1f))
        if (at < blocks.size) {
            val pos = paragraphStarts()[at]
            replace(pos, pos, "$OBJ\n")
            blocks.add(at, meta)
        } else {
            val end = text.length
            replace(end, end, "\n$OBJ")
            blocks.add(meta)
        }
        if (blocks.last() === meta) {
            val end = text.length
            replace(end, end, "\n")
            blocks.add(BlockState.empty())
        }
        changed()
        return meta.id
    }

    /**
     * Inserts a divider line after the paragraph with the cursor (or replaces it, if it's an empty
     * line), with a line after it to keep typing on.
     */
    fun insertDivider() {
        record()
        val t = text
        val index = currentIndex
        val starts = paragraphStarts(t)
        val emptyLine = !blocks[index].isObject && paragraphText(t, index).isEmpty()
        val divider = BlockState(newBlockId(), BlockType.DIVIDER, Align.START, false, 0)
        val at: Int
        if (emptyLine) {
            // Turn the empty line into the divider.
            replace(starts[index], starts[index], OBJ.toString())
            blocks[index] = divider
            at = index
        } else {
            val end = starts.getOrNull(index + 1)?.minus(1) ?: t.length
            replace(end, end, "\n$OBJ")
            blocks.add(index + 1, divider)
            at = index + 1
        }
        // Somewhere to keep typing below it.
        val after = paragraphStarts()
        val lineAfter = at + 1
        if (lineAfter > blocks.lastIndex || blocks[lineAfter].isObject) {
            val pos = (after.getOrNull(lineAfter)?.minus(1)) ?: text.length
            replace(pos, pos, "\n")
            blocks.add(lineAfter, BlockState.empty())
        }
        setCursor(paragraphStarts()[lineAfter])
        clearPending()
        if (!focused) requestFocus()
        changed()
    }

    /** Removes a paragraph (used to turn an in-line picture back into a floating one). */
    fun removeBlock(id: String) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return
        record()
        val t = text
        val starts = paragraphStarts(t)
        if (blocks.size == 1) {
            reset()
            return
        }
        if (index < blocks.lastIndex) {
            replace(starts[index], starts[index + 1], "")
        } else {
            replace(starts[index] - 1, t.length, "")
        }
        blocks.removeAt(index)
        changed()
    }

    fun updateImage(id: String, data: ImageData) {
        blocks.firstOrNull { it.id == id }?.let {
            record()
            it.image = data
            changed()
        }
    }

    fun setImageWidth(id: String, width: Float) {
        blocks.firstOrNull { it.id == id }?.let {
            record()
            it.imageWidth = width.coerceIn(0.25f, 1f)
            changed()
        }
    }

    fun setBlockAlign(id: String, align: Align) {
        blocks.firstOrNull { it.id == id }?.let {
            record()
            it.align = align
            changed()
        }
    }

    // ---- Text input ----

    internal fun onValueChange(incoming: TextFieldValue) {
        val old = value
        var raw = incoming

        // Backspace at the very start deletes the hidden marker (even when the note starts with an empty line).
        if (old.selection.collapsed && old.selection.start == MARK.length && incoming.selection.collapsed &&
            incoming.text.length == old.text.length - MARK.length && incoming.text == old.text.substring(MARK.length)
        ) {
            backspaceAtStart()
            return
        }

        if (!raw.text.startsWith(MARK)) {
            val oldDoc = old.text.removePrefix(MARK)
            if (raw.text == oldDoc) {
                // The hidden marker was deleted: backspace at the very start of the note.
                backspaceAtStart()
                return
            }
            val shift = raw.text.length - old.text.length
            raw = if (shift > 0 && raw.text.substring(shift) == old.text) {
                // Text was inserted in front of the marker; move it after the marker.
                val typed = raw.text.substring(0, shift)
                TextFieldValue(MARK + typed + oldDoc, TextRange(MARK.length + shift))
            } else {
                TextFieldValue(MARK + raw.text, TextRange(raw.selection.start + 1, raw.selection.end + 1))
            }
        }

        // Normalize Windows/old-Mac line breaks from pasted text.
        if ('\r' in raw.text) {
            val beforeCursor = raw.text.substring(0, raw.selection.end.coerceIn(0, raw.text.length))
                .replace("\r\n", "\n").replace('\r', '\n')
            raw = TextFieldValue(raw.text.replace("\r\n", "\n").replace('\r', '\n'), TextRange(beforeCursor.length))
        }

        // Keep the cursor from landing before the marker.
        val clamped = TextRange(raw.selection.start.coerceAtLeast(1), raw.selection.end.coerceAtLeast(1))
        if (clamped != raw.selection) raw = raw.copy(selection = clamped)

        val oldText = old.text.substring(1)
        val newText = raw.text.substring(1)
        if (oldText == newText) {
            if (raw.selection != old.selection) clearPending()
            value = raw
            return
        }
        record(typing = true)

        // Find the edited region.
        val minLen = minOf(oldText.length, newText.length)
        var p = 0
        while (p < minLen && oldText[p] == newText[p]) p++
        if (newText.length > oldText.length && raw.selection.collapsed) {
            // Use the cursor to disambiguate repeated characters ("a" typed before "a").
            val cursor = raw.selection.end - 1
            p = minOf(p, maxOf(0, cursor - (newText.length - oldText.length)))
        }
        var s = 0
        while (s < minOf(oldText.length, newText.length) - p &&
            oldText[oldText.length - 1 - s] == newText[newText.length - 1 - s]
        ) s++
        var removedEnd = oldText.length - s
        var insertedEnd = newText.length - s
        // A plain delete: use where the cursor was to tell which characters went ("\n\n" minus one).
        val deleted = oldText.length - newText.length
        if (old.selection.collapsed && deleted > 0 && removedEnd - p == deleted && insertedEnd == p) {
            val c = old.selection.end - MARK.length
            if (c - deleted >= 0 && c <= oldText.length && oldText.removeRange(c - deleted, c) == newText) {
                p = c - deleted
            } else if (c >= 0 && c + deleted <= oldText.length && oldText.removeRange(c, c + deleted) == newText) {
                p = c
            }
            removedEnd = p + deleted
            insertedEnd = p
        }
        val removed = oldText.substring(p, removedEnd)
        val inserted = newText.substring(p, insertedEnd)
        val pi = paragraphAt(oldText, p).coerceIn(0, blocks.lastIndex)
        val oldCursor = if (old.selection.collapsed) old.selection.end - 1 else -1

        // Backspace at the start of a paragraph (it deletes the line break before it).
        if (inserted.isEmpty() && removed == "\n" && pi + 1 <= blocks.lastIndex) {
            val prev = blocks[pi]
            val next = blocks[pi + 1]
            if (oldCursor == p + 1 && !next.isObject && next.type != BlockType.BODY) {
                // First backspace removes list or heading formatting.
                next.type = BlockType.BODY
                next.checked = false
                resync(old)
                changed()
                return
            }
            if (prev.isObject || next.isObject) {
                // Pictures keep their own line. An empty line beside one is removed instead.
                val nextText = paragraphText(oldText, pi + 1)
                val prevText = paragraphText(oldText, pi)
                when {
                    prev.type == BlockType.DIVIDER && !next.isObject && oldCursor == p + 1 && p >= 1 -> {
                        // Backspace just after a divider removes the divider.
                        resync(old)
                        replace(p - 1, p + 1, "")
                        blocks.removeAt(pi)
                        setCursor(p - 1)
                        changed()
                    }
                    prev.isObject && !next.isObject && nextText.isEmpty() && pi + 1 < blocks.lastIndex -> {
                        value = TextFieldValue(raw.text, TextRange(MARK.length + p + 1))
                        blocks.removeAt(pi + 1)
                        changed()
                    }
                    next.isObject && !prev.isObject && prevText.isEmpty() -> {
                        value = raw
                        blocks.removeAt(pi)
                        changed()
                    }
                    else -> resync(old)
                }
                return
            }
        }

        // Enter on an empty list item ends the list.
        if (removed.isEmpty() && inserted == "\n") {
            val cur = blocks[pi]
            if (cur.type in ListTypes && paragraphText(oldText, pi).isEmpty()) {
                cur.type = BlockType.BODY
                cur.checked = false
                resync(old)
                changed()
                return
            }
        }

        // Character styles follow the edit; pending styles apply to typed text.
        val onlyNewlines = inserted.isNotEmpty() && inserted.all { it == '\n' }
        var newSpans = SpanOps.remap(spans, p, removedEnd, inserted.length)
        if (inserted.isNotEmpty() && !onlyNewlines && (pendingOn.isNotEmpty() || pendingOff.isNotEmpty())) {
            newSpans = applyPending(newSpans, p, p + inserted.length)
            clearPending()
        }

        // Paragraph entries: merged paragraphs drop out; new line breaks add paragraphs.
        val removedBreaks = removed.count { it == '\n' }
        val insertedBreaks = inserted.count { it == '\n' }
        val head = blocks[pi]
        val oldStarts = paragraphStarts(oldText)
        val lastMerged = pi + removedBreaks
        // An edit that stops exactly where a picture's line starts leaves the picture alone.
        val keepPicture = removedBreaks > 0 && lastMerged <= blocks.lastIndex && blocks[lastMerged].isObject &&
            oldStarts.getOrNull(lastMerged) == removedEnd
        repeat(if (keepPicture) removedBreaks - 1 else removedBreaks) { if (pi + 1 <= blocks.lastIndex) blocks.removeAt(pi + 1) }
        if (insertedBreaks > 0) {
            val paraStart = oldText.lastIndexOf('\n', p - 1) + 1
            val atStart = p == paraStart && removed.isEmpty() && inserted == "\n" && paragraphText(oldText, pi).isNotEmpty()
            if (atStart && head.isObject) {
                // Enter in front of a picture: a plain empty line goes above it.
                blocks.add(pi, BlockState.empty())
            } else if (atStart) {
                // Enter at the very start: the paragraph moves down; the empty line above is plain.
                val below = head.copyNew()
                if (head.type in HeadingTypes) head.type = BlockType.BODY
                head.checked = false
                blocks.add(pi + 1, below)
            } else {
                val newType = if (head.type in ListTypes) head.type else BlockType.BODY
                val align = if (head.isObject) Align.START else head.align
                val indent = if (head.isObject) 0 else head.indent
                repeat(insertedBreaks) { blocks.add(pi + 1, BlockState.empty(newType, align, indent)) }
            }
        }

        spans = SpanOps.normalize(SpanOps.clip(newSpans, newText.length))
        value = raw
        if (keepPicture) {
            // Put back the line break in front of the picture.
            val at = p + inserted.length
            replace(at, at, "\n")
        }
        normalizePictures()
        changed()
    }

    /** Shows the old text again (an edit was turned into something else). */
    private fun resync(old: TextFieldValue) {
        value = TextFieldValue(old.text, old.selection)
    }

    /**
     * Keeps every picture alone on its own line: text typed beside one moves to a line of its own, a
     * picture whose placeholder was deleted is gone, and stray placeholders in text are removed.
     */
    private fun normalizePictures() {
        var i = 0
        while (i < blocks.size) {
            val t = text
            val starts = paragraphStarts(t)
            if (i >= starts.size) break
            val start = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1] - 1 else t.length
            val para = t.substring(start, end)
            val meta = blocks[i]
            if (meta.isObject) {
                when {
                    para == OBJ.toString() -> Unit
                    OBJ !in para -> {
                        // The picture was deleted; whatever's left is ordinary text.
                        meta.type = BlockType.BODY
                        meta.image = null
                    }
                    else -> {
                        val at = para.indexOf(OBJ)
                        val after = para.substring(at + 1).replace(OBJ.toString(), "")
                        val before = para.substring(0, at).replace(OBJ.toString(), "")
                        if (after.isNotEmpty() || para.length - at - 1 != after.length) {
                            replace(start + at + 1, end, "\n" + after)
                            blocks.add(i + 1, BlockState.empty(align = Align.START))
                        }
                        if (before.isNotEmpty() || at != before.length) {
                            val cursor = if (value.selection.collapsed) selEnd else -1
                            replace(start, start + at, before + "\n")
                            blocks.add(i, BlockState.empty(align = Align.START))
                            // Typing in front of a picture keeps the cursor on the new line above it.
                            if (cursor in start..(start + at)) setCursor(minOf(cursor, start + before.length))
                            i++
                        }
                    }
                }
            } else if (OBJ in para) {
                val clean = para.replace(OBJ.toString(), "")
                replace(start, end, clean)
            }
            i++
        }
        // Paragraph entries always match the text's lines.
        val lines = text.count { it == '\n' } + 1
        while (blocks.size < lines) blocks.add(BlockState.empty())
        while (blocks.size > lines) blocks.removeAt(blocks.lastIndex)
    }

    private fun backspaceAtStart() {
        val first = blocks.firstOrNull()
        if (first != null && !first.isObject && first.type != BlockType.BODY) {
            first.type = BlockType.BODY
            first.checked = false
            changed()
        }
        value = TextFieldValue(value.text, TextRange(MARK.length))
    }

    // ---- Paragraph formatting ----

    /** Paragraphs touched by the selection (or the cursor's paragraph). */
    private fun selectedParagraphs(): List<BlockState> {
        val t = text
        val a = paragraphAt(t, selStart).coerceIn(0, blocks.lastIndex)
        val b = paragraphAt(t, selEnd).coerceIn(a, blocks.lastIndex)
        return (a..b).map { blocks[it] }
    }

    fun toggleBlockType(type: BlockType) {
        if (type == BlockType.IMAGE) return
        val targets = selectedParagraphs().filter { !it.isObject }
        if (targets.isEmpty()) return
        record()
        val all = targets.all { it.type == type }
        val newType = if (all && type != BlockType.BODY) BlockType.BODY else type
        targets.forEach {
            it.type = newType
            if (newType != BlockType.TASK) it.checked = false
        }
        if (!focused) requestFocus()
        changed()
    }

    fun setBlockType(type: BlockType) {
        if (type == BlockType.IMAGE) return
        record()
        selectedParagraphs().filter { !it.isObject }.forEach {
            it.type = type
            if (type != BlockType.TASK) it.checked = false
        }
        changed()
    }

    fun toggleChecked(block: BlockState) {
        record()
        block.checked = !block.checked
        changed()
    }

    fun cycleAlign() {
        val next = Align.entries[(currentAlign.ordinal + 1) % Align.entries.size]
        setAlign(next)
    }

    fun setAlign(align: Align) {
        record()
        selectedParagraphs().forEach { it.align = align }
        changed()
    }

    // ---- Character formatting ----

    fun isStyleActive(style: InlineStyle): Boolean {
        if (style in pendingOff) return false
        if (pendingOn.containsKey(style)) return true
        return if (hasSelection) {
            SpanOps.covers(spans, style, selStart, selEnd)
        } else {
            SpanOps.activeAt(spans, style, selStart) != null
        }
    }

    fun currentValue(style: InlineStyle): String? {
        if (style in pendingOff) return null
        if (pendingOn.containsKey(style)) return pendingOn[style]
        return SpanOps.valueAt(spans, style, selStart, selEnd)
    }

    /** Toggles a style on the selection, or for the next typed text when nothing is selected. */
    fun toggleStyle(style: InlineStyle, value: String? = null) {
        if (hasSelection) {
            record()
            val s = selStart
            val e = selEnd
            spans = if (SpanOps.covers(spans, style, s, e)) {
                SpanOps.remove(spans, style, s, e)
            } else {
                SpanOps.add(spans, style, value, s, e)
            }
            changed()
        } else if (isStyleActive(style)) {
            pendingOn = pendingOn - style
            pendingOff = pendingOff + style
        } else {
            pendingOff = pendingOff - style
            pendingOn = pendingOn + (style to value)
        }
    }

    /** Sets a valued style (color, highlight, font). A null [value] removes it. */
    fun applyValue(style: InlineStyle, value: String?) {
        if (hasSelection) {
            record()
            spans = if (value == null) {
                SpanOps.remove(spans, style, selStart, selEnd)
            } else {
                SpanOps.add(spans, style, value, selStart, selEnd)
            }
            changed()
        } else if (value == null) {
            pendingOn = pendingOn - style
            pendingOff = pendingOff + style
        } else {
            pendingOff = pendingOff - style
            pendingOn = pendingOn + (style to value)
        }
    }

    // ---- Indent ----

    /** Indents (or, with a negative [delta], outdents) the selected paragraphs. */
    fun indent(delta: Int) {
        val targets = selectedParagraphs().filter { !it.isObject }
        if (targets.isEmpty() || targets.none { (it.indent + delta) in 0..MaxIndent }) return
        record()
        targets.forEach { it.indent = (it.indent + delta).coerceIn(0, MaxIndent) }
        changed()
    }

    /** Toggles the done mark on the selected task items. */
    fun toggleSelectedTasks() {
        val tasks = selectedParagraphs().filter { it.type == BlockType.TASK }
        if (tasks.isEmpty()) return
        record()
        val done = !tasks.all { it.checked }
        tasks.forEach { it.checked = done }
        changed()
    }

    // ---- Clipboard and pointer helpers ----

    /** The selected words as plain text (pictures left out). */
    fun selectedText(): String =
        if (hasSelection) text.substring(selStart, selEnd).replace(OBJ.toString(), "") else ""

    /** Replaces the selection with [s] (or inserts it at the cursor), as if typed. */
    fun insertText(s: String) {
        val v = value
        val start = v.selection.min.coerceAtLeast(MARK.length)
        val end = v.selection.max.coerceAtLeast(MARK.length)
        onValueChange(TextFieldValue(v.text.substring(0, start) + s + v.text.substring(end), TextRange(start + s.length)))
    }

    fun selectAll() {
        value = TextFieldValue(value.text, TextRange(MARK.length, value.text.length))
    }

    /**
     * Puts the cursor where a tap or click landed. [point] is in [from]'s coordinates; points beside
     * the text (in the page margin, or right of a short line) land on the nearest line.
     */
    fun placeCursorAt(point: Offset, from: LayoutCoordinates): Boolean {
        val coords = fieldCoords ?: return false
        val layout = fieldLayout ?: return false
        if (!coords.isAttached || !from.isAttached) return false
        val local = coords.localPositionOf(from, point)
        val x = local.x.coerceIn(0f, coords.size.width.toFloat())
        val y = local.y.coerceIn(0f, (coords.size.height - 1).coerceAtLeast(0).toFloat())
        val offset = layout.getOffsetForPosition(Offset(x, y)).coerceIn(0, text.length)
        clearPending()
        setCursor(offset)
        requestFocus()
        return true
    }

    // ---- Undo / redo ----

    private fun snapshot() = Snapshot(
        value = TextFieldValue(value.text, value.selection),
        spans = spans,
        blocks = blocks.map { BlockSnap(it.id, it.type, it.align, it.checked, it.indent, it.image, it.imageWidth) },
    )

    /**
     * Saves how things are before a change. Runs of typing within a moment of each other undo together;
     * every other change is its own step.
     */
    private fun record(typing: Boolean = false) {
        val now = System.currentTimeMillis()
        val coalesce = typing && lastRecordWasTyping && now - lastRecordAt < 1200 && undoStack.isNotEmpty()
        lastRecordAt = now
        lastRecordWasTyping = typing
        redoStack.clear()
        if (!coalesce) {
            val snap = snapshot()
            if (undoStack.lastOrNull() != snap) {
                undoStack.addLast(snap)
                if (undoStack.size > MaxUndo) undoStack.removeFirst()
            }
        }
        updateHistoryFlags()
    }

    private fun restore(snap: Snapshot) {
        blocks.clear()
        blocks.addAll(snap.blocks.map { BlockState(it.id, it.type, it.align, it.checked, it.indent, it.image, it.imageWidth) })
        spans = snap.spans
        value = TextFieldValue(snap.value.text, snap.value.selection)
        clearPending()
        changed()
    }

    fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(snap)
        lastRecordWasTyping = false
        updateHistoryFlags()
    }

    fun redo() {
        val snap = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(snap)
        lastRecordWasTyping = false
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun applyPending(spans: List<StyleSpan>, start: Int, end: Int): List<StyleSpan> {
        var out = spans
        pendingOff.forEach { out = SpanOps.remove(out, it, start, end) }
        pendingOn.forEach { (style, value) -> out = SpanOps.add(out, style, value, start, end) }
        return out
    }

    private fun clearPending() {
        if (pendingOn.isNotEmpty()) pendingOn = emptyMap()
        if (pendingOff.isNotEmpty()) pendingOff = emptySet()
    }
}

/** Index of the paragraph containing doc offset [offset]. */
internal fun paragraphAt(t: String, offset: Int): Int {
    var n = 0
    val end = offset.coerceIn(0, t.length)
    for (i in 0 until end) if (t[i] == '\n') n++
    return n
}
