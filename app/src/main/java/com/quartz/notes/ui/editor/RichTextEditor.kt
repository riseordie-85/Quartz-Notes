package com.quartz.notes.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.FontInfo
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.StyleSpan
import com.quartz.notes.ui.media.DecoratedImage
import com.quartz.notes.ui.media.heightFactor
import com.quartz.notes.ui.theme.parseHexColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Looks up font families by id for inline FONT spans. */
@Stable
class FontResolver(val fonts: List<FontInfo>) {
    fun resolve(id: String?): FontFamily? = if (id == null) null else fonts.firstOrNull { it.id == id }?.family
}

val LocalFontResolver = staticCompositionLocalOf { FontResolver(BuiltInFonts) }

/** Text styles for each block type. Every style shares [lineUnit] so text sits on page rulings. */
@Immutable
data class EditorStyles(
    val heading: TextStyle,
    val subheading: TextStyle,
    val body: TextStyle,
    val lineUnit: TextUnit,
) {
    fun forType(type: BlockType): TextStyle = when (type) {
        BlockType.HEADING -> heading
        BlockType.SUBHEADING -> subheading
        else -> body
    }
}

private val CenteredLines = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

fun editorStyles(lineUnit: TextUnit = 30.sp): EditorStyles = EditorStyles(
    heading = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = lineUnit, lineHeightStyle = CenteredLines),
    subheading = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = lineUnit, lineHeightStyle = CenteredLines),
    body = TextStyle(fontSize = 17.sp, lineHeight = lineUnit, lineHeightStyle = CenteredLines, letterSpacing = 0.2.sp),
    lineUnit = lineUnit,
)

fun Align.toTextAlign(): TextAlign = when (this) {
    Align.START -> TextAlign.Start
    Align.CENTER -> TextAlign.Center
    Align.END -> TextAlign.End
    Align.JUSTIFY -> TextAlign.Justify
}

/** Width of the bullet / checkbox column in front of list items. */
private val BulletColumn: Dp = 26.dp
private val TaskColumn: Dp = 32.dp

/** Extra start indent per indent level. */
private val IndentStep: Dp = 24.dp

