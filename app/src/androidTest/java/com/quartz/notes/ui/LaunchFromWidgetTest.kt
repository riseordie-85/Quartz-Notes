package com.quartz.notes.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quartz.notes.LaunchRequest
import com.quartz.notes.Launches
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the app does with a tap on one of its widgets. */
@RunWith(AndroidJUnit4::class)
class LaunchFromWidgetTest {
    @get:Rule
    val rule = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container

    private fun noteIds(): Set<Long> = runBlocking { container.repository.observeNotes().first().map { it.id }.toSet() }

    @Test
    fun theQuickNotesPlusMakesANoteAndOpensIt() {
        val before = noteIds()
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalFontResolver provides FontResolver(BuiltInFonts),
            ) {
                QuartzTheme(settings = settings, dark = false) { QuartzNavHost() }
            }
        }
        rule.waitForIdle()

        // The + on the quick notes widget.
        Launches.pending.value = LaunchRequest.NewNote

        // The note editor opens on a new note (its Share button is only on the editor).
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Share").fetchSemanticsNodes().isNotEmpty() }
        // Let the move to the editor finish: closing the test window part-way through trips up navigation.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
        val made = noteIds() - before
        assertEquals("one new note: $made", 1, made.size)
    }
}
