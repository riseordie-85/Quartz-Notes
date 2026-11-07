package com.quartz.notes.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.model.Texture
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The cover materials: made quickly, seamless, and visible on light and dark covers alike. */
@RunWith(AndroidJUnit4::class)
class CoverTexturesTest {
    @get:Rule
    val rule = createComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun eachMaterialIsQuickAndSeamless() {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        for (t in listOf(Texture.LINEN, Texture.CANVAS, Texture.PAPER, Texture.LEATHER)) {
            val start = SystemClock.elapsedRealtime()
            val tile = CoverTextures.get(t, density)
            val took = SystemClock.elapsedRealtime() - start
            assertNotNull(tile)
            assertTrue("$t took ${took}ms", took < 1500)
            // Across the tile's edge, neighbouring pixels differ no more than neighbours inside it do,
            // so the repeats don't show seams.
            val n = tile!!.width
            fun v(x: Int, y: Int): Int {
                val p = tile.getPixel(x, y)
                val a = p ushr 24
                return if (p and 0xFFFFFF != 0) a else -a
            }
            var seam = 0L
            var inside = 0L
            for (k in 0 until n) {
                seam += kotlin.math.abs(v(0, k) - v(n - 1, k)) + kotlin.math.abs(v(k, 0) - v(k, n - 1))
                inside += kotlin.math.abs(v(n / 2, k) - v(n / 2 - 1, k)) + kotlin.math.abs(v(k, n / 2) - v(k, n / 2 - 1))
            }
            assertTrue("$t: seam $seam, inside $inside", seam <= inside * 2 + n * 4)
        }
    }

    @Test
    fun coversShowTheirMaterial() {
        val colors = listOf(Color(0xFF0050D0), Color(0xFFD6004C), Color(0xFF2E9E5B), Color(0xFF795548), Color(0xFFE68A1E), Color(0xFF212121), Color(0xFFC9A227), Color(0xFFEDE7DA))
        val textures = listOf(Texture.LINEN, Texture.CANVAS, Texture.PAPER, Texture.LEATHER)
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFFEFEFF1)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (row in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (i in 0 until 4) {
                            val k = row * 4 + i
                            NotebookCover(
                                title = textures[k % 4].label,
                                color = colors[k],
                                texture = textures[k % 4],
                                icon = null,
                                modifier = Modifier.width(84.dp),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        SystemClock.sleep(600)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "cover_textures.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
