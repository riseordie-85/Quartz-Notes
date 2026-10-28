package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import com.quartz.notes.ui.media.model
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.model.VoiceData
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.decodeVoiceData
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.contentColorFor

/** Id of the notebook's index page, which isn't stored: it's built from the pages each time. */
const val INDEX_PAGE_ID = -1L

/** Stand-in page entry for the index, so it can sit in the page list like any other page. */
internal fun indexPage(notebookId: Long) = PageEntity(id = INDEX_PAGE_ID, notebookId = notebookId, title = "Index", position = -1)

/** One picture or recording listed in the index. */
private data class IndexAttachment(
    val key: String,
    val pageId: Long,
    val pageNumber: Int,
    val image: ImageData? = null,
    val voice: VoiceData? = null,
)

/**
 * The notebook's index: every page by number (grouped under its section), then every picture and
 * recording in the notebook. Tapping an entry opens that page.
 */
@Composable
internal fun IndexPageView(
    pages: List<PageEntity>,
    vm: NotebookViewModel,
    side: PageSide,
    onOpen: (Long) -> Unit,
) {
    val media by vm.notebookMedia.collectAsStateWithLifecycle()
    val quartz = LocalQuartzColors.current
    val bg = quartz.card
    val ink = contentColorFor(bg)
    val secondary = ink.copy(alpha = 0.55f)
    val seamShade = if (quartz.isDark) 0.45f else 0.13f
    val audio = LocalAppContainer.current.audio
    val audioState by audio.state.collectAsStateWithLifecycle()
    val files = LocalAppContainer.current.media

    val attachments = remember(pages, media) { collectAttachments(pages, media) }
    val anySections = pages.any { it.isSection }

    Box(
        Modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            .shadow(2.dp, side.shape)
            .clip(side.shape)
            .background(bg)
            .drawBehind {
                when (side) {
                    PageSide.LEFT, PageSide.RIGHT -> drawSeam(atRightEdge = side == PageSide.LEFT, strength = seamShade)
                    PageSide.SPREAD -> drawCenterSeam(seamShade)
                    PageSide.SINGLE -> Unit
                }
            },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 28.dp)) {
            item {
                Text("Index", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ink)
                Text(
                    "${pages.size} pages" + if (anySections) " · ${pages.count { it.isSection }} sections" else "",
                    fontSize = 14.sp,
                    color = secondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
                )
            }
            pages.forEachIndexed { index, page ->
                item(key = "p${page.id}") {
                    val number = index + 1
                    if (page.isSection) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpen(page.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(width = 8.dp, height = 26.dp).background(page.sectionColor, RoundedCornerShape(4.dp)))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                page.title.ifBlank { "Untitled section" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("$number", fontSize = 14.sp, color = secondary)
                        }
                    } else {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpen(page.id) }
                                .padding(start = if (anySections) 24.dp else 4.dp, end = 4.dp, top = 9.dp, bottom = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                page.title.ifBlank { page.plainText.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Untitled page" },
                                fontSize = 16.sp,
                                color = if (page.title.isBlank()) ink.copy(alpha = 0.7f) else ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("$number", fontSize = 14.sp, color = secondary)
                        }
                    }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 22.dp, bottom = 16.dp), color = ink.copy(alpha = 0.15f))
                Text("Attachments", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
                Spacer(Modifier.height(8.dp))
                if (attachments.isEmpty()) {
                    Text("Pictures and recordings in this notebook will be listed here.", fontSize = 14.sp, color = secondary)
                }
            }
            items(attachments, key = { it.key }) { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(a.pageId) }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val image = a.image
                    val voice = a.voice
                    if (image != null) {
                        AsyncImage(
                            model = files.model(image.path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    } else if (voice != null) {
                        val playing = audioState.path == voice.path && audioState.playing
                        IconButton(
                            onClick = { audio.toggle(voice.path) },
                            modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                        ) {
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                image != null -> image.caption.ifBlank { "Picture" }
                                else -> "Recording"
                            },
                            fontSize = 16.sp,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (voice != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Mic, null, tint = secondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(formatDuration(voice.durationMs), fontSize = 13.sp, color = secondary)
                            }
                        }
                    }
                    Text("p. ${a.pageNumber}", fontSize = 14.sp, color = secondary)
                }
            }
        }
    }
}

/** Every picture and recording, in page order: placed on pages, in the bottom strip, in text, in text boxes. */
private fun collectAttachments(pages: List<PageEntity>, media: List<PageElementEntity>): List<IndexAttachment> {
    val numbers = pages.withIndex().associate { (i, p) -> p.id to i + 1 }
    val out = ArrayList<IndexAttachment>()
    pages.forEach { page ->
        val number = numbers[page.id] ?: return@forEach
        RichDoc.decode(page.content).blocks.forEach { b ->
            b.image?.let { out += IndexAttachment("t${page.id}-${b.id}", page.id, number, image = it) }
        }
    }
    media.forEach { e ->
        val number = numbers[e.pageId] ?: return@forEach
        when (e.kind) {
            ElementKind.IMAGE -> decodeImageData(e.data)?.let { out += IndexAttachment("e${e.id}", e.pageId, number, image = it) }
            ElementKind.VOICE -> decodeVoiceData(e.data)?.let { out += IndexAttachment("e${e.id}", e.pageId, number, voice = it) }
            ElementKind.TEXT -> RichDoc.decode(decodePayload<TextBoxData>(e.data)?.doc).blocks.forEach { b ->
                b.image?.let { out += IndexAttachment("b${e.id}-${b.id}", e.pageId, number, image = it) }
            }
        }
    }
    return out.sortedBy { it.pageNumber }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
