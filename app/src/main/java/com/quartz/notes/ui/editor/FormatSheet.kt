package com.quartz.notes.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.FontInfo
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.ui.theme.LocalQuartzColors

/** The "Aa" sheet: paragraph style, character styles and fonts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatSheet(state: RichEditorState, fonts: List<FontInfo>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Label("Paragraph")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleChip("Heading", state.currentType == BlockType.HEADING, Modifier.weight(1f), TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)) {
                    state.setBlockType(BlockType.HEADING)
                }
                StyleChip("Subheading", state.currentType == BlockType.SUBHEADING, Modifier.weight(1f), TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)) {
                    state.setBlockType(BlockType.SUBHEADING)
                }
                StyleChip("Regular", state.currentType == BlockType.BODY, Modifier.weight(1f), TextStyle(fontSize = 15.sp)) {
                    state.setBlockType(BlockType.BODY)
                }
            }

            Spacer(Modifier.height(18.dp))
            Label("Style")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StyleChip("B", state.isStyleActive(InlineStyle.BOLD), Modifier.weight(1f), TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)) {
                    state.toggleStyle(InlineStyle.BOLD)
                }
                StyleChip("I", state.isStyleActive(InlineStyle.ITALIC), Modifier.weight(1f), TextStyle(fontSize = 19.sp, fontStyle = FontStyle.Italic)) {
                    state.toggleStyle(InlineStyle.ITALIC)
                }
                StyleChip("U", state.isStyleActive(InlineStyle.UNDERLINE), Modifier.weight(1f), TextStyle(fontSize = 19.sp, textDecoration = TextDecoration.Underline)) {
                    state.toggleStyle(InlineStyle.UNDERLINE)
                }
                StyleChip("S", state.isStyleActive(InlineStyle.STRIKE), Modifier.weight(1f), TextStyle(fontSize = 19.sp, textDecoration = TextDecoration.LineThrough)) {
                    state.toggleStyle(InlineStyle.STRIKE)
                }
            }
            if (!state.hasSelection) {
                Text(
                    "Select text to restyle it, or pick a style to use for what you type next.",
                    fontSize = 13.sp,
                    color = LocalQuartzColors.current.textSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(18.dp))
            Label("Font")
            val currentFont = state.currentValue(InlineStyle.FONT)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 8.dp)) {
                item {
                    StyleChip("Default", currentFont == null, Modifier, TextStyle(fontSize = 16.sp)) {
                        state.applyValue(InlineStyle.FONT, null)
                    }
                }
                items(fonts, key = { it.id }) { font ->
                    StyleChip(font.name, currentFont == font.id, Modifier, TextStyle(fontSize = 16.sp, fontFamily = font.family)) {
                        state.applyValue(InlineStyle.FONT, font.id)
                    }
                }
            }
            Text(
                "Import your own fonts in Settings.",
                fontSize = 13.sp,
                color = LocalQuartzColors.current.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun StyleChip(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    textStyle: TextStyle,
    fontFamily: FontFamily? = null,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) BorderStroke(1.5.dp, accent) else null,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(text, style = textStyle.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = fontFamily ?: textStyle.fontFamily), maxLines = 1)
        }
    }
}
