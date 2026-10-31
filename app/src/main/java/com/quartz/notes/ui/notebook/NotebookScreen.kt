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
import androidx.compose.material.icons.rounded.Settings
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
import com.quartz.notes.data.model.elementMediaPath
import com.quartz.notes.data.model.encode
import com.quartz.notes.ui.media.DecorateSheet
import com.quartz.notes.ui.media.ImageViewer
import com.quartz.notes.ui.media.VoiceRecorderSheet
import com.quartz.notes.ui.media.rememberImageAdder
import com.quartz.notes.ui.media.rememberMicPermission
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.automirrored.rounded.ArrowRightAlt
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Category
import android.widget.Toast
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.quartz.notes.ui.note.NotebookPickerSheet
import com.quartz.notes.ui.note.ShareNoteSheet
import com.quartz.notes.ui.note.saveImageToGallery
import com.quartz.notes.ui.note.shareFile
import com.quartz.notes.ui.note.shareText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.runtime.mutableIntStateOf
import com.quartz.notes.data.model.ShapeType
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

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
    DisposableEffect(Unit) { onDispose { container.audio.stop() } }

    val editors = remember { mutableStateMapOf<Long, RichEditorState>() }

    // The page the toolbar, page style and "+" act on: the one being written in, or the first one showing.
    var activePageId by rememberSaveable { mutableLongStateOf(initialPageId ?: pages.first().id) }
    // Falls back to the first page if the active one was deleted.
    val activeIndex = pages.indexOfFirst { it.id == activePageId }.coerceAtLeast(0)
    val activePage = pages[activeIndex]
    val activeSession = vm.session(activePage)
    // A text box being typed in takes the formatting tools; otherwise the page's own text does.
    // Scrapbook pages have no flowing text, so without an active text box there's nothing to format.
    // A locked page can't be changed, so there's nothing to format.
    // The index and section pages aren't ordinary pages: no formatting, items or drawing there.
    val onIndex = activePageId == INDEX_PAGE_ID
    val activeIsPlain = !onIndex && !activePage.isSection
    val currentEditor = if (activePage.locked || !activeIsPlain) {
        null
    } else {
        activeSession.activeEditor ?: if (activePage.layoutMode == "SCRAPBOOK") null else editors[activePage.id]
    }

    // Drawing and connecting tools.
    var tool by remember { mutableStateOf(PageTool.NONE) }
    // Drawing and connecting only work on ordinary pages.
    LaunchedEffect(activeIsPlain) { if (!activeIsPlain) tool = PageTool.NONE }
    var brushLevel by rememberSaveable { mutableIntStateOf(2) }
    var shapeMenu by remember { mutableStateOf(false) }
    // A text box added from the toolbar gets the cursor once it exists.
    var pendingTextFocus by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    var showFormat by remember { mutableStateOf(false) }
    var formatTarget by remember { mutableStateOf<RichEditorState?>(null) }
    var showStyle by remember { mutableStateOf(false) }
    // Long-pressing + offers templates; saving one asks for its name.
    var addSheetAfter by remember { mutableStateOf<Int?>(null) }
    // Drawing tools on a text page offer a Freeform page.
    var askFreeform by remember { mutableStateOf(false) }
    val activeFreeform = activePage.layoutMode == "SCRAPBOOK"
    fun onFreeform(action: () -> Unit): () -> Unit = { if (activeFreeform) action() else askFreeform = true }
    var templateFrom by remember { mutableStateOf<PageEntity?>(null) }
    var showPages by remember { mutableStateOf(false) }
    var showTimeline by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }
    // Section page whose color / icon / photo is being changed.
    var sectionStyleId by remember { mutableStateOf<Long?>(null) }
    var imageMenu by remember { mutableStateOf(false) }
    // Sharing: the pages to choose between (two showing), the page being shared, and one being copied.
    var shareChoices by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    var sharingId by remember { mutableStateOf<Long?>(null) }
    var copyingId by remember { mutableStateOf<Long?>(null) }
    var recording by remember { mutableStateOf(false) }
    // Pictures and recordings go onto the active page; remember which one in case it changes meanwhile.
    val targetPage by rememberUpdatedState(activePage.id)
    val context = LocalContext.current
    val latestPageList by rememberUpdatedState(pages)

    /** New items need placing, so adding one to a locked page unlocks it. */
    fun unlockForNewItem(pageId: Long = activePage.id) {
        val page = latestPageList.firstOrNull { it.id == pageId } ?: return
        if (page.locked) {
            vm.setPageLocked(page, false)
            Toast.makeText(context, "Page unlocked", Toast.LENGTH_SHORT).show()
        }
    }
    val imageAdder = rememberImageAdder { image ->
        unlockForNewItem(targetPage)
        vm.addImage(targetPage, image)
    }
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
        // Unfolded / tablet: two pages side by side, like an open notebook (unless the notebook turns
        // that off). A scrapbook page set to use both pages gets a whole spread to itself.
        val twoUp = wide && nb.twoPage
        val perSpread = if (twoUp) 2 else 1
        // The index, when the notebook has one, comes first like any other page.
        val displayPages = remember(pages, nb.showIndex) { if (nb.showIndex) listOf(indexPage(nb.id)) + pages else pages }
        val spreads = remember(displayPages, twoUp) { buildSpreads(displayPages, twoUp) }
        fun spreadOf(pageId: Long): Int = spreads.indexOfFirst { s -> s.any { it?.id == pageId } }.coerceAtLeast(0)
        val pagerState = rememberPagerState(initialPage = spreadOf(activePageId)) { spreads.size }

        // Jump to a requested page (new page, search result, pages list).
        var scrollTarget by rememberSaveable { mutableLongStateOf(-1L) }
        val latestSpreads by rememberUpdatedState(spreads)
        LaunchedEffect(pagerState) {
            // One long-running listener, so clearing the request never cancels the jump in progress.
            // A newer request replaces an older one.
            snapshotFlow { scrollTarget }.filter { it >= 0 }.collectLatest { target ->
                // A brand-new page may not be in the list yet: wait for it to arrive.
                val index = snapshotFlow { latestSpreads.indexOfFirst { s -> s.any { it?.id == target } } }.first { it >= 0 }
                activePageId = target
                scrollTarget = -1L
                pagerState.animateScrollToPage(index)
            }
        }
        // Folding, unfolding or spreading a page across both sides keeps the active page in view.
        val arrangement = spreads.map { s -> s.map { it?.id } }
        LaunchedEffect(arrangement) {
            if (scrollTarget >= 0) return@LaunchedEffect // a jump is on its way
            if (spreads.none { s -> s.any { it?.id == activePageId } }) {
                // The active page was deleted: stay about where we were.
                val index = pagerState.currentPage.coerceIn(0, spreads.lastIndex)
                (spreads[index].firstOrNull { it != null && it.id != INDEX_PAGE_ID } ?: spreads[index].firstOrNull { it != null })
                    ?.let { activePageId = it.id }
                if (pagerState.currentPage != index) pagerState.scrollToPage(index)
                return@LaunchedEffect
            }
            val target = spreadOf(activePageId)
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
            // With the index beside a real page (e.g. after unfolding), the real page is the one to act on.
            if (activePageId == INDEX_PAGE_ID) {
                spreads.getOrNull(target)?.firstOrNull { it != null && it.id != INDEX_PAGE_ID }?.let { activePageId = it.id }
            }
        }
        // Swiping to another spread makes its first page active, unless the active page is still showing.
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { index ->
                val visible = latestSpreads.getOrNull(index).orEmpty().filterNotNull()
                // Prefer a real page over the index when both are showing.
                if (visible.isNotEmpty() && visible.none { it.id == activePageId }) {
                    activePageId = (visible.firstOrNull { it.id != INDEX_PAGE_ID } ?: visible.first()).id
                }
            }
        }

        val shownAll = spreads.getOrNull(pagerState.currentPage).orEmpty().filterNotNull()
        // Real pages showing (not the index).
        val shown = shownAll.filter { it.id != INDEX_PAGE_ID }
        val firstShown = shown.firstOrNull()?.let { p -> pages.indexOfFirst { it.id == p.id } + 1 } ?: 1
        val pageLabel = when {
            shown.isEmpty() -> "Index"
            shown.size == 2 -> "$firstShown–${firstShown + 1}/${pages.size}"
            else -> "$firstShown/${pages.size}"
        }

        // Alt+Right / Alt+Left (or Ctrl+Page Down / Ctrl+Page Up) turn the page; Ctrl+Alt+Home / End go to
        // the first / last page. They work even while typing.
        fun turnTo(index: Int) {
            scope.launch { pagerState.animateScrollToPage(index.coerceIn(0, spreads.lastIndex)) }
        }
        // The tools (formatting, items, page buttons, add page) sit at the bottom, or under the title
        // bar when the "toolbar at the top" setting is on.
        val toolsOnTop = settings?.toolbarOnTop == true
        val brushBar: @Composable () -> Unit = {
            if (tool == PageTool.BRUSH || tool == PageTool.ERASER) {
                BrushBar(
                    level = brushLevel,
                    eraser = tool == PageTool.ERASER,
                    color = settings?.inkArgb?.takeIf { it != 0 }?.let { Color(it) }
                        ?: contentColorFor(activePage.backgroundArgb?.let { Color(it) } ?: LocalQuartzColors.current.card),
                    onLevel = {
                        brushLevel = it
                        tool = PageTool.BRUSH
                    },
                    onEraser = { tool = if (tool == PageTool.ERASER) PageTool.BRUSH else PageTool.ERASER },
                    onDone = { tool = PageTool.NONE },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 6.dp),
                )
            }
        }
        val toolRow: @Composable () -> Unit = {
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
                    onFormat = {
                        if (currentEditor != null) {
                            formatTarget = currentEditor
                            showFormat = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    // Items, text boxes and drawing only make sense on ordinary pages.
                    if (activeIsPlain) {
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
                        ToolButton(
                            onClick = askMic,
                            description = "Record voice note",
                        ) {
                            Icon(Icons.Rounded.Mic, null)
                        }
                        ToolButton(
                            onClick = onFreeform {
                                tool = PageTool.NONE
                                unlockForNewItem()
                                scope.launch {
                                    val y = activeSession.scrollUnits + 0.3f
                                    val id = vm.addTextBox(activePage.id, 0.12f, y, 0.6f)
                                    pendingTextFocus = activePage.id to id
                                }
                            },
                            description = "Add text box",
                        ) { Icon(TextBoxIcon, null) }
                        ToolButton(
                            selected = tool == PageTool.BRUSH || tool == PageTool.ERASER,
                            onClick = onFreeform {
                                if (tool == PageTool.BRUSH || tool == PageTool.ERASER) {
                                    tool = PageTool.NONE
                                } else {
                                    unlockForNewItem()
                                    tool = PageTool.BRUSH
                                }
                            },
                            description = "Brush",
                        ) { Icon(Icons.Rounded.Brush, null) }
                        Box {
                            ToolButton(onClick = onFreeform { shapeMenu = true }, description = "Add shape") { Icon(Icons.Rounded.Category, null) }
                            DropdownMenu(expanded = shapeMenu, onDismissRequest = { shapeMenu = false }) {
                                ShapeType.entries.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label) },
                                        leadingIcon = {
                                            val c = MaterialTheme.colorScheme.onSurface
                                            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                                                drawPath(shapePath(t, size), c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                            }
                                        },
                                        onClick = {
                                            shapeMenu = false
                                            tool = PageTool.NONE
                                            unlockForNewItem()
                                            // A new shape is ready to type in.
                                            scope.launch {
                                                val id = vm.addShape(activePage.id, t)
                                                pendingTextFocus = activePage.id to id
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        ToolButton(
                            onClick = onFreeform {
                                tool = PageTool.NONE
                                unlockForNewItem()
                                vm.addArrow(activePage.id)
                            },
                            description = "Add arrow",
                        ) { Icon(Icons.AutoMirrored.Rounded.ArrowRightAlt, null) }
                        ToolButton(
                            selected = tool == PageTool.CONNECT,
                            onClick = onFreeform {
                                if (tool == PageTool.CONNECT) {
                                    tool = PageTool.NONE
                                } else {
                                    unlockForNewItem()
                                    tool = PageTool.CONNECT
                                }
                            },
                            description = "Connect two items",
                        ) { Icon(Icons.Rounded.AccountTree, null) }
                    }
                    if (!onIndex) {
                        ToolButton(
                            onClick = { if (activePage.isSection) sectionStyleId = activePage.id else showStyle = true },
                            description = if (activePage.isSection) "Section style" else "Page style",
                        ) {
                            Icon(Icons.Rounded.Texture, null)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                PageNavPill(
                    labels = wide,
                    canPrevious = pagerState.currentPage > 0,
                    canNext = pagerState.currentPage < spreads.lastIndex,
                    onPrevious = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) } },
                    onPages = { showTimeline = true },
                    onNext = { scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(spreads.lastIndex)) } },
                )
                Spacer(Modifier.width(12.dp))
                // Tap: new page after this one. Long-press: a section, or a page from a template.
                val haptics = LocalHapticFeedback.current
                val insertAfter = if (onIndex) -1 else activeIndex
                Box(
                    Modifier
                        .size(58.dp)
                        .shadow(3.dp, CircleShape)
                        .clip(CircleShape)
                        .background(LocalQuartzColors.current.pill)
                        .combinedClickable(
                            onClickLabel = "Add page",
                            onLongClickLabel = "Add a section or a page from a template",
                            onClick = { vm.addPage(insertAfter) { newId -> scrollTarget = newId } },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                addSheetAfter = insertAfter
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add page (hold for sections and templates)", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // The notebook itself takes focus whenever nothing inside it has it, so page keys work even
        // when nobody is typing.
        val pageKeys = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { pageKeys.requestFocus() } }
        Column(
            Modifier
                .fillMaxSize()
                .focusRequester(pageKeys)
                .onFocusChanged { if (!it.hasFocus) scope.launch { runCatching { pageKeys.requestFocus() } } }
                .focusable()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val cmd = e.isCtrlPressed || e.isMetaPressed
                    val alt = e.isAltPressed
                    when {
                        // Alt + arrows, like a browser's back and forward.
                        alt && !cmd && e.key == Key.DirectionRight -> true.also { turnTo(pagerState.currentPage + 1) }
                        alt && !cmd && e.key == Key.DirectionLeft -> true.also { turnTo(pagerState.currentPage - 1) }
                        cmd && e.key == Key.PageDown -> true.also { turnTo(pagerState.currentPage + 1) }
                        cmd && e.key == Key.PageUp -> true.also { turnTo(pagerState.currentPage - 1) }
                        cmd && alt && e.key == Key.MoveHome -> true.also { turnTo(0) }
                        cmd && alt && e.key == Key.MoveEnd -> true.also { turnTo(spreads.lastIndex) }
                        else -> false
                    }
                },
        ) {
            NotebookTopBar(
                notebook = nb,
                wide = wide,
                pageLabel = pageLabel,
                onBack = onBack,
                onSearch = onSearch,
                onPages = { showPages = true },
                onEdit = { showEdit = true },
                onShare = {
                    // Two pages showing: ask which one.
                    when {
                        shown.size > 1 -> shareChoices = shown
                        shown.isNotEmpty() -> sharingId = shown.first().id
                        else -> Toast.makeText(context, "Turn to a page to share it", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            if (toolsOnTop) {
                toolRow()
                brushBar()
            }

            // Section tabs along the page edges, in the margin beside the pages.
            val tabSections = if (nb.sectionTabs) pages.filter { it.isSection } else emptyList()
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                HorizontalPager(
                    state = pagerState,
                    key = { index -> spreads.getOrNull(index)?.firstOrNull()?.id ?: (Long.MIN_VALUE + index) },
                    // Zoomed in on a Freeform page, a swipe moves around it instead of turning the page.
                    userScrollEnabled = !WindowInsets.isImeVisible && shownAll.none { it.id != INDEX_PAGE_ID && !it.isSection && vm.session(it).zoomedIn },
                    beyondViewportPageCount = 0,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = if (tabSections.isNotEmpty() && twoUp) TabExtra else 0.dp, end = if (tabSections.isNotEmpty()) TabExtra else 0.dp),
                ) { index ->
                    val spread = spreads.getOrNull(index).orEmpty()

                    @Composable
                    fun Page(page: PageEntity, side: PageSide) {
                        if (page.id == INDEX_PAGE_ID) {
                            IndexPageView(pages = pages, vm = vm, side = side, onOpen = { scrollTarget = it })
                            return
                        }
                        if (page.isSection) {
                            SectionPageView(
                                page = page,
                                session = vm.session(page),
                                vm = vm,
                                side = side,
                                onActivate = { activePageId = page.id },
                                onStyle = {
                                    activePageId = page.id
                                    sectionStyleId = page.id
                                },
                            )
                            return
                        }
                                    PageView(
                                        page = page,
                                        session = vm.session(page),
                                        vm = vm,
                                        side = side,
                                        canSpread = twoUp,
                                        tool = tool,
                                        brushLevel = brushLevel,
                                        inkArgb = settings?.inkArgb ?: 0,
                                        onToolDone = { tool = PageTool.NONE },
                                        focusTextBox = pendingTextFocus?.takeIf { it.first == page.id }?.second,
                                        onFocusTextBoxHandled = { pendingTextFocus = null },
                                        onActivate = { activePageId = page.id },
                                        onEditorReady = { editors[page.id] = it },
                                        onEditorGone = { editors.remove(page.id) },
                                        onSave = { vm.saveIfChanged(page.id) },
                                    )
                    }

                    Row(Modifier.fillMaxSize().padding(horizontal = if (perSpread == 2) 10.dp else 0.dp)) {
                        if (spread.size == 1) {
                            val page = spread[0]
                            if (page != null) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    key(page.id) { Page(page, if (perSpread == 2) PageSide.SPREAD else PageSide.SINGLE) }
                                }
                            }
                        } else {
                            for ((slot, page) in spread.withIndex()) {
                                Box(Modifier.weight(1f).fillMaxHeight()) {
                                    when {
                                        page != null -> key(page.id) { Page(page, if (slot == 0) PageSide.LEFT else PageSide.RIGHT) }
                                        index == spreads.lastIndex -> BlankPageSlot(side = PageSide.RIGHT, onClick = {
                                            vm.addPage(pages.lastIndex) { newId -> scrollTarget = newId }
                                        })
                                        // The next page spreads across both sides, so this side is left blank.
                                        else -> EmptyPageFace(side = PageSide.RIGHT)
                                    }
                                }
                            }
                        }
                    }
                }
                // Drawn after the pager so taps reach them.
                SectionTabs(
                    sections = tabSections,
                    indexOf = { section -> pages.indexOfFirst { it.id == section.id } },
                    firstShown = shown.firstOrNull()?.let { p -> pages.indexOfFirst { it.id == p.id } } ?: -1,
                    twoUp = twoUp,
                    height = maxHeight,
                    onOpen = { scrollTarget = it },
                )
            }

            if (!toolsOnTop) {
                brushBar()
                toolRow()
            }
        }

        if (askFreeform) {
            val size = vm.defaultPageSize
            AlertDialog(
                onDismissRequest = { askFreeform = false },
                title = { Text("Shapes and drawing go on Freeform pages") },
                text = {
                    Text(
                        "Text pages keep words, in-line pictures and attachments. Freeform pages have shapes, connectors, " +
                            "arrows, text boxes and drawing, at a set page size (${size.label}).",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        askFreeform = false
                        vm.addPageOfType(activeIndex, freeform = true, size = size) { newId -> scrollTarget = newId }
                    }) { Text("Add a Freeform page") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        askFreeform = false
                        vm.setLayoutMode(activePage, scrapbook = true, size = size)
                    }) { Text("Make this page Freeform") }
                },
            )
        }
        addSheetAfter?.let { after ->
            com.quartz.notes.ui.templates.AddPageSheet(
                onBlank = {
                    addSheetAfter = null
                    vm.addPageOfType(after, freeform = false, size = null) { newId -> scrollTarget = newId }
                },
                sizes = settings?.pageSizes ?: com.quartz.notes.data.model.PageSize.PAPER,
                defaultSize = vm.defaultPageSize,
                onFreeform = { size ->
                    addSheetAfter = null
                    vm.addPageOfType(after, freeform = true, size = size) { newId -> scrollTarget = newId }
                },
                onSection = {
                    addSheetAfter = null
                    vm.addSection(after + 1) { newId -> scrollTarget = newId }
                },
                onTemplate = { t ->
                    addSheetAfter = null
                    vm.addPageFromTemplate(after, t.id) { newId -> scrollTarget = newId }
                },
                onStarter = { st ->
                    addSheetAfter = null
                    vm.addPageFromStarter(after, st) { newId -> scrollTarget = newId }
                },
                onDismiss = { addSheetAfter = null },
            )
        }
        if (showTimeline) {
            PageTimelineSheet(
                pages = displayPages,
                showing = shownAll.map { it.id }.toSet(),
                vm = vm,
                onOpen = { page ->
                    showTimeline = false
                    if (page.id == INDEX_PAGE_ID) {
                        scope.launch { pagerState.animateScrollToPage(spreadOf(INDEX_PAGE_ID)) }
                    } else {
                        scrollTarget = page.id
                    }
                },
                onManage = {
                    showTimeline = false
                    showPages = true
                },
                onMove = { page, delta ->
                    val from = pages.indexOfFirst { it.id == page.id }
                    val to = from + delta
                    if (from >= 0 && to in pages.indices) vm.movePage(from, to)
                },
                onMoveTo = { page, to ->
                    val from = pages.indexOfFirst { it.id == page.id }
                    if (from >= 0 && to in pages.indices && to != from) vm.movePage(from, to)
                },
                onStartSection = { page ->
                    showTimeline = false
                    val at = pages.indexOfFirst { it.id == page.id }
                    if (at >= 0) vm.addSection(at) { newId -> scrollTarget = newId }
                },
                onSaveTemplate = { page ->
                    showTimeline = false
                    templateFrom = page
                },
                onDelete = { page -> vm.deletePage(page, isOnlyPage = pages.size <= 1) },
                onDismiss = { showTimeline = false },
            )
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
                onStartSection = { index ->
                    showPages = false
                    vm.addSection(index) { newId -> scrollTarget = newId }
                },
                onDismiss = { showPages = false },
            )
        }
    }

    if (shareChoices.isNotEmpty()) {
        val choices = shareChoices
        AlertDialog(
            onDismissRequest = { shareChoices = emptyList() },
            title = { Text("Share which page?") },
            text = {
                Column {
                    choices.forEachIndexed { i, p ->
                        val number = pages.indexOfFirst { it.id == p.id } + 1
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    shareChoices = emptyList()
                                    sharingId = p.id
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (i == 0) "Left" else "Right",
                                fontSize = 13.sp,
                                color = LocalQuartzColors.current.textSecondary,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(
                                p.title.ifBlank { "Page $number" },
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("$number", fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { shareChoices = emptyList() }) { Text("Cancel") } },
        )
    }
    sharingId?.let { id ->
        val page = pages.firstOrNull { it.id == id }
        if (page == null) {
            sharingId = null
        } else {
            val session = vm.session(page)
            fun withImage(action: suspend (java.io.File) -> Unit) {
                sharingId = null
                val exporter = session.exporter
                if (exporter == null) {
                    Toast.makeText(context, "Open the page to share it as a picture", Toast.LENGTH_SHORT).show()
                    return
                }
                scope.launch {
                    val file = try {
                        exporter.image()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Toast.makeText(context, "Couldn't make the picture", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    action(file)
                }
            }
            ShareNoteSheet(
                noun = "page",
                onText = {
                    sharingId = null
                    val text = session.exporter?.text()
                        ?: listOf(session.title.trim(), page.plainText.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
                    shareText(context, session.title, text, chooserTitle = "Share page")
                },
                onImage = { withImage { shareFile(context, it, "image/png", "${context.packageName}.files", chooserTitle = "Share page") } },
                onSaveImage = {
                    withImage { file ->
                        val saved = saveImageToGallery(context, file, file.nameWithoutExtension + " " + System.currentTimeMillis() / 1000)
                        Toast.makeText(context, if (saved) "Saved to your gallery" else "Couldn't save the picture", Toast.LENGTH_SHORT).show()
                    }
                },
                onNotebook = {
                    sharingId = null
                    copyingId = page.id
                },
                onDismiss = { sharingId = null },
            )
        }
    }
    copyingId?.let { pageId ->
        NotebookPickerSheet(
            onPick = { target ->
                copyingId = null
                scope.launch {
                    runCatching { vm.copyPageTo(pageId, target.id) }
                        .onSuccess { Toast.makeText(context, "Copied to ${target.title}", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(context, "Couldn't copy the page", Toast.LENGTH_SHORT).show() }
                }
            },
            onDismiss = { copyingId = null },
        )
    }

    sectionStyleId?.let { id ->
        val page = pages.firstOrNull { it.id == id && it.isSection }
        if (page == null) {
            sectionStyleId = null
        } else {
            SectionStyleSheet(
                page = page,
                onColor = { vm.setSectionColor(page, it) },
                onIcon = { vm.setSectionIcon(page, it) },
                onImage = { vm.setSectionImage(page, it) },
                onDismiss = { sectionStyleId = null },
            )
        }
    }

    // The sheet keeps formatting the editor it was opened for, even though opening it moves focus.
    val sheetTarget = formatTarget
    if (showFormat && sheetTarget != null) {
        FormatSheet(state = sheetTarget, fonts = fonts, onDismiss = { showFormat = false })
    }
    if (showStyle) {
        PageStyleSheet(
            page = activePage,
            sizes = settings?.pageSizes ?: com.quartz.notes.data.model.PageSize.PAPER,
            onLayoutMode = { scrapbook -> vm.setLayoutMode(activePage, scrapbook) },
            onSize = { size -> vm.setLayoutMode(activePage, scrapbook = true, size = size) },
            onAdjustPicture = {
                showStyle = false
                val session = vm.session(activePage)
                session.zoom = 1f
                session.pan = androidx.compose.ui.geometry.Offset.Zero
                session.adjustingBackground = true
            },
            onChange = vm::updateStyle,
            onApplyToAll = {
                vm.applyStyleToAll(it)
                showStyle = false
            },
            onSaveTemplate = {
                showStyle = false
                templateFrom = activePage
            },
            onDismiss = { showStyle = false },
        )
    }
    templateFrom?.let { page ->
        com.quartz.notes.ui.components.TextInputDialog(
            title = "Save as template",
            initial = page.templateName ?: page.title,
            label = "Template name",
            onDismiss = { templateFrom = null },
            onConfirm = { name ->
                templateFrom = null
                if (name.isNotBlank()) {
                    vm.saveAsTemplate(page.id, name) {
                        Toast.makeText(context, "Saved as a template. Hold + to use it.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
    if (recording) {
        val pageForRecording = remember { activePage.id }
        VoiceRecorderSheet(
            onSaved = { path, duration ->
                recording = false
                unlockForNewItem(pageForRecording)
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
            sizes = settings?.pageSizes ?: com.quartz.notes.data.model.PageSize.PAPER,
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
    onShare: () -> Unit,
) {
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
                IconButton(onClick = onShare) { Icon(Icons.Rounded.Share, contentDescription = "Share page", tint = onColor) }
                IconButton(onClick = onPages) { Icon(Icons.Rounded.Layers, contentDescription = "Page list", tint = onColor) }
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Settings, contentDescription = "Notebook settings", tint = onColor) }
            }
        }
    }
}

/** Previous page, all pages (timeline) and next page, beside the add-page button. */
@Composable
private fun PageNavPill(
    /** Room for words beside the icons (tablet / unfolded). */
    labels: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onPages: () -> Unit,
    onNext: () -> Unit,
) {
    val content = MaterialTheme.colorScheme.onSurface
    Surface(shape = CircleShape, color = LocalQuartzColors.current.pill, shadowElevation = 3.dp) {
        Row(Modifier.height(58.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Segment(enabled: Boolean, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
                Row(
                    Modifier
                        .height(46.dp)
                        .clip(CircleShape)
                        .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
                        .padding(horizontal = if (labels) 14.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { content() }
            }
            Segment(canPrevious, "Previous page", onPrevious) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous page", tint = content.copy(alpha = if (canPrevious) 1f else 0.3f))
                if (labels) Text("Previous", fontSize = 15.sp, color = content.copy(alpha = if (canPrevious) 1f else 0.3f))
            }
            Segment(true, "All pages", onPages) {
                Icon(Icons.Rounded.ViewCarousel, contentDescription = "Pages", tint = content)
                if (labels) {
                    Spacer(Modifier.width(8.dp))
                    Text("Pages", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = content)
                }
            }
            Segment(canNext, "Next page", onNext) {
                if (labels) Text("Next", fontSize = 15.sp, color = content.copy(alpha = if (canNext) 1f else 0.3f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next page", tint = content.copy(alpha = if (canNext) 1f else 0.3f))
            }
        }
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
internal enum class PageSide(
    val shape: RoundedCornerShape,
    val outerStart: androidx.compose.ui.unit.Dp,
    val outerEnd: androidx.compose.ui.unit.Dp,
) {
    SINGLE(RoundedCornerShape(24.dp), 16.dp, 16.dp),
    // A scrapbook page using both sides of an open spread.
    SPREAD(RoundedCornerShape(24.dp), 6.dp, 6.dp),
    // Spread halves meet at the seam with square inner corners.
    LEFT(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp), 6.dp, 0.dp),
    RIGHT(RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp), 0.dp, 6.dp),
}

/** Is this a scrapbook page set to use both sides of an open spread? */
internal val PageEntity.usesBothSides: Boolean get() = spread && layoutMode == "SCRAPBOOK"

/**
 * Groups pages into what's shown at once: pairs when [wide], one page otherwise. A page using both
 * sides stands alone; a page that can't pair up gets an empty slot (null) beside it.
 */
internal fun buildSpreads(pages: List<PageEntity>, wide: Boolean): List<List<PageEntity?>> {
    if (!wide) return pages.map { listOf(it) }
    val out = ArrayList<List<PageEntity?>>()
    var i = 0
    while (i < pages.size) {
        val page = pages[i]
        val next = pages.getOrNull(i + 1)
        when {
            page.usesBothSides -> {
                out += listOf(page)
                i += 1
            }
            next != null && !next.usesBothSides -> {
                out += listOf(page, next)
                i += 2
            }
            else -> {
                out += listOf(page, null)
                i += 1
            }
        }
    }
    return out
}

/** A plain, empty right-hand page (the next page needs a whole spread of its own). */
@Composable
private fun EmptyPageFace(side: PageSide) {
    val quartz = LocalQuartzColors.current
    val shape = side.shape
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = side.outerStart, end = side.outerEnd, top = 6.dp, bottom = 6.dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(quartz.card.copy(alpha = 0.6f))
            .drawBehind { drawSeam(atRightEdge = false, strength = if (quartz.isDark) 0.45f else 0.13f) },
    )
}

/** Shading down the middle of a page that spreads across both sides. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCenterSeam(strength: Float) {
    val half = 30.dp.toPx().coerceAtMost(size.width / 8)
    val mid = size.width / 2
    val dark = Color.Black
    drawRect(
        brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
            colors = listOf(dark.copy(alpha = 0f), dark.copy(alpha = strength * 0.7f), dark.copy(alpha = 0f)),
            startX = mid - half,
            endX = mid + half,
        ),
        topLeft = androidx.compose.ui.geometry.Offset(mid - half, 0f),
        size = androidx.compose.ui.geometry.Size(half * 2, size.height),
    )
    drawLine(
        color = dark.copy(alpha = (strength * 1.2f).coerceAtMost(0.5f)),
        start = androidx.compose.ui.geometry.Offset(mid, 0f),
        end = androidx.compose.ui.geometry.Offset(mid, size.height),
        strokeWidth = 1.dp.toPx(),
    )
}

/** "T in a box": the add-text-box button. */
private val TextBoxIcon: ImageVector by lazy {
    ImageVector.Builder(name = "TextBox", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // Rounded box
            moveTo(7f, 4f)
            lineTo(17f, 4f)
            quadTo(20f, 4f, 20f, 7f)
            lineTo(20f, 17f)
            quadTo(20f, 20f, 17f, 20f)
            lineTo(7f, 20f)
            quadTo(4f, 20f, 4f, 17f)
            lineTo(4f, 7f)
            quadTo(4f, 4f, 7f, 4f)
            close()
            // T
            moveTo(8.5f, 8.5f)
            lineTo(15.5f, 8.5f)
            moveTo(12f, 8.5f)
            lineTo(12f, 16f)
        }
        .build()
}

/** Soft gutter shadow plus a fine crease line along the edge where two pages meet. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeam(atRightEdge: Boolean, strength: Float) {
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
