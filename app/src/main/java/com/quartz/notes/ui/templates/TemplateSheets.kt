package com.quartz.notes.ui.templates

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.templates.StarterNote
import com.quartz.notes.data.templates.StarterPage
import com.quartz.notes.data.templates.Starters
import com.quartz.notes.ui.components.QuartzSheet
import com.quartz.notes.ui.notebook.PageThumbnail
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NoteColor
import com.quartz.notes.ui.theme.contentColorFor

/** A starter as a page, so it can be drawn like any other page's thumbnail. */
internal fun StarterPage.asPage(index: Int) = PageEntity(
    id = -1000L - index,
    notebookId = 0,
    title = title,
    pageType = pageType,
    backgroundArgb = backgroundArgb,
    texture = texture,
    layoutMode = layoutMode,
    content = content.encode(),
    plainText = content.plainText(),
)

/** Adding a page: blank (like the page before it), a new section, or a page from a template. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddPageSheet(
    onBlank: () -> Unit,
    onSection: () -> Unit,
    onTemplate: (PageEntity) -> Unit,
    onStarter: (StarterPage) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val saved by container.repository.observePageTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    val starters = remember { Starters.get(context).pages }
    val starterPages = remember(starters) { starters.mapIndexed { i, s -> s.asPage(i) } }

    QuartzSheet(
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
            Text("Add a page", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChoiceCard(Icons.Rounded.NoteAdd, "Blank page", "Looks like the page before it", Modifier.weight(1f), onBlank)
                ChoiceCard(Icons.Rounded.Bookmark, "New section", "A divider page with a tab", Modifier.weight(1f), onSection)
            }

            SheetLabel("Your templates")
            if (saved.isEmpty()) {
                Text(
                    "None yet. Save any page as a template from its Page style button.",
                    fontSize = 14.sp,
                    color = LocalQuartzColors.current.textSecondary,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    saved.forEach { t -> TemplateThumb(t, t.templateName.orEmpty()) { onTemplate(t) } }
                }
            }

            SheetLabel("Starters")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                starters.forEachIndexed { i, s -> TemplateThumb(starterPages[i], s.name) { onStarter(s) } }
            }
        }
    }
}

/** A new quick note: blank, or from a template. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewNoteSheet(
    onBlank: () -> Unit,
    onTemplate: (QuickNoteEntity) -> Unit,
    onStarter: (StarterNote) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val saved by container.repository.observeNoteTemplates().collectAsStateWithLifecycle(initialValue = emptyList())
    val starters = remember { Starters.get(context).notes }

    QuartzSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text("New quick note", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                ChoiceCard(Icons.AutoMirrored.Rounded.Notes, "Blank note", "Start from nothing", Modifier.weight(1f), onBlank)
            }

            SheetLabel("Your templates")
            if (saved.isEmpty()) {
                Text(
                    "None yet. Save any note as a template from its ⋮ menu.",
                    fontSize = 14.sp,
                    color = LocalQuartzColors.current.textSecondary,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    saved.forEach { t -> NoteTemplateCard(t.templateName.orEmpty(), t.colorKey, t.title, t.content) { onTemplate(t) } }
                }
            }

            SheetLabel("Starters")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                starters.forEach { s -> NoteTemplateCard(s.name, s.colorKey, s.title, s.content.encode()) { onStarter(s) } }
            }
        }
    }
}

@Composable
internal fun SheetLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalQuartzColors.current.textSecondary,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp),
    )
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 12.sp, color = LocalQuartzColors.current.textSecondary, maxLines = 2)
            }
        }
    }
}

/** A page template's thumbnail and name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TemplateThumb(page: PageEntity, name: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Use $name")
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageThumbnail(page = page, elements = emptyList(), highlighted = false)
        Text(
            name,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(108.dp).padding(top = 6.dp),
        )
    }
}

/** A quick-note template: its colour, title and first lines. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteTemplateCard(name: String, colorKey: String, title: String, content: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val bg = NoteColor.of(colorKey).background(LocalQuartzColors.current.isDark).let {
        if (colorKey == NoteColor.DEFAULT.key) MaterialTheme.colorScheme.surfaceContainerHigh else it
    }
    val ink = contentColorFor(bg)
    val lines = remember(content) { RichDoc.decode(content).previewLines().take(5).joinToString("\n") }
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onClickLabel = "Use $name"),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .shadow(2.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(10.dp),
        ) {
            Column {
                if (title.isNotBlank()) Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(lines, fontSize = 10.sp, lineHeight = 13.sp, color = ink.copy(alpha = 0.8f), overflow = TextOverflow.Ellipsis)
            }
        }
        Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}
