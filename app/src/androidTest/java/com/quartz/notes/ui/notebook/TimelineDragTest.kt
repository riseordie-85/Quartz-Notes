package com.quartz.notes.ui.notebook

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Touch and hold a page in the timeline, then drag it sideways to move it. */
@RunWith(AndroidJUnit4::class)
class TimelineDragTest {
    @get:Rule
    val rule = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container

    @Test
    fun dragAPageAlongTheTimeline() {
        val repo = container.repository
        val notebookId = runBlocking {
            val shelf = repo.observeShelves().first().first()
            val id = repo.createNotebook(shelf.id, "Timeline test", 0xFF5B6CFF.toInt(), "GLOSS", "none")
            repo.insertPage(id, 0)
            repo.insertPage(id, 1)
            repo.observePages(id).first().zip(listOf("One", "Two", "Three")).forEach { (p, t) -> repo.savePageTitle(p.id, t) }
            repo.getPage(-1) // waits for the saves
            id
        }
        fun titles() = runBlocking { repo.getPage(-1); repo.observePages(notebookId).first().map { it.title } }
        assertEquals(listOf("One", "Two", "Three"), titles())

        val settings = runBlocking { container.settings.settings.first() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    val vm = androidx.compose.runtime.remember { NotebookViewModel(notebookId, repo, scope) }
                    val pages by repo.observePages(notebookId).collectAsState(initial = emptyList())
                    if (pages.isNotEmpty()) {
                        PageTimelineSheet(
                            pages = pages,
                            showing = emptySet(),
                            vm = vm,
                            onOpen = {},
                            onManage = {},
                            onMove = { _, _ -> },
                            onMoveTo = { page, to ->
                                val from = pages.indexOfFirst { it.id == page.id }
                                if (from >= 0 && from != to) vm.movePage(from, to)
                            },
                            onStartSection = {},
                            onDelete = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        SystemClock.sleep(800)
        rule.waitForIdle()

        // Thumbnails are 108dp wide plus 16dp: move "One" a little over one place to the right.
        val step = with(rule.density) { 140.dp.toPx() }
        rule.onAllNodesWithText("One").onFirst().performTouchInput {
            down(center)
            advanceEventTime(900)
            repeat(14) { moveBy(Offset(step / 14, 0f)) }
            up()
        }
        rule.waitForIdle()
        SystemClock.sleep(800)
        assertEquals(listOf("Two", "One", "Three"), titles())

        // Holding without dragging opens the page's menu instead.
        rule.onAllNodesWithText("Three").onFirst().performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Move earlier").assertExists()
        assertEquals(listOf("Two", "One", "Three"), titles())
    }
}
