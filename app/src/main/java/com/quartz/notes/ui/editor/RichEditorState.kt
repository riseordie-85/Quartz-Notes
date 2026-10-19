package com.quartz.notes.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.rich.SpanOps
import com.quartz.notes.data.rich.StyleSpan
import com.quartz.notes.data.rich.newBlockId

/**
 * Every block's text field starts with an invisible zero-width space. When the keyboard deletes it,
 * we know backspace was pressed at the very start of the block (which soft keyboards don't otherwise
 * report), so the block can merge into the one above.
 */
internal const val ZWSP = "​"

private val ListTypes = setOf(BlockType.BULLET, BlockType.TASK)
private val HeadingTypes = setOf(BlockType.HEADING, BlockType.SUBHEADING)

@Stable
class BlockState internal constructor(
    val id: String,
    type: BlockType,
    text: String,
    spans: List<StyleSpan>,
    align: Align,
    checked: Boolean,
    indent: Int,
) {
    var type by mutableStateOf(type)
    var align by mutableStateOf(align)
    var checked by mutableStateOf(checked)
    var indent by mutableIntStateOf(indent)

    /** Raw field value, including the leading [ZWSP]. Never-equal so rejected edits still resync the field. */
    var value by mutableStateOf(TextFieldValue(ZWSP + text, TextRange(ZWSP.length + text.length)), neverEqualPolicy())
    var spans by mutableStateOf(spans)

    val focusRequester = FocusRequester()

    val text: String get() = value.text.removePrefix(ZWSP)
    val selStart: Int get() = (value.selection.min - 1).coerceIn(0, text.length)
    val selEnd: Int get() = (value.selection.max - 1).coerceIn(0, text.length)
    val hasSelection: Boolean get() = selStart < selEnd

    internal fun setContent(newText: String, newSpans: List<StyleSpan>, cursor: Int) {
        val c = cursor.coerceIn(0, newText.length)
        value = TextFieldValue(ZWSP + newText, TextRange(c + 1))
        spans = SpanOps.normalize(SpanOps.clip(newSpans, newText.length))
    }

    internal fun setCursor(cursor: Int) {
        val c = cursor.coerceIn(0, text.length)
        value = TextFieldValue(value.text, TextRange(c + 1))
    }

    fun toBlock(): Block = Block(
        id = id,
        type = type,
        text = text,
        spans = SpanOps.normalize(SpanOps.clip(spans, text.length)),
        align = align,
        checked = checked,
        indent = indent,
    )

    companion object {
        fun from(b: Block) = BlockState(b.id, b.type, b.text, b.spans, b.align, b.checked, b.indent)
        fun empty(type: BlockType = BlockType.BODY, align: Align = Align.START, indent: Int = 0) =
            BlockState(newBlockId(), type, "", emptyList(), align, false, indent)
    }
}

data class FocusRequest(val blockId: String, val nonce: Long)

/** Editing state for one rich text document. */
@Stable
class RichEditorState(doc: RichDoc) {
    val blocks = mutableStateListOf<BlockState>()

    var focusedId by mutableStateOf<String?>(null)
        private set

    var focusRequest by mutableStateOf<FocusRequest?>(null)
        private set

    /** Bumped on every content change; observe it to autosave. */
    var version by mutableIntStateOf(0)
        private set

    // Styles toggled with a collapsed cursor, applied to the next typed characters.
    private var pendingOn by mutableStateOf<Map<InlineStyle, String?>>(emptyMap())
    private var pendingOff by mutableStateOf<Set<InlineStyle>>(emptySet())
    private var nonce = 0L

    init {
        blocks.addAll(doc.blocks.ifEmpty { listOf(Block()) }.map(BlockState::from))
    }

    val focusedBlock: BlockState? get() = blocks.firstOrNull { it.id == focusedId }

    /** Block that toolbar actions apply to: the focused one, or the last block. */
    private val target: BlockState get() = focusedBlock ?: blocks.last()

    val currentType: BlockType get() = (focusedBlock ?: blocks.first()).type
    val currentAlign: Align get() = (focusedBlock ?: blocks.first()).align
    val hasSelection: Boolean get() = focusedBlock?.hasSelection == true

    fun toDoc(): RichDoc = RichDoc(blocks.map { it.toBlock() })

    fun isBlank(): Boolean = blocks.all { it.text.isBlank() }

    private fun changed() {
        version++
    }

    private fun requestFocus(block: BlockState) {
        focusRequest = FocusRequest(block.id, ++nonce)
    }

    internal fun onFocused(block: BlockState) {
        if (focusedId != block.id) {
            focusedId = block.id
            clearPending()
        }
    }

    /** Places the cursor at the end of the document. */
    fun focusEnd() {
        val last = blocks.last()
        last.setCursor(last.text.length)
        requestFocus(last)
    }

    // ---- Text input ----

