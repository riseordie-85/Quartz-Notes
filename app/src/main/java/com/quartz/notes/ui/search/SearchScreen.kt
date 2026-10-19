package com.quartz.notes.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.SearchResults
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.ui.components.ColorDot
import com.quartz.notes.ui.components.EmptyHint
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.components.QuartzPill
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.shortTime
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.NoteColor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repo: QuartzRepository, val notebookId: Long?) : ViewModel() {
    val query = MutableStateFlow("")

    val notebook: StateFlow<NotebookEntity?> =
        (if (notebookId != null) repo.observeNotebook(notebookId) else flowOf(null))
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val results: StateFlow<SearchResults?> = query
        .debounce(180)
        .mapLatest { q -> if (q.isBlank()) null else repo.search(q.trim(), notebookId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun SearchScreen(
    notebookId: Long?,
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenPage: (notebookId: Long, pageId: Long) -> Unit,
) {
    val vm = quartzViewModel(key = "search-${notebookId ?: "all"}") { SearchViewModel(it.repository, notebookId) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val notebook by vm.notebook.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 16.dp, top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            QuartzPill(Modifier.weight(1f)) {
                val hint = if (notebookId != null) "Search ${notebook?.title ?: "notebook"}" else "Search notes and notebooks"
                BasicTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 19.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) Text(hint, fontSize = 19.sp, color = LocalQuartzColors.current.textSecondary, maxLines = 1)
                            inner()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
                }
            }
        }

        val r = results
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            when {
                query.isBlank() -> item {
                    EmptyHint(if (notebookId != null) "Search the pages in this notebook." else "Search every quick note and notebook page.")
                }
                r == null -> Unit
                r.isEmpty -> item { EmptyHint("Nothing matches \"$query\".") }
                else -> {
                    if (r.notes.isNotEmpty()) {
                        item { SectionHeader("Quick Notes") }
                        item {
                            QuartzCard(Modifier.fillMaxWidth()) {
                                r.notes.forEachIndexed { i, note ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalQuartzColors.current.divider)
                                    ResultRow(
                                        title = note.title.ifBlank { note.plainText.lineSequence().firstOrNull { it.isNotBlank() } ?: "Untitled" },
                                        snippet = snippet(note.plainText, query),
                                        meta = shortTime(note.updatedAt),
                                        dot = NoteColor.of(note.colorKey).dot.takeIf { NoteColor.of(note.colorKey) != NoteColor.DEFAULT },
                                        onClick = { onOpenNote(note.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (r.pages.isNotEmpty()) {
                        item { SectionHeader(if (notebookId != null) "Pages" else "Notebook pages") }
                        item {
                            QuartzCard(Modifier.fillMaxWidth()) {
                                r.pages.forEachIndexed { i, hit ->
                                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalQuartzColors.current.divider)
                                    ResultRow(
                                        title = hit.title.ifBlank { "Untitled page" },
                                        snippet = snippet(hit.plainText, query),
                                        meta = if (notebookId == null) "${hit.notebookTitle} · ${shortTime(hit.updatedAt)}" else shortTime(hit.updatedAt),
                                        dot = Color(hit.notebookColor),
                                        onClick = { onOpenPage(hit.notebookId, hit.pageId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 22.sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 10.dp),
    )
}

@Composable
private fun ResultRow(title: String, snippet: AnnotatedString, meta: String, dot: Color?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (snippet.isNotEmpty()) {
                Text(snippet, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
            }
            Text(meta, fontSize = 12.sp, color = LocalQuartzColors.current.textSecondary)
        }
        if (dot != null) {
            Spacer(Modifier.width(12.dp))
            ColorDot(dot, size = 12.dp)
        }
    }
}

/** A short excerpt around the first match, with the match highlighted. */
@Composable
private fun snippet(text: String, query: String): AnnotatedString {
    val flat = text.replace('\n', ' ').trim()
    val q = query.trim()
    val accent = MaterialTheme.colorScheme.primary
    val index = if (q.isEmpty()) -1 else flat.indexOf(q, ignoreCase = true)
    if (index < 0) return AnnotatedString(flat.take(120))
    val start = (index - 40).coerceAtLeast(0)
    val end = (index + q.length + 80).coerceAtMost(flat.length)
    return buildAnnotatedString {
        if (start > 0) append("…")
        append(flat.substring(start, index))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent, background = accent.copy(alpha = 0.12f)))
        append(flat.substring(index, index + q.length))
        pop()
        append(flat.substring(index + q.length, end))
        if (end < flat.length) append("…")
    }
}
