package com.quartz.notes.ui.notebook

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.StoredImage
import com.quartz.notes.data.model.PageSize
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.home.HomeRoute
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Freeform pages: a set size, zooming, notebook defaults, and text pages offering them. */
@RunWith(AndroidJUnit4::class)
class FreeformTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container
    private val repo get() = container.repository

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

    private fun clickLabel(label: String) = SemanticsMatcher("click label is $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun show(notebookId: Long) {
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = notebookId, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        settle()
    }

    private fun shelf() = runBlocking { repo.observeShelves().first().first().id }

    @Test
    fun freeformNotebooksMakePagesOfTheirSize() = runBlocking {
        val a5 = PageSize.PAPER.first { it.key == PageSize.A5 }
        val nb = repo.createNotebook(shelf(), "Sketches", 0xFF6A3FD0.toInt(), "GLOSS", "none", pageLayout = "SCRAPBOOK", pageSize = a5)
        val first = repo.observePages(nb).first().single()
        assertEquals("SCRAPBOOK", first.layoutMode)
        assertEquals(420f, first.canvasWidth)
        assertEquals(595f, first.canvasHeight)
        assertEquals(PageSize.A5, first.sizeName)

        // + adds the notebook's kind of page; a text page can be picked too.
        val second = repo.insertPage(nb, 0)
        assertEquals(420f, repo.getPage(second)!!.canvasWidth)
        val text = repo.insertPageOfType(nb, 1, "INLINE", null)
        assertEquals("INLINE", repo.getPage(text)!!.layoutMode)
        assertNull(repo.getPage(text)!!.canvasWidth)

        // Turning a page sideways; unfolded-screen pages use both sides of an open notebook.
        repo.setPageType(second, "SCRAPBOOK", a5.rotated())
        repo.getPage(-1) // waits for queued writes
        assertEquals(595f, repo.getPage(second)!!.canvasWidth)
        val wide = repo.insertPageOfType(nb, 2, "SCRAPBOOK", PageSize(PageSize.UNFOLDED, 840f, 700f))
        assertTrue(repo.getPage(wide)!!.spread)
    }

    @Test
    fun aFreeformPageZoomsAndFitsAgain() {
        val nb = runBlocking {
            val id = repo.createNotebook(shelf(), "Zoom test", 0xFF2E9E5B.toInt(), "GLOSS", "none", pageLayout = "SCRAPBOOK", pageSize = PageSize.PAPER.first { it.key == PageSize.A4 })
            val page = repo.observePages(id).first().single()
            repo.savePageTitle(page.id, "Plan")
            repo.getPage(-1)
            id
        }
        show(nb)
        screen("f1_freeform_a4")

        rule.onRoot().performTouchInput {
            pinch(center - Offset(60f, 0f), center + Offset(60f, 0f), center - Offset(260f, 0f), center + Offset(260f, 0f), durationMillis = 500)
        }
        settle()
        rule.onNodeWithText("Fit page").assertExists()
        screen("f2_zoomed")
        rule.onNodeWithText("Fit page").performClick()
        settle()
        rule.onNodeWithText("Fit page").assertDoesNotExist()
    }

    @Test
    fun drawingToolsOnATextPageOfferAFreeformPage() {
        val nb = runBlocking { repo.createNotebook(shelf(), "Text test", 0xFF0050D0.toInt(), "GLOSS", "none") }
        show(nb)
        rule.onNode(clickLabel("Add shape")).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNodeWithText("Shapes and drawing go on Freeform pages").assertExists()
        screen("f3_freeform_offer")
        rule.onNodeWithText("Add a Freeform page").performClick()
        settle()
        val pages = runBlocking { repo.getPage(-1); repo.observePages(nb).first() }
        assertEquals(listOf("INLINE", "SCRAPBOOK"), pages.map { it.layoutMode })
        screen("f4_new_freeform_page")
    }

    @Test
    fun picturesOnATextPageGoInLine() = runBlocking {
        val nb = repo.createNotebook(shelf(), "Inline test", 0, "GLOSS", "none")
        val page = repo.observePages(nb).first().single()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        withContext(Dispatchers.Main) {
            val vm = NotebookViewModel(nb, repo, scope)
            vm.session(page)
            vm.addImage(page.id, StoredImage("media/inline-test.jpg", 400, 300))
        }
        repo.getPage(-1)
        val saved = repo.getPage(page.id)!!
        assertTrue(RichDoc.decode(saved.content).blocks.any { it.type == BlockType.IMAGE && it.image?.path == "media/inline-test.jpg" })
        assertTrue("not a floating item", repo.observeElements(page.id).first().isEmpty())
    }

    @Test
    fun aNewNotebookIsSetUpStepByStep() {
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    HomeRoute(onOpenNote = {}, onOpenNotebook = {}, onOpenRecent = {}, onSearch = {}, onSettings = {})
                }
            }
        }
        settle()
        rule.onNodeWithText("New").performSemanticsAction(SemanticsActions.OnClick) // may be past the edge of the shelf
        settle()
        rule.onNodeWithText("What should your notebook be called?").assertExists()
        rule.onNode(SemanticsMatcher("title field") { it.config.getOrNull(SemanticsActions.SetText) != null }).performTextInput("Wizard test")
        screen("f5_setup_name")
        for (question in listOf("Pick a color", "Choose a cover", "Add an icon", "What kind of pages?")) {
            rule.onNodeWithText("Next").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            rule.onNodeWithText(question).assertExists()
        }
        rule.onNodeWithText("Freeform").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNode(clickLabel("Use A5")).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        screen("f6_setup_pages")
        rule.onNodeWithText("Create").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val nb = runBlocking { repo.observeAllNotebooks().first().first { it.title == "Wizard test" } }
        assertEquals("SCRAPBOOK", nb.pageLayout)
        assertEquals(PageSize.A5, PageSize.decode(nb.pageSize)?.key)
        val first = runBlocking { repo.observePages(nb.id).first().single() }
        assertEquals(420f, first.canvasWidth)
    }
}
