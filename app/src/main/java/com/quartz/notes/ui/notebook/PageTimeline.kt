package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import com.quartz.notes.ui.media.model
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.isSecondaryPressed
import com.quartz.notes.ui.components.ConfirmDialog
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
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
 * are highlighted; tap one to go there. Touch and hold a page, then drag it sideways to move it
 * (holding without dragging opens its menu).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PageTimelineSheet(
    /** Pages in order, the index (if shown) first. */
    pages: List<PageEntity>,
    /** Ids of the pages showing now. */
    showing: Set<Long>,
    vm: NotebookViewModel,
    onOpen: (PageEntity) -> Unit,
    onManage: () -> Unit,
    /** Moves a page one place earlier (-1) or later (+1). */
    onMove: (PageEntity, Int) -> Unit,
    /** Moves a page to a position among the real pages (0 = first page after the index). */
    onMoveTo: (PageEntity, Int) -> Unit,
    onStartSection: (PageEntity) -> Unit,
    onDelete: (PageEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<PageEntity?>(null) }
    val realPages = pages.filter { it.id != INDEX_PAGE_ID }
    val media by vm.notebookMedia.collectAsStateWithLifecycle()
    val byPage = remember(media) { media.groupBy { it.pageId } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val current = pages.indexOfFirst { it.id in showing }.coerceAtLeast(0)
    LaunchedEffect(Unit) { listState.scrollToItem((current - 1).coerceAtLeast(0)) }
    val accent = MaterialTheme.colorScheme.primary
    val lineColor = LocalQuartzColors.current.textSecondary.copy(alpha = 0.35f)

    // Dragging a page: the order shown follows the notebook, except while a page is being dragged.
    var order by remember { mutableStateOf(pages) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pages) { if (draggingId == null) order = pages }
    // The drag handlers outlive recompositions, so they read these instead of the parameters.
    val latestPages by rememberUpdatedState(pages)
    val latestMoveTo by rememberUpdatedState(onMoveTo)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val step = with(density) { (ThumbWidth + 16.dp).toPx() }
    val slop = with(density) { 12.dp.toPx() }
    val edge = with(density) { 56.dp.toPx() }

    /** Swaps the dragged page with its neighbour once it has moved past half of it. */
    fun settle(id: Long) {
        while (true) {
            val i = order.indexOfFirst { it.id == id }
            if (i < 0) return
            val first = if (order.firstOrNull()?.id == INDEX_PAGE_ID) 1 else 0
            val target = when {
                dragOffset > step / 2 && i < order.lastIndex -> i + 1
                dragOffset < -step / 2 && i > first -> i - 1
                else -> return
            }
            order = order.toMutableList().apply { add(target, removeAt(i)) }
            dragOffset -= (target - i) * step
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun finish(page: PageEntity, travelled: Float, openMenu: () -> Unit) {
        val from = latestPages.filter { it.id != INDEX_PAGE_ID }.indexOfFirst { it.id == page.id }
        val to = order.filter { it.id != INDEX_PAGE_ID }.indexOfFirst { it.id == page.id }
        if (from >= 0 && to >= 0 && from != to) latestMoveTo(page, to) else if (travelled < slop) openMenu()
        if (from == to) order = latestPages
        draggingId = null
        dragOffset = 0f
    }

    // Near either edge while dragging, the timeline scrolls along.
    LaunchedEffect(draggingId) {
        val id = draggingId ?: return@LaunchedEffect
        while (draggingId == id) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            val left = item.offset + dragOffset
            val right = left + item.size
            val speed = when {
                left < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - left) / edge).coerceIn(0f, 1f) * 18f
                right > info.viewportEndOffset - edge -> ((right - (info.viewportEndOffset - edge)) / edge).coerceIn(0f, 1f) * 18f
                else -> 0f
            }
            if (speed == 0f) continue
            val moved = listState.dispatchRawDelta(speed)
            dragOffset += moved
            settle(id)
        }
    }

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
                            listState.dispatchRawDelta(step)
                        }
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                itemsIndexed(order, key = { _, p -> p.id }) { i, page ->
                    val isIndex = page.id == INDEX_PAGE_ID
                    val label = if (isIndex) "Index" else "${order.subList(0, i + 1).count { it.id != INDEX_PAGE_ID }}"
                    val isShowing = page.id in showing
                    var menu by remember { mutableStateOf(false) }
                    val dragging = page.id == draggingId
                    val lift by animateFloatAsState(if (dragging) 1.08f else 1f, label = "lift")
                    Box(
                        if (dragging) {
                            Modifier.zIndex(1f).graphicsLayer {
                                translationX = dragOffset
                                scaleX = lift
                                scaleY = lift
                            }
                        } else {
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        },
                    ) {
                    Column(
                        Modifier
                            .width(ThumbWidth + 16.dp)
                            .pointerInput(page.id) {
                                // Right-click opens the page's menu.
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                            event.changes.forEach { it.consume() }
                                            if (!isIndex) menu = true
                                        }
                                    }
                                }
                            }
                            .combinedClickable(onClick = { onOpen(page) })
                            .then(
                                if (isIndex) {
                                    Modifier
                                } else {
                                    Modifier.pointerInput(page.id) {
                                        var travelled = 0f
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggingId = page.id
                                                dragOffset = 0f
                                                travelled = 0f
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffset += amount.x
                                                travelled += kotlin.math.abs(amount.x) + kotlin.math.abs(amount.y)
                                                settle(page.id)
                                            },
                                            onDragEnd = { finish(page, travelled) { menu = true } },
                                            onDragCancel = { finish(page, Float.MAX_VALUE) {} },
                                        )
                                    }
                                },
                            )
                            .semantics { if (!isIndex) onLongClick("Page options") { menu = true; true } },
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
                                    val endX = if (i == order.lastIndex) size.width / 2 else size.width
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
                    val position = realPages.indexOfFirst { it.id == page.id }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = {
                                menu = false
                                onOpen(page)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move earlier") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null) },
                            enabled = position > 0,
                            onClick = {
                                menu = false
                                onMove(page, -1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move later") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                            enabled = position in 0 until realPages.lastIndex,
                            onClick = {
                                menu = false
                                onMove(page, 1)
                            },
                        )
                        if (!page.isSection) {
                            DropdownMenuItem(
                                text = { Text("Start a section here") },
                                leadingIcon = { Icon(Icons.Rounded.Bookmark, null) },
                                onClick = {
                                    menu = false
                                    onStartSection(page)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menu = false
                                pendingDelete = page
                            },
                        )
                    }
                    }
                }
            }
        }
    }

    pendingDelete?.let { page ->
        val only = realPages.size <= 1
        val number = realPages.indexOfFirst { it.id == page.id } + 1
        ConfirmDialog(
            title = if (only) "Clear page?" else if (page.isSection) "Delete section page?" else "Delete page $number?",
            message = if (only) "This is the only page, so its content will be cleared." else "This page will be permanently deleted.",
            confirmLabel = if (only) "Clear" else "Delete",
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                onDelete(page)
            },
        )
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
                        model = files.model(page.sectionImage),
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
                        model = files.model(picture),
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
