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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import com.quartz.notes.data.model.BackgroundFit
import com.quartz.notes.data.model.LineRoute
import com.quartz.notes.data.model.PageSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.note.noteAsText
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
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
import com.quartz.notes.data.model.isAttachedElement
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
import com.quartz.notes.ui.editor.fingerTaps
import com.quartz.notes.ui.editor.editorStyles
import com.quartz.notes.ui.editor.inlineImageSlotHeight
import com.quartz.notes.ui.media.DecorateSheet
import com.quartz.notes.ui.media.model
import com.quartz.notes.ui.media.DecoratedImage
import com.quartz.notes.ui.media.ImageViewer
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.FlowPreview
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

/** One notebook page: title, text (or scrapbook items), and everything placed on it. */
@OptIn(FlowPreview::class)
@Composable
internal fun PageView(
    page: PageEntity,
    session: PageSession,
    vm: NotebookViewModel,
    side: PageSide,
    /** The screen is wide enough for a scrapbook page to spread across both sides. */
    canSpread: Boolean,
    tool: PageTool,
    /** The brush: a [com.quartz.notes.data.model.BrushType] and its size in page units. */
    brushType: String,
    brushSize: Float,
    /** The highlighter's color. */
    highlightArgb: Int,
    inkArgb: Int,
    /** Fingers draw with the brush (off: only a pen draws, fingers move the page). */
    fingerDraw: Boolean,
    /** A pen touched the page for the first time while fingers could draw. */
    onPenFound: () -> Unit,
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
    val audio = LocalAppContainer.current.audio
    val mediaFiles = LocalAppContainer.current.media
    val backgroundModel = page.backgroundImage?.let { mediaFiles.model(it) }
    var backgroundAspect by remember(page.backgroundImage) { mutableStateOf(0f) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val elementsFlow = remember(page.id) { vm.elements(page.id) }
    val allElements by elementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    // Pictures and recordings in the bottom strip aren't placed on the page itself.
    val elements = remember(allElements) { allElements.filterNot { isAttachedElement(it.kind, it.data) } }
    val attachedItems = remember(allElements) { allElements.filter { isAttachedElement(it.kind, it.data) } }
    val canvas = session.canvas
    SideEffect { session.elements = elements }
    val scrapbook = page.layoutMode == LayoutMode.SCRAPBOOK.name
    // Freeform pages have a fixed size: laid out at it, then scaled to fit the screen (and zoomed).
    val freeform = scrapbook
    SideEffect { session.freeform = freeform }
    val canvasSize = if (freeform) {
        DpSize((page.canvasWidth ?: PageSize.DEFAULT.width).dp, (page.canvasHeight ?: PageSize.DEFAULT.height).dp)
    } else {
        null
    }

    var viewing by remember { mutableStateOf<String?>(null) }
    var decorating by remember { mutableStateOf<Pair<PageElementEntity, ImageData>?>(null) }
    var decoratingInline by remember { mutableStateOf<String?>(null) }
    var shapeStyling by remember { mutableStateOf<Long?>(null) }
    var lineStyling by remember { mutableStateOf<Long?>(null) }

    /** Removes text boxes nobody typed in. */
    fun sweepBlankTextBoxes() {
        elements.filter { it.kind == ElementKind.TEXT }.forEach { e ->
            val state = session.textBoxes[e.id] ?: return@forEach
            if (state.isBlank() && session.activeEditor !== state && canvas.pendingFocusTextBox != e.id &&
                canvas.editingTextId != e.id
            ) {
                session.textBoxes.remove(e.id)
                vm.deleteElement(e, undoable = false)
            }
        }
    }

    DisposableEffect(session) {
        onEditorReady(editor)
        onDispose {
            onSave()
            onEditorGone()
            sweepBlankTextBoxes()
            session.activeEditor = null
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { editor.version to session.title }.drop(1).debounce(600).collect { onSave() }
    }
    // Typing in the page text puts away item toolbars and makes the page text the formatting target.
    LaunchedEffect(editor) {
        snapshotFlow { editor.focused }.drop(1).collect { focused ->
            if (focused) {
                canvas.clearSelection()
                session.activeEditor = null
            }
        }
    }
    // A finished brush stroke stays on screen until the database has it.
    val strokeCount = elements.count { it.kind == ElementKind.STROKE }
    LaunchedEffect(strokeCount) {
        if (!canvas.drawing) canvas.liveStroke.clear()
    }
    LaunchedEffect(elements) {
        // Text no longer wraps beside pictures: pictures saved as wrapped just float.
        elements.forEach { e ->
            if (e.kind == ElementKind.IMAGE) {
                val d = decodeImageData(e.data)
                if (d != null && d.wraps) vm.updateElementData(e, d.copy(placement = ImagePlacement.FLOAT).encode(), undoable = false)
            }
        }
        // Pasted or duplicated items are selected once they're on the page.
        canvas.pendingSelect?.let { ids ->
            if (ids.all { id -> elements.any { it.id == id } }) {
                canvas.pendingSelect = null
                canvas.select(ids, elements)
            }
        }
        // A group move is saved: the items are where they were dragged to.
        if (canvas.groupSettling) {
            canvas.groupSettling = false
            canvas.groupDrag = Offset.Zero
        }
        // A dragged arrow's live copy is dropped once its saved row has changed.
        canvas.arrowDrag?.let { drag -> if (elements.none { it == drag.first }) canvas.arrowDrag = null }
        val ids = elements.map { it.id }.toSet()
        canvas.erased.keys.retainAll(ids)
        canvas.bounds.keys.retainAll(ids)
        if (canvas.groupIds.any { it !in ids }) canvas.groupIds = canvas.groupIds.intersect(ids)
    }
    // A text box (or shape) added from the toolbar gets the cursor.
    LaunchedEffect(focusTextBox) {
        if (focusTextBox != null) {
            canvas.pendingFocusTextBox = focusTextBox
            onFocusTextBoxHandled()
        }
    }
    // A new shape is ready to type in once it's on the page.
    LaunchedEffect(elements, canvas.pendingFocusTextBox) {
        val id = canvas.pendingFocusTextBox ?: return@LaunchedEffect
        if (elements.any { it.id == id && it.kind == ElementKind.SHAPE }) {
            canvas.pendingFocusTextBox = null
            canvas.selectedId = id
            canvas.editingShapeId = id
        }
    }
    // Locking freezes the page: anything picked up or being typed in is put away.
    val locked = page.locked
    LaunchedEffect(locked) {
        if (locked) {
            canvas.clearSelection()
            focusManager.clearFocus()
            session.activeEditor = null
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

    val pageNow by rememberUpdatedState(page)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            // Tap with two fingers to undo, three to redo.
            .fingerTaps(onTwo = { vm.undo(pageNow) }, onThree = { vm.redo(pageNow) })
            .onFocusChanged { if (it.hasFocus) onActivate() }
            .then(
                if (freeform) {
                    // The paper is drawn on the page itself, which is scaled; around it is the desk.
                    Modifier.clipToBounds()
                } else {
                    Modifier
                        .shadow(2.dp, shape)
                        .clip(shape)
                        .background(bg)
                        .drawBehind {
                            texture?.let { drawRect(it) }
                            when (side) {
                                PageSide.LEFT, PageSide.RIGHT -> drawSeam(atRightEdge = side == PageSide.LEFT, strength = seamShade)
                                PageSide.SPREAD -> drawCenterSeam(seamShade)
                                PageSide.SINGLE -> Unit
                            }
                        }
                },
            ),
    ) {
        // The space a page gets on this screen: offered as a Freeform page size ("Cover screen" …).
        if (side == PageSide.SINGLE || side == PageSide.SPREAD) {
            val w = maxWidth.value
            val h = maxHeight.value
            val settingsRepo = LocalAppContainer.current.settings
            LaunchedEffect(w, h) { settingsRepo.rememberScreen(w, h) }
        }
        val viewportPx = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val viewport = canvasSize?.height ?: maxHeight
        val pageWidth = canvasSize?.width ?: maxWidth
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
            // Save the text (now holding the picture) before removing the floating copy; the queue keeps the order.
            vm.saveIfChanged(page.id)
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
            vm.saveIfChanged(page.id)
            canvas.clearSelection()
        }

        val actions = remember(page.id, widthPx, textWidthPx) {
            ElementActions(
                onSelect = { id ->
                    canvas.groupIds = emptySet()
                    canvas.selectedId = id
                    canvas.editingShapeId = null
                    canvas.selectedInlineBlock = null
                    if (id != null) focusManager.clearFocus()
                },
                onMove = { e -> vm.moveElement(e) },
                onView = { viewing = it.path },
                onDecorate = { e, d -> decorating = e to d },
                onPictureLayout = { e, d, layout ->
                    when (layout) {
                        PictureLayout.FLOAT -> if (d.wraps) vm.updateElementData(e, d.copy(placement = ImagePlacement.FLOAT).encode())
                        PictureLayout.INLINE -> toInline(e, d)
                    }
                },
                onFront = vm::bringToFront,
                onBack = vm::sendToBack,
                onCopy = { e -> vm.copyItems(page.id, session.elements, setOf(e.id)) },
                onDuplicate = { e ->
                    scope.launch { canvas.pendingSelect = vm.duplicateItems(page.id, session.elements, setOf(e.id)) }
                },
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
                onTextFocus = { e, state, focused ->
                    if (focused) {
                        session.activeEditor = state
                        // A box being typed in keeps its handles; anything else selected is put away.
                        if (canvas.selectedId != e.id) {
                            canvas.selectedId = null
                            canvas.toolbarRect = null
                        }
                        canvas.editingShapeId = null
                        canvas.selectedInlineBlock = null
                        canvas.editingTextId = e.id
                    } else if (session.activeEditor === state) {
                        // The formatting tools go back to the page (an open Format sheet keeps its own target).
                        session.activeEditor = null
                    }
                },
                onArrowEdit = { moved, d -> vm.replaceElement(moved.copy(data = d.encodePayload())) },
            )
        }

        // Where "Paste" is offered after holding on empty paper.
        var pasteAt by remember { mutableStateOf<Offset?>(null) }

        fun backgroundTap(p: Offset) {
            pasteAt = null
            // Tapping empty space finishes typing in a text box.
            val typingInBox = canvas.editingTextId != null || session.activeEditor != null
            if (canvas.selectedId != null || canvas.editingShapeId != null || canvas.selectedInlineBlock != null || typingInBox) {
                canvas.clearSelection()
                if (typingInBox) {
                    focusManager.clearFocus()
                    session.activeEditor = null
                }
                sweepBlankTextBoxes()
                return
            }
            sweepBlankTextBoxes()
            if (!scrapbook && !page.locked && p.y > titleBottomPx) {
                val root = canvas.contentCoords
                val field = editor.fieldCoords
                val fieldRect = if (root != null && field != null && root.isAttached && field.isAttached) {
                    root.localBoundingBoxOf(field, clipBounds = false)
                } else {
                    null
                }
                when {
                    // On the text itself: the text field already put the cursor there (or selected).
                    fieldRect != null && fieldRect.contains(p) -> Unit
                    // Beside a line (in the page margin): that line.
                    root != null && fieldRect != null && p.y <= fieldRect.bottom -> editor.placeCursorAt(p, root)
                    // Below the text: the end.
                    else -> editor.focusEnd()
                }
                keyboard?.show()
            }
        }

        // ---- Pictures in line with the text ----

        val hooks = EditorLayoutHooks(
            root = { canvas.contentCoords },
            onBlockBounds = { id, top, bottom ->
                val v = top to bottom
                if (canvas.blockBounds[id] != v) canvas.blockBounds[id] = v
            },
            imageBlock = { block, textWidth ->
                InlineImageSlot(
                    block = block,
                    textWidth = textWidth,
                    styles = styles,
                    selected = canvas.selectedInlineBlock == block.id,
                    onTap = { block.image?.let { viewing = it.path } },
                    onLongPress = {
                        if (page.locked) return@InlineImageSlot
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

        // ---- Sharing ----
        val capture = remember { PageCapture() }
        val exporting = capture.exporting
        val context = LocalContext.current
        val latestPage by rememberUpdatedState(page)
        DisposableEffect(session) {
            val exporter = PageExporter(
                text = { pageAsText(session, latestPage, latestElements) },
                image = {
                    capture.toPng(context, session.title) {
                        // Put away handles, toolbars and the cursor.
                        canvas.clearSelection()
                        focusManager.clearFocus()
                        session.activeEditor = null
                    }
                },
            )
            session.exporter = exporter
            onDispose { if (session.exporter === exporter) session.exporter = null }
        }
        val latestTool by rememberUpdatedState(tool)
        val latestBrushType by rememberUpdatedState(brushType)
        val latestBrushSize by rememberUpdatedState(brushSize)
        val latestHighlight by rememberUpdatedState(highlightArgb)
        val latestInk by rememberUpdatedState(inkArgbResolved)
        val inkColor by rememberUpdatedState(ink)
        val tapHandler by rememberUpdatedState(::backgroundTap)
        val latestToolDone by rememberUpdatedState(onToolDone)
        val latestFingerDraw by rememberUpdatedState(fingerDraw)
        val latestPenFound by rememberUpdatedState(onPenFound)
        val latestFreeform by rememberUpdatedState(freeform)
        val accentActions by rememberUpdatedState(actions)
        val latestLocked by rememberUpdatedState(locked)

        val canvasPx = canvasSize?.let { with(density) { Size(it.width.toPx(), it.height.toPx()) } }
        val fit = canvasPx?.let { minOf(viewportPx.width / it.width, viewportPx.height / it.height) } ?: 1f
        canvas.pageSizePx = canvasPx
        if (freeform && canvasPx != null) {
            // Typing in a text box or shape on a page shown small: zoom in so the words are readable,
            // with the box near the top (clear of the keyboard).
            val latestFit by rememberUpdatedState(fit)
            val latestViewport by rememberUpdatedState(viewportPx)
            LaunchedEffect(canvas.editingTextId, canvas.editingShapeId) {
                val id = canvas.editingTextId ?: canvas.editingShapeId ?: return@LaunchedEffect
                delay(350) // the keyboard comes up and the page re-fits
                val r = canvas.bounds[id] ?: return@LaunchedEffect
                val f = latestFit
                val vp = latestViewport
                if (f * session.zoom >= 0.9f) return@LaunchedEffect
                val target = minOf(1f, vp.width * 0.92f / r.width.coerceAtLeast(1f)).coerceAtLeast(f * session.zoom)
                val zoom = (target / f).coerceIn(1f, 8f)
                val sNew = f * zoom
                val pan = Offset(
                    -(r.center.x - canvasPx.width / 2) * sNew,
                    vp.height * 0.15f - vp.height / 2 - (r.top - canvasPx.height / 2) * sNew,
                )
                val goal = clampPan(pan, sNew, canvasPx, vp)
                val fromZoom = session.zoom
                val fromPan = session.pan
                androidx.compose.animation.core.animate(0f, 1f, animationSpec = androidx.compose.animation.core.tween(280)) { t, _ ->
                    session.zoom = fromZoom + (zoom - fromZoom) * t
                    session.pan = fromPan + (goal - fromPan) * t
                }
            }
            // Where new items land: the middle of what's in view.
            val s = fit * session.zoom
            session.scrollUnits = ((canvasPx.height / 2 - session.pan.y / s - viewportPx.height / (2 * s)) / widthPx).coerceAtLeast(0f)
        }
        val pictureMode = BackgroundFit.mode(page.backgroundFit, freeform)
        // Inside a Freeform page, text ignores the phone's font size setting so every device lays it out alike.
        val pageDensity = if (freeform) Density(density.density, 1f) else density

        // The Freeform page sits in the middle of the space; pinching a selected item resizes the item.
        var zoomCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        val itemReachPx = with(density) { 24.dp.toPx() }
        Box(
            if (freeform && canvasPx != null) {
                Modifier
                    .fillMaxSize()
                    .onPlaced { zoomCoords = it }
                    .freeformZoom(session, fit, canvasPx, viewportPx) { p ->
                        val id = canvas.selectedId ?: return@freeformZoom false
                        val rect = canvas.bounds[id] ?: return@freeformZoom false
                        val from = zoomCoords ?: return@freeformZoom false
                        val content = canvas.contentCoords?.takeIf { it.isAttached } ?: return@freeformZoom false
                        rect.inflate(itemReachPx).contains(content.localPositionOf(from, p))
                    }
            } else {
                Modifier.fillMaxSize()
            },
            contentAlignment = Alignment.Center,
        ) {
        CompositionLocalProvider(LocalDensity provides pageDensity) {
        Column(
            if (freeform && canvasSize != null) {
                Modifier
                    .requiredSize(canvasSize)
                    .freeformTransform(session, fit)
                    .shadow(3.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .drawBehind { texture?.let { drawRect(it) } }
            } else {
                Modifier.fillMaxSize().verticalScroll(scroll)
            },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (freeform) {
                            Modifier.fillMaxHeight()
                        } else {
                            Modifier.heightIn(
                                min = maxOf(elementsBottom + 48.dp, viewport, if (pictureMode == BackgroundFit.WIDTH) pageWidth * backgroundAspect else 0.dp),
                            )
                        },
                    )
                    .onPlaced { canvas.contentCoords = it }
                    // "Share as image" records the whole page, on its paper color and texture.
                    .then(capture.modifier(background = { bg }, texture = { texture }))
                    .pointerInput(widthPx) {
                        // Lines (arrows/connectors) and taps on empty space. Tools use the overlay below instead.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (latestTool != PageTool.NONE) return@awaitEachGesture
                            val pageLocked = latestLocked
                            val p = down.position
                            val slop = viewConfiguration.touchSlop
                            // A selected text box's handles reach outside it (the rotate handle sits above).
                            val handles = canvas.selectedId?.takeIf { id -> latestElements.any { it.id == id && it.kind == ElementKind.TEXT } }
                                ?.let { canvas.bounds[it] }?.inflate(60.dp.toPx())?.contains(p) == true
                            val onItem = handles || canvas.bounds.values.any { it.inflate(4.dp.toPx()).contains(p) } ||
                                canvas.toolbarRect?.contains(p) == true
                            // The selected arrow's end handles get their own touches.
                            val nearHandle = canvas.selectedId?.let { id ->
                                val e = latestElements.firstOrNull { it.id == id && it.kind == ElementKind.ARROW } ?: return@let false
                                val d = decodePayload<ArrowData>(e.data) ?: return@let false
                                val (a, b) = arrowEnds(e, d, widthPx)
                                (p - a).getDistance() < 22.dp.toPx() || (p - b).getDistance() < 22.dp.toPx()
                            } ?: false
                            val line = if (onItem || nearHandle || pageLocked) null else lineHit(p, latestElements, canvas.bounds, widthPx, 18.dp.toPx())
                            if (line != null) {
                                // Touching a line: a tap selects it; dragging a selected arrow moves it.
                                // Anything else (a scroll or swipe) is left alone.
                                val arrowData = if (line.kind == ElementKind.ARROW && canvas.selectedId == line.id) decodePayload<ArrowData>(line.data) else null
                                var dragging = false
                                var last = p
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) {
                                        if (dragging) {
                                            canvas.arrowDrag?.takeIf { it.first == line }?.let { (_, el, d) -> accentActions.onArrowEdit(el, d) }
                                        } else if (!c.isConsumed) {
                                            c.consume()
                                            val connector = if (line.kind == ElementKind.CONNECTOR) decodePayload<ConnectorData>(line.data) else null
                                            if (connector != null && canvas.selectedId == line.id && !pageLocked) {
                                                // Tapping a selected connector switches between square corners and a flowing curve.
                                                val next = if (connector.route == LineRoute.CURVE) LineRoute.ELBOW else LineRoute.CURVE
                                                vm.updateElementData(line, connector.copy(route = next).encodePayload())
                                            } else {
                                                accentActions.onSelect(line.id)
                                            }
                                        }
                                        break
                                    }
                                    if (!dragging && (c.position - p).getDistance() > slop) {
                                        if (arrowData == null) break // a scroll or swipe: not ours
                                        dragging = true
                                        last = c.position
                                    }
                                    if (dragging && arrowData != null) {
                                        val delta = c.position - last
                                        last = c.position
                                        val cur = canvas.arrowDrag?.takeIf { it.first == line } ?: Triple(line, line, arrowData)
                                        canvas.arrowDrag = Triple(
                                            line,
                                            cur.second.copy(x = cur.second.x + delta.x / widthPx, y = (cur.second.y + delta.y / widthPx).coerceAtLeast(0f)),
                                            cur.third,
                                        )
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } else {
                                // Not ours: let the items and text have it, then see if anyone used the tap.
                                // Holding still on empty paper (Freeform pages) offers Paste.
                                pasteAt = null
                                var upAt: Offset? = null
                                val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (c.isConsumed) break
                                        if (!c.pressed) {
                                            upAt = c.position
                                            break
                                        }
                                        if ((c.position - p).getDistance() > slop) break
                                    }
                                    true
                                }
                                if (ended == null && latestFreeform && !onItem && !pageLocked && ItemClipboard.items.isNotEmpty()) {
                                    pasteAt = p
                                    // The finger lifting is part of the hold, not a tap.
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Final)
                                        val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                        c.consume()
                                        if (!c.pressed) break
                                    }
                                }
                                upAt?.let { tapHandler(it) }
                            }
                        }
                    },
            ) {
                // A picture as the paper.
                if (page.backgroundImage != null) {
                    PaperPicture(
                        model = backgroundModel,
                        mode = pictureMode,
                        manual = BackgroundFit.manual(page.backgroundFit),
                        rotation = BackgroundFit.rotation(page.backgroundFit),
                        pageWidthPx = widthPx,
                        onAspect = { backgroundAspect = it },
                        modifier = Modifier.matchParentSize(),
                    )
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = padH, end = padH - 10.dp, top = 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = title,
                            onValueChange = { session.title = it.replace("\n", "") },
                            readOnly = locked,
                            textStyle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.weight(1f).padding(top = 10.dp, bottom = 10.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (title.isEmpty() && !exporting) {
                                        Text("Page title", style = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ink.copy(alpha = 0.32f)))
                                    }
                                    inner()
                                }
                            },
                        )
                        Row(Modifier.alpha(if (exporting) 0f else 1f), verticalAlignment = Alignment.CenterVertically) {
                        if (scrapbook && canSpread) {
                            PageToggle(
                                on = page.spread,
                                ink = ink,
                                icon = Icons.Rounded.AutoStories,
                                description = if (page.spread) "Use one page" else "Use both pages",
                                onClick = {
                                    // Keeps this page in view as the spreads rearrange.
                                    onActivate()
                                    vm.setPageSpread(page, !page.spread)
                                },
                            )
                        }
                        PageToggle(
                            on = locked,
                            ink = ink,
                            icon = if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            description = if (locked) "Unlock page" else "Lock page",
                            onClick = { vm.setPageLocked(page, !locked) },
                        )
                        }
                    }
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
                            .heightIn(
                                min = if (scrapbook && attachedItems.isNotEmpty()) {
                                    // Keep the strip below everything placed on the page.
                                    maxOf((viewport - 80.dp).coerceAtLeast(200.dp), elementsBottom - 40.dp)
                                } else {
                                    (viewport - 80.dp).coerceAtLeast(200.dp)
                                },
                            )
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
                            Column(Modifier.fillMaxWidth()) {
                                RichTextEditor(
                                    state = editor,
                                    styles = styles,
                                    contentColor = ink,
                                    layout = hooks,
                                    readOnly = locked,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = padH, end = padH, top = 10.dp, bottom = if (attachedItems.isEmpty()) 40.dp else 12.dp)
                                        .onFocusChanged { if (it.hasFocus) session.activeEditor = null },
                                )
                                // Attachments sit right under the text.
                                PageAttachments(
                                    items = attachedItems,
                                    ink = ink,
                                    locked = locked,
                                    onView = { viewing = it },
                                    onPlace = vm::placeAttachment,
                                    onRemove = actions.onDelete,
                                    modifier = Modifier.attachmentsPadding(padH),
                                )
                            }
                        } else if (elements.none { it.kind == ElementKind.TEXT } && !exporting) {
                            Text(
                                "Freeform page: add text with the T button below",
                                color = ink.copy(alpha = 0.35f),
                                fontSize = 15.sp,
                                modifier = Modifier.padding(start = padH, top = 16.dp),
                            )
                        }
                    }
                    if (scrapbook) {
                        PageAttachments(
                            items = attachedItems,
                            ink = ink,
                            locked = locked,
                            onView = { viewing = it },
                            onPlace = vm::placeAttachment,
                            onRemove = actions.onDelete,
                            modifier = Modifier.attachmentsPadding(padH),
                        )
                    }
                }
                PageElementLayer(
                    elements = elements,
                    pageWidth = pageWidth,
                    ink = ink,
                    tool = tool,
                    locked = locked,
                    allowTextLayout = !scrapbook,
                    canvas = canvas,
                    styles = styles,
                    textEditor = vm::textEditor,
                    actions = actions,
                )
                // Several items picked with the lasso: a box to drag them by, and their toolbar.
                if (canvas.groupIds.isNotEmpty() && tool == PageTool.NONE) {
                    val group = canvas.groupIds
                    val groupItems = elements.filter { it.id in group }
                    val drag = canvas.groupDrag
                    val restBounds = canvas.bounds.mapValues { (id, r) -> if (id in group) r.translate(-drag) else r }
                    val box = groupBoundsPx(groupItems, restBounds, widthPx) { strokeWidthPx(it, widthPx, with(density) { 1.dp.toPx() }) }
                    if (box != null) {
                        GroupBox(
                            box = box.inflate(with(density) { 8.dp.toPx() }).translate(drag),
                            pageWidthPx = widthPx,
                            canvas = canvas,
                            locked = locked,
                            onMoved = { moved ->
                                canvas.groupSettling = true
                                vm.moveItems(page.id, groupItems, moved.x / widthPx, moved.y / widthPx)
                            },
                            onCopy = { vm.copyItems(page.id, elements, group) },
                            onDuplicate = { scope.launch { canvas.pendingSelect = vm.duplicateItems(page.id, elements, group) } },
                            onFront = { vm.itemsToFront(page.id, groupItems) },
                            onBack = { vm.itemsToBack(page.id, groupItems) },
                            onDelete = {
                                canvas.clearSelection()
                                vm.deleteItems(page.id, groupItems)
                            },
                            onDone = { canvas.clearSelection() },
                        )
                    }
                }
                // Hold on empty paper (Freeform pages): paste what was copied, right there.
                pasteAt?.let { at ->
                    PastePill(at) {
                        pasteAt = null
                        scope.launch { canvas.pendingSelect = vm.pasteItems(page.id, Offset(at.x / widthPx, at.y / widthPx)) }
                    }
                }
                if (tool != PageTool.NONE) {
                    // While a tool is on, this layer takes every touch so text and items underneath stay put.
                    Box(
                        Modifier
                            .matchParentSize()
                            .pointerInput(widthPx, tool) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                                    // The first time a pen touches the page, fingers stop drawing (palm rejection);
                                    // the brush bar's finger switch turns finger drawing back on.
                                    if (pen && !vm.penSeen) {
                                        vm.penSeen = true
                                        if (latestFingerDraw) latestPenFound()
                                    }
                                    val penOnly = !latestFingerDraw
                                    val drawingTool = latestTool == PageTool.BRUSH || latestTool == PageTool.ERASER
                                    if (drawingTool && !pen && penOnly) return@awaitEachGesture
                                    val erasing = latestTool == PageTool.ERASER || (latestTool == PageTool.BRUSH && down.type == PointerType.Eraser)
                                    when {
                                erasing -> {
                                    down.consume()
                                    val radius = brushWidthPx(5, widthPx) / 2 + 10.dp.toPx()
                                    fun eraseAt(p: Offset) {
                                        latestElements.filter { it.kind == ElementKind.STROKE && !canvas.erased.containsKey(it.id) }.forEach { e ->
                                            val d = decodePayload<StrokeData>(e.data) ?: return@forEach
                                            if (distanceToPolyline(p, strokePoints(d, widthPx)) <= radius + strokeWidthPx(d, widthPx, 1.dp.toPx()) / 2) {
                                                canvas.erased[e.id] = true
                                                vm.deleteElement(e, key = "erase")
                                            }
                                        }
                                    }
                                    eraseAt(down.position)
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.forEach { c ->
                                            if (c.pressed && (c.id == down.id || !pen)) eraseAt(c.position)
                                            c.consume()
                                        }
                                    } while (event.changes.any { it.id == down.id && it.pressed })
                                }
                                latestTool == PageTool.BRUSH -> {
                                    down.consume()
                                    canvas.drawing = true
                                    val type = latestBrushType
                                    val highlighter = type == com.quartz.notes.data.model.BrushType.HIGHLIGHTER
                                    val strokeColor = if (highlighter) latestHighlight else latestInk
                                    canvas.liveColor = inkOr(strokeColor, inkColor)
                                    canvas.liveWidth = latestBrushSize * 1.dp.toPx()
                                    canvas.liveTool = type
                                    canvas.liveStroke.clear()
                                    canvas.liveStroke.add(down.position)
                                    var twoFinger = false
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.count { it.pressed }
                                        if (!pen && pressed > 1) {
                                            // Two fingers scroll or zoom the page instead of drawing.
                                            twoFinger = true
                                            canvas.liveStroke.clear()
                                            if (!freeform) scroll.dispatchRawDelta(-event.calculatePan().y)
                                        } else if (!twoFinger) {
                                            // A pen draws on, whatever a resting palm does.
                                            event.changes.firstOrNull { it.id == down.id && it.pressed }?.let { canvas.liveStroke.add(it.position) }
                                        }
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.id == down.id && it.pressed })
                                    canvas.drawing = false
                                    if (!twoFinger && canvas.liveStroke.isNotEmpty()) {
                                        val pts = canvas.liveStroke.flatMap { listOf(it.x / widthPx, it.y / widthPx) }
                                        vm.addStroke(page.id, if (pts.size == 2) pts + pts else pts, strokeColor, type, latestBrushSize)
                                    } else {
                                        canvas.liveStroke.clear()
                                    }
                                }
                                latestTool == PageTool.LASSO -> {
                                    // Draw a loop around items to pick them all.
                                    down.consume()
                                    canvas.lasso.clear()
                                    canvas.lasso.add(down.position)
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } > 1) {
                                            canvas.lasso.clear()
                                            return@awaitEachGesture
                                        }
                                        event.changes.firstOrNull { it.id == down.id && it.pressed }?.let { canvas.lasso.add(it.position) }
                                        event.changes.forEach { it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    val picked = itemsInLoop(canvas.lasso.toList(), latestElements, canvas.bounds, widthPx)
                                    canvas.lasso.clear()
                                    if (picked.isNotEmpty()) {
                                        latestToolDone()
                                        canvas.select(picked, latestElements)
                                    }
                                }
                                latestTool == PageTool.CONNECT -> {
                                    down.consume()
                                    val up = waitForUpOrCancellation()
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
                                        else -> Unit
                                    }
                                }
                            },
                    )
                }
            }
        }
        }
            if (freeform && session.zoomedIn) {
                FloatingPill(
                    "Fit page",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                ) {
                    session.zoom = 1f
                    session.pan = Offset.Zero
                }
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

/** A page's words: title, flowing text, then text boxes and shape labels from top to bottom. */
private fun pageAsText(session: PageSession, page: PageEntity, elements: List<PageElementEntity>): String {
    val parts = ArrayList<String>()
    if (page.layoutMode != LayoutMode.SCRAPBOOK.name) parts += noteAsText("", session.editor.toDoc())
    elements
        .filter { it.kind == ElementKind.TEXT || it.kind == ElementKind.SHAPE }
        .sortedWith(compareBy({ it.y }, { it.x }))
        .forEach { e ->
            parts += if (e.kind == ElementKind.TEXT) {
                val doc = session.textBoxes[e.id]?.toDoc() ?: RichDoc.decode(decodePayload<TextBoxData>(e.data)?.doc)
                noteAsText("", doc)
            } else {
                decodePayload<ShapeData>(e.data)?.text.orEmpty()
            }
        }
    val body = parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    val title = session.title.trim()
    return listOf(title, body).filter { it.isNotEmpty() }.joinToString("\n\n")
}

/** Small icon toggle on the page's title line (lock, use both pages). */
@Composable
private fun PageToggle(
    on: Boolean,
    ink: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            Modifier
                .size(34.dp)
                .background(if (on) accent.copy(alpha = 0.14f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = if (on) accent else ink.copy(alpha = 0.42f), modifier = Modifier.size(21.dp))
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
    val latestTap by rememberUpdatedState(onTap)
    val latestLongPress by rememberUpdatedState(onLongPress)
    Box(Modifier.fillMaxWidth().heightIn(min = inlineImageSlotHeight(block, textWidth, styles)), contentAlignment = alignment) {
        Box(
            Modifier
                .pointerInput(block.id) { detectTapGestures(onTap = { latestTap() }, onLongPress = { latestLongPress() }) }
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
                // Compact buttons so the bar fits a phone's text width (tap the picture itself to view it).
                val b = Modifier.size(40.dp)
                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDecorate, modifier = b) { Icon(Icons.Rounded.AutoAwesome, contentDescription = "Decorate") }
                    IconButton(onClick = onSize, modifier = b) { Icon(Icons.Rounded.PhotoSizeSelectLarge, contentDescription = "Size") }
                    IconButton(onClick = onAlign, modifier = b) { Icon(Icons.AutoMirrored.Rounded.FormatAlignLeft, contentDescription = "Alignment") }
                    IconButton(onClick = onFloat, modifier = b) { Icon(Icons.Rounded.PictureInPicture, contentDescription = "Float freely") }
                    IconButton(onClick = onDone, modifier = b) { Icon(Icons.Rounded.Check, contentDescription = "Done", tint = accent) }
                    IconButton(onClick = onDelete, modifier = b) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** A small floating button over the page ("Fit page", "Done" …). */
@Composable
private fun FloatingPill(label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}
