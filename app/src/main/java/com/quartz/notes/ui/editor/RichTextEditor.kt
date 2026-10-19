package com.quartz.notes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.FontInfo
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.StyleSpan
import com.quartz.notes.ui.theme.parseHexColor

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

/** A block-based rich text editor. Place it inside a vertically scrolling container. */
@Composable
fun RichTextEditor(
    state: RichEditorState,
    styles: EditorStyles,
    contentColor: Color,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    placeholder: String? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Column(Modifier.fillMaxWidth()) {
            for (block in state.blocks) {
                key(block.id) {
                    BlockRow(state, block, styles, contentColor, accent, readOnly)
                }
            }
        }
        if (placeholder != null && state.blocks.size == 1 && state.blocks[0].text.isEmpty() &&
            state.blocks[0].type == BlockType.BODY
        ) {
            Text(
                text = placeholder,
                style = styles.body.copy(color = contentColor.copy(alpha = 0.38f)),
            )
        }
    }
}

@Composable
private fun BlockRow(
    state: RichEditorState,
    block: BlockState,
    styles: EditorStyles,
    contentColor: Color,
    accent: Color,
    readOnly: Boolean,
) {
    val resolver = LocalFontResolver.current
    val typeStyle = styles.forType(block.type)
    var textStyle = typeStyle.copy(color = contentColor, textAlign = block.align.toTextAlign())
    if (block.type == BlockType.TASK && block.checked) {
        textStyle = textStyle.copy(color = contentColor.copy(alpha = 0.5f), textDecoration = TextDecoration.LineThrough)
    }
    val spans = block.spans
    val transformation = remember(spans, resolver) { BlockTransformation(spans, resolver) }
    val lineHeight = with(LocalDensity.current) { typeStyle.lineHeight.toDp() }

    val request = state.focusRequest
    LaunchedEffect(request) {
        if (request != null && request.blockId == block.id) {
            runCatching { block.focusRequester.requestFocus() }
        }
    }

    Row(Modifier.fillMaxWidth().padding(start = (block.indent * 24).dp)) {
        when (block.type) {
            BlockType.BULLET -> Box(Modifier.width(26.dp).height(lineHeight), contentAlignment = Alignment.Center) {
                Box(Modifier.size(6.dp).background(contentColor, CircleShape))
            }
            BlockType.TASK -> TaskBox(
                checked = block.checked,
                height = lineHeight,
                accent = accent,
                contentColor = contentColor,
                onToggle = { if (!readOnly) state.toggleChecked(block) },
            )
            else -> Unit
        }
        BasicTextField(
            value = block.value,
            onValueChange = { state.onValueChange(block, it) },
            modifier = Modifier
                .weight(1f)
                .focusRequester(block.focusRequester)
                .onFocusChanged { if (it.isFocused) state.onFocused(block) },
            readOnly = readOnly,
            textStyle = textStyle,
            cursorBrush = SolidColor(accent),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
private fun TaskBox(
    checked: Boolean,
    height: androidx.compose.ui.unit.Dp,
    accent: Color,
    contentColor: Color,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(5.dp)
    Box(
        Modifier
            .width(32.dp)
            .height(height)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
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

/** Renders a block's style spans. Offsets are shifted by one for the leading marker character. */
private class BlockTransformation(
    private val spans: List<StyleSpan>,
    private val resolver: FontResolver,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val length = text.length
        val builder = AnnotatedString.Builder(text)

        fun add(style: SpanStyle, start: Int, end: Int) {
            val s = (start + 1).coerceIn(0, length)
            val e = (end + 1).coerceIn(0, length)
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
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
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
