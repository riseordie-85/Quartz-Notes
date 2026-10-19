package com.quartz.notes.ui.notebook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.model.ArrowData
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.ImagePlacement
import com.quartz.notes.data.model.LayoutMode
import com.quartz.notes.data.model.PageType
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.elementMediaPath
import com.quartz.notes.data.model.encode
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.data.rich.Align
import com.quartz.notes.ui.components.pageBackground
import com.quartz.notes.ui.components.rememberTextureBrush
import com.quartz.notes.ui.editor.BlockState
import com.quartz.notes.ui.editor.EditorLayoutHooks
import com.quartz.notes.ui.editor.EditorStyles
import com.quartz.notes.ui.editor.RichEditorState
import com.quartz.notes.ui.editor.RichTextEditor
import com.quartz.notes.ui.editor.editorStyles
import com.quartz.notes.ui.editor.inlineImageSlotHeight
import com.quartz.notes.ui.media.DecorateSheet
import com.quartz.notes.ui.media.DecoratedImage
import com.quartz.notes.ui.media.ImageViewer
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.max

/** Gap between a wrapped picture and the text beside it. */
private val WrapGap: Dp = 12.dp

/** Distance of a wrapped picture from the page edge. */
private val WrapMargin: Dp = 16.dp

/** One notebook page: title, text (or scrapbook items), and everything placed on it. */
@OptIn(FlowPreview::class)
@Composable
internal fun PageView(
    page: PageEntity,
    session: PageSession,
    vm: NotebookViewModel,
    side: PageSide,
    tool: PageTool,
    brushLevel: Int,
    inkArgb: Int,
    onToolDone: () -> Unit,
    focusTextBox: Long?,
    onFocusTextBoxHandled: () -> Unit,
    onActivate: () -> Unit,
    onEditorReady: (RichEditorState) -> Unit,
    onEditorGone: () -> Unit,
    onSave: () -> Unit,
) {
    val editor = session.editor
    val title = session.title
    val scope = rememberCoroutineScope()
    val audio = LocalAppContainer.current.audio
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val elementsFlow = remember(page.id) { vm.elements(page.id) }
    val elements by elementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val canvas = remember(page.id) { PageCanvasState() }
    val scrapbook = page.layoutMode == LayoutMode.SCRAPBOOK.name

    var viewing by remember { mutableStateOf<String?>(null) }
    var decorating by remember { mutableStateOf<Pair<PageElementEntity, ImageData>?>(null) }
    var decoratingInline by remember { mutableStateOf<String?>(null) }
    var shapeStyling by remember { mutableStateOf<Long?>(null) }
    var lineStyling by remember { mutableStateOf<Long?>(null) }

    /** Removes text boxes nobody typed in (they're created on a tap, so empty ones are leftovers). */
    fun sweepBlankTextBoxes() {
        elements.filter { it.kind == ElementKind.TEXT }.forEach { e ->
            val state = session.textBoxes[e.id] ?: return@forEach
            if (state.isBlank() && session.activeEditor !== state && canvas.pendingFocusTextBox != e.id) {
                session.textBoxes.remove(e.id)
                vm.deleteElement(e)
            }
        }
    }

    DisposableEffect(session) {
        onEditorReady(editor)
        onDispose {
            onSave()
            onEditorGone()
            sweepBlankTextBoxes()
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { editor.version to session.title }.drop(1).debounce(600).collect { onSave() }
    }
    // Typing in the page text puts away item toolbars and makes the page text the formatting target.
    LaunchedEffect(editor) {
        snapshotFlow { editor.focusedId }.drop(1).collect {
            canvas.clearSelection()
            if (it != null) session.activeEditor = null
        }
    }
    // A finished brush stroke stays on screen until the database has it.
    LaunchedEffect(elements) {
        if (!canvas.drawing) canvas.liveStroke.clear()
        canvas.erased.keys.retainAll(elements.map { it.id }.toSet())
    }
    // A text box added from the toolbar gets the cursor.
    LaunchedEffect(focusTextBox) {
        if (focusTextBox != null) {
            canvas.pendingFocusTextBox = focusTextBox
            onFocusTextBoxHandled()
        }
    }
    // Tools put away selections.
    LaunchedEffect(tool) {
        if (tool != PageTool.NONE) {
            canvas.clearSelection()
            focusManager.clearFocus()
        }
        if (tool != PageTool.CONNECT) canvas.connectFrom = null
    }

    val quartz = LocalQuartzColors.current
    val bg = page.backgroundArgb?.let { Color(it) } ?: quartz.card
    val ink = contentColorFor(bg)
    val type = enumOrDefault(page.pageType, PageType.LINES)
    val texture = rememberTextureBrush(enumOrDefault(page.texture, Texture.NONE), whiteInk = ink != Color(0xFF111114))
    val lineColor = ink.copy(alpha = if (quartz.isDark && page.backgroundArgb == null) 0.22f else 0.28f)
    val styles = remember { editorStyles() }
    val density = LocalDensity.current
    val unitPx = with(density) { styles.lineUnit.toPx() }
    val shape = side.shape
    val padH = if (side == PageSide.SINGLE) 22.dp else 24.dp
    val seamShade = if (quartz.isDark) 0.45f else 0.13f

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            .onFocusChanged { if (it.hasFocus) onActivate() }
            .shadow(2.dp, shape)
            .clip(shape)
            .background(bg)
            .drawBehind {
                texture?.let { drawRect(it) }
                if (side != PageSide.SINGLE) drawSeam(atRightEdge = side == PageSide.LEFT, strength = seamShade)
            },
    ) {
        val viewport = maxHeight
        val pageWidth = maxWidth
        val widthPx = with(density) { pageWidth.toPx() }
        val padHPx = with(density) { padH.toPx() }
        val textWidthPx = widthPx - 2 * padHPx
        val scroll = rememberScrollState()
        LaunchedEffect(scroll, widthPx) {
            snapshotFlow { scroll.value }.collect { session.scrollUnits = if (widthPx > 0) it / widthPx else 0f }
        }
        var titleBottomPx by remember { mutableStateOf(0f) }
        val elementsBottomPx = elements.maxOfOrNull { elementBottomPx(it, widthPx, canvas.bounds) } ?: 0f
        val elementsBottom = with(density) { elementsBottomPx.toDp() }
        val inkArgbResolved = if (inkArgb == 0) 0 else inkArgb

        // ---- Actions from the items on the page ----

        fun snapWrap(e: PageElementEntity, data: ImageData): Pair<PageElementEntity, ImageData> {
            val margin = with(density) { WrapMargin.toPx() } / widthPx
            val left = e.x + e.width / 2 < 0.5f
            val placement = if (left) ImagePlacement.WRAP_LEFT else ImagePlacement.WRAP_RIGHT
            val x = if (left) margin else 1f - e.width - margin
            return e.copy(x = x, rotation = 0f) to data.copy(placement = placement)
        }

        fun toInline(e: PageElementEntity, data: ImageData) {
            val top = e.y * widthPx
            val blocks = editor.blocks
            var index = blocks.indexOfFirst { b -> (canvas.blockBounds[b.id]?.first ?: Float.MAX_VALUE) >= top }
            if (index < 0) index = blocks.size
            val center = e.x + e.width / 2
            val align = when {
                center < 0.4f -> Align.START
                center > 0.6f -> Align.END
                else -> Align.CENTER
            }
            editor.insertImage(index, data.copy(placement = ImagePlacement.FLOAT), (e.width * widthPx / textWidthPx), align)
            canvas.clearSelection()
            vm.deleteElement(e)
        }

        fun toFloating(block: BlockState) {
            val data = block.image ?: return
            val top = canvas.blockBounds[block.id]?.first ?: (session.scrollUnits * widthPx + 200f)
            val w = block.imageWidth * textWidthPx / widthPx
            val x = when (block.align) {
                Align.START -> padHPx / widthPx
                Align.END -> 1f - padHPx / widthPx - w
                else -> (1f - w) / 2
            }
            vm.addImageAt(page.id, data, x, top / widthPx, w)
            editor.removeBlock(block.id)
            canvas.clearSelection()
        }

        val actions = remember(page.id, widthPx, textWidthPx) {
            ElementActions(
                onSelect = { id ->
                    canvas.selectedId = id
                    canvas.editingShapeId = null
                    canvas.selectedInlineBlock = null
                    if (id != null) focusManager.clearFocus()
                },
                onMove = { e ->
                    val data = if (e.kind == ElementKind.IMAGE) decodeImageData(e.data) else null
                    if (data != null && data.wraps) {
                        val (snapped, newData) = snapWrap(e, data)
                        vm.moveElement(snapped)
                        if (newData.placement != data.placement) vm.updateElementData(e, newData.encode())
                    } else {
                        vm.moveElement(e)
                    }
                },
                onView = { viewing = it.path },
                onDecorate = { e, d -> decorating = e to d },
                onPictureLayout = { e, d, layout ->
                    when (layout) {
                        PictureLayout.FLOAT -> if (d.wraps) vm.updateElementData(e, d.copy(placement = ImagePlacement.FLOAT).encode())
                        PictureLayout.WRAP -> {
                            val (snapped, newData) = snapWrap(e, d)
                            vm.moveElement(snapped)
                            vm.updateElementData(e, newData.encode())
                        }
                        PictureLayout.INLINE -> toInline(e, d)
                    }
                },
                onFront = vm::bringToFront,
                onDelete = { e ->
                    canvas.clearSelection()
                    elementMediaPath(e.kind, e.data)?.let(audio::stop)
                    session.textBoxes.remove(e.id)?.let { if (session.activeEditor === it) session.activeEditor = null }
                    vm.deleteElement(e)
                },
                onShapeStyle = { shapeStyling = it.id },
                onLineStyle = { lineStyling = it.id },
                onShapeText = { e, d, text -> vm.updateElementData(e, d.copy(text = text).encodePayload()) },
                onTextChanged = vm::saveTextBox,
                onTextFocus = { _, state, focused ->
                    if (focused) {
                        session.activeEditor = state
                        canvas.clearSelection()
                    }
                },
                onArrowEdit = { moved, d -> vm.replaceElement(moved.copy(data = d.encodePayload())) },
            )
        }

        fun backgroundTap(p: Offset) {
            if (canvas.selectedId != null || canvas.editingShapeId != null || canvas.selectedInlineBlock != null) {
                canvas.clearSelection()
                return
            }
            sweepBlankTextBoxes()
            if (scrapbook) {
                if (p.y > titleBottomPx) {
                    // Scrapbook pages: tap anywhere to start a text box there.
                    val w = 0.6f
                    val x = (p.x / widthPx - 0.03f).coerceIn(0.03f, 0.97f - w)
                    val y = (p.y / widthPx - 0.04f).coerceAtLeast(0f)
                    scope.launch {
                        val id = vm.addTextBox(page.id, x, y, w)
                        canvas.pendingFocusTextBox = id
                    }
                }
            } else if (p.y > titleBottomPx) {
                editor.focusEnd()
                keyboard?.show()
            }
        }

        // ---- Wrapping text around pictures ----

        val gapPx = with(density) { WrapGap.toPx() }
        val wrapRects = elements.mapNotNull { e ->
            if (e.kind != ElementKind.IMAGE) return@mapNotNull null
            val d = decodeImageData(e.data) ?: return@mapNotNull null
            if (!d.wraps) return@mapNotNull null
            val r = canvas.bounds[e.id] ?: return@mapNotNull null
            d.placement to r
        }
        val hooks = EditorLayoutHooks(
            insets = if (wrapRects.isEmpty()) null else { id ->
                val b = canvas.blockBounds[id]
                if (b == null) {
                    null
                } else {
                    var start = 0f
                    var end = 0f
                    for ((placement, r) in wrapRects) {
                        if (b.first < r.bottom && b.second > r.top) {
                            if (placement == ImagePlacement.WRAP_LEFT) start = max(start, r.right + gapPx - padHPx)
                            else end = max(end, widthPx - r.left + gapPx - padHPx)
                        }
                    }
                    if (start <= 0f && end <= 0f) null else with(density) { start.coerceAtLeast(0f).toDp() to end.coerceAtLeast(0f).toDp() }
                }
            },
            root = { canvas.contentCoords },
            onBlockBounds = { id, top, bottom -> canvas.blockBounds[id] = top to bottom },
            imageBlock = { block, textWidth ->
                InlineImageSlot(
                    block = block,
                    textWidth = textWidth,
                    styles = styles,
                    selected = canvas.selectedInlineBlock == block.id,
                    onTap = { block.image?.let { viewing = it.path } },
                    onLongPress = {
                        canvas.clearSelection()
                        focusManager.clearFocus()
                        canvas.selectedInlineBlock = block.id
                    },
                    onDecorate = { decoratingInline = block.id },
                    onSize = {
                        val sizes = listOf(0.4f, 0.6f, 0.8f, 1f)
                        val next = sizes.firstOrNull { it > block.imageWidth + 0.01f } ?: sizes.first()
                        editor.setImageWidth(block.id, next)
                    },
                    onAlign = {
                        val next = when (block.align) {
                            Align.START -> Align.CENTER
                            Align.CENTER, Align.JUSTIFY -> Align.END
                            Align.END -> Align.START
                        }
                        editor.setBlockAlign(block.id, next)
                    },
                    onFloat = { toFloating(block) },
                    onDone = { canvas.selectedInlineBlock = null },
                    onDelete = {
                        canvas.selectedInlineBlock = null
                        editor.removeBlock(block.id)
                    },
                )
            },
        )

        // ---- Touch handling for tools, lines and empty space ----

        val latestElements by rememberUpdatedState(elements)
        val latestTool by rememberUpdatedState(tool)
        val latestBrush by rememberUpdatedState(brushLevel)
        val latestInk by rememberUpdatedState(inkArgbResolved)
        val inkColor by rememberUpdatedState(ink)
        val tapHandler by rememberUpdatedState(::backgroundTap)
        val latestToolDone by rememberUpdatedState(onToolDone)
        val accentActions by rememberUpdatedState(actions)

        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxOf(elementsBottom + 48.dp, viewport))
                    .onPlaced { canvas.contentCoords = it }
                    .pointerInput(widthPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            when (latestTool) {
                                PageTool.BRUSH -> {
                                    down.consume()
                                    canvas.drawing = true
                                    canvas.liveColor = inkOr(latestInk, inkColor)
                                    canvas.liveWidth = brushWidthPx(latestBrush, widthPx)
                                    canvas.liveStroke.clear()
                                    canvas.liveStroke.add(down.position)
                                    var twoFinger = false
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.count { it.pressed }
                                        if (pressed > 1) {
                                            // Two fingers scroll the page instead of drawing.
                                            twoFinger = true
                                            canvas.liveStroke.clear()
                                            scroll.dispatchRawDelta(-event.calculatePan().y)
                                        } else if (!twoFinger) {
                                            event.changes.firstOrNull { it.id == down.id && it.pressed }?.let { canvas.liveStroke.add(it.position) }
                                        }
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    canvas.drawing = false
                                    if (!twoFinger && canvas.liveStroke.isNotEmpty()) {
                                        val pts = canvas.liveStroke.flatMap { listOf(it.x / widthPx, it.y / widthPx) }
                                        vm.addStroke(page.id, if (pts.size == 2) pts + pts else pts, latestInk, latestBrush)
                                    } else {
                                        canvas.liveStroke.clear()
                                    }
                                }
                                PageTool.ERASER -> {
                                    down.consume()
                                    val radius = brushWidthPx(5, widthPx) / 2 + 10.dp.toPx()
                                    fun eraseAt(p: Offset) {
                                        latestElements.filter { it.kind == ElementKind.STROKE && !canvas.erased.containsKey(it.id) }.forEach { e ->
                                            val d = decodePayload<StrokeData>(e.data) ?: return@forEach
                                            if (distanceToPolyline(p, strokePoints(d, widthPx)) <= radius + brushWidthPx(d.level, widthPx) / 2) {
                                                canvas.erased[e.id] = true
                                                vm.deleteElement(e)
                                            }
                                        }
                                    }
                                    eraseAt(down.position)
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { c ->
                                            if (c.pressed) eraseAt(c.position)
                                            c.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                                PageTool.CONNECT -> {
                                    down.consume()
                                    val up = waitForUpOrCancellation(PointerEventPass.Initial)
                                    up?.consume()
                                    val p = up?.position ?: return@awaitEachGesture
                                    val hit = latestElements
                                        .filter { it.kind in ConnectableKinds }
                                        .sortedByDescending { it.z }
                                        .firstOrNull { canvas.bounds[it.id]?.contains(p) == true }
                                        ?: return@awaitEachGesture
                                    val first = canvas.connectFrom
                                    if (first == null) {
                                        canvas.connectFrom = hit.id
                                    } else if (first != hit.id) {
                                        vm.addConnector(page.id, first, hit.id)
                                        canvas.connectFrom = null
                                        latestToolDone()
                                    }
                                }
                                PageTool.NONE -> {
                                    val p = down.position
                                    val onItem = canvas.bounds.values.any { it.inflate(4.dp.toPx()).contains(p) }
                                    val line = if (onItem) null else lineHit(p, latestElements, canvas.bounds, widthPx, 18.dp.toPx())
                                    if (line != null) {
                                        down.consume()
                                        val arrowData = if (line.kind == ElementKind.ARROW && canvas.selectedId == line.id) decodePayload<ArrowData>(line.data) else null
                                        var moved = false
                                        var last = p
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val c = event.changes.firstOrNull { it.id == down.id }
                                            if (c != null && c.pressed && arrowData != null) {
                                                val delta = c.position - last
                                                if (moved || delta.getDistance() > 2f) {
                                                    moved = true
                                                    last = c.position
                                                    val cur = canvas.arrowDrag?.takeIf { it.first == line } ?: Triple(line, line, arrowData)
                                                    canvas.arrowDrag = Triple(line, cur.second.copy(x = cur.second.x + delta.x / widthPx, y = (cur.second.y + delta.y / widthPx).coerceAtLeast(0f)), cur.third)
                                                }
                                            }
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })
                                        if (moved) {
                                            canvas.arrowDrag?.takeIf { it.first == line }?.let { (_, el, d) -> accentActions.onArrowEdit(el, d) }
                                        } else {
                                            accentActions.onSelect(line.id)
                                        }
                                    } else {
                                        // Not ours: let the items and text have it, then see if anyone used the tap.
                                        val up = waitForUpOrCancellation(PointerEventPass.Final)
                                        if (up != null && !up.isConsumed) tapHandler(up.position)
                                    }
                                }
                            }
                        }
                    },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = title,
                        onValueChange = { session.title = it.replace("\n", "") },
                        textStyle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().padding(start = padH, end = padH, top = 24.dp, bottom = 14.dp),
                        decorationBox = { inner ->
                            Box {
                                if (title.isEmpty()) {
                                    Text("Page title", style = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.32f)))
                                }
                                inner()
                            }
                        },
                    )
                    HorizontalDivider(
                        Modifier
                            .padding(horizontal = 18.dp)
                            .onGloballyPositioned { c ->
                                val root = canvas.contentCoords
                                if (root != null && root.isAttached && c.isAttached) titleBottomPx = root.localPositionOf(c, Offset.Zero).y
                            },
                        thickness = 2.dp,
                        color = lineColor,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = (viewport - 80.dp).coerceAtLeast(200.dp))
                            .pageBackground(
                                background = Color.Transparent,
                                texture = null,
                                type = type,
                                lineColor = lineColor,
                                spacingPx = unitPx,
                                topOffsetPx = with(density) { 10.dp.toPx() },
                            ),
                    ) {
                        if (!scrapbook) {
                            RichTextEditor(
                                state = editor,
                                styles = styles,
                                contentColor = ink,
                                layout = hooks,
                                modifier = Modifier.fillMaxWidth().padding(start = padH, end = padH, top = 10.dp, bottom = 40.dp),
                            )
                        } else if (elements.none { it.kind == ElementKind.TEXT }) {
                            Text(
                                "Scrapbook page: tap anywhere to add text",
                                color = ink.copy(alpha = 0.35f),
                                fontSize = 15.sp,
                                modifier = Modifier.padding(start = padH, top = 16.dp),
                            )
                        }
                    }
                }
                PageElementLayer(
                    elements = elements,
                    pageWidth = pageWidth,
                    ink = ink,
                    tool = tool,
                    allowTextLayout = !scrapbook,
                    canvas = canvas,
                    styles = styles,
                    textEditor = vm::textEditor,
                    actions = actions,
                )
            }
        }

        if (tool == PageTool.CONNECT) {
            ToolHint(
                if (canvas.connectFrom == null) "Tap the first item to connect" else "Now tap the item to connect it to",
                Modifier.align(Alignment.TopCenter),
            )
        }
    }

    viewing?.let { path -> ImageViewer(path = path, onDismiss = { viewing = null }) }
    decorating?.let { (element, data) ->
        DecorateSheet(
            data = data,
            onChange = { updated ->
                decorating = element to updated
                vm.updateElementData(element, updated.encode())
            },
            onDismiss = { decorating = null },
        )
    }
    decoratingInline?.let { id ->
        val block = editor.blocks.firstOrNull { it.id == id }
        val data = block?.image
        if (data == null) {
            decoratingInline = null
        } else {
            DecorateSheet(
                data = data,
                onChange = { editor.updateImage(id, it) },
                onDismiss = { decoratingInline = null },
            )
        }
    }
    shapeStyling?.let { id ->
        val e = elements.firstOrNull { it.id == id }
        val d = e?.let { decodePayload<ShapeData>(it.data) }
        if (e == null || d == null) {
            shapeStyling = null
        } else {
            ShapeStyleSheet(
                data = d,
                ink = contentColorFor(page.backgroundArgb?.let { Color(it) } ?: LocalQuartzColors.current.card),
                onChange = { vm.updateElementData(e, it.encodePayload()) },
                onDismiss = { shapeStyling = null },
            )
        }
    }
    lineStyling?.let { id ->
        val e = elements.firstOrNull { it.id == id }
        if (e == null) {
            lineStyling = null
        } else {
            LineStyleSheet(
                arrow = if (e.kind == ElementKind.ARROW) decodePayload<ArrowData>(e.data) else null,
                connector = if (e.kind == ElementKind.CONNECTOR) decodePayload<ConnectorData>(e.data) else null,
                ink = contentColorFor(page.backgroundArgb?.let { Color(it) } ?: LocalQuartzColors.current.card),
                onArrow = { vm.updateElementData(e, it.encodePayload()) },
                onConnector = { vm.updateElementData(e, it.encodePayload()) },
                onDismiss = { lineStyling = null },
            )
        }
    }
}

