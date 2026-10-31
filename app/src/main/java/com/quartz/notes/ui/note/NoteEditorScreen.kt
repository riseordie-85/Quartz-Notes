package com.quartz.notes.ui.note

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.BookmarkAdd
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
import android.widget.Toast
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.platform.LocalContext
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.model.AttachmentKind
import com.quartz.notes.ui.editor.ToolButton
import com.quartz.notes.ui.media.ImageViewer
import com.quartz.notes.ui.media.VoiceRecorderSheet
import com.quartz.notes.ui.media.rememberImageAdder
import com.quartz.notes.ui.media.rememberMicPermission
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
    val vm = quartzViewModel(key = "note-$noteId") { NoteViewModel(noteId, it.repository) }
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
    val session = load.session
    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val fonts by container.fonts.fonts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val editor = session.editor
    val title = session.title
    val colorKey = session.colorKey
    var showFormat by remember { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var savingTemplate by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var pickNotebook by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<AttachmentEntity?>(null) }
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imageAdder = rememberImageAdder { vm.addImage(it) }
    val askMic = rememberMicPermission { recording = true }

    // Autosave shortly after typing stops, and whenever this pane goes away.
    LaunchedEffect(session) {
        snapshotFlow { Triple(editor.version, session.title, session.colorKey) }
            .drop(1)
            .debounce(600)
            .collect { vm.saveIfChanged() }
    }
    DisposableEffect(session) {
        onDispose {
            vm.saveIfChanged()
            container.audio.stop()
        }
    }

    // Leaving on purpose (back arrow / back gesture) saves, or discards the note if it's empty.
    val closeNote = {
        vm.close()
        onClose()
    }
    BackHandler(enabled = showBack, onBack = closeNote)

    val dark = LocalQuartzColors.current.isDark
    val noteColor = NoteColor.of(colorKey)
    val cardColor = noteColor.background(dark)
    val ink = contentColorFor(cardColor)
    val styles = remember { editorStyles() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Formatting toolbar under the note or, with "toolbar at the top" on, above it.
    val toolsOnTop = settings?.toolbarOnTop == true
    val toolbar: @Composable () -> Unit = {
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
        ) {
            Box {
                ToolButton(onClick = { attachMenu = true }, description = "Attach") {
                    Icon(Icons.Rounded.AttachFile, null)
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Photo library") },
                        leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                        onClick = {
                            attachMenu = false
                            imageAdder.pickFromGallery()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Take photo") },
                        leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                        onClick = {
                            attachMenu = false
                            imageAdder.takePhoto()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Voice note") },
                        leadingIcon = { Icon(Icons.Rounded.Mic, null) },
                        onClick = {
                            attachMenu = false
                            askMic()
                        },
                    )
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (toolsOnTop) {
            toolbar()
            Spacer(Modifier.height(12.dp))
        }
        QuartzCard(Modifier.weight(1f).fillMaxWidth(), color = cardColor) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) {
                    IconButton(onClick = closeNote) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = ink) }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showShare = true }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share", tint = ink)
                }
                IconButton(onClick = { showColors = !showColors }) {
                    Icon(Icons.Rounded.Palette, contentDescription = "Note color", tint = ink)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Save as template") },
                            leadingIcon = { Icon(Icons.Rounded.BookmarkAdd, null) },
                            onClick = {
                                menu = false
                                savingTemplate = true
                            },
                        )
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
                            onClick = { session.colorKey = c.key },
                        )
                    }
                }
            }

            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 22.dp),
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = { session.title = it.replace("\n", "") },
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
                // Pictures and voice notes come after the text, at the bottom of the note.
                NoteAttachments(
                    attachments = attachments,
                    ink = ink,
                    onOpenImage = { viewing = it },
                    onRemove = {
                        container.audio.stop(it.path)
                        vm.removeAttachment(it)
                    },
                    modifier = Modifier.padding(top = 18.dp),
                )
                // Tapping the empty space below the text puts the cursor at the end.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (attachments.isEmpty()) 240.dp else 120.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            editor.focusEnd()
                            keyboard?.show()
                        },
                )
            }
        }
        if (!toolsOnTop) {
            Spacer(Modifier.height(12.dp))
            toolbar()
        }
    }

    if (recording) {
        VoiceRecorderSheet(
            onSaved = { path, duration ->
                recording = false
                vm.addVoice(path, duration)
            },
            onDismiss = { recording = false },
        )
    }
    viewing?.let { a ->
        ImageViewer(
            path = a.path,
            onDismiss = { viewing = null },
            onDelete = {
                viewing = null
                vm.removeAttachment(a)
            },
        )
    }
    if (showShare) {
        ShareNoteSheet(
            onText = {
                showShare = false
                shareText(context, session.title, noteAsText(session.title, editor.toDoc()))
            },
            onImage = {
                showShare = false
                scope.launch {
                    val pictures = attachments.filter { it.kind == AttachmentKind.IMAGE.name }.map { container.media.file(it.path) }
                    runCatching { renderNoteImage(context, session.title, editor.toDoc(), session.colorKey, pictures) }
                        .onSuccess { shareFile(context, it, "image/png", "${context.packageName}.files") }
                        .onFailure { Toast.makeText(context, "Couldn't make an image of this note", Toast.LENGTH_SHORT).show() }
                }
            },
            onSaveImage = {
                showShare = false
                scope.launch {
                    val pictures = attachments.filter { it.kind == AttachmentKind.IMAGE.name }.map { container.media.file(it.path) }
                    val saved = runCatching {
                        val file = renderNoteImage(context, session.title, editor.toDoc(), session.colorKey, pictures)
                        saveImageToGallery(context, file, file.nameWithoutExtension + " " + System.currentTimeMillis() / 1000)
                    }.getOrDefault(false)
                    Toast.makeText(context, if (saved) "Saved to your gallery" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
                }
            },
            onNotebook = {
                showShare = false
                pickNotebook = true
            },
            onDismiss = { showShare = false },
        )
    }
    if (pickNotebook) {
        NotebookPickerSheet(
            onPick = { nb ->
                pickNotebook = false
                scope.launch {
                    runCatching { vm.sendToNotebook(nb.id) }
                        .onSuccess { Toast.makeText(context, "Added to ${nb.title}", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "Couldn't send the note", Toast.LENGTH_SHORT).show() }
                }
            },
            onDismiss = { pickNotebook = false },
        )
    }

    if (showFormat) {
        FormatSheet(state = editor, fonts = fonts, onDismiss = { showFormat = false })
    }
    if (savingTemplate) {
        com.quartz.notes.ui.components.TextInputDialog(
            title = "Save as template",
            initial = session.title,
            label = "Template name",
            onDismiss = { savingTemplate = false },
            onConfirm = { name ->
                savingTemplate = false
                if (name.isNotBlank()) {
                    vm.saveAsTemplate(name) { Toast.makeText(context, "Saved as a template. Hold + on the home screen to use it.", Toast.LENGTH_SHORT).show() }
                }
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete note?",
            message = "This note will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                vm.delete()
                onClose()
            },
        )
    }
}
