package com.quartz.notes.ui.notebook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlipToFront
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ViewQuilt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ArrowData
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.decodeVoiceData
import com.quartz.notes.ui.editor.EditorStyles
import com.quartz.notes.ui.editor.RichEditorState
import com.quartz.notes.ui.editor.RichTextEditor
import com.quartz.notes.ui.media.DecoratedImage
import com.quartz.notes.ui.media.VoicePlayer
import com.quartz.notes.ui.media.heightFactor
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fixed height of a voice note player on a page. */
val VoiceElementHeight: Dp = 56.dp
private val GripWidth: Dp = 30.dp
private val TextGripHeight: Dp = 24.dp

/** Where to put a picture relative to the text. */
enum class PictureLayout { FLOAT, WRAP, INLINE }

/**
 * Interaction state for one page's canvas (selection, live drawing, item bounds).
 * Bounds are in px relative to the page content (the same space element positions use).
 */
@Stable
class PageCanvasState {
    var selectedId by mutableStateOf<Long?>(null)
    var editingShapeId by mutableStateOf<Long?>(null)
    var selectedInlineBlock by mutableStateOf<String?>(null)
    var connectFrom by mutableStateOf<Long?>(null)
    var pendingFocusTextBox by mutableStateOf<Long?>(null)

    /** Laid-out boxes of pictures, voice notes, text boxes and shapes. */
    val bounds = mutableStateMapOf<Long, Rect>()

    /** Paragraph tops/bottoms of the page's main text, for wrapping and in-line pictures. */
    val blockBounds = mutableStateMapOf<String, Pair<Float, Float>>()

    /** The brush stroke being drawn, in px, and how it looks. */
    val liveStroke = mutableStateListOf<Offset>()
    var liveColor by mutableStateOf<Color?>(null)
    var liveWidth by mutableStateOf(6f)

    /** Strokes the eraser has removed but the database hasn't confirmed yet. */
    val erased = mutableStateMapOf<Long, Boolean>()

    /** An arrow being dragged: its saved row and the live version. */
    var arrowDrag by mutableStateOf<Triple<PageElementEntity, PageElementEntity, ArrowData>?>(null)

    var contentCoords: LayoutCoordinates? = null

    /** True while a brush stroke is being drawn (so the live stroke isn't cleared mid-draw). */
    var drawing = false

    /** Where the selected item's toolbar is, so taps on it are never mistaken for taps on a line. */
    var toolbarRect: Rect? = null

    fun clearSelection() {
        toolbarRect = null
        selectedId = null
        editingShapeId = null
        selectedInlineBlock = null
    }
}

/** Callbacks from the element layer. */
class ElementActions(
    val onSelect: (Long?) -> Unit,
    /** Saves a new position, size or rotation (only those fields are written). */
    val onMove: (PageElementEntity) -> Unit,
    val onView: (ImageData) -> Unit,
    val onDecorate: (PageElementEntity, ImageData) -> Unit,
    val onPictureLayout: (PageElementEntity, ImageData, PictureLayout) -> Unit,
    val onFront: (PageElementEntity) -> Unit,
    val onDelete: (PageElementEntity) -> Unit,
    val onShapeStyle: (PageElementEntity) -> Unit,
    val onLineStyle: (PageElementEntity) -> Unit,
    /** Saves a dragged arrow (position and shape together). */
    val onArrowEdit: (PageElementEntity, ArrowData) -> Unit,
    val onShapeText: (PageElementEntity, ShapeData, String) -> Unit,
    val onTextChanged: (PageElementEntity) -> Unit,
    val onTextFocus: (PageElementEntity, RichEditorState, Boolean) -> Unit,
)