/** Items a connector can join. */
private val ConnectableKinds = setOf(ElementKind.SHAPE, ElementKind.TEXT, ElementKind.IMAGE, ElementKind.VOICE)

@Composable
private fun ToolHint(text: String, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(top = 12.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 4.dp,
    ) {
        Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** A picture placed in line with the text. Tap to view; long-press for its toolbar. */
@Composable
private fun InlineImageSlot(
    block: BlockState,
    textWidth: Dp,
    styles: EditorStyles,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDecorate: () -> Unit,
    onSize: () -> Unit,
    onAlign: () -> Unit,
    onFloat: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val data = block.image ?: return
    val alignment = when (block.align) {
        Align.CENTER, Align.JUSTIFY -> Alignment.Center
        Align.END -> Alignment.CenterEnd
        Align.START -> Alignment.CenterStart
    }
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().heightIn(min = inlineImageSlotHeight(block, textWidth, styles)), contentAlignment = alignment) {
        Box(
            Modifier
                .pointerInput(block.id) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
                .then(if (selected) Modifier.border(2.dp, accent, RoundedCornerShape(4.dp)).padding(2.dp) else Modifier),
        ) {
            DecoratedImage(data, textWidth * block.imageWidth)
        }
        if (selected) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTap) { Icon(Icons.Rounded.OpenInFull, contentDescription = "View full size") }
                    IconButton(onClick = onDecorate) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Decorate") }
                    IconButton(onClick = onSize) { Icon(Icons.Rounded.PhotoSizeSelectLarge, contentDescription = "Size") }
                    IconButton(onClick = onAlign) { Icon(Icons.AutoMirrored.Rounded.FormatAlignLeft, contentDescription = "Alignment") }
                    IconButton(onClick = onFloat) { Icon(Icons.Rounded.PictureInPicture, contentDescription = "Float freely") }
                    IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = accent) }
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
