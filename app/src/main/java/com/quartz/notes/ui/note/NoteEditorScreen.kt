package com.quartz.notes.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.editor.EditorToolbar
import com.quartz.notes.ui.editor.FormatSheet
import com.quartz.notes.ui.editor.RichEditorState
import com.quartz.notes.ui.editor.RichTextEditor
import com.quartz.notes.ui.editor.editorStyles
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NoteColor
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Full-screen quick note editor (phones). */
@Composable
fun NoteEditorRoute(noteId: Long, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        NoteEditorPane(noteId = noteId, onClose = onBack, showBack = true)
    }
}

/** The note card plus its toolbar. Used full screen on phones and as the right pane on tablets. */
@Composable
fun NoteEditorPane(noteId: Long, onClose: () -> Unit, showBack: Boolean) {
    val vm = quartzViewModel(key = "note-$noteId") { NoteViewModel(noteId, it.repository, it.appScope) }
    val load by vm.load.collectAsStateWithLifecycle()
    when (val l = load) {
        NoteLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        NoteLoad.Missing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This note no longer exists.", color = LocalQuartzColors.current.textSecondary)
        }
        is NoteLoad.Ready -> NoteEditorContent(l, vm, onClose, showBack)
    }
}

@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
private fun NoteEditorContent(load: NoteLoad.Ready, vm: NoteViewModel, onClose: () -> Unit, showBack: Boolean) {
    val note = load.note
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val fonts by container.fonts.fonts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val editor = remember(note.id) { RichEditorState(RichDoc.decode(note.content)) }
    var title by remember(note.id) { mutableStateOf(note.title) }
    var colorKey by remember(note.id) { mutableStateOf(note.colorKey) }
    var showFormat by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    // Autosave shortly after typing stops, and once more when the editor closes.
    LaunchedEffect(editor) {
        snapshotFlow { Triple(editor.version, title, colorKey) }
            .drop(1)
            .debounce(600)
            .collect { (_, t, c) -> vm.save(t, editor.toDoc(), c) }
    }
    val latestTitle by rememberUpdatedState(title)
    val latestColor by rememberUpdatedState(colorKey)
    DisposableEffect(editor) {
        onDispose { vm.saveOnClose(latestTitle, editor.toDoc(), latestColor) }
    }

    val dark = LocalQuartzColors.current.isDark
    val noteColor = NoteColor.of(colorKey)
    val cardColor = noteColor.background(dark)
    val ink = contentColorFor(cardColor)
    val styles = remember { editorStyles() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().imePadding()) {
        QuartzCard(Modifier.weight(1f).fillMaxWidth(), color = cardColor) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = ink) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showColors = !showColors }) {
                    Icon(Icons.Rounded.Palette, contentDescription = "Note color", tint = ink)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete note") },
                            leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = {
                                menu = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
            if (showColors) {
                FlowRow(
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NoteColor.entries.forEach { c ->
                        Swatch(
                            color = if (c == NoteColor.DEFAULT) MaterialTheme.colorScheme.surface else c.background(dark),
                            selected = c == noteColor,
                            size = 34.dp,
                            onClick = { colorKey = c.key },
                        )
                    }
                }
            }

            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 22.dp),
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { title = it.replace("\n", "") },
                    textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (title.isEmpty()) Text("Title", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.35f)))
                            inner()
                        }
                    },
                )
                HorizontalDivider(color = ink.copy(alpha = 0.15f))
                Spacer(Modifier.height(12.dp))
                RichTextEditor(
                    state = editor,
                    styles = styles,
                    contentColor = ink,
                    placeholder = "Start writing…",
                )
                // Tapping the empty space below the text puts the cursor at the end.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            editor.focusEnd()
                            keyboard?.show()
                        },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val s = settings
        EditorToolbar(
            state = editor,
            ink = s?.inkArgb?.takeIf { it != 0 }?.let { Color(it) },
            autoInk = ink,
            highlight = Color(s?.highlightArgb ?: 0xFFFFE14D.toInt()),
            onInkChange = { c -> scope.launch { container.settings.setInk(c?.toArgb() ?: 0) } },
            onHighlightChange = { c -> scope.launch { container.settings.setHighlight(c.toArgb()) } },
            onFormat = { showFormat = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showFormat) {
        FormatSheet(state = editor, fonts = fonts, onDismiss = { showFormat = false })
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete note?",
            message = "This note will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                vm.delete(onClose)
            },
        )
    }
}
