package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.model.PageType
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.ui.components.ColorPickerDialog
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.DefaultSwatch
import com.quartz.notes.ui.components.PageTypePreview
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.PageColorPresets
import com.quartz.notes.ui.theme.contentColorFor

/** Page ruling, background color and texture. Changes apply live. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PageStyleSheet(
    page: PageEntity,
    onLayoutMode: (scrapbook: Boolean) -> Unit,
    onChange: (PageEntity) -> Unit,
    onApplyToAll: (PageEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by remember { mutableStateOf(false) }
    val defaultPage = LocalQuartzColors.current.card
    val bg = page.backgroundArgb?.let { Color(it) } ?: defaultPage
    val lines = contentColorFor(bg).copy(alpha = 0.3f)
    val type = enumOrDefault(page.pageType, PageType.LINES)
    val texture = enumOrDefault(page.texture, Texture.NONE)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("Page style", fontSize = 22.sp, fontWeight = FontWeight.Medium)

            Label("Layout")
            val scrapbook = page.layoutMode == "SCRAPBOOK"
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !scrapbook,
                    onClick = { if (scrapbook) onLayoutMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Flowing text") }
                SegmentedButton(
                    selected = scrapbook,
                    onClick = { if (!scrapbook) onLayoutMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Scrapbook") }
            }
            Text(
                if (scrapbook) "Add text boxes with the T button. Everything can be moved, resized and turned."
                else "Text flows down the page. Pictures, shapes and drawings can still go anywhere.",
                fontSize = 13.sp,
                color = LocalQuartzColors.current.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )

            Label("Paper")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageType.entries.forEach { t ->
                    Column(
                        Modifier.weight(1f).clickable { onChange(page.copy(pageType = t.name)) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val shape = RoundedCornerShape(12.dp)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(76.dp)
                                .clip(shape)
                                .border(
                                    if (t == type) 2.5.dp else 1.dp,
                                    if (t == type) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape,
                                ),
                        ) { PageTypePreview(t, bg, lines) }
                        Text(t.label, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            Label("Color")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DefaultSwatch(selected = page.backgroundArgb == null, onClick = { onChange(page.copy(backgroundArgb = null)) }, size = 40.dp)
                PageColorPresets.forEach { c ->
                    Swatch(c, selected = page.backgroundArgb == c.toArgb(), size = 40.dp, onClick = { onChange(page.copy(backgroundArgb = c.toArgb())) })
                }
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                        .clickable { custom = true },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Colorize, contentDescription = "Custom color", tint = Color.White, modifier = Modifier.size(20.dp)) }
            }

            Label("Texture")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Texture.pageOptions.forEach { t ->
                    FilterChip(selected = texture == t, onClick = { onChange(page.copy(texture = t.name)) }, label = { Text(t.label) })
                }
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { onApplyToAll(page) }) { Text("Use this style on every page") }
        }
    }

    if (custom) {
        ColorPickerDialog(
            title = "Page color",
            initial = page.backgroundArgb?.let { Color(it) },
            presets = PageColorPresets,
            allowDefault = true,
            onDismiss = { custom = false },
            onPick = {
                custom = false
                onChange(page.copy(backgroundArgb = it?.toArgb()))
            },
        )
    }
}

/** List of pages in the notebook: jump, reorder, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesSheet(
    pages: List<PageEntity>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (PageEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<PageEntity?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Text("Pages", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            itemsIndexed(pages, key = { _, p -> p.id }) { index, page ->
                val selected = index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        fontSize = 15.sp,
                        color = LocalQuartzColors.current.textSecondary,
                        modifier = Modifier.width(30.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            page.title.ifBlank { "Untitled page" },
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val preview = page.plainText.lineSequence().firstOrNull { it.isNotBlank() }
                        if (preview != null) {
                            Text(preview, fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(enabled = index > 0, onClick = { onMove(index, index - 1) }) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                    }
                    IconButton(enabled = index < pages.lastIndex, onClick = { onMove(index, index + 1) }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                    }
                    IconButton(onClick = { pendingDelete = page }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete page")
                    }
                }
            }
        }
    }
    pendingDelete?.let { page ->
        ConfirmDialog(
            title = if (pages.size > 1) "Delete page?" else "Clear page?",
            message = if (pages.size > 1) "This page will be permanently deleted." else "This is the only page, so its content will be cleared.",
            confirmLabel = if (pages.size > 1) "Delete" else "Clear",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(page)
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
