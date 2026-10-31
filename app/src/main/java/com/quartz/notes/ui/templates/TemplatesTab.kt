package com.quartz.notes.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.TextInputDialog
import com.quartz.notes.ui.theme.LocalQuartzColors

/** One saved template, page or quick note, picked for renaming or deleting. */
private data class Picked(val id: Long, val name: String, val isPage: Boolean)

/**
 * Settings → Templates: the saved page and quick-note templates. Tap one to edit it, hold it to
 * rename or delete it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatesTab(
    onOpenNote: (Long) -> Unit,
    onOpenPage: (notebookId: Long, pageId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalAppContainer.current.repository
    val pages by repo.observePageTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by repo.observeNoteTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    var picked by remember { mutableStateOf<Picked?>(null) }
    var renaming by remember { mutableStateOf<Picked?>(null) }
    var deleting by remember { mutableStateOf<Picked?>(null) }
    val muted = LocalQuartzColors.current.textSecondary

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            "Tap a template to change it, or hold it to rename or delete it. New pages and notes made from a template don't change when the template does.",
            fontSize = 14.sp,
            color = muted,
            modifier = Modifier.padding(top = 14.dp),
        )

        SheetLabel("Page templates")
        if (pages.isEmpty()) {
            Text("None yet. Save a page from its Page style button, or from the pages timeline.", fontSize = 14.sp, color = muted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                pages.forEach { t ->
                    val name = t.templateName.orEmpty()
                    TemplateThumb(t, name, onLongClick = { picked = Picked(t.id, name, isPage = true) }) { onOpenPage(t.notebookId, t.id) }
                }
            }
        }

        SheetLabel("Quick note templates")
        if (notes.isEmpty()) {
            Text("None yet. Save a note from its ⋮ menu.", fontSize = 14.sp, color = muted)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                notes.forEach { t ->
                    val name = t.templateName.orEmpty()
                    NoteTemplateCard(name, t.colorKey, t.title, t.content, onLongClick = { picked = Picked(t.id, name, isPage = false) }) { onOpenNote(t.id) }
                }
            }
        }
        Text(
            "To use a template, hold the + button: on the home screen for quick notes, in a notebook for pages.",
            fontSize = 13.sp,
            color = muted,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
    }

    picked?.let { p ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text(p.name.ifBlank { "Template" }) },
            confirmButton = {
                TextButton(onClick = {
                    picked = null
                    renaming = p
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = {
                    picked = null
                    deleting = p
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    renaming?.let { p ->
        TextInputDialog(
            title = "Rename template",
            initial = p.name,
            label = "Template name",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                if (name.isNotBlank()) {
                    if (p.isPage) repo.renamePageTemplate(p.id, name) else repo.renameNoteTemplate(p.id, name)
                }
            },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = "Delete template?",
            message = "\"${p.name}\" will be deleted on all your devices. Pages and notes made from it stay.",
            confirmLabel = "Delete",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                if (p.isPage) repo.deletePageTemplate(p.id) else repo.deleteNote(p.id)
            },
        )
    }
}
