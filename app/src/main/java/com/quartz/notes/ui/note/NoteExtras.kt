package com.quartz.notes.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.model.AttachmentKind
import com.quartz.notes.ui.components.ColorDot
import com.quartz.notes.ui.components.EmptyHint
import com.quartz.notes.ui.components.ListRow
import com.quartz.notes.ui.media.VoicePlayer
import com.quartz.notes.ui.theme.LocalQuartzColors

/** Picture thumbnails and voice note players shown at the bottom of a quick note. */
@Composable
fun NoteAttachments(
    attachments: List<AttachmentEntity>,
    ink: Color,
    onOpenImage: (AttachmentEntity) -> Unit,
    onRemove: (AttachmentEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val media = LocalAppContainer.current.media
    val images = attachments.filter { it.kind == AttachmentKind.IMAGE.name }
    val voices = attachments.filter { it.kind == AttachmentKind.AUDIO.name }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(images, key = { it.id }) { a ->
                    AsyncImage(
                        model = media.file(a.path),
                        contentDescription = "Picture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onOpenImage(a) },
                    )
                }
            }
        }
        voices.forEach { a ->
            VoicePlayer(
                path = a.path,
                durationMs = a.durationMs ?: 0,
                modifier = Modifier.fillMaxWidth(),
                content = MaterialTheme.colorScheme.onSurface,
                trailing = {
                    IconButton(onClick = { onRemove(a) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Remove voice note", tint = ink.copy(alpha = 0.7f))
                    }
                },
            )
        }
    }
}

/** Share options for a quick note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareNoteSheet(
    onText: () -> Unit,
    onImage: () -> Unit,
    onNotebook: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Share note", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            ListRow(
                title = "Share as text",
                subtitle = "Send the words to any app",
                onClick = onText,
                leading = { Icon(Icons.Rounded.TextFields, null, tint = MaterialTheme.colorScheme.primary) },
            )
            ListRow(
                title = "Share as image",
                subtitle = "A picture of the note, with its color and photos",
                onClick = onImage,
                leading = { Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.primary) },
            )
            ListRow(
                title = "Send to notebook",
                subtitle = "Copy it onto a new page in a notebook",
                onClick = onNotebook,
                leading = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, tint = MaterialTheme.colorScheme.primary) },
            )
        }
    }
}

/** Lists every notebook, grouped by shelf. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookPickerSheet(onPick: (NotebookEntity) -> Unit, onDismiss: () -> Unit) {
    val repo = LocalAppContainer.current.repository
    val shelves by repo.observeShelves().collectAsStateWithLifecycle(initialValue = emptyList())
    val notebooks by repo.observeAllNotebooks().collectAsStateWithLifecycle(initialValue = null)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Text("Send to notebook", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val list = notebooks
        LazyColumn(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            if (list != null && list.isEmpty()) {
                item { EmptyHint("You don't have any notebooks yet.") }
            }
            shelves.forEach { shelf ->
                val onShelf = list.orEmpty().filter { it.shelfId == shelf.id }
                if (onShelf.isEmpty()) return@forEach
                item(key = "s${shelf.id}") {
                    Text(
                        shelf.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalQuartzColors.current.textSecondary,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 2.dp),
                    )
                }
                items(onShelf, key = { it.id }) { nb ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(nb) }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(width = 22.dp, height = 30.dp).clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp, topStart = 2.dp, bottomStart = 2.dp))) {
                            ColorDot(Color(nb.colorArgb), size = 40.dp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(nb.title, fontSize = 17.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
