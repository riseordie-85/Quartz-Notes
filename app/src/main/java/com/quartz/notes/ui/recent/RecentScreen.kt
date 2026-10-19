package com.quartz.notes.ui.recent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.PageHit
import com.quartz.notes.ui.components.ColorDot
import com.quartz.notes.ui.components.EmptyHint
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.dateLabel
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.shortTime
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.SectionTitleStyle
import com.quartz.notes.ui.toLocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class RecentViewModel(repo: QuartzRepository) : ViewModel() {
    val pages: StateFlow<List<PageHit>?> = repo.observeRecentPages().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Recently edited notebook pages across every shelf. */
@Composable
fun RecentScreen(onBack: () -> Unit, onOpenPage: (notebookId: Long, pageId: Long) -> Unit) {
    val vm = quartzViewModel { RecentViewModel(it.repository) }
    val pages by vm.pages.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Text("Recent pages", style = SectionTitleStyle, color = MaterialTheme.colorScheme.onBackground)
        }
        val list = pages ?: return@Column
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (list.isEmpty()) {
                item { EmptyHint("Pages you write in will show up here.") }
            }
            val groups = list.groupBy { dateLabel(it.updatedAt.toLocalDate()) }
            groups.forEach { (label, hits) ->
                item(key = "d-$label") {
                    Text(label, fontSize = 20.sp, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp))
                }
                item(key = "g-$label") {
                    QuartzCard(Modifier.fillMaxWidth()) {
                        hits.forEachIndexed { i, hit ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = LocalQuartzColors.current.divider)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenPage(hit.notebookId, hit.pageId) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ColorDot(Color(hit.notebookColor), size = 12.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(hit.title.ifBlank { "Untitled page" }, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val preview = hit.plainText.lineSequence().firstOrNull { it.isNotBlank() }
                                    if (preview != null) Text(preview, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${hit.notebookTitle} · ${shortTime(hit.updatedAt)}", fontSize = 12.sp, color = LocalQuartzColors.current.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
