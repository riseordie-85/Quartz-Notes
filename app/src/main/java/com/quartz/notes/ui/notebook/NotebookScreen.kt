package com.quartz.notes.ui.notebook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Texture
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.encode
import com.quartz.notes.ui.media.DecorateSheet
import com.quartz.notes.ui.media.ImageViewer
import com.quartz.notes.ui.media.VoiceRecorderSheet
import com.quartz.notes.ui.media.rememberImageAdder
import com.quartz.notes.ui.media.rememberMicPermission
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.model.PageType
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.QuartzPill
import com.quartz.notes.ui.components.RoundActionButton
import com.quartz.notes.ui.components.pageBackground
import com.quartz.notes.ui.components.rememberTextureBrush
import com.quartz.notes.ui.editor.EditorToolbar
import com.quartz.notes.ui.editor.FormatSheet
import com.quartz.notes.ui.editor.RichEditorState
import com.quartz.notes.ui.editor.RichTextEditor
import com.quartz.notes.ui.editor.ToolButton
import com.quartz.notes.ui.editor.editorStyles
import com.quartz.notes.ui.home.TwoPaneMinWidth
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.contentColorFor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NotebookScreen(
    notebookId: Long,
    initialPageId: Long?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val vm = quartzViewModel(key = "notebook-$notebookId") { NotebookViewModel(notebookId, it.repository, it.appScope) }
    val notebook by vm.notebook.collectAsStateWithLifecycle()
    val pagesOrNull by vm.pages.collectAsStateWithLifecycle()
    val shelves by vm.shelves.collectAsStateWithLifecycle()

    val nb = notebook
    val pages = pagesOrNull
    if (nb == null || pages == null || pages.isEmpty()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val container = LocalAppContainer.current
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val fonts by container.fonts.fonts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val editors = remember { mutableStateMapOf<Long, RichEditorState>() }

    // The page the toolbar, page style and "+" act on: the one being written in, or the first one showing.
    var activePageId by rememberSaveable { mutableLongStateOf(initialPageId ?: pages.first().id) }
    // Falls back to the first page if the active one was deleted.
    val activeIndex = pages.indexOfFirst { it.id == activePageId }.coerceAtLeast(0)
    val activePage = pages[activeIndex]
    val currentEditor = editors[activePage.id]

    var showFormat by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    var imageMenu by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    // Pictures and recordings go onto the active page; remember which one in case it changes meanwhile.
    val targetPage by rememberUpdatedState(activePage.id)
    val imageAdder = rememberImageAdder { image -> vm.addImage(targetPage, image) }
    val askMic = rememberMicPermission { recording = true }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val wide = maxWidth >= TwoPaneMinWidth
        // Unfolded / tablet: two pages side by side, like an open notebook.
        val perSpread = if (wide) 2 else 1
        val spreadCount = (pages.size + perSpread - 1) / perSpread
        val pagerState = rememberPagerState(initialPage = activeIndex / perSpread) { spreadCount }

        // Jump to a requested page (new page, search result, pages list).
        var scrollTarget by rememberSaveable { mutableLongStateOf(-1L) }
        LaunchedEffect(scrollTarget, pages, perSpread) {
            if (scrollTarget >= 0) {
                val index = pages.indexOfFirst { it.id == scrollTarget }
                if (index >= 0) {
                    activePageId = scrollTarget
                    pagerState.animateScrollToPage(index / perSpread)
                    scrollTarget = -1L
                }
            }
        }
        // Folding or unfolding keeps the active page in view.
        LaunchedEffect(perSpread) {
            pagerState.scrollToPage((pages.indexOfFirst { it.id == activePageId }.coerceAtLeast(0)) / perSpread)
        }
        // Swiping to another spread makes its first page active, unless the active page is still showing.
        val latestPages by rememberUpdatedState(pages)
        LaunchedEffect(pagerState, perSpread) {
            snapshotFlow { pagerState.settledPage }.collect { spread ->
                val pages = latestPages
                val first = spread * perSpread
                val visible = pages.subList(first.coerceAtMost(pages.size), (first + perSpread).coerceAtMost(pages.size))
                if (visible.isNotEmpty() && visible.none { it.id == activePageId }) activePageId = visible.first().id
            }
        }

        val spreadStart = pagerState.currentPage * perSpread
        val pageLabel = when {
            perSpread == 2 && spreadStart + 1 < pages.size -> "${spreadStart + 1}–${spreadStart + 2}/${pages.size}"
            else -> "${(spreadStart + 1).coerceAtMost(pages.size)}/${pages.size}"
        }

        Column(Modifier.fillMaxSize()) {
            NotebookTopBar(
                notebook = nb,
                wide = wide,
                pageLabel = pageLabel,
                onBack = onBack,
                onSearch = onSearch,
                onPages = { showPages = true },
                onEdit = { showEdit = true },
            )

            HorizontalPager(
                state = pagerState,
                key = { spread -> pages.getOrNull(spread * perSpread)?.id ?: -spread.toLong() },
                userScrollEnabled = !WindowInsets.isImeVisible,
                beyondViewportPageCount = 0,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { spread ->
                Row(Modifier.fillMaxSize().padding(horizontal = if (perSpread == 2) 10.dp else 0.dp)) {
                    for (slot in 0 until perSpread) {
                        val page = pages.getOrNull(spread * perSpread + slot)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (page != null) {
                                PageView(
                                    page = page,
                                    session = vm.session(page),
                                    vm = vm,
                                    side = when {
                                        perSpread == 1 -> PageSide.SINGLE
                                        slot == 0 -> PageSide.LEFT
                                        else -> PageSide.RIGHT
                                    },
                                    onActivate = { activePageId = page.id },
                                    onEditorReady = { editors[page.id] = it },
                                    onEditorGone = { editors.remove(page.id) },
                                    onSave = { vm.saveIfChanged(page.id) },
                                )
                            } else {
                                BlankPageSlot(side = PageSide.RIGHT, onClick = {
                                    vm.addPage(pages.lastIndex) { newId -> scrollTarget = newId }
                                })
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val s = settings
                val pageBg = activePage.backgroundArgb?.let { Color(it) } ?: LocalQuartzColors.current.card
                EditorToolbar(
                    state = currentEditor,
                    ink = s?.inkArgb?.takeIf { it != 0 }?.let { Color(it) },
                    autoInk = contentColorFor(pageBg),
                    highlight = Color(s?.highlightArgb ?: 0xFFFFE14D.toInt()),
                    onInkChange = { c -> scope.launch { container.settings.setInk(c?.toArgb() ?: 0) } },
                    onHighlightChange = { c -> scope.launch { container.settings.setHighlight(c.toArgb()) } },
                    onFormat = { if (currentEditor != null) showFormat = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Box {
                        ToolButton(onClick = { imageMenu = true }, description = "Add picture") {
                            Icon(Icons.Rounded.Image, null)
                        }
                        DropdownMenu(expanded = imageMenu, onDismissRequest = { imageMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Photo library") },
                                leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                                onClick = {
                                    imageMenu = false
                                    imageAdder.pickFromGallery()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Take photo") },
                                leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                                onClick = {
                                    imageMenu = false
                                    imageAdder.takePhoto()
                                },
                            )
                        }
                    }
                    ToolButton(onClick = askMic, description = "Record voice note") {
                        Icon(Icons.Rounded.Mic, null)
                    }
                    ToolButton(onClick = { showStyle = true }, description = "Page style") {
                        Icon(Icons.Rounded.Texture, null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                RoundActionButton(
                    onClick = {
                        vm.addPage(activeIndex) { newId -> scrollTarget = newId }
                    },
                    size = 58.dp,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add page", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        if (showPages) {
            PagesSheet(
                pages = pages,
                currentIndex = activeIndex,
                onSelect = { index ->
                    showPages = false
                    scrollTarget = pages[index].id
                },
                onMove = { from, to ->
                    if (from == activeIndex) scrollTarget = pages[from].id
                    vm.movePage(from, to)
                },
                onDelete = { page -> vm.deletePage(page, isOnlyPage = pages.size <= 1) },
                onDismiss = { showPages = false },
            )
        }
    }

    if (showFormat && currentEditor != null) {
        FormatSheet(state = currentEditor, fonts = fonts, onDismiss = { showFormat = false })
    }
    if (showStyle) {
        PageStyleSheet(
            page = activePage,
            onChange = vm::updateStyle,
            onApplyToAll = {
                vm.applyStyleToAll(it)
                showStyle = false
            },
            onDismiss = { showStyle = false },
        )
    }
    if (recording) {
        val pageForRecording = remember { activePage.id }
        VoiceRecorderSheet(
            onSaved = { path, duration ->
                recording = false
                vm.addVoice(pageForRecording, path, duration)
            },
            onDismiss = { recording = false },
        )
    }
    if (showEdit) {
        NotebookEditorSheet(
            existing = nb,
            shelves = shelves,
            defaultShelfId = nb.shelfId,
            onDismiss = { showEdit = false },
            onSave = { draft ->
                vm.updateNotebook(nb.applyDraft(draft))
                showEdit = false
            },
            onDelete = {
                showEdit = false
                vm.deleteNotebook(nb, onBack)
            },
        )
    }
}

@Composable
private fun NotebookTopBar(
    notebook: NotebookEntity,
    wide: Boolean,
    pageLabel: String,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onPages: () -> Unit,
    onEdit: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val color = Color(notebook.colorArgb)
    val onColor = contentColorFor(color)
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }
        if (wide) {
            QuartzPill(Modifier.weight(1f), onClick = onSearch) {
                Text(
                    "Search Notebook",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.width(14.dp))
        }
        Surface(
            modifier = Modifier.weight(1f).height(56.dp),
            shape = CircleShape,
            color = color,
            shadowElevation = 3.dp,
        ) {
            Row(Modifier.padding(start = 22.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notebook.title,
                        fontSize = 21.sp,
                        color = onColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    NotebookIcons.of(notebook.icon)?.let {
                        Spacer(Modifier.width(10.dp))
                        Icon(it, contentDescription = null, tint = onColor, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(pageLabel, fontSize = 13.sp, color = onColor.copy(alpha = 0.75f), maxLines = 1)
                }
                if (!wide) {
                    IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, contentDescription = "Search notebook", tint = onColor) }
                }
                IconButton(onClick = onPages) { Icon(Icons.Rounded.Layers, contentDescription = "Pages", tint = onColor) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More", tint = onColor) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit notebook") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = {
                                menu = false
                                onEdit()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("All pages") },
                            leadingIcon = { Icon(Icons.Rounded.ViewDay, null) },
                            onClick = {
                                menu = false
                                onPages()
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun PageView(
    page: PageEntity,
    session: PageSession,
    vm: NotebookViewModel,
    side: PageSide,
    onActivate: () -> Unit,
    onEditorReady: (RichEditorState) -> Unit,
    onEditorGone: () -> Unit,
    onSave: () -> Unit,
) {
    val editor = session.editor
    val title = session.title

    DisposableEffect(session) {
        onEditorReady(editor)
        onDispose {
            onSave()
            onEditorGone()
        }
    }
    LaunchedEffect(session) {
        snapshotFlow { editor.version to session.title }.drop(1).debounce(600).collect { onSave() }
    }

    val elementsFlow = remember(page.id) { vm.elements(page.id) }
    val elements by elementsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedElement by remember(page.id) { mutableStateOf<Long?>(null) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var decorating by remember { mutableStateOf<Pair<PageElementEntity, ImageData>?>(null) }
    val elementActions = remember(page.id) {
        ElementActions(
            onSelect = { selectedElement = it },
            onCommit = vm::updateElement,
            onView = { viewing = it.path },
            onDecorate = { e, d -> decorating = e to d },
            onFront = vm::bringToFront,
            onDelete = {
                selectedElement = null
                vm.deleteElement(it)
            },
        )
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
    val keyboard = LocalSoftwareKeyboardController.current
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
        val pageWidthPx = with(density) { pageWidth.toPx() }
        val scroll = rememberScrollState()
        LaunchedEffect(scroll, pageWidthPx) {
            snapshotFlow { scroll.value }.collect { session.scrollUnits = if (pageWidthPx > 0) it / pageWidthPx else 0f }
        }
        val elementsBottom = elements.maxOfOrNull { it.bottom(pageWidth) } ?: 0.dp
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
          Box(Modifier.fillMaxWidth().heightIn(min = elementsBottom + 48.dp)) {
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
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), thickness = 2.dp, color = lineColor)
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
                    )
                    .onFocusChanged { if (it.hasFocus) selectedElement = null }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (selectedElement != null) {
                            selectedElement = null
                        } else {
                            editor.focusEnd()
                            keyboard?.show()
                        }
                    },
            ) {
                RichTextEditor(
                    state = editor,
                    styles = styles,
                    contentColor = ink,
                    modifier = Modifier.fillMaxWidth().padding(start = padH, end = padH, top = 10.dp, bottom = 40.dp),
                )
            }
          }
            PageElementLayer(
                elements = elements,
                pageWidth = pageWidth,
                selectedId = selectedElement,
                actions = elementActions,
            )
          }
        }
    }

    viewing?.let { path -> ImageViewer(path = path, onDismiss = { viewing = null }) }
    decorating?.let { (element, data) ->
        DecorateSheet(
            data = data,
            onChange = { updated ->
                val e = element.copy(data = updated.encode(), height = element.width * updated.aspect)
                decorating = e to updated
                vm.updateElement(e)
            },
            onDismiss = { decorating = null },
        )
    }
}

/** Right-hand page of the last spread when the notebook has an odd number of pages. */
@Composable
private fun BlankPageSlot(side: PageSide, onClick: () -> Unit) {
    val outline = LocalQuartzColors.current.textSecondary.copy(alpha = 0.45f)
    val shape = side.shape
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            .clip(shape)
            .drawBehind {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = outline,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(16f, 12f)),
                    ),
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = outline, modifier = Modifier.size(40.dp))
            Text("New page", color = outline, fontSize = 15.sp)
        }
    }
}

/** Where a page sits: alone, or as the left/right half of an open spread. */
private enum class PageSide(
    val shape: RoundedCornerShape,
    val outerStart: androidx.compose.ui.unit.Dp,
    val outerEnd: androidx.compose.ui.unit.Dp,
) {
    SINGLE(RoundedCornerShape(24.dp), 16.dp, 16.dp),
    // Spread halves meet at the seam with square inner corners.
    LEFT(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp), 6.dp, 0.dp),
    RIGHT(RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp), 0.dp, 6.dp),
}

/** Soft gutter shadow plus a fine crease line along the edge where two pages meet. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeam(atRightEdge: Boolean, strength: Float) {
    val width = 34.dp.toPx().coerceAtMost(size.width / 4)
    val dark = Color.Black
    val edgeX = if (atRightEdge) size.width else 0f
    val brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
        colors = if (atRightEdge) {
            listOf(dark.copy(alpha = 0f), dark.copy(alpha = strength * 0.45f), dark.copy(alpha = strength))
        } else {
            listOf(dark.copy(alpha = strength), dark.copy(alpha = strength * 0.45f), dark.copy(alpha = 0f))
        },
        startX = if (atRightEdge) size.width - width else 0f,
        endX = if (atRightEdge) size.width else width,
    )
    drawRect(
        brush = brush,
        topLeft = androidx.compose.ui.geometry.Offset(if (atRightEdge) size.width - width else 0f, 0f),
        size = androidx.compose.ui.geometry.Size(width, size.height),
    )
    drawLine(
        color = dark.copy(alpha = (strength * 1.4f).coerceAtMost(0.6f)),
        start = androidx.compose.ui.geometry.Offset(edgeX, 0f),
        end = androidx.compose.ui.geometry.Offset(edgeX, size.height),
        strokeWidth = 1.dp.toPx(),
    )
}
