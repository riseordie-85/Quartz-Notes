package com.quartz.notes.ui.home

import androidx.activity.compose.BackHandler
import com.quartz.notes.ui.media.model
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin as PushPinOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.data.model.Texture
import com.quartz.notes.ui.components.ColorDot
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.EmptyHint
import com.quartz.notes.ui.components.NewNotebookCover
import com.quartz.notes.ui.components.NotebookCover
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.components.QuartzPill
import com.quartz.notes.ui.components.RecentCover
import com.quartz.notes.ui.components.RoundActionButton
import com.quartz.notes.ui.components.SwipeAction
import com.quartz.notes.ui.components.SwipeReveal
import com.quartz.notes.ui.components.SwipeRevealGroup
import com.quartz.notes.ui.components.TextInputDialog
import com.quartz.notes.ui.components.rememberSwipeRevealGroup
import com.quartz.notes.ui.note.NoteEditorPane
import com.quartz.notes.ui.notebook.NotebookEditorSheet
import com.quartz.notes.ui.notebook.applyDraft
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NoteColor
import com.quartz.notes.ui.theme.SectionTitleStyle
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Width at which the home screen shows notes side by side with the list (tablets, unfolded phones). */
val TwoPaneMinWidth: Dp = 600.dp

@Composable
fun HomeRoute(
    onOpenNote: (Long) -> Unit,
    onOpenNotebook: (Long) -> Unit,
    onOpenRecent: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm = quartzViewModel { HomeViewModel(it.repository, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedNote by rememberSaveable { mutableStateOf<Long?>(null) }
    var notebookSheet by remember { mutableStateOf<NotebookSheet?>(null) }
    var newShelfDialog by remember { mutableStateOf(false) }
    val previousNote = remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<NoteUi?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Clean up empty notes left over from earlier sessions (never the one that's open).
    LaunchedEffect(Unit) { vm.sweepBlankNotes(except = selectedNote) }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val twoPane = maxWidth >= TwoPaneMinWidth

        // Folding the device closed while a note is open moves it to its own screen.
        LaunchedEffect(twoPane) {
            val open = selectedNote
            if (!twoPane && open != null) {
                previousNote.value = null // handing it over, not closing it
                selectedNote = null
                onOpenNote(open)
            }
        }
        // When the side pane switches away from a note, drop it if it was left empty. This runs after the
        // old pane's final save has been queued, so the check sees its latest content.
        LaunchedEffect(selectedNote) {
            val prev = previousNote.value
            if (prev != null && prev != selectedNote) vm.discardIfBlank(prev)
            previousNote.value = selectedNote
        }
        BackHandler(enabled = twoPane && selectedNote != null) { selectedNote = null }

        val openNote: (Long) -> Unit = { id -> if (twoPane) selectedNote = id else onOpenNote(id) }

        // What the buttons behind a swiped quick note do.
        val noteActions = NoteActions(
            onTogglePin = { note -> vm.setPinned(note.id, !note.pinned) },
            onArchive = { note ->
                if (selectedNote == note.id) selectedNote = null
                vm.archive(note.id)
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar("Note archived", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) vm.unarchive(note.id, wasPinned = note.pinned)
                }
            },
            onDelete = { note -> pendingDelete = note },
        )

        val content: @Composable (Modifier) -> Unit = { modifier ->
            HomeContent(
                state = state,
                selectedNoteId = if (twoPane) selectedNote else null,
                modifier = modifier,
                onOpenNote = openNote,
                noteActions = noteActions,
                snackbar = snackbar,
                onNewNote = { vm.createNote(openNote) },
                onOpenNotebook = onOpenNotebook,
                onEditNotebook = { notebookSheet = NotebookSheet(it) },
                onNewNotebook = { notebookSheet = NotebookSheet(null) },
                onOpenRecent = onOpenRecent,
                onSelectShelf = vm::selectShelf,
                onNewShelf = { newShelfDialog = true },
                onToggleGrid = { vm.setGrid(!state.grid) },
                onSearch = onSearch,
                onSettings = onSettings,
            )
        }

        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                content(Modifier.weight(0.48f).fillMaxHeight())
                Box(
                    Modifier
                        .weight(0.52f)
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(top = 12.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    val id = selectedNote
                    if (id != null) {
                        androidx.compose.runtime.key(id) {
                            NoteEditorPane(noteId = id, onClose = { selectedNote = null }, showBack = false)
                        }
                    } else {
                        QuartzCard(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "Select a note, or tap + to start one",
                                    color = LocalQuartzColors.current.textSecondary,
                                    fontSize = 16.sp,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            content(Modifier.fillMaxSize())
        }
    }

    notebookSheet?.let { sheet ->
        val defaultShelf = state.selectedShelf?.id ?: state.shelves.firstOrNull()?.id ?: return@let
        NotebookEditorSheet(
            existing = sheet.notebook,
            shelves = state.shelves,
            defaultShelfId = defaultShelf,
            onDismiss = { notebookSheet = null },
            onSave = { draft ->
                val existing = sheet.notebook
                if (existing == null) {
                    vm.createNotebook(draft.shelfId, draft.title, draft.colorArgb, draft.texture.name, draft.icon, draft.twoPage)
                } else {
                    vm.updateNotebook(existing.applyDraft(draft))
                }
                notebookSheet = null
            },
            onDelete = sheet.notebook?.let { nb -> { vm.deleteNotebook(nb); notebookSheet = null } },
        )
    }
    pendingDelete?.let { note ->
        ConfirmDialog(
            title = "Delete note?",
            message = if (note.title.isNotBlank()) "\"${note.title}\" will be permanently deleted." else "This note will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                if (selectedNote == note.id) selectedNote = null
                vm.delete(note.id)
            },
        )
    }
    if (newShelfDialog) {
        TextInputDialog(
            title = "New shelf",
            confirmLabel = "Create",
            onDismiss = { newShelfDialog = false },
            onConfirm = {
                vm.addShelf(it)
                newShelfDialog = false
            },
        )
    }
}

private data class NotebookSheet(val notebook: NotebookEntity?)

/** Handlers for the pin / archive / delete buttons uncovered by swiping a quick note. */
private class NoteActions(
    val onTogglePin: (NoteUi) -> Unit,
    val onArchive: (NoteUi) -> Unit,
    val onDelete: (NoteUi) -> Unit,
)

@Composable
private fun HomeContent(
    state: HomeState,
    selectedNoteId: Long?,
    modifier: Modifier,
    onOpenNote: (Long) -> Unit,
    noteActions: NoteActions,
    snackbar: SnackbarHostState,
    onNewNote: () -> Unit,
    onOpenNotebook: (Long) -> Unit,
    onEditNotebook: (NotebookEntity) -> Unit,
    onNewNotebook: () -> Unit,
    onOpenRecent: () -> Unit,
    onSelectShelf: (Long) -> Unit,
    onNewShelf: () -> Unit,
    onToggleGrid: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    // At most one quick note is swiped open at a time; scrolling the list closes it.
    val swipeGroup = rememberSwipeRevealGroup()
    val listState = rememberLazyListState()
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(listState, gridState) {
        snapshotFlow { listState.isScrollInProgress || gridState.isScrollInProgress }
            .filter { it }
            .collect { swipeGroup.closeAll() }
    }

    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = bottom + 110.dp)

    BoxWithConstraints(modifier) {
        val coverWidth = ((maxWidth - 32.dp - 36.dp) / 4).coerceIn(74.dp, 150.dp)

        val header: @Composable () -> Unit = {
            HomeHeader(
                state = state,
                coverWidth = coverWidth,
                onOpenNotebook = onOpenNotebook,
                onEditNotebook = onEditNotebook,
                onNewNotebook = onNewNotebook,
                onOpenRecent = onOpenRecent,
                onSelectShelf = onSelectShelf,
                onNewShelf = onNewShelf,
                onToggleGrid = onToggleGrid,
                onSettings = onSettings,
            )
        }

        if (state.grid) {
            LazyVerticalStaggeredGrid(
                state = gridState,
                columns = StaggeredGridCells.Adaptive(150.dp),
                contentPadding = listPadding,
                verticalItemSpacing = 12.dp,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header", span = StaggeredGridItemSpan.FullLine) { header() }
                if (state.loaded && state.groups.isEmpty()) {
                    item(key = "empty", span = StaggeredGridItemSpan.FullLine) { EmptyHint("No quick notes yet. Tap + to jot one down.") }
                }
                state.groups.forEach { group ->
                    item(key = group.key, span = StaggeredGridItemSpan.FullLine) { DateHeader(group.label) }
                    items(group.notes, key = { it.id }) { note ->
                        SwipeableNote(note, swipeGroup, noteActions, inline = false) {
                            NoteGridCard(note, selected = note.id == selectedNoteId, onClick = { onOpenNote(note.id) })
                        }
                    }
                }
            }
        } else {
            LazyColumn(state = listState, contentPadding = listPadding, modifier = Modifier.fillMaxSize()) {
                item(key = "header") { header() }
                if (state.loaded && state.groups.isEmpty()) {
                    item(key = "empty") { EmptyHint("No quick notes yet. Tap + to jot one down.") }
                }
                state.groups.forEach { group ->
                    item(key = group.key) { DateHeader(group.label) }
                    item(key = "group-${group.key}") {
                        NoteGroupCard(group, selectedNoteId, onOpenNote, swipeGroup, noteActions)
                    }
                }
            }
        }

        // Fade + floating search bar and new-note button.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottom + 120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.92f), MaterialTheme.colorScheme.background),
                    ),
                ),
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuartzPill(Modifier.weight(1f), onClick = onSearch, height = 60.dp) {
                Text(
                    "Search Notes",
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.width(14.dp))
            RoundActionButton(onClick = onNewNote, size = 60.dp) {
                Icon(Icons.Rounded.Add, contentDescription = "New quick note", modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        // Sits just above the search bar.
        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 84.dp),
        )
    }
}

@Composable
private fun HomeHeader(
    state: HomeState,
    coverWidth: Dp,
    onOpenNotebook: (Long) -> Unit,
    onEditNotebook: (NotebookEntity) -> Unit,
    onNewNotebook: () -> Unit,
    onOpenRecent: () -> Unit,
    onSelectShelf: (Long) -> Unit,
    onNewShelf: () -> Unit,
    onToggleGrid: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        QuartzPill(Modifier.fillMaxWidth()) {
            Text(
                buildTitle(),
                modifier = Modifier.weight(1f).padding(start = 6.dp),
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = LocalQuartzColors.current.textSecondary, modifier = Modifier.size(30.dp))
            }
        }

        ShelfSelector(state, onSelectShelf, onNewShelf)

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "recent") {
                RecentCover(Icons.Rounded.History, Modifier.width(coverWidth), onClick = onOpenRecent)
            }
            items(state.notebooks, key = { it.id }) { nb ->
                NotebookCover(
                    title = nb.title,
                    color = Color(nb.colorArgb),
                    texture = enumOrDefault(nb.texture, Texture.GLOSS),
                    icon = NotebookIcons.of(nb.icon),
                    modifier = Modifier.width(coverWidth),
                    onClick = { onOpenNotebook(nb.id) },
                    onLongClick = { onEditNotebook(nb) },
                )
            }
            item(key = "new") {
                NewNotebookCover(Modifier.width(coverWidth), onClick = onNewNotebook)
            }
        }

        Box(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp)) {
            Text("Quick Notes", style = SectionTitleStyle, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.align(Alignment.Center))
            IconButton(onClick = onToggleGrid, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(
                    if (state.grid) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                    contentDescription = if (state.grid) "Show as list" else "Show as grid",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private fun buildTitle(): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Medium))
        append("Quartz")
        pop()
        append(" Notes")
    }

