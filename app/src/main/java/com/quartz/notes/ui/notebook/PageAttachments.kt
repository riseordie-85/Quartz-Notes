package com.quartz.notes.ui.notebook

import androidx.compose.foundation.ExperimentalFoundationApi
import com.quartz.notes.ui.media.model
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodeVoiceData
import com.quartz.notes.ui.media.VoicePlayer

/**
 * Pictures and recordings kept in a strip at the bottom of a page (the notebook's "attachments at
 * the bottom" option). Tap a picture to view it; long-press it, or use a recording's menu, to place
 * it on the page or remove it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageAttachments(
    items: List<PageElementEntity>,
    ink: Color,
    locked: Boolean,
    onView: (String) -> Unit,
    onPlace: (PageElementEntity) -> Unit,
    onRemove: (PageElementEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val media = LocalAppContainer.current.media
    val images = items.filter { it.kind == ElementKind.IMAGE }
    val voices = items.filter { it.kind == ElementKind.VOICE }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Attachments",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink.copy(alpha = 0.55f),
        )
        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(images, key = { it.id }) { e ->
                    val data = remember(e.data) { decodeImageData(e.data) } ?: return@items
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        AsyncImage(
                            model = media.model(data.path),
                            contentDescription = data.caption.ifBlank { "Picture" },
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(104.dp)
                                .shadow(3.dp, RoundedCornerShape(14.dp))
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { onView(data.path) },
                                    onLongClick = { if (!locked) menu = true },
                                ),
                        )
                        AttachmentMenu(expanded = menu, onDismiss = { menu = false }, onPlace = { onPlace(e) }, onRemove = { onRemove(e) })
                    }
                }
            }
        }
        voices.forEach { e ->
            val data = remember(e.data) { decodeVoiceData(e.data) } ?: return@forEach
            var menu by remember { mutableStateOf(false) }
            VoicePlayer(
                path = data.path,
                durationMs = data.durationMs,
                modifier = Modifier.fillMaxWidth(),
                content = MaterialTheme.colorScheme.onSurface,
                trailing = if (locked) {
                    null
                } else {
                    {
                        Box {
                            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Recording options")
                            }
                            AttachmentMenu(expanded = menu, onDismiss = { menu = false }, onPlace = { onPlace(e) }, onRemove = { onRemove(e) })
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun AttachmentMenu(expanded: Boolean, onDismiss: () -> Unit, onPlace: () -> Unit, onRemove: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Place on page") },
            leadingIcon = { Icon(Icons.Rounded.OpenWith, null) },
            onClick = {
                onDismiss()
                onPlace()
            },
        )
        DropdownMenuItem(
            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            onClick = {
                onDismiss()
                onRemove()
            },
        )
    }
}

/** Space reserved under the page text for the strip. */
internal val AttachmentsTopGap = 8.dp

/** Padding used around the strip on a page. */
internal fun Modifier.attachmentsPadding(horizontal: androidx.compose.ui.unit.Dp): Modifier =
    padding(start = horizontal, end = horizontal, top = AttachmentsTopGap, bottom = 28.dp)
