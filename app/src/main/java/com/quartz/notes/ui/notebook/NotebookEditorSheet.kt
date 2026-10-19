package com.quartz.notes.ui.notebook

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.ui.components.ColorPickerDialog
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.NotebookCover
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NotebookColorPresets

/** What the sheet hands back when the user saves. */
data class NotebookDraft(
    val title: String,
    val colorArgb: Int,
    val texture: Texture,
    val icon: String,
    val shelfId: Long,
)

/** Create or edit a notebook's title, color, cover texture, icon and shelf. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotebookEditorSheet(
    existing: NotebookEntity?,
    shelves: List<ShelfEntity>,
    defaultShelfId: Long,
    onDismiss: () -> Unit,
    onSave: (NotebookDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var color by remember { mutableIntStateOf(existing?.colorArgb ?: NotebookColorPresets[1].toArgb()) }
    var texture by remember { mutableStateOf(enumOrDefault(existing?.texture, Texture.GLOSS)) }
    var icon by remember { mutableStateOf(existing?.icon ?: "none") }
    var shelfId by remember { mutableLongStateOf(existing?.shelfId ?: defaultShelfId) }
    var customColor by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var shelfMenu by remember { mutableStateOf(false) }

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
            Text(
                if (existing == null) "New notebook" else "Edit notebook",
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NotebookCover(
                    title = title,
                    color = Color(color),
                    texture = texture,
                    icon = NotebookIcons.of(icon),
                    modifier = Modifier.width(112.dp),
                )
                Spacer(Modifier.width(18.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(40) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            SheetLabel("Color")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                items(NotebookColorPresets) { c ->
                    Swatch(c, selected = c.toArgb() == color, onClick = { color = c.toArgb() }, size = 40.dp)
                }
                item {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                            .clickable { customColor = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Colorize, contentDescription = "Custom color", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            SheetLabel("Cover")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Texture.coverOptions.forEach { t ->
                    FilterChip(
                        selected = texture == t,
                        onClick = { texture = t },
                        label = { Text(if (t == Texture.NONE) "Matte" else t.label) },
                    )
                }
            }

            SheetLabel("Icon")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconChoice(selected = icon == "none", onClick = { icon = "none" }) {
                    Icon(Icons.Rounded.Block, contentDescription = "No icon", modifier = Modifier.size(22.dp))
                }
                NotebookIcons.all.forEach { (key, vector) ->
                    IconChoice(selected = icon == key, onClick = { icon = key }) {
                        Icon(vector, contentDescription = key, modifier = Modifier.size(22.dp))
                    }
                }
            }

            if (shelves.size > 1) {
                SheetLabel("Shelf")
                Box {
                    OutlinedButton(onClick = { shelfMenu = true }) {
                        Text(shelves.firstOrNull { it.id == shelfId }?.name ?: "Choose shelf")
                    }
                    DropdownMenu(expanded = shelfMenu, onDismissRequest = { shelfMenu = false }) {
                        shelves.forEach { s ->
                            DropdownMenuItem(text = { Text(s.name) }, onClick = {
                                shelfId = s.id
                                shelfMenu = false
                            })
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = title.isNotBlank(),
                    onClick = { onSave(NotebookDraft(title.trim(), color, texture, icon, shelfId)) },
                ) { Text(if (existing == null) "Create" else "Save") }
            }
        }
    }

    if (customColor) {
        ColorPickerDialog(
            title = "Notebook color",
            initial = Color(color),
            presets = NotebookColorPresets,
            onDismiss = { customColor = false },
            onPick = { picked ->
                picked?.let { color = it.toArgb() }
                customColor = false
            },
        )
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete notebook?",
            message = "\"${existing?.title}\" and all of its pages will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun IconChoice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Convenience to build an updated entity from a draft. */
fun NotebookEntity.applyDraft(d: NotebookDraft) = copy(
    title = d.title,
    colorArgb = d.colorArgb,
    texture = d.texture.name,
    icon = d.icon,
    shelfId = d.shelfId,
)
