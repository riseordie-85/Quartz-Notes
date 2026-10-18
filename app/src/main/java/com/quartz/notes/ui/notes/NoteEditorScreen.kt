package com.quartz.notes.ui.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quartz.notes.data.local.entity.ChecklistItem
import com.quartz.notes.data.local.entity.NoteEntity
import com.quartz.notes.domain.model.GradientSpec
import com.quartz.notes.domain.model.QuartzPresets
import com.quartz.notes.ui.components.QuartzGradientContainer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    initialNote: NoteEntity,
    globalGradient: GradientSpec,
    onSaveNote: (NoteEntity) -> Unit,
    onNavigateBack: () -> Unit
) {
    var title by remember(initialNote.id) { mutableStateOf(initialNote.title) }
    var content by remember(initialNote.id) { mutableStateOf(initialNote.contentMarkdown) }
    var isPinned by remember(initialNote.id) { mutableStateOf(initialNote.isPinned) }
    var selectedGradientId by remember(initialNote.id) { mutableStateOf(initialNote.customGradientId) }
    var showGradientSheet by remember { mutableStateOf(false) }

    var checklists by remember(initialNote.id) {
        mutableStateOf(
            runCatching { Json.decodeFromString<List<ChecklistItem>>(initialNote.checklistJson) }
                .getOrDefault(emptyList())
        )
    }

    val noteOverrideGradient = remember(selectedGradientId) {
        QuartzPresets.findById(selectedGradientId)
    }

    fun persistAndExit() {
        onSaveNote(
            initialNote.copy(
                title = title,
                contentMarkdown = content,
                checklistJson = Json.encodeToString(checklists),
                isPinned = isPinned,
                customGradientId = selectedGradientId,
                updatedAt = System.currentTimeMillis()
            )
        )
        onNavigateBack()
    }

    QuartzGradientContainer(
        globalGradient = globalGradient,
        noteOverrideGradient = noteOverrideGradient
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = { persistAndExit() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back & Save")
                        }
                    },
                    actions = {
                        IconButton(onClick = { isPinned = !isPinned }) {
                            Icon(
                                Icons.Default.PushPin,
                                contentDescription = "Pin Note",
                                tint = if (isPinned) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                        IconButton(onClick = { showGradientSheet = true }) {
                            Icon(Icons.Default.Palette, contentDescription = "Note Background Gradient")
                        }
                        TextButton(onClick = { persistAndExit() }) {
                            Text("Done", fontWeight = FontWeight.Bold)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            bottomBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            checklists = checklists + ChecklistItem(UUID.randomUUID().toString(), "", false)
                        }) {
                            Icon(Icons.Default.CheckCircleOutline, contentDescription = "Add Checklist")
                        }
                        IconButton(onClick = { content += "\n## " }) {
                            Icon(Icons.Default.Title, contentDescription = "Heading")
                        }
                        IconButton(onClick = { content += "\n| Column 1 | Column 2 |\n|---|---|\n| | |" }) {
                            Icon(Icons.Default.TableChart, contentDescription = "Insert Table")
                        }
                        IconButton(onClick = { content += " #" }) {
                            Icon(Icons.Default.Tag, contentDescription = "Add Tag")
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Title", style = MaterialTheme.typography.headlineMedium) },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                checklists.forEachIndexed { index, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = item.isChecked,
                            onCheckedChange = { checked ->
                                checklists = checklists.toMutableList().also {
                                    it[index] = item.copy(isChecked = checked)
                                }
                            }
                        )
                        OutlinedTextField(
                            value = item.text,
                            onValueChange = { newText ->
                                checklists = checklists.toMutableList().also {
                                    it[index] = item.copy(text = newText)
                                }
                            },
                            placeholder = { Text("Checklist item...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            checklists = checklists.toMutableList().also { it.removeAt(index) }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove item")
                        }
                    }
                }

                TextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = { Text("Start typing your note...") },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        if (showGradientSheet) {
            ModalBottomSheet(onDismissRequest = { showGradientSheet = false }) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Note Background Override",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Choose a gradient specific to this note, or follow the global Quartz theme.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            FilterChip(
                                selected = selectedGradientId == null,
                                onClick = { selectedGradientId = null },
                                label = { Text("Follow Global") }
                            )
                        }
                        items(QuartzPresets.all.filter { it.id != "none" }) { preset ->
                            FilterChip(
                                selected = selectedGradientId == preset.id,
                                onClick = { selectedGradientId = preset.id },
                                label = { Text(preset.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}
