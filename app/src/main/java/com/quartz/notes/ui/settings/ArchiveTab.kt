package com.quartz.notes.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.ui.components.ColorDot
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.dateLabel
import com.quartz.notes.ui.home.notePreview
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NoteColor
import com.quartz.notes.ui.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An archived quick note as listed in Settings → Archive. */
data class ArchivedNoteUi(
    val id: Long,
    val title: String,
    val preview: String,
    val colorKey: String,
    /** When it was archived (the note's last update, which archiving stamps). */
    val archivedAt: Long,
)

class ArchiveViewModel(private val repo: QuartzRepository) : ViewModel() {
    /** Null until the first load, so the empty state doesn't flash. */
    val notes: StateFlow<List<ArchivedNoteUi>?> = repo.observeArchivedNotes()
        .map { list -> list.map(::toUi) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun restore(id: Long) = repo.setNoteArchived(id, false)

    fun delete(id: Long) = repo.deleteNote(id)

    fun deleteAll() {
        notes.value.orEmpty().forEach { repo.deleteNote(it.id) }
    }

    private fun toUi(note: QuickNoteEntity): ArchivedNoteUi {
        val (title, preview) = notePreview(note)
        return ArchivedNoteUi(note.id, title, preview, note.colorKey, note.updatedAt)
    }
}

private sealed interface ArchiveDialog {
    data class Delete(val note: ArchivedNoteUi) : ArchiveDialog
    data object DeleteAll : ArchiveDialog
}

/** Settings' "Archive" tab: archived quick notes, each with Restore and Delete. Tapping one opens it. */
@Composable
fun ArchiveTab(snackbar: SnackbarHostState, onOpenNote: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm = quartzViewModel { ArchiveViewModel(it.repository) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<ArchiveDialog?>(null) }
    val scope = rememberCoroutineScope()
    val list = notes ?: return

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        if (list.isEmpty()) {
            item(key = "empty") { ArchiveEmpty() }
        } else {
            item(key = "summary") {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (list.size == 1) "1 archived note" else "${list.size} archived notes",
                        fontSize = 14.sp,
                        color = LocalQuartzColors.current.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { dialog = ArchiveDialog.DeleteAll }) {
                        Text("Delete all", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            items(list, key = { it.id }) { note ->
                ArchivedNoteCard(
                    note = note,
                    onOpen = { onOpenNote(note.id) },
                    onRestore = {
                        vm.restore(note.id)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar("Restored to Quick Notes")
                        }
                    },
                    onDelete = { dialog = ArchiveDialog.Delete(note) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        is ArchiveDialog.Delete -> ConfirmDialog(
            title = "Delete note?",
            message = if (d.note.title.isNotBlank()) "\"${d.note.title}\" will be permanently deleted." else "This note will be permanently deleted.",
            confirmLabel = "Delete",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.delete(d.note.id)
                dialog = null
            },
        )
        ArchiveDialog.DeleteAll -> ConfirmDialog(
            title = "Delete all archived notes?",
            message = if (list.size == 1) "1 note will be permanently deleted." else "${list.size} notes will be permanently deleted.",
            confirmLabel = "Delete all",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.deleteAll()
                dialog = null
            },
        )
    }
}

@Composable
private fun ArchiveEmpty() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Archive,
            contentDescription = null,
            tint = LocalQuartzColors.current.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text("Archived notes show up here", fontSize = 17.sp, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        Text(
            "Swipe a quick note to the left and tap Archive to put it away without deleting it.",
            fontSize = 14.sp,
            color = LocalQuartzColors.current.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ArchivedNoteCard(
    note: ArchivedNoteUi,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = NoteColor.of(note.colorKey)
    QuartzCard(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    note.title.ifBlank { "Untitled" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (color != NoteColor.DEFAULT) {
                    Spacer(Modifier.width(12.dp))
                    ColorDot(color.dot)
                }
            }
            if (note.preview.isNotBlank()) {
                Text(
                    note.preview.replace('\n', ' '),
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                archivedLabel(note.archivedAt),
                fontSize = 13.sp,
                color = LocalQuartzColors.current.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onRestore) {
                Icon(Icons.Rounded.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restore")
            }
        }
    }
}

/** "Archived today", "Archived yesterday" or "Archived on October 10th". */
private fun archivedLabel(millis: Long): String = when (val day = dateLabel(millis.toLocalDate())) {
    "Today", "Yesterday" -> "Archived ${day.lowercase()}"
    else -> "Archived on $day"
}
