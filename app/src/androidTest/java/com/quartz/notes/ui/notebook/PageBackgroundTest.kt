package com.quartz.notes.ui.notebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.BuiltInFonts
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.BrushType
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.PageSize
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.ui.editor.FontResolver
import com.quartz.notes.ui.editor.LocalFontResolver
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The background picture window (fit, adjust, turn) and the pencil's grain. */
@RunWith(AndroidJUnit4::class)
class PageBackgroundTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = ApplicationProvider.getApplicationContext<QuartzApp>().container
    private val repo get() = container.repository

    private fun screen(name: String) {
        rule.waitForIdle()
        SystemClock.sleep(700)
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

    /** A wide picture with an arrow pointing up, so turning it shows. */
    private fun widePicture(): String {
        val path = "media/wide-arrow.png"
        val file = container.media.file(path).apply { parentFile?.mkdirs() }
        val bmp = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, 900f, 600f, Paint().apply { shader = LinearGradient(0f, 0f, 900f, 0f, 0xFF9AD0F5.toInt(), 0xFFF5C69A.toInt(), Shader.TileMode.CLAMP) })
        val arrow = Path().apply {
            moveTo(450f, 80f); lineTo(620f, 300f); lineTo(510f, 300f); lineTo(510f, 520f); lineTo(390f, 520f); lineTo(390f, 300f); lineTo(280f, 300f); close()
        }
        c.drawPath(arrow, Paint().apply { color = 0xFF1A3D8F.toInt(); isAntiAlias = true })
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return path
    }

    @Test
    fun pictureWindowFitsAndTurnsThePicture() {
        val picture = widePicture()
        val (nb, pageId) = runBlocking {
            val shelf = repo.observeShelves().first().first().id
            val nb = repo.createNotebook(shelf, "Wallpaper test", 0xFF2E9E5B.toInt(), "GLOSS", "none", pageLayout = "SCRAPBOOK", pageSize = PageSize.PAPER.first { it.key == PageSize.A5 })
            val page = repo.observePages(nb).first().single()
            repo.updatePageStyle(page.copy(pageType = "PLAIN", backgroundImage = picture))
            // Pencil lines, thin and thick, to see the grain.
            listOf(2f to 0.95f, 14f to 1.1f).forEach { (size, y) ->
                repo.addElement(
                    PageElementEntity(
                        pageId = page.id, kind = ElementKind.STROKE, x = 0.1f, y = y, width = 0.8f, height = 0.05f,
                        data = StrokeData(points = listOf(0.1f, y, 0.3f, y + 0.05f, 0.5f, y, 0.7f, y + 0.05f, 0.9f, y), color = 0xFF333333.toInt(), tool = BrushType.PENCIL, size = size).encodePayload(),
                    ),
                )
            }
            repo.getPage(-1)
            nb to page.id
        }
        val settings = runBlocking { container.settings.settings.first() }
        rule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalFontResolver provides FontResolver(BuiltInFonts)) {
                QuartzTheme(settings = settings, dark = false) {
                    NotebookScreen(notebookId = nb, initialPageId = null, onBack = {}, onSearch = {})
                }
            }
        }
        settle()
        screen("p1_fitted_and_pencil")

        rule.onNode(clickLabel("Page style")).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNode(hasText("Fit, position and turn…")).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        rule.onNodeWithContentDescription("Turn left").assertExists()
        rule.onNodeWithText("Fill").performClick()
        rule.onNodeWithContentDescription("Turn right").performClick()
        settle()
        screen("p2_window_fill_turned")
        rule.onNodeWithText("Adjust").performClick()
        rule.onNodeWithContentDescription("Bigger").performClick()
        settle()
        screen("p3_window_adjust")
        rule.onNodeWithText("Fill").performClick()
        rule.onNodeWithText("Save").performClick()
        settle()
        val saved = runBlocking { repo.getPage(-1); repo.getPage(pageId)!!.backgroundFit }
        assertEquals("FILL@90", saved)
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        settle()
        screen("p4_page_fill_turned")
    }
}