/**
 * A rich text editor for a whole document in one text field, so selecting, select all, copy and
 * paste work across paragraphs. Paragraph looks (headings, lists, tasks, alignment) come from
 * paragraph styles; bullets, checkboxes and in-line pictures are drawn at their lines. Place it
 * inside a vertically scrolling container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RichTextEditor(
    state: RichEditorState,
    styles: EditorStyles,
    contentColor: Color,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    placeholder: String? = null,
    layout: EditorLayoutHooks? = null,
    /** When false the text can't be focused or tapped (taps go to whatever is underneath). */
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val resolver = LocalFontResolver.current
    BoxWithConstraints(modifier) {
        val textWidth = maxWidth
        val version = state.version
        val transformation = remember(version, styles, contentColor, resolver, textWidth, density) {
            DocTransformation(
                metas = state.blocks.map { ParagraphLook(it.type, it.align, it.checked, it.indent, it.isImage && it.image != null) },
                slotHeights = state.blocks.map { b -> if (b.isImage && b.image != null) slotHeight(b, textWidth, styles, density) else 0.dp },
                spans = state.spans,
                styles = styles,
                contentColor = contentColor,
                resolver = resolver,
                density = density,
            )
        }
        var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val holder = remember { LayoutHolder() }
        val reveal = remember { BringIntoViewRequester() }
        val scope = rememberCoroutineScope()
        val revealMargin = with(density) { 24.dp.toPx() }
        val focusManager = LocalFocusManager.current
        val clipboard = LocalClipboardManager.current
        var contextMenuAt by remember { mutableStateOf<Offset?>(null) }

        LaunchedEffect(state.focusRequest) {
            if (state.focusRequest > 0) runCatching { state.focusRequester.requestFocus() }
        }

        fun reportBounds() {
            val hooks = layout ?: return
            val report = hooks.onBlockBounds ?: return
            val coords = holder.coords ?: return
            val root = hooks.root() ?: return
            val l = holder.layout ?: return
            if (!coords.isAttached || !root.isAttached) return
            val origin = root.localPositionOf(coords, Offset.Zero).y
            val t = state.text
            val starts = state.paragraphStarts(t)
            val len = l.layoutInput.text.length
            state.blocks.forEachIndexed { i, b ->
                val start = starts.getOrNull(i) ?: return@forEachIndexed
                val end = starts.getOrNull(i + 1)?.minus(1) ?: t.length
                val first = l.getLineForOffset(start.coerceIn(0, len))
                val last = l.getLineForOffset(end.coerceIn(0, len))
                report(b.id, origin + l.getLineTop(first), origin + l.getLineBottom(last))
            }
        }

        BasicTextField(
            value = state.value,
            onValueChange = {
                if (it.text != state.value.text) holder.revealPending = true
                state.onValueChange(holder.fixTapPastLineEnd(state, it))
            },
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(reveal)
                .focusRequester(state.focusRequester)
                .onFocusChanged { state.onFocusChanged(it.isFocused) }
                .onGloballyPositioned {
                    holder.coords = it
                    state.fieldCoords = it
                    reportBounds()
                }
                .onPreviewKeyEvent { e -> if (enabled && !readOnly) handleShortcut(state, e) { focusManager.clearFocus() } else false }
                .pointerInput(state) {
                    // Right-click (mouse) opens the edit menu where it was clicked. Every press is also
                    // noted (without taking it), so a tap past the end of a line can stay on that line.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            // A mouse moves the cursor when the button goes down; a finger or pen when it lifts.
                            val change = event.changes.firstOrNull()
                            if (change != null) {
                                val mouse = change.type == PointerType.Mouse
                                if ((mouse && event.type == PointerEventType.Press) || (!mouse && event.type == PointerEventType.Release)) {
                                    holder.noteTap(change.position)
                                }
                            }
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                val change = event.changes.firstOrNull() ?: continue
                                change.consume()
                                if (!state.hasSelection) holder.coords?.let { c -> state.placeCursorAt(change.position, c) }
                                contextMenuAt = change.position
                            }
                        }
                    }
                }
                .drawBehind {
                    // Bullets in front of list items.
                    val l = textLayout ?: return@drawBehind
                    val starts = state.paragraphStarts(state.text)
                    val len = l.layoutInput.text.length
                    state.blocks.forEachIndexed { i, b ->
                        if (b.type != BlockType.BULLET) return@forEachIndexed
                        val start = starts.getOrNull(i) ?: return@forEachIndexed
                        val line = l.getLineForOffset(start.coerceIn(0, len))
                        val cy = (l.getLineTop(line) + l.getLineBottom(line)) / 2
                        val cx = (IndentStep * b.indent + BulletColumn / 2).toPx()
                        drawCircle(contentColor, radius = 3.dp.toPx(), center = Offset(cx, cy))
                    }
                },
            enabled = enabled,
            readOnly = readOnly,
            textStyle = styles.body.copy(color = contentColor),
            cursorBrush = SolidColor(accent),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            onTextLayout = { result ->
                textLayout = result
                holder.layout = result
                state.fieldLayout = result
                reportBounds()
                if (holder.revealPending && state.focused) {
                    holder.revealPending = false
                    // Keep the cursor on screen while typing, even after scrolling away from it.
                    val offset = state.selEnd.coerceIn(0, result.layoutInput.text.length)
                    val cursor = result.getCursorRect(offset)
                    scope.launch {
                        runCatching {
                            reveal.bringIntoView(cursor.copy(top = cursor.top - revealMargin / 2, bottom = cursor.bottom + revealMargin))
                        }
                    }
                }
            },
        )

        // Checkboxes and in-line pictures, placed on their lines.
        val l = textLayout
        if (l != null) {
            val starts = state.paragraphStarts(state.text)
            val len = l.layoutInput.text.length
            state.blocks.forEachIndexed { i, b ->
                val start = starts.getOrNull(i) ?: return@forEachIndexed
                val line = l.getLineForOffset(start.coerceIn(0, len))
                val top = l.getLineTop(line)
                val lineHeight = with(density) { (l.getLineBottom(line) - top).toDp() }
                key(b.id) {
                    when {
                        b.type == BlockType.TASK -> TaskBox(
                            checked = b.checked,
                            height = lineHeight,
                            accent = accent,
                            contentColor = contentColor,
                            enabled = enabled && !readOnly,
                            onToggle = { state.toggleChecked(b) },
                            modifier = Modifier.offset { IntOffset((IndentStep * b.indent).roundToPx(), top.roundToInt()) },
                        )
                        b.isImage && b.image != null -> Box(
                            Modifier
                                .offset { IntOffset(0, top.roundToInt()) }
                                .width(textWidth)
                                .height(lineHeight),
                        ) {
                            val img = layout?.imageBlock
                            if (img != null) img(b, textWidth) else DefaultImageBlock(b, textWidth, styles)
                            // A selected picture is tinted, like selected text.
                            if (state.hasSelection && state.selStart <= start && state.selEnd > start) {
                                val alignment = when (b.align) {
                                    Align.CENTER, Align.JUSTIFY -> Alignment.Center
                                    Align.END -> Alignment.CenterEnd
                                    Align.START -> Alignment.CenterStart
                                }
                                val image = b.image
                                Box(
                                    Modifier
                                        .align(alignment)
                                        .size(textWidth * b.imageWidth, textWidth * b.imageWidth * (image?.heightFactor() ?: 1f))
                                        .background(accent.copy(alpha = 0.3f)),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Mouse right-click menu.
        contextMenuAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                EditMenu(
                    state = state,
                    onDismiss = { contextMenuAt = null },
                    onCopy = { clipboard.setText(AnnotatedString(state.selectedText())) },
                    onPaste = { clipboard.getText()?.text?.let { state.insertText(it) } },
                    readOnly = readOnly || !enabled,
                )
            }
        }

        if (placeholder != null && state.blocks.size == 1 && state.text.isEmpty() && state.blocks[0].type == BlockType.BODY) {
            Text(text = placeholder, style = styles.body.copy(color = contentColor.copy(alpha = 0.38f)))
        }
    }
}

/**
 * Keyboard shortcuts (Ctrl, or Cmd on a Mac keyboard): B / I / U bold, italic, underline; Shift+X
 * strikethrough; Z undo, Shift+Z or Y redo; Shift+8 bullets; Shift+L checklist; Shift+U check or
 * uncheck; Shift+H heading, Shift+J subheading, Shift+B body; ] / [ or Tab / Shift+Tab indent;
 * Escape stops typing. Returns true when the key was used.
 */
private fun handleShortcut(state: RichEditorState, e: KeyEvent, onEscape: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val cmd = e.isCtrlPressed || e.isMetaPressed
    val shift = e.isShiftPressed
    fun done(action: () -> Unit): Boolean {
        action()
        return true
    }
    return when {
        cmd && !shift && e.key == Key.Z -> done(state::undo)
        cmd && ((shift && e.key == Key.Z) || (!shift && e.key == Key.Y)) -> done(state::redo)
        cmd && !shift && e.key == Key.B -> done { state.toggleStyle(InlineStyle.BOLD) }
        cmd && !shift && e.key == Key.I -> done { state.toggleStyle(InlineStyle.ITALIC) }
        cmd && !shift && e.key == Key.U -> done { state.toggleStyle(InlineStyle.UNDERLINE) }
        cmd && shift && e.key == Key.X -> done { state.toggleStyle(InlineStyle.STRIKE) }
        cmd && shift && e.key == Key.Eight -> done { state.toggleBlockType(BlockType.BULLET) }
        cmd && shift && e.key == Key.L -> done { state.toggleBlockType(BlockType.TASK) }
        cmd && shift && e.key == Key.U -> done(state::toggleSelectedTasks)
        cmd && shift && e.key == Key.H -> done { state.setBlockType(BlockType.HEADING) }
        cmd && shift && e.key == Key.J -> done { state.setBlockType(BlockType.SUBHEADING) }
        cmd && shift && e.key == Key.B -> done { state.setBlockType(BlockType.BODY) }
        cmd && e.key == Key.RightBracket -> done { state.indent(1) }
        cmd && e.key == Key.LeftBracket -> done { state.indent(-1) }
        !cmd && e.key == Key.Tab -> done { state.indent(if (shift) -1 else 1) }
        e.key == Key.Escape -> done(onEscape)
        else -> false
    }
}

/** Edit menu for a mouse right-click. */
@Composable
private fun EditMenu(
    state: RichEditorState,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    readOnly: Boolean,
) {
    val selected = state.hasSelection
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        @Composable
        fun Item(label: String, shortcut: String, enabled: Boolean = true, action: () -> Unit) {
            DropdownMenuItem(
                text = { Text(label) },
                trailingIcon = { Text(shortcut, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                enabled = enabled,
                onClick = {
                    onDismiss()
                    action()
                },
            )
        }
        Item("Cut", "Ctrl+X", selected && !readOnly) {
            onCopy()
            state.insertText("")
        }
        Item("Copy", "Ctrl+C", selected) { onCopy() }
        Item("Paste", "Ctrl+V", !readOnly) { onPaste() }
        Item("Select all", "Ctrl+A") { state.selectAll() }
        if (!readOnly) {
            HorizontalDivider()
            Item("Bold", "Ctrl+B") { state.toggleStyle(InlineStyle.BOLD) }
            Item("Italic", "Ctrl+I") { state.toggleStyle(InlineStyle.ITALIC) }
            Item("Underline", "Ctrl+U") { state.toggleStyle(InlineStyle.UNDERLINE) }
            HorizontalDivider()
            Item("Undo", "Ctrl+Z", state.canUndo) { state.undo() }
            Item("Redo", "Ctrl+Y", state.canRedo) { state.redo() }
        }
    }
}

/** Last text layout and field position, without triggering recomposition. */
private class LayoutHolder {
    var layout: TextLayoutResult? = null
    var coords: LayoutCoordinates? = null
    var revealPending = false
    private var tapAt: Offset? = null
    private var tapTime = 0L

    fun noteTap(position: Offset) {
        tapAt = position
        tapTime = System.currentTimeMillis()
    }

    /**
     * Each paragraph ends with a space standing in for its line break, so a tap or click to the right
     * of a paragraph's last line lands after that space: the start of the next paragraph. When a tap
     * just put the cursor at a paragraph's start but the tap was on an earlier line, keep it on that
     * line, at the end of the paragraph.
     */
    fun fixTapPastLineEnd(state: RichEditorState, incoming: TextFieldValue): TextFieldValue {
        val at = tapAt ?: return incoming
        // Only the cursor move that comes straight from the tap (not later ones from the keyboard).
        tapAt = null
        if (System.currentTimeMillis() - tapTime > 400) return incoming
        val l = layout ?: return incoming
        if (!incoming.selection.collapsed || incoming.text != state.value.text) return incoming
        val doc = incoming.selection.start - MARK.length
        val t = state.text
        if (doc <= 0 || doc > t.length || t[doc - 1] != '\n') return incoming
        val len = l.layoutInput.text.length
        val cursorLine = l.getLineForOffset(doc.coerceIn(0, len))
        val tapLine = l.getLineForVerticalPosition(at.y)
        if (tapLine >= cursorLine) return incoming
        return incoming.copy(selection = TextRange(incoming.selection.start - 1))
    }
}

/**
 * Extra layout features used by notebook pages: reporting where each paragraph sits, and custom
 * drawing of in-line pictures.
 */
class EditorLayoutHooks(
    /** The coordinates paragraph positions are reported in. */
    val root: () -> LayoutCoordinates? = { null },
    /** Called with a paragraph's top and bottom, in px relative to [root]. */
    val onBlockBounds: ((blockId: String, top: Float, bottom: Float) -> Unit)? = null,
    /** Draws an in-line picture, given the text width. */
    val imageBlock: (@Composable (BlockState, Dp) -> Unit)? = null,
)

/** Height an in-line picture occupies, rounded up to whole ruled lines so the text below stays on the lines. */
@Composable
fun inlineImageSlotHeight(block: BlockState, textWidth: Dp, styles: EditorStyles): Dp =
    slotHeight(block, textWidth, styles, LocalDensity.current)

private fun slotHeight(block: BlockState, textWidth: Dp, styles: EditorStyles, density: Density): Dp {
    val data = block.image ?: return 0.dp
    val unit = with(density) { styles.lineUnit.toDp() }
    val h = textWidth * block.imageWidth * data.heightFactor() + 16.dp
    val lines = kotlin.math.ceil(h / unit).toInt().coerceAtLeast(1)
    return unit * lines
}

@Composable
private fun DefaultImageBlock(block: BlockState, textWidth: Dp, styles: EditorStyles) {
    val data = block.image ?: return
    val alignment = when (block.align) {
        Align.CENTER, Align.JUSTIFY -> Alignment.Center
        Align.END -> Alignment.CenterEnd
        Align.START -> Alignment.CenterStart
    }
    Box(Modifier.fillMaxWidth().height(inlineImageSlotHeight(block, textWidth, styles)), contentAlignment = alignment) {
        DecoratedImage(data, textWidth * block.imageWidth)
    }
}

@Composable
private fun TaskBox(
    checked: Boolean,
    height: Dp,
    accent: Color,
    contentColor: Color,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .width(TaskColumn)
            .height(height)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggle,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(19.dp)
                .clip(shape)
                .then(
                    if (checked) Modifier.background(accent)
                    else Modifier.border(2.dp, contentColor.copy(alpha = 0.55f), shape),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Rounded.Check, contentDescription = "Done", tint = Color.White, modifier = Modifier.size(15.dp))
        }
    }
}

/** How one paragraph looks, captured for the visual transformation. */
private data class ParagraphLook(
    val type: BlockType,
    val align: Align,
    val checked: Boolean,
    val indent: Int,
    val picture: Boolean,
)

/**
 * Shows the document: hides the leading marker, turns line breaks into paragraph boundaries (each
 * paragraph gets its own paragraph style), and renders headings, list indents, done tasks, picture
 * lines and the character styles. Offsets map one to one apart from the marker.
 */
private class DocTransformation(
    private val metas: List<ParagraphLook>,
    private val slotHeights: List<Dp>,
    private val spans: List<StyleSpan>,
    private val styles: EditorStyles,
    private val contentColor: Color,
    private val resolver: FontResolver,
    private val density: Density,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val doc = if (raw.startsWith(MARK)) raw.substring(MARK.length) else raw
        val hidden = raw.length - doc.length
        val parts = doc.split('\n')
        val lastEmpty = parts.last().isEmpty()

        // Line breaks become spaces at the end of each paragraph (the paragraph styles make the breaks;
        // a real line break there would add an empty line), so offsets stay the same. Picture
        // placeholders become zero-width spaces.
        val sb = StringBuilder(doc.length + 1)
        for (c in doc) sb.append(if (c == '\n') ' ' else if (c == OBJ) '\u200B' else c)
        // An empty last paragraph needs a character so it still gets its own line.
        if (lastEmpty) sb.append('\u200B')
        val visible = sb.toString()
        val length = visible.length
        val builder = AnnotatedString.Builder(visible)

        var start = 0
        parts.forEachIndexed { i, part ->
            val look = metas.getOrNull(i) ?: ParagraphLook(BlockType.BODY, Align.START, false, 0, false)
            val end = start + part.length
            val styleEnd = if (i < parts.lastIndex) end + 1 else length
            val isPicture = look.picture && part == OBJ.toString()
            val paragraph = if (isPicture) {
                val h = slotHeights.getOrNull(i) ?: 0.dp
                ParagraphStyle(
                    textAlign = look.align.toTextAlign(),
                    lineHeight = with(density) { h.toSp() },
                    lineHeightStyle = CenteredLines,
                )
            } else {
                val column = when (look.type) {
                    BlockType.BULLET -> BulletColumn
                    BlockType.TASK -> TaskColumn
                    else -> 0.dp
                }
                val indent = IndentStep * look.indent + column
                ParagraphStyle(
                    textAlign = look.align.toTextAlign(),
                    lineHeight = styles.lineUnit,
                    lineHeightStyle = CenteredLines,
                    textIndent = if (indent > 0.dp) with(density) { TextIndent(indent.toSp(), indent.toSp()) } else null,
                )
            }
            if (styleEnd > start) builder.addStyle(paragraph, start, styleEnd)

            if (end > start) {
                when (look.type) {
                    BlockType.HEADING -> builder.addStyle(SpanStyle(fontSize = styles.heading.fontSize, fontWeight = styles.heading.fontWeight), start, end)
                    BlockType.SUBHEADING -> builder.addStyle(SpanStyle(fontSize = styles.subheading.fontSize, fontWeight = styles.subheading.fontWeight), start, end)
                    else -> Unit
                }
                if (look.type == BlockType.TASK && look.checked) {
                    builder.addStyle(SpanStyle(color = contentColor.copy(alpha = 0.5f), textDecoration = TextDecoration.LineThrough), start, end)
                }
            }
            start = end + 1
        }

        addCharacterStyles(builder, doc.length)

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = (offset - hidden).coerceIn(0, length)
            override fun transformedToOriginal(offset: Int): Int = (offset + hidden).coerceIn(0, raw.length)
        }
        return TransformedText(builder.toAnnotatedString(), mapping)
    }

    private fun addCharacterStyles(builder: AnnotatedString.Builder, length: Int) {
        fun add(style: SpanStyle, start: Int, end: Int) {
            val s = start.coerceIn(0, length)
            val e = end.coerceIn(0, length)
            if (s < e) builder.addStyle(style, s, e)
        }
        for (inline in StyleOrder) {
            for (sp in spans) {
                if (sp.style != inline) continue
                val style = when (inline) {
                    InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    InlineStyle.HIGHLIGHT -> SpanStyle(
                        background = parseHexColor(sp.value) ?: Color(0xFFFFE14D),
                        color = Color(0xFF111114),
                    )
                    InlineStyle.COLOR -> parseHexColor(sp.value)?.let { SpanStyle(color = it) }
                    InlineStyle.FONT -> resolver.resolve(sp.value)?.let { SpanStyle(fontFamily = it) }
                    InlineStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                    InlineStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                } ?: continue
                add(style, sp.start, sp.end)
            }
        }
        // Where underline and strikethrough overlap, apply both (a later decoration would replace the earlier).
        val both = TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        for (u in spans) {
            if (u.style != InlineStyle.UNDERLINE) continue
            for (st in spans) {
                if (st.style != InlineStyle.STRIKE) continue
                val a = maxOf(u.start, st.start)
                val b = minOf(u.end, st.end)
                if (a < b) add(SpanStyle(textDecoration = both), a, b)
            }
        }
    }

    companion object {
        private val StyleOrder = listOf(
            InlineStyle.BOLD,
            InlineStyle.ITALIC,
            InlineStyle.FONT,
            InlineStyle.HIGHLIGHT,
            InlineStyle.COLOR,
            InlineStyle.UNDERLINE,
            InlineStyle.STRIKE,
        )
    }
}
