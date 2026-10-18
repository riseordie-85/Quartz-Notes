package com.quartz.notes.ui.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.quartz.notes.data.local.entity.NoteEntity
import com.quartz.notes.domain.model.GradientSpec
import com.quartz.notes.domain.model.QuartzPresets
import com.quartz.notes.ui.components.QuartzGradientContainer
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    notes: List<NoteEntity>,
    globalGradient: GradientSpec,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onNoteClick: (Long) -> Unit,
    onCreateNote: () -> Unit,
    onDeleteNote: (Long) -> Unit,
    onOpenSettings: () -> Unit
) {
    val groupedNotes = remember(notes) { groupNotesLikeApple(notes) }

    QuartzGradientContainer(globalGradient = globalGradient) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                LargeTopAppBar(
                    title = { Text("Quartz Notes", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings & Backup")
                        }
                    },
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onCreateNote,
                    icon = { Icon(Icons.Default.EditNote, contentDescription = null) },
                    text = { Text("New Note") }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search notes, checklists, #tags...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }

                if (notes.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No notes yet. Tap 'New Note' to start.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                groupedNotes.forEach { (sectionTitle, sectionNotes) ->
                    item(key = "header_$sectionTitle") {
                        Text(
                            text = sectionTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }

                    items(sectionNotes, key = { it.id }) { note ->
                        AppleStyleNoteCard(
                            note = note,
                            onClick = { onNoteClick(note.id) },
                            onDelete = { onDeleteNote(note.id) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(88.dp)) }
            }
        }
    }
}

@Composable
private fun AppleStyleNoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val noteGradient = remember(note.customGradientId, note.customStartColorHex, note.customEndColorHex) {
        when {
            note.customStartColorHex != null && note.customEndColorHex != null ->
                GradientSpec("custom", "Custom", note.customStartColorHex, note.customEndColorHex, note.customGradientAngle)
            note.customGradientId != null -> QuartzPresets.findById(note.customGradientId)
            else -> null
        }
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(16.dp).padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = note.title.ifBlank { "New Note" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                val dateStr = remember(note.updatedAt) {
                    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(note.updatedAt))
                }
                val preview = note.contentMarkdown.replace("\n", " ").ifBlank { "No additional text" }

                Text(
                    text = "$dateStr  •  $preview",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (noteGradient != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(18.dp).padding(end = 4.dp),
                    color = Color(noteGradient.startColorHex)
                ) {}
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Move to Trash",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun groupNotesLikeApple(notes: List<NoteEntity>): Map<String, List<NoteEntity>> {
    val result = linkedMapOf<String, MutableList<NoteEntity>>()
    val now = System.currentTimeMillis()
    val oneDayMs = 24 * 60 * 60 * 1000L

    notes.forEach { note ->
        val bucket = when {
            note.isPinned -> "Pinned"
            now - note.updatedAt < oneDayMs -> "Today"
            now - note.updatedAt < 7 * oneDayMs -> "Previous 7 Days"
            now - note.updatedAt < 30 * oneDayMs -> "Previous 30 Days"
            else -> "Older"
        }
        result.getOrPut(bucket) { mutableListOf() }.add(note)
    }
    return result
}