@Composable
private fun ShelfSelector(state: HomeState, onSelectShelf: (Long) -> Unit, onNewShelf: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val anchorWidth = with(density) { anchorWidthPx.toDp() }
    val menuWidth = maxOf(anchorWidth, 240.dp)

    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        // The menu is anchored to the title itself and drops straight down from its center.
        Box {
            Row(
                Modifier
                    .onSizeChanged { anchorWidthPx = it.width }
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shelf: ", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                Text(state.selectedShelf?.name ?: "", fontSize = 24.sp, color = MaterialTheme.colorScheme.onBackground)
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Choose shelf", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(32.dp))
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                offset = androidx.compose.ui.unit.DpOffset(x = (anchorWidth - menuWidth) / 2, y = 6.dp),
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(menuWidth),
            ) {
                state.shelves.forEach { shelf ->
                    val selected = shelf.id == state.selectedShelf?.id
                    DropdownMenuItem(
                        text = {
                            Text(
                                shelf.name,
                                fontSize = 18.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        onClick = {
                            open = false
                            onSelectShelf(shelf.id)
                        },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DropdownMenuItem(
                    text = {
                        Text(
                            "New shelf…",
                            fontSize = 16.sp,
                            color = LocalQuartzColors.current.textSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    onClick = {
                        open = false
                        onNewShelf()
                    },
                )
            }
        }
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        label,
        style = SectionTitleStyle,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 10.dp),
    )
}

/** Amber for pins, blue for the archive; delete uses the theme's error color. */
private val PinActionColor = Color(0xFFFFC94D)
private val OnPinActionColor = Color(0xFF3D2C00)
private val ArchiveActionColor = Color(0xFF1E6FE8)

/**
 * A quick note that swipes right to pin / unpin it and left to archive or delete it.
 * [inline] notes are rows inside a shared group card, so their buttons sit inset within that card.
 */
@Composable
private fun SwipeableNote(
    note: NoteUi,
    group: SwipeRevealGroup,
    actions: NoteActions,
    inline: Boolean,
    content: @Composable () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    val onError = MaterialTheme.colorScheme.onError
    val pin = SwipeAction(
        label = if (note.pinned) "Unpin" else "Pin",
        icon = if (note.pinned) Icons.Outlined.PushPinOutline else Icons.Rounded.PushPin,
        color = PinActionColor,
        contentColor = OnPinActionColor,
        onClick = { actions.onTogglePin(note) },
    )
    val archive = SwipeAction("Archive", Icons.Rounded.Archive, ArchiveActionColor, Color.White) { actions.onArchive(note) }
    val delete = SwipeAction("Delete", Icons.Rounded.DeleteOutline, error, onError) { actions.onDelete(note) }
    SwipeReveal(
        key = note.id,
        group = group,
        startActions = listOf(pin),
        endActions = listOf(archive, delete),
        // Rows nest inside the 22dp group card, so inset buttons get a matching smaller radius.
        corner = if (inline) 16.dp else 18.dp,
        inset = if (inline) 6.dp else 0.dp,
        spacing = if (inline) 6.dp else 8.dp,
        content = content,
    )
}

@Composable
private fun NoteGroupCard(
    group: NoteGroup,
    selectedNoteId: Long?,
    onOpenNote: (Long) -> Unit,
    swipeGroup: SwipeRevealGroup,
    actions: NoteActions,
) {
    QuartzCard(Modifier.fillMaxWidth()) {
        group.notes.forEachIndexed { index, note ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = LocalQuartzColors.current.divider)
            // Keyed so each row keeps its own swipe state when notes move between groups.
            androidx.compose.runtime.key(note.id) {
                SwipeableNote(note, swipeGroup, actions, inline = true) {
                    NoteRow(note, selected = note.id == selectedNoteId, onClick = { onOpenNote(note.id) })
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: NoteUi, selected: Boolean, onClick: () -> Unit) {
    val color = NoteColor.of(note.colorKey)
    Row(
        Modifier
            .fillMaxWidth()
            // Opaque, so the swipe buttons stay hidden until the row slides away from them.
            .background(LocalQuartzColors.current.card)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                note.title.ifBlank { "Untitled" },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (note.preview.isNotBlank()) {
                Text(
                    note.preview.replace('\n', ' '),
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
        }
        if (note.imageCount > 0) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Image, contentDescription = "Has pictures", tint = LocalQuartzColors.current.textSecondary, modifier = Modifier.size(20.dp))
        }
        if (note.voiceCount > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.Mic, contentDescription = "Has voice notes", tint = LocalQuartzColors.current.textSecondary, modifier = Modifier.size(20.dp))
        }
        if (note.pinned) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = LocalQuartzColors.current.textSecondary, modifier = Modifier.size(18.dp))
        }
        if (color != NoteColor.DEFAULT) {
            Spacer(Modifier.width(12.dp))
            ColorDot(color.dot)
        }
    }
}

@Composable
private fun NoteGridCard(note: NoteUi, selected: Boolean, onClick: () -> Unit) {
    val dark = LocalQuartzColors.current.isDark
    val bg = NoteColor.of(note.colorKey).background(dark)
    val fg = contentColorFor(bg)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = bg,
        shadowElevation = if (selected) 6.dp else 2.dp,
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (note.pinned) {
            Box(Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = "Pinned",
                    tint = fg.copy(alpha = 0.55f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(16.dp),
                )
            }
        }
        // Leaves room on the right for the pin mark.
        Column(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = if (note.pinned) 28.dp else 16.dp)) {
            if (note.title.isNotBlank()) {
                Text(note.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = fg, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (note.preview.isNotBlank()) {
                if (note.title.isNotBlank()) Spacer(Modifier.height(6.dp))
                Text(note.preview, fontSize = 15.sp, color = fg.copy(alpha = 0.85f), maxLines = 9, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
            }
            if (note.images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                val media = com.quartz.notes.LocalAppContainer.current.media
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    note.images.forEach { path ->
                        coil.compose.AsyncImage(
                            model = media.model(path),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)),
                        )
                    }
                    repeat(3 - note.images.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (note.voiceCount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Mic, contentDescription = null, tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    Text(
                        if (note.voiceCount == 1) "Voice note" else "${note.voiceCount} voice notes",
                        fontSize = 13.sp,
                        color = fg.copy(alpha = 0.7f),
                    )
                }
            }
            if (note.title.isBlank() && note.preview.isBlank() && note.images.isEmpty() && note.voiceCount == 0) {
                Text("Empty note", fontSize = 15.sp, color = fg.copy(alpha = 0.5f))
            }
        }
    }
}
