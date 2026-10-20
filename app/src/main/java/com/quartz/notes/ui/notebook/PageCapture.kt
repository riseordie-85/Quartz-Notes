package com.quartz.notes.ui.notebook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Picture
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.draw
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Makes a picture of a page for "share as image". The page's content records itself on the next
 * draw (all of it, even the part scrolled off screen); while that happens [exporting] is true so the
 * page can hide its controls and hints.
 */
@Stable
class PageCapture {
    var exporting by mutableStateOf(false)
        private set
    private var request by mutableStateOf<CompletableDeferred<Picture>?>(null)

    /** Records the content it's applied to, on [background] (and [texture]), when a capture is asked for. */
    fun modifier(background: () -> Color, texture: () -> Brush?): Modifier = Modifier.drawWithContent {
        val pending = request
        if (pending != null && !pending.isCompleted && size.width >= 1f && size.height >= 1f) {
            val picture = Picture()
            val canvas = androidx.compose.ui.graphics.Canvas(picture.beginRecording(size.width.toInt(), size.height.toInt()))
            draw(this, layoutDirection, canvas, size) {
                drawRect(background())
                texture()?.let { drawRect(it) }
                this@drawWithContent.drawContent()
            }
            picture.endRecording()
            pending.complete(picture)
        }
        drawContent()
    }

    /** Captures the page and writes it as a PNG in the shared cache folder. Call from a UI coroutine. */
    suspend fun toPng(context: Context, title: String, prepare: () -> Unit = {}): File {
        prepare()
        exporting = true
        val picture = try {
            // Let the page redraw without handles, cursor and controls.
            withFrameNanos { }
            withFrameNanos { }
            val pending = CompletableDeferred<Picture>()
            request = pending
            withTimeout(3000) { pending.await() }
        } finally {
            request = null
            exporting = false
        }
        return withContext(Dispatchers.Default) {
            val w = picture.width
            val h = picture.height
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val target = android.graphics.Canvas(out)
            // Rendered in strips: a long page can be taller than the GPU allows in one go.
            val strip = 2048
            var top = 0
            while (top < h) {
                val sh = minOf(strip, h - top)
                val part = Picture()
                part.beginRecording(w, sh).apply {
                    translate(0f, -top.toFloat())
                    drawPicture(picture)
                }
                part.endRecording()
                val hw = Bitmap.createBitmap(part, w, sh, Bitmap.Config.ARGB_8888)
                val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                target.drawBitmap(sw, 0f, top.toFloat(), null)
                sw.recycle()
                hw.recycle()
                top += sh
            }
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000) it.delete() }
            val name = title.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).trim().ifEmpty { "Page" }
            val file = File(dir, "$name ${System.currentTimeMillis() / 1000}.png")
            file.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.recycle()
            file
        }
    }
}
