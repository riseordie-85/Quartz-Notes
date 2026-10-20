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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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

/** Where to put a picture relative to the text. */
enum class PictureLayout { FLOAT, INLINE }

/**
 * Interaction state for one page's canvas (selection, live drawing, item bounds).
 * Bounds are in px relative to the page content (the same space element positions use).
 */
@Stable
class PageCanvasState {
    var selectedId by mutableStateOf<Long?>(null)
    var editingShapeId by mutableStateOf<Long?>(null)
    /** The text box being typed in. */
    var editingTextId by mutableStateOf<Long?>(null)
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
        editingTextId = null
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
    /** Page is locked: items stay where they are. */
    locked: Boolean,
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
                locked = locked,
                canvas = canvas,
                styles = styles,
                textEditor = textEditor,
                actions = actions,
            )
        }
    }

    // Handles for the selected arrow's two ends.
    val selectedElement = elements.firstOrNull { it.id == canvas.selectedId }
    if (selectedElement != null && selectedElement.kind == ElementKind.ARROW && tool == PageTool.NONE && !locked) {
        ArrowHandles(selectedElement, widthPx, canvas, actions)
    }
    if (selectedElement != null && tool == PageTool.NONE && !locked) {
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
    locked: Boolean,
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
    // Laid-out size (before rotation), and the height a text box's text needs.
    var sizePx by remember(element.id) { mutableStateOf(IntSize.Zero) }
    var textHeightPx by remember(element.id) { mutableIntStateOf(0) }

    val imageData = remember(element.data) { if (element.kind == ElementKind.IMAGE) decodeImageData(element.data) else null }
    val voiceData = remember(element.data) { if (element.kind == ElementKind.VOICE) decodeVoiceData(element.data) else null }
    val shapeData = remember(element.data) { if (element.kind == ElementKind.SHAPE) decodePayload<ShapeData>(element.data) else null }
    val heightFactor by rememberUpdatedState(imageData?.heightFactor())
    val latestSelected by rememberUpdatedState(selected)
    val latestImage by rememberUpdatedState(imageData)
    val latestLocked by rememberUpdatedState(locked)
    val kind = element.kind
    val rotates = kind == ElementKind.IMAGE || kind == ElementKind.TEXT
    val stalkPx = with(density) { RotateStalk.toPx() }

    fun commit() {
        val moved = live
        val saved = latestSaved
        if (moved.x != saved.x || moved.y != saved.y || moved.width != saved.width || moved.height != saved.height ||
            moved.rotation != saved.rotation
        ) {
            actions.onMove(moved)
        }
    }

    fun startTextEditing() {
        canvas.selectedId = element.id
        canvas.editingTextId = element.id
    }

    Box(
        Modifier
            .offset { IntOffset((live.x * widthPx).roundToInt(), (live.y * widthPx).roundToInt()) }
            // Turned first, so touches and the reported bounds match what's on screen.
            .graphicsLayer { rotationZ = if (rotates) live.rotation else 0f }
            .onGloballyPositioned { c ->
                if (c.size != sizePx) sizePx = c.size
                val root = canvas.contentCoords ?: return@onGloballyPositioned
                if (!c.isAttached || !root.isAttached) return@onGloballyPositioned
                val rect = root.localBoundingBoxOf(c, clipBounds = false)
                if (canvas.bounds[element.id] != rect) canvas.bounds[element.id] = rect
            }
            .pointerInput(element.id, kind) {
                detectTapGestures(
                    onTap = {
                        if (latestLocked) {
                            // Locked page: nothing moves or changes, but pictures still open full size.
                            if (kind == ElementKind.IMAGE) latestImage?.let(actions.onView)
                        } else if (kind == ElementKind.TEXT && latestSelected) {
                            // Tapping a selected text box starts typing in it.
                            startTextEditing()
                        } else {
                            actions.onSelect(element.id)
                        }
                    },
                    onDoubleTap = if (kind == ElementKind.SHAPE) {
                        {
                            if (!latestLocked) {
                                actions.onSelect(element.id)
                                canvas.editingShapeId = element.id
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            .then(
                if (selected && !locked && tool == PageTool.NONE && canvas.editingShapeId != element.id) {
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
            .then(
                if (kind == ElementKind.TEXT && selected && !locked && tool == PageTool.NONE) {
                    // The stem joining the box to its rotate handle (turns with the box).
                    Modifier.drawWithContent {
                        drawContent()
                        drawLine(accent, Offset(size.width / 2, 0f), Offset(size.width / 2, -stalkPx), strokeWidth = 1.5.dp.toPx())
                    }
                } else {
                    Modifier
                },
            ),
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
                        Icon(
                            if (locked) Icons.Rounded.GraphicEq else Icons.Rounded.DragIndicator,
                            contentDescription = if (locked) null else "Move voice note",
                            tint = accent,
                        )
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
                if (selected && !locked) {
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
                    minHeight = pageWidth * live.height,
                    ink = ink,
                    styles = styles,
                    framed = (selected && !locked) || connecting,
                    editing = canvas.editingTextId == element.id && !locked,
                    canvas = canvas,
                    actions = actions,
                    onTextHeight = { textHeightPx = it },
                )
            }
        }
    }

    // A selected text box gets corner handles (resize) and a rotate handle above it.
    if (kind == ElementKind.TEXT && selected && !locked && tool == PageTool.NONE && sizePx != IntSize.Zero) {
        TextBoxHandles(
            live = live,
            boxHeightPx = sizePx.height.toFloat(),
            textHeightPx = textHeightPx.toFloat(),
            widthPx = widthPx,
            stalkPx = stalkPx,
            onStart = { gesturing = true },
            onChange = { live = it },
            onEnd = {
                gesturing = false
                commit()
            },
        )
    }
}

/** Length of the stem between a text box and its rotate handle. */
private val RotateStalk: Dp = 28.dp

private fun rotateBy(v: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/**
 * Square handles on a text box's four corners and a round rotate handle above its top edge.
 * Positions are in the page's own space, worked out from the box's (possibly rotated) geometry.
 */
@Composable
private fun TextBoxHandles(
    live: PageElementEntity,
    boxHeightPx: Float,
    textHeightPx: Float,
    widthPx: Float,
    stalkPx: Float,
    onStart: () -> Unit,
    onChange: (PageElementEntity) -> Unit,
    onEnd: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val minWidthPx = with(density) { 70.dp.toPx() }
    val latestLive by rememberUpdatedState(live)
    val latestBoxHeight by rememberUpdatedState(boxHeightPx)
    val latestTextHeight by rememberUpdatedState(textHeightPx)
    val start by rememberUpdatedState(onStart)
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)

    val w = live.width * widthPx
    val h = boxHeightPx
    val center = Offset(live.x * widthPx + w / 2, live.y * widthPx + h / 2)

    @Composable
    fun Handle(at: Offset, target: Dp, gesture: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit, content: @Composable BoxScope.() -> Unit) {
        val half = with(density) { target.toPx() } / 2
        Box(
            Modifier
                .offset { IntOffset((at.x - half).roundToInt(), (at.y - half).roundToInt()) }
                .size(target)
                .pointerInput(widthPx) { gesture() },
            contentAlignment = Alignment.Center,
            content = content,
        )
    }

    // Corners: (sx, sy) says which side of the box each one is on.
    for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val at = center + rotateBy(Offset(sx * w / 2, sy * h / 2), live.rotation)
        Handle(
            at = at,
            target = 36.dp,
            gesture = {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    start()
                    var cur = latestLive
                    var curH = latestBoxHeight
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val delta = c.positionChange()
                            c.consume()
                            if (delta == Offset.Zero) continue
                            // The drag in the box's own (unrotated) directions.
                            val d = rotateBy(delta, -cur.rotation)
                            val oldW = cur.width * widthPx
                            val newW = (oldW + sx * d.x).coerceIn(minWidthPx, widthPx)
                            val newH = (curH + sy * d.y).coerceAtLeast(latestTextHeight)
                            // Keep the opposite corner where it is.
                            val oldCenter = Offset(cur.x * widthPx + oldW / 2, cur.y * widthPx + curH / 2)
                            val c2 = oldCenter + rotateBy(Offset(sx * (newW - oldW) / 2, sy * (newH - curH) / 2), cur.rotation)
                            cur = cur.copy(
                                x = (c2.x - newW / 2) / widthPx,
                                y = ((c2.y - newH / 2) / widthPx).coerceAtLeast(0f),
                                width = newW / widthPx,
                                height = newH / widthPx,
                            )
                            curH = newH
                            change(cur)
                        }
                    } finally {
                        end()
                    }
                }
            },
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(Color.White, RoundedCornerShape(2.dp))
                    .border(2.dp, accent, RoundedCornerShape(2.dp)),
            )
        }
    }

    // Rotate handle at the top center, at the end of the stem.
    val rotateAt = center + rotateBy(Offset(0f, -(h / 2 + stalkPx)), live.rotation)
    Handle(
        at = rotateAt,
        target = 40.dp,
        gesture = {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                start()
                val cur = latestLive
                val curW = cur.width * widthPx
                val curH = latestBoxHeight
                val pivot = Offset(cur.x * widthPx + curW / 2, cur.y * widthPx + curH / 2)
                val handleAt = pivot + rotateBy(Offset(0f, -(curH / 2 + stalkPx)), cur.rotation)
                val half = 20.dp.toPx()
                // Track the finger in page space by adding up its movement.
                var finger = handleAt + (down.position - Offset(half, half))
                val startAngle = atan2(finger.y - pivot.y, finger.x - pivot.x)
                val startRotation = cur.rotation
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        finger += c.positionChange()
                        c.consume()
                        val angle = atan2(finger.y - pivot.y, finger.x - pivot.x)
                        var deg = startRotation + Math.toDegrees((angle - startAngle).toDouble()).toFloat()
                        deg = ((deg % 360f) + 540f) % 360f - 180f
                        // Snap to straight and right angles.
                        val nearest = (deg / 90f).roundToInt() * 90f
                        if (abs(deg - nearest) < 5f) deg = nearest
                        change(cur.copy(rotation = deg))
                    }
                } finally {
                    end()
                }
            }
        },
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(Color.White, CircleShape)
                .border(2.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Rotate", tint = accent, modifier = Modifier.size(17.dp))
        }
    }
}