/** Bottom edge of an element in px, for sizing the page's scroll area. */
fun elementBottomPx(e: PageElementEntity, pageWidthPx: Float, bounds: Map<Long, Rect>): Float = when (e.kind) {
    ElementKind.ARROW -> {
        val d = decodePayload<ArrowData>(e.data)
        (e.y + max(0f, d?.dy ?: 0f)) * pageWidthPx
    }
    ElementKind.STROKE -> decodePayload<StrokeData>(e.data)?.points?.chunked(2)?.maxOfOrNull { it.getOrElse(1) { 0f } }?.times(pageWidthPx) ?: 0f
    ElementKind.CONNECTOR -> 0f
    ElementKind.IMAGE -> bounds[e.id]?.bottom ?: (e.y + e.width * (decodeImageData(e.data)?.heightFactor() ?: 1f)) * pageWidthPx
    else -> bounds[e.id]?.bottom ?: (e.y + max(e.height, 0.15f)) * pageWidthPx
}

/**
 * Everything placed on a page: drawings, lines, pictures, voice notes, text boxes and shapes.
 * The layer itself ignores touches outside its items, so the text underneath stays editable.
 */
@Composable
fun BoxScope.PageElementLayer(
    elements: List<PageElementEntity>,
    pageWidth: Dp,
    ink: Color,
    tool: PageTool,
    allowTextLayout: Boolean,
    canvas: PageCanvasState,
    styles: EditorStyles,
    textEditor: (PageElementEntity) -> RichEditorState,
    actions: ElementActions,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { pageWidth.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    // Drawings, arrows and connectors share one canvas below the other items.
    Canvas(Modifier.matchParentSize()) {
        val selected = canvas.selectedId
        for (e in elements.sortedBy { it.z }) {
            when (e.kind) {
                ElementKind.STROKE -> {
                    if (canvas.erased.containsKey(e.id)) continue
                    val d = decodePayload<StrokeData>(e.data) ?: continue
                    drawInkStroke(strokePoints(d, widthPx), inkOr(d.color, ink), brushWidthPx(d.level, widthPx))
                }
                ElementKind.ARROW -> {
                    val drag = canvas.arrowDrag?.takeIf { it.first == e }
                    val el = drag?.second ?: e
                    val d = drag?.third ?: decodePayload<ArrowData>(e.data) ?: continue
                    val (s, t) = arrowEnds(el, d, widthPx)
                    val w = lineWidthPx(d.level, widthPx)
                    val color = inkOr(d.color, ink)
                    if (selected == e.id) drawLine(accent.copy(alpha = 0.3f), s, t, strokeWidth = w + 10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(color, s, t, strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    if (d.endHead) drawArrowHead(s, t, color, w)
                    if (d.startHead) drawArrowHead(t, s, color, w)
                }
                ElementKind.CONNECTOR -> {
                    val d = decodePayload<ConnectorData>(e.data) ?: continue
                    val a = canvas.bounds[d.from] ?: continue
                    val b = canvas.bounds[d.to] ?: continue
                    val pts = connectorPoints(a, b, d.route)
                    val path = connectorPath(pts, d.route)
                    val w = lineWidthPx(d.level, widthPx)
                    val color = inkOr(d.color, ink)
                    if (selected == e.id) drawPath(path, accent.copy(alpha = 0.3f), style = Stroke(w + 10.dp.toPx()))
                    drawPath(path, color, style = Stroke(w, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    if (d.endHead && pts.size >= 2) drawArrowHead(pts[pts.size - 2], pts.last(), color, w)
                }
            }
        }
        if (canvas.liveStroke.isNotEmpty()) {
            drawInkStroke(canvas.liveStroke.toList(), canvas.liveColor ?: ink, canvas.liveWidth)
        }
    }

    for (element in elements.sortedBy { it.z }) {
        if (element.kind !in ItemKinds) continue
        key(element.id) {
            ElementItem(
                element = element,
                pageWidth = pageWidth,
                ink = ink,
                tool = tool,
                canvas = canvas,
                styles = styles,
                textEditor = textEditor,
                actions = actions,
            )
        }
    }

    // Handles for the selected arrow's two ends.
    val selectedElement = elements.firstOrNull { it.id == canvas.selectedId }
    if (selectedElement != null && selectedElement.kind == ElementKind.ARROW && tool == PageTool.NONE) {
        ArrowHandles(selectedElement, widthPx, canvas, actions)
    }
    if (selectedElement != null && tool == PageTool.NONE) {
        ElementToolbar(selectedElement, pageWidth, canvas, allowTextLayout, actions)
    }
}

/** Kinds drawn as their own composables (the rest are drawn on the shared canvas). */
private val ItemKinds = setOf(ElementKind.IMAGE, ElementKind.VOICE, ElementKind.TEXT, ElementKind.SHAPE)

@OptIn(FlowPreview::class)
@Composable
private fun ElementItem(
    element: PageElementEntity,
    pageWidth: Dp,
    ink: Color,
    tool: PageTool,
    canvas: PageCanvasState,
    styles: EditorStyles,
    textEditor: (PageElementEntity) -> RichEditorState,
    actions: ElementActions,
) {
    // One live copy per element. It follows the saved row, except while a finger is down.
    var live by remember(element.id) { mutableStateOf(element) }
    var gesturing by remember(element.id) { mutableStateOf(false) }
    LaunchedEffect(element) { if (!gesturing) live = element }
    val latestSaved by rememberUpdatedState(element)
    val density = LocalDensity.current
    val widthPx = with(density) { pageWidth.toPx() }
    val selected = canvas.selectedId == element.id
    val connecting = tool == PageTool.CONNECT && canvas.connectFrom == element.id
    val accent = MaterialTheme.colorScheme.primary

    val imageData = remember(element.data) { if (element.kind == ElementKind.IMAGE) decodeImageData(element.data) else null }
    val voiceData = remember(element.data) { if (element.kind == ElementKind.VOICE) decodeVoiceData(element.data) else null }
    val shapeData = remember(element.data) { if (element.kind == ElementKind.SHAPE) decodePayload<ShapeData>(element.data) else null }
    val heightFactor by rememberUpdatedState(imageData?.heightFactor())
    val latestSelected by rememberUpdatedState(selected)
    val latestImage by rememberUpdatedState(imageData)
    val kind = element.kind

    fun commit() {
        val moved = live
        val saved = latestSaved
        if (moved.x != saved.x || moved.y != saved.y || moved.width != saved.width || moved.height != saved.height ||
            moved.rotation != saved.rotation
        ) {
            actions.onMove(moved)
        }
    }

    Box(
        Modifier
            .offset { IntOffset((live.x * widthPx).roundToInt(), (live.y * widthPx).roundToInt()) }
            .onGloballyPositioned { c ->
                val root = canvas.contentCoords ?: return@onGloballyPositioned
                if (!c.isAttached || !root.isAttached) return@onGloballyPositioned
                val topLeft = root.localPositionOf(c, Offset.Zero)
                val rect = Rect(topLeft, androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
                if (canvas.bounds[element.id] != rect) canvas.bounds[element.id] = rect
            }
            .pointerInput(element.id, kind) {
                detectTapGestures(
                    onTap = {
                        when (kind) {
                            // Pictures open full size on tap; long-press to move or edit them.
                            ElementKind.IMAGE -> latestImage?.let(actions.onView)
                            ElementKind.SHAPE -> if (latestSelected) canvas.editingShapeId = element.id else actions.onSelect(element.id)
                            else -> actions.onSelect(element.id)
                        }
                    },
                    onDoubleTap = if (kind == ElementKind.SHAPE) {
                        { actions.onSelect(element.id); canvas.editingShapeId = element.id }
                    } else {
                        null
                    },
                    onLongPress = { actions.onSelect(element.id) },
                )
            }
            .then(
                if (selected && tool == PageTool.NONE && canvas.editingShapeId != element.id) {
                    Modifier.pointerInput(element.id, widthPx) {
                        // Move / pinch / twist. Saves once, when the last finger lifts.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = true)
                            gesturing = true
                            try {
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.any { it.isConsumed }) continue
                                    val zoom = event.calculateZoom()
                                    val rotation = event.calculateRotation()
                                    val pan = event.calculatePan()
                                    if (zoom != 1f || rotation != 0f || pan != Offset.Zero) {
                                        live = transformed(live, kind, zoom, rotation, pan, widthPx, heightFactor)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            } finally {
                                gesturing = false
                                commit()
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .graphicsLayer { rotationZ = if (kind == ElementKind.IMAGE) live.rotation else 0f },
    ) {
        val outline = when {
            connecting -> Modifier.border(3.dp, accent, RoundedCornerShape(6.dp))
            selected -> Modifier.border(2.dp, accent, RoundedCornerShape(4.dp))
            else -> Modifier
        }
        when (kind) {
            ElementKind.IMAGE -> if (imageData != null) {
                Box(outline.padding(if (selected || connecting) 2.dp else 0.dp)) {
                    DecoratedImage(imageData, pageWidth * live.width)
                }
            }
            ElementKind.VOICE -> if (voiceData != null) {
                val w = pageWidth * live.width
                Row(
                    Modifier
                        .width(w + GripWidth)
                        .height(VoiceElementHeight)
                        .then(if (selected) Modifier.border(2.dp, accent, CircleShape) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Grab handle: the player's own buttons take taps, so selecting starts here.
                    Box(Modifier.width(GripWidth).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragIndicator, contentDescription = "Move voice note", tint = accent)
                    }
                    VoicePlayer(voiceData.path, voiceData.durationMs, modifier = Modifier.width(w))
                }
            }
            ElementKind.SHAPE -> if (shapeData != null) {
                ShapeItem(
                    element = element,
                    data = shapeData,
                    widthUnits = live.width,
                    width = pageWidth * live.width,
                    height = pageWidth * live.height,
                    ink = ink,
                    editing = canvas.editingShapeId == element.id,
                    modifier = outline,
                    onText = { actions.onShapeText(element, shapeData, it) },
                    onDoneEditing = { if (canvas.editingShapeId == element.id) canvas.editingShapeId = null },
                )
                if (selected) {
                    ResizeHandle(
                        Modifier.align(Alignment.BottomEnd),
                        onStart = { gesturing = true },
                        onEnd = {
                            gesturing = false
                            commit()
                        },
                    ) { drag ->
                        live = live.copy(
                            width = (live.width + drag.x / widthPx).coerceIn(0.08f, 1f),
                            height = (live.height + drag.y / widthPx).coerceIn(0.06f, 2f),
                        )
                    }
                }
            }
            ElementKind.TEXT -> {
                val state = remember(element.id) { textEditor(element) }
                TextBoxItem(
                    element = element,
                    state = state,
                    width = pageWidth * live.width,
                    ink = ink,
                    styles = styles,
                    selected = selected,
                    canvas = canvas,
                    outline = outline,
                    actions = actions,
                    onGripDrag = { drag ->
                        gesturing = true
                        live = live.copy(
                            x = (live.x + drag.x / widthPx).coerceIn(-live.width * 0.8f, 1f - live.width * 0.2f),
                            y = (live.y + drag.y / widthPx).coerceAtLeast(0f),
                        )
                    },
                    onGripEnd = {
                        gesturing = false
                        commit()
                    },
                    onResizeStart = { gesturing = true },
                    onResizeEnd = {
                        gesturing = false
                        commit()
                    },
                    onResize = { drag -> live = live.copy(width = (live.width + drag.x / widthPx).coerceIn(0.2f, 1f)) },
                )
            }
        }
    }

}

/** Applies a move / pinch / twist to an element. Only pictures rotate. */
private fun transformed(
    cur: PageElementEntity,
    kind: String,
    zoom: Float,
    rotation: Float,
    pan: Offset,
    widthPx: Float,
    heightFactor: Float?,
): PageElementEntity {
    val scalable = kind == ElementKind.IMAGE || kind == ElementKind.SHAPE || kind == ElementKind.TEXT
    val newWidth = if (scalable) (cur.width * zoom).coerceIn(0.08f, 1f) else cur.width
    val newHeight = if (kind == ElementKind.SHAPE) (cur.height * zoom).coerceIn(0.06f, 2f) else cur.height
    val h = when (kind) {
        ElementKind.IMAGE -> heightFactor ?: 0f
        ElementKind.SHAPE -> if (cur.width > 0) cur.height / cur.width else 0f
        else -> 0f
    }
    // Keep the center fixed while resizing.
    val dx = (cur.width - newWidth) / 2
    val dy = (cur.width - newWidth) * h / 2
    return cur.copy(
        x = (cur.x + dx + pan.x / widthPx).coerceIn(-newWidth * 0.8f, 1f - newWidth * 0.2f),
        y = (cur.y + dy + pan.y / widthPx).coerceAtLeast(0f),
        width = newWidth,
        height = newHeight,
        rotation = if (kind == ElementKind.IMAGE) (cur.rotation + rotation) % 360f else 0f,
    )
}

@Composable
private fun ShapeItem(
    element: PageElementEntity,
    data: ShapeData,
    widthUnits: Float,
    width: Dp,
    height: Dp,
    ink: Color,
    editing: Boolean,
    modifier: Modifier,
    onText: (String) -> Unit,
    onDoneEditing: () -> Unit,
) {
    val fill = data.fill?.let { Color(it) }
    val outline = inkOr(data.outline, ink)
    val textColor = when {
        data.textColor != 0 -> Color(data.textColor)
        fill != null && fill.alpha > 0.5f -> contentColorFor(fill)
        else -> ink
    }
    Box(modifier.size(width, height), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = lineWidthPx(data.outlineLevel, size.width / max(0.01f, widthUnits))
            val inset = stroke / 2
            val path = shapePath(data.shape, androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke))
            path.translate(Offset(inset, inset))
            if (fill != null) drawPath(path, fill)
            if (data.outlineLevel > 0) drawPath(path, outline, style = Stroke(stroke))
        }
        val fontSize = (min(width.value, height.value) * 0.16f).coerceIn(11f, 30f).sp
        val textStyle = TextStyle(fontSize = fontSize, color = textColor, textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
        val pad = Modifier.padding(horizontal = width * 0.14f, vertical = height * 0.14f)
        if (editing) {
            var text by remember(element.id) { mutableStateOf(data.text) }
            val focus = remember { FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onText(it)
                },
                textStyle = textStyle,
                cursorBrush = SolidColor(textColor),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = pad
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) onDoneEditing()
                    },
            )
        } else if (data.text.isNotBlank()) {
            Text(data.text, style = textStyle, modifier = pad)
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun TextBoxItem(
    element: PageElementEntity,
    state: RichEditorState,
    width: Dp,
    ink: Color,
    styles: EditorStyles,
    selected: Boolean,
    canvas: PageCanvasState,
    outline: Modifier,
    actions: ElementActions,
    onGripDrag: (Offset) -> Unit,
    onGripEnd: () -> Unit,
    onResizeStart: () -> Unit,
    onResizeEnd: () -> Unit,
    onResize: (Offset) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val latestActions by rememberUpdatedState(actions)
    val latestGripDrag by rememberUpdatedState(onGripDrag)
    val latestGripEnd by rememberUpdatedState(onGripEnd)

    // Save typing shortly after it stops.
    LaunchedEffect(state) {
        snapshotFlow { state.version }.drop(1).debounce(500).collect { latestActions.onTextChanged(element) }
    }
    // Leaving the page (or swiping away) saves anything not yet written.
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { latestActions.onTextChanged(element) }
    }
    // A brand-new box gets the cursor right away.
    LaunchedEffect(canvas.pendingFocusTextBox) {
        if (canvas.pendingFocusTextBox == element.id) {
            canvas.pendingFocusTextBox = null
            state.focusEnd()
        }
    }

    val active = focused || selected
    Column(
        Modifier
            .width(width)
            .then(
                if (active) {
                    Modifier.border(1.5.dp, accent.copy(alpha = if (selected) 1f else 0.5f), RoundedCornerShape(8.dp))
                } else {
                    outline
                },
            )
            .onFocusChanged {
                if (it.hasFocus != focused) {
                    focused = it.hasFocus
                    latestActions.onTextFocus(element, state, it.hasFocus)
                }
            },
    ) {
        if (active) {
            // Grip strip: drag to move, tap to select (for the toolbar).
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TextGripHeight)
                    .background(accent.copy(alpha = 0.08f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .pointerInput(element.id) {
                        // Tap selects the box (for its toolbar); drag moves it. Claims the touch right away
                        // so the box's own move gesture doesn't compete.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var dragging = false
                            var last = down.position
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                if (!dragging && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    last = c.position
                                }
                                if (dragging) {
                                    latestGripDrag(c.position - last)
                                    last = c.position
                                }
                                c.consume()
                            }
                            if (dragging) latestGripEnd() else latestActions.onSelect(element.id)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.DragIndicator, contentDescription = "Move text box", tint = accent, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f })
            }
        }
        Box {
            RichTextEditor(
                state = state,
                styles = styles,
                contentColor = ink,
                placeholder = "Type here",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (selected) {
                ResizeHandle(Modifier.align(Alignment.BottomEnd), onStart = onResizeStart, onEnd = onResizeEnd, onDrag = onResize)
            }
        }
    }
}

/**
 * Small round handle for resizing; reports drag deltas in px. It claims the touch as soon as it
 * lands, so the item's own move gesture never starts.
 */
@Composable
private fun ResizeHandle(modifier: Modifier, onStart: () -> Unit, onEnd: () -> Unit, onDrag: (Offset) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    val drag by rememberUpdatedState(onDrag)
    Box(
        modifier
            .size(30.dp)
            .padding(3.dp)
            .background(Color.White, CircleShape)
            .border(2.5.dp, accent, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    start()
                    var last = down.position
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val delta = c.position - last
                            last = c.position
                            c.consume()
                            if (delta != Offset.Zero) drag(delta)
                        }
                    } finally {
                        end()
                    }
                }
            },
    )
}

@Composable
private fun ArrowHandles(element: PageElementEntity, widthPx: Float, canvas: PageCanvasState, actions: ElementActions) {
    val base = remember(element) { decodePayload<ArrowData>(element.data) } ?: return
    val drag = canvas.arrowDrag?.takeIf { it.first == element }
    val live = drag?.second ?: element
    val data = drag?.third ?: base
    val (s, t) = arrowEnds(live, data, widthPx)
    val latestActions by rememberUpdatedState(actions)

    @Composable
    fun Handle(at: Offset, isStart: Boolean) {
        val accent = MaterialTheme.colorScheme.primary
        val sizePx = with(LocalDensity.current) { 30.dp.toPx() }
        Box(
            Modifier
                .offset { IntOffset((at.x - sizePx / 2).roundToInt(), (at.y - sizePx / 2).roundToInt()) }
                .size(30.dp)
                .padding(4.dp)
                .background(Color.White, CircleShape)
                .border(2.5.dp, accent, CircleShape)
                .pointerInput(element, isStart) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val delta = c.position - last
                            last = c.position
                            c.consume()
                            // Start from any drag already in progress for this arrow (e.g. a body move).
                            val (_, el, d) = canvas.arrowDrag?.takeIf { it.first == element } ?: Triple(element, element, base)
                            val ddx = delta.x / widthPx
                            val ddy = delta.y / widthPx
                            canvas.arrowDrag = if (isStart) {
                                Triple(element, el.copy(x = el.x + ddx, y = (el.y + ddy).coerceAtLeast(0f)), d.copy(dx = d.dx - ddx, dy = d.dy - ddy))
                            } else {
                                Triple(element, el, d.copy(dx = d.dx + ddx, dy = d.dy + ddy))
                            }
                        }
                        canvas.arrowDrag?.takeIf { it.first == element }?.let { (_, moved, d) -> latestActions.onArrowEdit(moved, d) }
                    }
                },
        )
    }
    Handle(s, isStart = true)
    Handle(t, isStart = false)
}

@Composable
private fun ElementToolbar(
    element: PageElementEntity,
    pageWidth: Dp,
    canvas: PageCanvasState,
    allowTextLayout: Boolean,
    actions: ElementActions,
) {
    val image = remember(element.data) { if (element.kind == ElementKind.IMAGE) decodeImageData(element.data) else null }
    val density = LocalDensity.current
    val widthPx = with(density) { pageWidth.toPx() }
    val rect = when (element.kind) {
        ElementKind.ARROW -> {
            val d = decodePayload<ArrowData>(element.data) ?: return
            val (s, t) = arrowEnds(element, d, widthPx)
            Rect(min(s.x, t.x), min(s.y, t.y), max(s.x, t.x), max(s.y, t.y))
        }
        ElementKind.CONNECTOR -> {
            val d = decodePayload<ConnectorData>(element.data) ?: return
            val a = canvas.bounds[d.from] ?: return
            val b = canvas.bounds[d.to] ?: return
            val pts = connectorPoints(a, b, d.route)
            Rect(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
        }
        else -> canvas.bounds[element.id] ?: return
    }
    val barHeightPx = with(density) { 58.dp.toPx() }
    val barWidthPx = with(density) { 300.dp.toPx() }
    val y = if (rect.top > barHeightPx) rect.top - barHeightPx else rect.bottom + with(density) { 8.dp.toPx() }
    val x = rect.left.coerceIn(0f, (widthPx - barWidthPx).coerceAtLeast(0f))
    var layoutMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onGloballyPositioned { c ->
                val root = canvas.contentCoords
                if (root != null && root.isAttached && c.isAttached) {
                    canvas.toolbarRect = Rect(root.localPositionOf(c, Offset.Zero), androidx.compose.ui.geometry.Size(c.size.width.toFloat(), c.size.height.toFloat()))
                }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when (element.kind) {
                ElementKind.IMAGE -> if (image != null) {
                    IconButton(onClick = { actions.onView(image) }) { Icon(Icons.Rounded.OpenInFull, contentDescription = "View full size") }
                    IconButton(onClick = { actions.onDecorate(element, image) }) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Decorate") }
                    Box {
                        IconButton(onClick = { layoutMenu = true }) { Icon(Icons.Rounded.ViewQuilt, contentDescription = "Layout with text") }
                        DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Float freely") },
                                trailingIcon = { if (!image.wraps) Icon(Icons.Rounded.Check, null) },
                                onClick = {
                                    layoutMenu = false
                                    actions.onPictureLayout(element, image, PictureLayout.FLOAT)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Wrap text") },
                                enabled = allowTextLayout,
                                trailingIcon = { if (image.wraps) Icon(Icons.Rounded.Check, null) },
                                onClick = {
                                    layoutMenu = false
                                    actions.onPictureLayout(element, image, PictureLayout.WRAP)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("In line with text") },
                                enabled = allowTextLayout,
                                onClick = {
                                    layoutMenu = false
                                    actions.onPictureLayout(element, image, PictureLayout.INLINE)
                                },
                            )
                        }
                    }
                }
                ElementKind.SHAPE -> {
                    IconButton(onClick = { canvas.editingShapeId = element.id }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit text") }
                    IconButton(onClick = { actions.onShapeStyle(element) }) { Icon(Icons.Rounded.Palette, contentDescription = "Shape style") }
                }
                ElementKind.ARROW, ElementKind.CONNECTOR -> {
                    IconButton(onClick = { actions.onLineStyle(element) }) { Icon(Icons.Rounded.Palette, contentDescription = "Line style") }
                }
            }
            if (element.kind != ElementKind.CONNECTOR) {
                IconButton(onClick = { actions.onFront(element) }) { Icon(Icons.Rounded.FlipToFront, contentDescription = "Bring to front") }
            }
            IconButton(onClick = { actions.onSelect(null) }) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { actions.onDelete(element) }) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
