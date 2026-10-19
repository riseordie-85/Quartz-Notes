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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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

    val pagerState = rememberPagerState { pages.size }
    var scrollTarget by rememberSaveable { mutableStateOf(initialPageId ?: -1L) }
    LaunchedEffect(scrollTarget, pages) {
        if (scrollTarget >= 0) {
            val index = pages.indexOfFirst { it.id == scrollTarget }
            if (index >= 0) {
                pagerState.scrollToPage(index)
                scrollTarget = -1L
            }
        }
    }

    val editors = remember { mutableStateMapOf<Long, RichEditorState>() }
    val currentIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)
    val currentPage = pages[currentIndex]
    val currentEditor = editors[currentPage.id]

    var showFormat by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val wide = maxWidth >= TwoPaneMinWidth
        Column(Modifier.fillMaxSize()) {
            NotebookTopBar(
                notebook = nb,
                wide = wide,
                pageLabel = "Page ${currentIndex + 1} of ${pages.size}",
                onBack = onBack,
                onSearch = onSearch,
                onPages = { showPages = true },
                onEdit = { showEdit = true },
            )

            HorizontalPager(
                state = pagerState,
                key = { index -> pages.getOrNull(index)?.id ?: index },
                userScrollEnabled = !WindowInsets.isImeVisible,
                beyondViewportPageCount = 0,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { index ->
                val page = pages.getOrNull(index)
                if (page != null) {
                    PageView(
                        page = page,
                        wide = wide,
                        onEditorReady = { editors[page.id] = it },
                        onEditorGone = { editors.remove(page.id) },
                        onSave = { doc -> vm.savePage(page.id, doc) },
                        onTitleChange = { vm.saveTitle(page.id, it) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val s = settings
                val pageBg = currentPage.backgroundArgb?.let { Color(it) } ?: LocalQuartzColors.current.card
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
                    ToolButton(onClick = { showStyle = true }, description = "Page style") {
                        Icon(Icons.Rounded.Texture, null)
                    }
                }
                Spacer(Modifier.width(12.dp))
                RoundActionButton(
                    onClick = {
                        vm.addPage(currentIndex) { newId -> scrollTarget = newId }
                    },
                    size = 58.dp,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add page", modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    if (showFormat && currentEditor != null) {
        FormatSheet(state = currentEditor, fonts = fonts, onDismiss = { showFormat = false })
    }
    if (showStyle) {
        PageStyleSheet(
            page = currentPage,
            onChange = vm::updateStyle,
            onApplyToAll = {
                vm.applyStyleToAll(it)
                showStyle = false
            },
            onDismiss = { showStyle = false },
        )
    }
    if (showPages) {
        PagesSheet(
            pages = pages,
            currentIndex = currentIndex,
            onSelect = { index ->
                showPages = false
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            onMove = { from, to ->
                if (from == currentIndex) scrollTarget = pages[from].id
                vm.movePage(from, to)
            },
            onDelete = vm::deletePage,
            onDismiss = { showPages = false },
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
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    }
                    Text(pageLabel, fontSize = 12.sp, color = onColor.copy(alpha = 0.8f))
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
    wide: Boolean,
    onEditorReady: (RichEditorState) -> Unit,
    onEditorGone: () -> Unit,
    onSave: (RichDoc) -> Unit,
    onTitleChange: (String) -> Unit,
) {
    val editor = remember(page.id) { RichEditorState(RichDoc.decode(page.content)) }
    var title by remember(page.id) { mutableStateOf(page.title) }

    DisposableEffect(editor) {
        onEditorReady(editor)
        onDispose {
            onSave(editor.toDoc())
            onEditorGone()
        }
    }
    LaunchedEffect(editor) {
        snapshotFlow { editor.version }.drop(1).debounce(600).collect { onSave(editor.toDoc()) }
    }
    LaunchedEffect(page.id) {
        snapshotFlow { title }.drop(1).distinctUntilChanged().debounce(500).collect { onTitleChange(it) }
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
    val shape = RoundedCornerShape(24.dp)
    val padH = if (wide) 40.dp else 22.dp

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(bg)
            .drawBehind { texture?.let { drawRect(it) } },
    ) {
        val viewport = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            BasicTextField(
                value = title,
                onValueChange = { title = it.replace("\n", "") },
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
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        editor.focusEnd()
                        keyboard?.show()
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
    }
}