/** Applies a move / pinch / twist to an element. Only pictures twist. */
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
    val minWidth = if (kind == ElementKind.TEXT) 0.2f else 0.08f
    val newWidth = if (scalable) (cur.width * zoom).coerceIn(minWidth, 1f) else cur.width
    // Touches arrive in the item's own (turned) directions; move it in the page's.
    val pagePan = if (cur.rotation != 0f) rotateBy(pan, cur.rotation) else pan
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
        x = (cur.x + dx + pagePan.x / widthPx).coerceIn(-newWidth * 0.8f, 1f - newWidth * 0.2f),
        y = (cur.y + dy + pagePan.y / widthPx).coerceAtLeast(0f),
        width = newWidth,
        height = newHeight,
        rotation = when (kind) {
            ElementKind.IMAGE -> (cur.rotation + rotation) % 360f
            // Text boxes turn with their rotate handle, not with two fingers.
            ElementKind.TEXT -> cur.rotation
            else -> 0f
        },
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
    minHeight: Dp,
    ink: Color,
    styles: EditorStyles,
    /** Show the bounding box (selected, or picked for a connector). */
    framed: Boolean,
    /** Typing in it: the text takes taps and shows a cursor. */
    editing: Boolean,
    canvas: PageCanvasState,
    actions: ElementActions,
    onTextHeight: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val latestActions by rememberUpdatedState(actions)

    // Save typing shortly after it stops.
    LaunchedEffect(state) {
        snapshotFlow { state.version }.drop(1).debounce(500).collect { latestActions.onTextChanged(element) }
    }
    // Leaving the page (or swiping away) saves anything not yet written.
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose { latestActions.onTextChanged(element) }
    }
    // A brand-new box is selected and ready for typing.
    LaunchedEffect(canvas.pendingFocusTextBox) {
        if (canvas.pendingFocusTextBox == element.id) {
            canvas.pendingFocusTextBox = null
            canvas.selectedId = element.id
            canvas.editingTextId = element.id
        }
    }
    // Starting to type puts the cursor at the end of the text.
    LaunchedEffect(editing) {
        if (editing && !focused) state.focusEnd()
    }

    Box(
        Modifier
            .width(width)
            .heightIn(min = minHeight)
            .then(
                when {
                    framed -> Modifier.border(1.5.dp, accent)
                    editing -> Modifier.border(1.dp, accent.copy(alpha = 0.35f))
                    else -> Modifier
                },
            )
            .onFocusChanged {
                if (it.hasFocus != focused) {
                    focused = it.hasFocus
                    latestActions.onTextFocus(element, state, it.hasFocus)
                    // Typing ends when the cursor leaves the box.
                    if (!it.hasFocus && canvas.editingTextId == element.id) canvas.editingTextId = null
                }
            },
    ) {
        RichTextEditor(
            state = state,
            styles = styles,
            contentColor = ink,
            placeholder = "Type here",
            enabled = editing,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onTextHeight(it.height) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
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
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            // Finger movement (the handle itself moves as the item grows).
                            val delta = c.positionChange()
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
    val y = when {
        // A text box's rotate handle sits above it, so its toolbar goes underneath.
        element.kind == ElementKind.TEXT -> rect.bottom + with(density) { 22.dp.toPx() }
        rect.top > barHeightPx -> rect.top - barHeightPx
        else -> rect.bottom + with(density) { 8.dp.toPx() }
    }
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
                                trailingIcon = { Icon(Icons.Rounded.Check, null) },
                                onClick = {
                                    layoutMenu = false
                                    actions.onPictureLayout(element, image, PictureLayout.FLOAT)
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
                ElementKind.TEXT -> {
                    IconButton(onClick = { canvas.editingTextId = element.id }) { Icon(Icons.Rounded.Edit, contentDescription = "Type") }
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
