package com.quartz.notes.ui.templates

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.notebook.NotebookScreen
import com.quartz.notes.ui.settings.SettingsScreen
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Templates and background pictures on screen (screenshots go to the shots folder). */
@RunWith(AndroidJUnit4::class)
class TemplatesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container

    private fun screen(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun settle() {
        rule.waitForIdle()
        SystemClock.sleep(700)
        rule.waitForIdle()
    }

    /** A made-up planner sheet: a tall picture with ruled boxes. */
    private fun plannerPicture(): String {
        val path = "media/planner-test.png"
        val file = container.media.file(path).apply { parentFile?.mkdirs() }
        val bmp = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 900f, 0xFFFFF4D6.toInt(), 0xFFFFE0E6.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, 600f, 900f, p)
        val line = Paint().apply { color = 0xFFD6004C.toInt(); strokeWidth = 3f; style = Paint.Style.STROKE }
        for (i in 0 until 6) c.drawRect(40f, 140f + i * 120f, 560f, 240f + i * 120f, line)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return path
    }

    @Test
    fun templatesAndBackgroundPictures() {
        val repo = container.repository
        val picture = plannerPicture()
        val notebookId = runBlocking {
            val shelf = repo.observeShelves().first().first()
            val id = repo.createNotebook(shelf.id, "Template test", 0xFF2E9E5B.toInt(), "GLOSS", "none")
            val page = repo.observePages(id).first().first()
            repo.savePageTitle(page.id, "Week plan")
            repo.savePageContent(page.id, RichDoc.fromPlainText("Goals for the week"))
            repo.updatePageStyle(page.copy(pageType = "PLAIN", backgroundImage = picture))
            repo.getPage(-1)
            repo.savePageAsTemplate(page.id, "My planner")
            id
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = notebookId, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        settle()
        screen("t1_page_background_picture")

        // Hold + : blank page, section, saved templates and starters.
        rule.onNodeWithContentDescription("Add page (hold for sections and templates)").performTouchInput { longClick() }
        settle()
        rule.onNodeWithText("Add a page").assertExists()
        rule.onNodeWithText("My planner").assertExists()
        rule.onNodeWithText("Meeting notes").assertExists()
        screen("t2_add_page_sheet")

        rule.onNodeWithText("Meeting notes").performClick()
        settle()
        val pages = runBlocking { repo.observePages(notebookId).first() }
        assertEquals(listOf("Week plan", "Meeting"), pages.map { it.title })
        assertTrue(RichDoc.decode(pages[1].content).blocks.any { it.text == "Action items" })
        screen("t3_page_from_starter")

        // The page style sheet: background pictures and saving as a template.
        rule.onNodeWithContentDescription("Page style").performClick()
        settle()
        rule.onAllNodesWithText("Background picture").onFirst().assertExists()
        rule.onNodeWithText("Save page as template").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNodeWithText("Save as template").assertExists()
        screen("t4_save_as_template")
    }

    @Test
    fun settingsListsTemplates() {
        val repo = container.repository
        runBlocking {
            val note = repo.createNote()
            repo.saveNote(note, "Standup", RichDoc.fromPlainText("Yesterday\nToday"), "blue")
            repo.getNote(-1)
            repo.saveNoteAsTemplate(note, "Standup notes")
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    SettingsScreen(onBack = {}, onOpenNote = {})
                }
            }
        }
        settle()
        rule.onNodeWithText("Templates").performClick()
        settle()
        rule.onNodeWithText("Standup notes").assertExists()
        screen("t5_settings_templates")
    }
}
