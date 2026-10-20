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
            if (meta.isImage) {
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
            } else {
                val words = part.replace(OBJ.toString(), "")
                Block(
                    id = meta.id,
                    type = if (meta.isImage) BlockType.BODY else meta.type,
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

    fun isBlank(): Boolean = blocks.none { it.isImage } && text.isBlank()

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
        load(doc)
        clearPending()
        changed()
    }

    /** Places the cursor at the end of the document. */
    fun focusEnd() {
        if (blocks.lastOrNull()?.isImage == true) {
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

    /** Removes a paragraph (used to turn an in-line picture back into a floating one). */
    fun removeBlock(id: String) {
        val index = blocks.indexOfFirst { it.id == id }
        if (index < 0) return
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
            it.image = data
            changed()
        }
    }

    fun setImageWidth(id: String, width: Float) {
        blocks.firstOrNull { it.id == id }?.let {
            it.imageWidth = width.coerceIn(0.25f, 1f)
            changed()
        }
    }

    fun setBlockAlign(id: String, align: Align) {
        blocks.firstOrNull { it.id == id }?.let {
            it.align = align
            changed()
        }
    }

    // ---- Text input ----

    internal fun onValueChange(incoming: TextFieldValue) {
        val old = value
        var raw = incoming

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
        val removedEnd = oldText.length - s
        val insertedEnd = newText.length - s
        val removed = oldText.substring(p, removedEnd)
        val inserted = newText.substring(p, insertedEnd)
        val pi = paragraphAt(oldText, p).coerceIn(0, blocks.lastIndex)
        val oldCursor = if (old.selection.collapsed) old.selection.end - 1 else -1

        // Backspace at the start of a paragraph (it deletes the line break before it).
        if (inserted.isEmpty() && removed == "\n" && pi + 1 <= blocks.lastIndex) {
            val prev = blocks[pi]
            val next = blocks[pi + 1]
            if (oldCursor == p + 1 && !next.isImage && next.type != BlockType.BODY) {
                // First backspace removes list or heading formatting.
                next.type = BlockType.BODY
                next.checked = false
                resync(old)
                changed()
                return
            }
            if (prev.isImage || next.isImage) {
                // Pictures keep their own line. An empty line beside one is removed instead.
                val nextText = paragraphText(oldText, pi + 1)
                val prevText = paragraphText(oldText, pi)
                when {
                    prev.isImage && !next.isImage && nextText.isEmpty() && pi + 1 < blocks.lastIndex -> {
                        value = TextFieldValue(raw.text, TextRange(MARK.length + p + 1))
                        blocks.removeAt(pi + 1)
                        changed()
                    }
                    next.isImage && !prev.isImage && prevText.isEmpty() -> {
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
        repeat(removedBreaks) { if (pi + 1 <= blocks.lastIndex) blocks.removeAt(pi + 1) }
        if (insertedBreaks > 0) {
            val paraStart = oldText.lastIndexOf('\n', p - 1) + 1
            val atStart = p == paraStart && removed.isEmpty() && inserted == "\n" && paragraphText(oldText, pi).isNotEmpty()
            if (atStart) {
                // Enter at the very start: the paragraph moves down; the empty line above is plain.
                val below = head.copyNew()
                if (head.isImage) {
                    head.type = BlockType.BODY
                    head.image = null
                }
                if (head.type in HeadingTypes) head.type = BlockType.BODY
                head.checked = false
                blocks.add(pi + 1, below)
            } else {
                val newType = if (head.type in ListTypes) head.type else BlockType.BODY
                repeat(insertedBreaks) { blocks.add(pi + 1, BlockState.empty(newType, head.align, head.indent)) }
            }
        }

        spans = SpanOps.normalize(SpanOps.clip(newSpans, newText.length))
        value = raw
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
            if (meta.isImage) {
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
                            replace(start, start + at, before + "\n")
                            blocks.add(i, BlockState.empty(align = Align.START))
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
        if (first != null && !first.isImage && first.type != BlockType.BODY) {
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
        val targets = selectedParagraphs().filter { !it.isImage }
        if (targets.isEmpty()) return
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
        selectedParagraphs().filter { !it.isImage }.forEach {
            it.type = type
            if (type != BlockType.TASK) it.checked = false
        }
        changed()
    }

    fun toggleChecked(block: BlockState) {
        block.checked = !block.checked
        changed()
    }

    fun cycleAlign() {
        val next = Align.entries[(currentAlign.ordinal + 1) % Align.entries.size]
        setAlign(next)
    }

    fun setAlign(align: Align) {
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