    internal fun onValueChange(block: BlockState, incoming: TextFieldValue) {
        if (blocks.indexOf(block) < 0) return
        val old = block.value
        var raw = incoming

        if (!raw.text.startsWith(ZWSP)) {
            val oldActual = old.text.removePrefix(ZWSP)
            if (raw.text == oldActual) {
                // The invisible marker was deleted: backspace at the start of the block.
                backspaceAtStart(block)
                return
            }
            val idx = raw.text.indexOf(ZWSP)
            val cleaned = if (idx >= 0) raw.text.removeRange(idx, idx + 1) else raw.text
            fun fix(o: Int) = if (idx >= 0 && o > idx) o else o + 1
            raw = TextFieldValue(ZWSP + cleaned, TextRange(fix(raw.selection.start), fix(raw.selection.end)))
        }

        // Keep the cursor from landing before the marker.
        val clamped = TextRange(raw.selection.start.coerceAtLeast(1), raw.selection.end.coerceAtLeast(1))
        if (clamped != raw.selection) raw = raw.copy(selection = clamped)

        val oldText = old.text.substring(1)
        val newText = raw.text.substring(1)
        if (oldText == newText) {
            if (raw.selection != old.selection) clearPending()
            block.value = raw
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
        val insertedLength = insertedEnd - p

        var spans = SpanOps.remap(block.spans, p, removedEnd, insertedLength)
        if (insertedLength > 0 && (pendingOn.isNotEmpty() || pendingOff.isNotEmpty())) {
            spans = applyPending(spans, p, p + insertedLength)
            clearPending()
        }
        block.spans = spans
        block.value = raw

        if (newText.indexOf('\n', p).let { it in p until insertedEnd }) {
            splitAtNewlines(block)
        }
        changed()
    }

    private fun splitAtNewlines(block: BlockState) {
        val index = blocks.indexOf(block)
        if (index < 0) return
        val text = block.text
        val cursor = block.selEnd
        val parts = text.split('\n')

        // Enter on an empty list item ends the list.
        if (parts.size == 2 && parts[0].isEmpty() && parts[1].isEmpty() && block.type in ListTypes) {
            block.type = BlockType.BODY
            block.checked = false
            block.setContent("", emptyList(), 0)
            return
        }

        val spans = block.spans
        val originalType = block.type
        val originalChecked = block.checked
        val insertingAbove = parts.size == 2 && parts[0].isEmpty()

        var offset = 0
        val created = ArrayList<BlockState>()
        var focusTarget = block
        var focusCursor = 0
        parts.forEachIndexed { i, part ->
            val start = offset
            val end = start + part.length
            val partSpans = SpanOps.slice(spans, start, end)
            val target = if (i == 0) {
                block.setContent(part, partSpans, part.length)
                block
            } else {
                val type = when {
                    insertingAbove -> originalType
                    originalType in ListTypes -> originalType
                    else -> BlockType.BODY
                }
                BlockState.empty(type, block.align, block.indent).also {
                    it.setContent(part, partSpans, 0)
                    if (insertingAbove) it.checked = originalChecked
                    created += it
                }
            }
            if (cursor in start..end) {
                focusTarget = target
                focusCursor = cursor - start
            }
            offset = end + 1
        }

        if (insertingAbove) {
            // Enter at the very start: the empty line above becomes a plain line.
            if (originalType in HeadingTypes) block.type = BlockType.BODY
            block.checked = false
        }

        blocks.addAll(index + 1, created)
        focusTarget.setCursor(focusCursor)
        if (focusTarget !== block) requestFocus(focusTarget)
    }

    private fun backspaceAtStart(block: BlockState) {
        val index = blocks.indexOf(block)
        val currentText = block.text
        val currentSpans = block.spans
        if (block.type != BlockType.BODY) {
            // First backspace removes list/heading formatting.
            block.type = BlockType.BODY
            block.checked = false
            block.setContent(currentText, currentSpans, 0)
            changed()
            return
        }
        if (index <= 0) {
            block.setContent(currentText, currentSpans, 0)
            return
        }
        val prev = blocks[index - 1]
        val prevLength = prev.text.length
        prev.setContent(prev.text + currentText, prev.spans + SpanOps.shift(currentSpans, prevLength), prevLength)
        blocks.removeAt(index)
        if (focusedId == block.id) focusedId = prev.id
        requestFocus(prev)
        changed()
    }

    // ---- Block formatting ----

    fun toggleBlockType(type: BlockType) {
        val b = target
        b.type = if (b.type == type && type != BlockType.BODY) BlockType.BODY else type
        if (b.type != BlockType.TASK) b.checked = false
        if (focusedBlock == null) requestFocus(b)
        changed()
    }

    fun setBlockType(type: BlockType) {
        val b = target
        b.type = type
        if (type != BlockType.TASK) b.checked = false
        changed()
    }

    fun toggleChecked(block: BlockState) {
        block.checked = !block.checked
        changed()
    }

    fun cycleAlign() {
        val b = target
        b.align = Align.entries[(b.align.ordinal + 1) % Align.entries.size]
        changed()
    }

    fun setAlign(align: Align) {
        target.align = align
        changed()
    }

    // ---- Inline formatting ----

    fun isStyleActive(style: InlineStyle): Boolean {
        if (style in pendingOff) return false
        if (pendingOn.containsKey(style)) return true
        val b = focusedBlock ?: return false
        return if (b.hasSelection) {
            SpanOps.covers(b.spans, style, b.selStart, b.selEnd)
        } else {
            SpanOps.activeAt(b.spans, style, b.selStart) != null
        }
    }

    fun currentValue(style: InlineStyle): String? {
        if (style in pendingOff) return null
        if (pendingOn.containsKey(style)) return pendingOn[style]
        val b = focusedBlock ?: return null
        return SpanOps.valueAt(b.spans, style, b.selStart, b.selEnd)
    }

    /** Toggles a style on the selection, or for the next typed text when nothing is selected. */
    fun toggleStyle(style: InlineStyle, value: String? = null) {
        val b = focusedBlock ?: return
        if (b.hasSelection) {
            val s = b.selStart
            val e = b.selEnd
            b.spans = if (SpanOps.covers(b.spans, style, s, e)) {
                SpanOps.remove(b.spans, style, s, e)
            } else {
                SpanOps.add(b.spans, style, value, s, e)
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
        val b = focusedBlock ?: return
        if (b.hasSelection) {
            b.spans = if (value == null) {
                SpanOps.remove(b.spans, style, b.selStart, b.selEnd)
            } else {
                SpanOps.add(b.spans, style, value, b.selStart, b.selEnd)
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
