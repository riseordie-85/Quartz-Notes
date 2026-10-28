package com.quartz.notes.ui.home

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Rearranging notebooks on the home screen, and the split view button on wide screens. */
@RunWith(AndroidJUnit4::class)
class HomeArrangeTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container

    private fun screen(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(400)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** A new shelf with three notebooks, selected on the home screen. Returns the shelf id. */
    private fun shelfWithNotebooks(name: String): Long = runBlocking {
        val repo = container.repository
        val shelf = repo.addShelf(name)
        for ((title, color) in listOf("Ant" to 0xFF1E6FE8, "Bee" to 0xFFD6004C, "Cat" to 0xFF2E9E5B)) {
            repo.createNotebook(shelf, title, color.toInt(), "GLOSS", "none")
        }
        container.settings.setSelectedShelf(shelf)
        shelf
    }

    private fun titles(shelf: Long): List<String> = runBlocking { container.repository.observeNotebooks(shelf).first().map { it.title } }

    private fun show(modifier: Modifier = Modifier) {
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(
                LocalAppContainer provides container,
                LocalFontResolver provides FontResolver(BuiltInFonts),
            ) {
                QuartzTheme(settings = settings, dark = false) {
                    Box(modifier) {
                        HomeRoute(onOpenNote = {}, onOpenNotebook = {}, onOpenRecent = {}, onSearch = {}, onSettings = {})
                    }
                }
            }
        }
        rule.waitForIdle()
        SystemClock.sleep(500)
        rule.waitForIdle()
    }

    @Test
    fun holdAndDragANotebookToMoveIt() {
        val shelf = shelfWithNotebooks("Arrange")
        show()
        rule.onNodeWithText("Ant").assertIsDisplayed()
        val step = with(rule.density) { 110.dp.toPx() }
        rule.onNodeWithText("Ant").performTouchInput {
            down(center)
            advanceEventTime(900) // hold
            repeat(12) { moveBy(Offset(step / 12, 0f)) }
            up()
        }
        rule.waitForIdle()
        SystemClock.sleep(600)
        assertEquals(listOf("Bee", "Ant", "Cat"), titles(shelf))
        screen("home_arranged")

        // Holding without dragging still opens the notebook's settings.
        rule.onNodeWithText("Cat").performTouchInput {
            down(center)
            advanceEventTime(900)
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithText("Notebook settings").assertIsDisplayed()
        assertEquals("nothing moved", listOf("Bee", "Ant", "Cat"), titles(shelf))
    }

    @Test
    fun splitViewCanBeTurnedOffOnWideScreens() {
        shelfWithNotebooks("Split")
        runBlocking { container.settings.setSplitView(true) }
        try {
            // Laid out as wide as an unfolded phone.
            show(Modifier.requiredSize(900.dp, 760.dp))
            rule.onNodeWithText("Select a note, or tap + to start one").assertExists()
            rule.onNodeWithContentDescription("Turn off split view").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            SystemClock.sleep(300)
            rule.waitForIdle()
            assertFalse(runBlocking { container.settings.settings.first().splitView })
            rule.onNodeWithContentDescription("Turn on split view").assertExists()
            rule.onNodeWithText("Select a note, or tap + to start one").assertDoesNotExist()

            rule.onNodeWithContentDescription("Turn on split view").performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            SystemClock.sleep(300)
            rule.waitForIdle()
            assertTrue(runBlocking { container.settings.settings.first().splitView })
            rule.onNodeWithText("Select a note, or tap + to start one").assertExists()
        } finally {
            runBlocking { container.settings.setSplitView(true) }
        }
    }

    @Test
    fun noSplitViewButtonOnAPhone() {
        shelfWithNotebooks("Phone")
        show()
        rule.onNodeWithContentDescription("Turn off split view").assertDoesNotExist()
        rule.onNodeWithContentDescription("Turn on split view").assertDoesNotExist()
    }
}
