package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.quartz.notes.ui.components.QuartzSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.PageType
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.drawRuling
import com.quartz.notes.ui.components.rememberTextureBrush
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.contentColorFor

private val ThumbWidth = 108.dp
private val ThumbHeight = 146.dp

/**
 * Every page as a little thumbnail along a timeline, numbered across the top. The pages showing now
 * are highlighted; tap one to go there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTimelineSheet(
    /** Pages in order, the index (if shown) first. */
    pages: List<PageEntity>,
    /** Ids of the pages showing now. */
    showing: Set<Long>,
    vm: NotebookViewModel,
    onOpen: (PageEntity) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val media by vm.notebookMedia.collectAsStateWithLifecycle()
    val byPage = remember(media) { media.groupBy { it.pageId } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val current = pages.indexOfFirst { it.id in showing }.coerceAtLeast(0)
    LaunchedEffect(Unit) { listState.scrollToItem((current - 1).coerceAtLeast(0)) }
    val accent = MaterialTheme.colorScheme.primary
    val lineColor = LocalQuartzColors.current.textSecondary.copy(alpha = 0.35f)

    QuartzSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pages", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                TextButton(onClick = onManage) { Text("Reorder & delete") }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(
                state = listState,
                modifier = Modifier.pointerInput(listState) {
                    // An up/down mouse wheel scrolls the pages sideways (and never pulls the sheet shut).
                    // Side-scrolling wheels and trackpads keep working as before.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            val delta = event.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                            if (delta.y == 0f) continue
                            event.changes.forEach { it.consume() }
                            val step = (if (delta.x != 0f) delta.x else delta.y) * 96.dp.toPx()
                            scope.launch { listState.animateScrollBy(step) }
                        }
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                itemsIndexed(pages, key = { _, p -> p.id }) { i, page ->
                    val isIndex = page.id == INDEX_PAGE_ID
                    val label = if (isIndex) "Index" else "${pages.subList(0, i + 1).count { it.id != INDEX_PAGE_ID }}"
                    val isShowing = page.id in showing
                    Column(
                        Modifier
                            .width(ThumbWidth + 16.dp)
                            .clickable { onOpen(page) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // The timeline: a line through every page's number.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .drawBehind {
                                    val y = size.height / 2
                                    val startX = if (i == 0) size.width / 2 else 0f
                                    val endX = if (i == pages.lastIndex) size.width / 2 else size.width
                                    drawLine(lineColor, Offset(startX, y), Offset(endX, y), strokeWidth = 2.dp.toPx())
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val chipColor = when {
                                isShowing -> accent
                                page.isSection -> page.sectionColor
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                            Box(
                                Modifier
                                    .height(26.dp)
                                    .background(chipColor, CircleShape)
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isShowing || page.isSection) contentColorFor(chipColor) else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        PageThumbnail(
                            page = page,
                            elements = byPage[page.id].orEmpty(),
                            highlighted = isShowing,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                isIndex -> "Contents"
                                page.title.isNotBlank() -> page.title
                                page.isSection -> "Untitled section"
                                else -> "Untitled"
                            },
                            fontSize = 12.sp,
                            color = LocalQuartzColors.current.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(ThumbWidth),
                        )
                    }
                }
            }
        }
    }
}

/** A miniature page: its paper, title, first lines and first picture (or a section's look). */
@Composable
private fun PageThumbnail(page: PageEntity, elements: List<PageElementEntity>, highlighted: Boolean) {
    val quartz = LocalQuartzColors.current
    val accent = MaterialTheme.colorScheme.primary
    val files = LocalAppContainer.current.media
    val shape = RoundedCornerShape(10.dp)
    val isIndex = page.id == INDEX_PAGE_ID
    val bg = when {
        page.isSection -> page.sectionColor
        else -> page.backgroundArgb?.let { Color(it) } ?: quartz.card
    }
    val ink = contentColorFor(bg)
    val texture = rememberTextureBrush(enumOrDefault(page.texture, Texture.NONE), whiteInk = ink != Color(0xFF111114))
    val type = if (page.isSection || isIndex) PageType.PLAIN else enumOrDefault(page.pageType, PageType.LINES)
    val lineColor = ink.copy(alpha = 0.2f)
    val spacing = with(LocalDensity.current) { 9.dp.toPx() }
    val margin = with(LocalDensity.current) { 6.dp.toPx() }

    // Words and the first picture, from the page text and anything placed on it.
    val doc = remember(page.content) { RichDoc.decode(page.content) }
    val picture = remember(doc, elements) {
        doc.blocks.firstNotNullOfOrNull { it.image?.path }
            ?: elements.firstNotNullOfOrNull { e -> if (e.kind == ElementKind.IMAGE) decodeImageData(e.data)?.path else null }
    }
    val words = remember(doc, elements) {
        val lines = doc.previewLines().toMutableList()
        elements.filter { it.kind == ElementKind.TEXT }.forEach { e ->
            lines += RichDoc.decode(decodePayload<TextBoxData>(e.data)?.doc).previewLines()
        }
        lines.take(9).joinToString("\n")
    }
    val hasVoice = elements.any { it.kind == ElementKind.VOICE }

    Box(
        Modifier
            .size(ThumbWidth, ThumbHeight)
            .shadow(if (highlighted) 6.dp else 2.dp, shape)
            .clip(shape)
            .background(bg)
            .drawBehind {
                texture?.let { drawRect(it) }
                drawRuling(type, lineColor, spacing, spacing * 2.2f, margin)
            }
            .then(if (highlighted) Modifier.border(2.5.dp, accent, shape) else Modifier),
    ) {
        when {
            isIndex -> Column(Modifier.padding(8.dp)) {
                Text("Index", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ink)
                repeat(7) {
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth(if (it % 3 == 2) 0.5f else 0.85f).height(3.dp).background(ink.copy(alpha = 0.25f), CircleShape))
                }
            }
            page.isSection -> Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    page.title.ifBlank { "Section" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val icon = NotebookIcons.of(page.sectionIcon ?: "none")
                when {
                    page.sectionImage != null -> AsyncImage(
                        model = files.file(page.sectionImage),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 8.dp).size(44.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    icon != null -> Icon(icon, null, tint = ink, modifier = Modifier.padding(top = 8.dp).size(30.dp))
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp)) {
                if (page.title.isNotBlank()) {
                    Text(page.title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (picture != null) {
                    AsyncImage(
                        model = files.file(picture),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(46.dp).clip(RoundedCornerShape(4.dp)),
                    )
                }
                if (words.isNotEmpty()) {
                    Text(
                        words,
                        fontSize = 6.5.sp,
                        lineHeight = 9.sp,
                        color = ink.copy(alpha = 0.85f),
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        if (hasVoice) {
            Icon(
                Icons.Rounded.Mic,
                contentDescription = null,
                tint = ink.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).size(13.dp),
            )
        }
    }
}
