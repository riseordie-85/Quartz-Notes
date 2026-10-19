package com.quartz.notes.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import com.quartz.notes.data.model.Texture
import kotlin.math.floor
import kotlin.random.Random

/** Procedurally generated, seamlessly tiling texture overlays. */
object TextureCache {
    private const val SIZE = 160
    private val cache = HashMap<Pair<Texture, Boolean>, ImageBitmap?>()

    /** [whiteInk] draws light marks (for dark surfaces) instead of dark ones. */
    @Synchronized
    fun get(texture: Texture, whiteInk: Boolean): ImageBitmap? =
        cache.getOrPut(texture to whiteInk) { generate(texture, whiteInk) }

    private fun generate(texture: Texture, whiteInk: Boolean): ImageBitmap? {
        if (texture == Texture.NONE || texture == Texture.GLOSS) return null
        val rnd = Random(texture.ordinal * 7919 + 17)
        val rgb = if (whiteInk) 0x00FFFFFF else 0x00000000
        fun px(alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or rgb
        val pixels = IntArray(SIZE * SIZE)

        when (texture) {
            Texture.PAPER -> for (i in pixels.indices) {
                val speck = if (rnd.nextInt(90) == 0) 30 else 0
                pixels[i] = px(rnd.nextInt(0, 16) + speck)
            }
            Texture.LINEN -> {
                val rows = IntArray(SIZE) { rnd.nextInt(0, 30) }
                val cols = IntArray(SIZE) { rnd.nextInt(0, 30) }
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    pixels[y * SIZE + x] = px((rows[y] + cols[x]) / 2 + rnd.nextInt(0, 6))
                }
            }
            Texture.CANVAS -> {
                val cell = 4
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val horizontal = ((x / cell) + (y / cell)) % 2 == 0
                    val across = if (horizontal) y % cell else x % cell
                    val edge = if (across == 0 || across == cell - 1) 16 else 0
                    pixels[y * SIZE + x] = px(8 + edge + rnd.nextInt(0, 8))
                }
            }
            Texture.LEATHER -> {
                val grid = 10
                val cellPx = SIZE / grid
                val nodes = Array(grid) { FloatArray(grid) { rnd.nextFloat() } }
                fun smooth(t: Float) = t * t * (3 - 2 * t)
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val gx = x.toFloat() / cellPx
                    val gy = y.toFloat() / cellPx
                    val x0 = floor(gx).toInt() % grid
                    val y0 = floor(gy).toInt() % grid
                    val x1 = (x0 + 1) % grid
                    val y1 = (y0 + 1) % grid
                    val tx = smooth(gx - floor(gx))
                    val ty = smooth(gy - floor(gy))
                    val top = nodes[y0][x0] * (1 - tx) + nodes[y0][x1] * tx
                    val bottom = nodes[y1][x0] * (1 - tx) + nodes[y1][x1] * tx
                    val n = top * (1 - ty) + bottom * ty
                    val crease = if (rnd.nextInt(140) == 0) 25 else 0
                    pixels[y * SIZE + x] = px((n * 34).toInt() + rnd.nextInt(0, 10) + crease)
                }
            }
            Texture.NONE, Texture.GLOSS -> return null
        }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        return bmp.asImageBitmap()
    }
}

@Composable
fun rememberTextureBrush(texture: Texture, whiteInk: Boolean): Brush? = remember(texture, whiteInk) {
    TextureCache.get(texture, whiteInk)?.let { ShaderBrush(ImageShader(it, TileMode.Repeated, TileMode.Repeated)) }
}
