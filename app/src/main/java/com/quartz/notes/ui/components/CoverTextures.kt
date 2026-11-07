package com.quartz.notes.ui.components

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import com.quartz.notes.data.model.Texture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Notebook cover materials as seamless tiles: a height map for each (threads, pebbles, fibres), lit
 * from the top left so it reads as raised and sunken, and kept as white highlights and dark shadows
 * over the cover's colour. Sized in dp, so the grain is the same size on every screen.
 */
object CoverTextures {
    /** A tile is this many dp square. */
    private const val TILE_DP = 96f

    private val cache = HashMap<Pair<Texture, Int>, Bitmap?>()

    /** The tile for [texture] at [density] (pixels per dp), or null for none and gloss. */
    @Synchronized
    fun get(texture: Texture, density: Float): Bitmap? {
        val key = texture to (density * 4).roundToInt()
        return cache.getOrPut(key) { generate(texture, density) }
    }

    private class Look(val height: FloatArray, val light: Float, val dark: Float, val relief: Float)

    private fun generate(texture: Texture, density: Float): Bitmap? {
        val n = (TILE_DP * density).roundToInt().coerceIn(48, 512)
        val d = n / TILE_DP
        val look = when (texture) {
            Texture.LINEN -> Look(linen(n, d), 0.24f, 0.30f, 0.8f)
            Texture.CANVAS -> Look(canvas(n, d), 0.26f, 0.32f, 0.9f)
            Texture.PAPER -> Look(paper(n, d), 0.22f, 0.24f, 1.6f)
            Texture.LEATHER -> Look(leather(n, d), 0.24f, 0.36f, 1.3f)
            Texture.NONE, Texture.GLOSS -> return null
        }
        return shade(n, look)
    }

    // ---- The materials ----

    /** Fine threads both ways, each a little thicker or thinner than the next, with slubs along them. */
    private fun linen(n: Int, d: Float): FloatArray {
        val (count, period) = spacing(n, 1.5f * d)
        val rnd = Random(7)
        val weightRows = FloatArray(count) { 0.5f + 0.5f * rnd.nextFloat() }
        val weightCols = FloatArray(count) { 0.5f + 0.5f * rnd.nextFloat() }
        val slubRows = streaks(n, count, 11, 40)
        val slubCols = streaks(n, count, 23, 40)
        val profile = FloatArray(n) { sin(((it % period) / period) * PI.toFloat()).pow(1.5f) }
        val mottle = fractal(n, 6, 3, 3)
        val h = FloatArray(n * n)
        for (y in 0 until n) {
            val ry = (y / period).toInt() % count
            for (x in 0 until n) {
                val cx = (x / period).toInt() % count
                val sr = slubRows[ry * n + x]
                val sc = slubCols[cx * n + y]
                val row = profile[y] * weightRows[ry] * (0.4f + 1.2f * sr * sr)
                val col = profile[x] * weightCols[cx] * (0.4f + 1.2f * sc * sc)
                h[y * n + x] = row + col * 0.85f + 0.2f * mottle[y * n + x]
            }
        }
        return h
    }

    /** A coarser plain weave: threads passing over and under each other. */
    private fun canvas(n: Int, d: Float): FloatArray {
        val (_, cell) = spacing(n, 2.4f * d)
        val coarse = fractal(n, 8, 3, 9)
        val fine = fractal(n, 32, 2, 13)
        val h = FloatArray(n * n)
        val pi = PI.toFloat()
        for (y in 0 until n) {
            val i = (y / cell).toInt()
            val fy = (y % cell) / cell
            for (x in 0 until n) {
                val j = (x / cell).toInt()
                val fx = (x % cell) / cell
                val v = if ((i + j) % 2 == 0) {
                    sin(fy * pi).pow(0.7f) * (0.75f + 0.25f * sin(fx * pi))
                } else {
                    sin(fx * pi).pow(0.7f) * (0.75f + 0.25f * sin(fy * pi))
                }
                h[y * n + x] = v * 0.8f + 0.2f * coarse[y * n + x] + 0.12f * fine[y * n + x]
            }
        }
        return h
    }

    /** Soft mottling with short fibres pressed into it. */
    private fun paper(n: Int, d: Float): FloatArray {
        val broad = fractal(n, 12, 4, 31)
        val fine = fractal(n, 48, 2, 37)
        val fibres = fibres(n, d)
        return FloatArray(n * n) { 0.6f * broad[it] + 0.4f * fine[it] + 0.9f * fibres[it] }
    }

    /** Pebble grain: rounded pebbles about 4dp across with creases between them. */
    private fun leather(n: Int, d: Float): FloatArray {
        val (g, cell) = spacing(n, 4f * d)
        val rnd = Random(53)
        // One pebble centre per grid cell, jittered, so they're even without looking gridded.
        val px = FloatArray(g * g)
        val py = FloatArray(g * g)
        for (i in 0 until g) for (j in 0 until g) {
            py[i * g + j] = (i + 0.15f + 0.7f * rnd.nextFloat()) * cell
            px[i * g + j] = (j + 0.15f + 0.7f * rnd.nextFloat()) * cell
        }
        val broad = fractal(n, 3, 3, 59)
        val fine = fractal(n, 24, 2, 61)
        val h = FloatArray(n * n)
        for (y in 0 until n) {
            val ci = (y / cell).toInt()
            for (x in 0 until n) {
                val cj = (x / cell).toInt()
                var f1 = Float.MAX_VALUE
                var f2 = Float.MAX_VALUE
                for (di in -1..1) for (dj in -1..1) {
                    // Neighbouring cells, wrapping round the tile.
                    var i = ci + di
                    var j = cj + dj
                    var oy = 0f
                    var ox = 0f
                    if (i < 0) { i += g; oy = -n.toFloat() } else if (i >= g) { i -= g; oy = n.toFloat() }
                    if (j < 0) { j += g; ox = -n.toFloat() } else if (j >= g) { j -= g; ox = n.toFloat() }
                    val dy = y - (py[i * g + j] + oy)
                    val dx = x - (px[i * g + j] + ox)
                    val dist = sqrt(dx * dx + dy * dy)
                    if (dist < f1) {
                        f2 = f1
                        f1 = dist
                    } else if (dist < f2) {
                        f2 = dist
                    }
                }
                val e = ((f2 - f1) / (cell * 0.45f)).coerceIn(0f, 1f)
                h[y * n + x] = e * e * (3 - 2 * e) + 0.25f * broad[y * n + x] + 0.12f * fine[y * n + x]
            }
        }
        return h
    }

    // ---- Building blocks ----

    /** How many repeats of about [target] px fit the tile exactly, and their exact size. */
    private fun spacing(n: Int, target: Float): Pair<Int, Float> {
        val count = max(1, (n / target).roundToInt())
        return count to n.toFloat() / count
    }

    /** Smooth random values on a [cells]×[cells] lattice, wrapping round the tile. */
    private fun valueNoise(n: Int, cells: Int, seed: Int): FloatArray {
        val rnd = Random(seed)
        val grid = FloatArray(cells * cells) { rnd.nextFloat() }
        val i0 = IntArray(n)
        val i1 = IntArray(n)
        val t = FloatArray(n)
        for (k in 0 until n) {
            val p = k.toFloat() * cells / n
            val f = floor(p)
            i0[k] = f.toInt() % cells
            i1[k] = (i0[k] + 1) % cells
            val u = p - f
            t[k] = u * u * (3 - 2 * u)
        }
        val out = FloatArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val a = grid[i0[y] * cells + i0[x]]
            val b = grid[i0[y] * cells + i1[x]]
            val c = grid[i1[y] * cells + i0[x]]
            val e = grid[i1[y] * cells + i1[x]]
            val top = a + (b - a) * t[x]
            val bottom = c + (e - c) * t[x]
            out[y * n + x] = top + (bottom - top) * t[y]
        }
        return out
    }

    /** Several octaves of [valueNoise], from [base] cells across. */
    private fun fractal(n: Int, base: Int, octaves: Int, seed: Int): FloatArray {
        val out = FloatArray(n * n)
        var amp = 1f
        var total = 0f
        for (o in 0 until octaves) {
            val layer = valueNoise(n, min(n, base shl o), seed + o)
            for (k in out.indices) out[k] += amp * layer[k]
            total += amp
            amp *= 0.5f
        }
        for (k in out.indices) out[k] /= total
        return out
    }

    /** One smooth random line of [n] values per thread: thicker and thinner stretches along it. */
    private fun streaks(n: Int, count: Int, seed: Int, cells: Int): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(count * n)
        for (k in 0 until count) {
            val g = FloatArray(cells) { rnd.nextFloat() }
            for (x in 0 until n) {
                val p = x.toFloat() * cells / n
                val f = floor(p)
                val a = f.toInt() % cells
                val b = (a + 1) % cells
                val u = p - f
                val s = u * u * (3 - 2 * u)
                out[k * n + x] = g[a] + (g[b] - g[a]) * s
            }
        }
        return out
    }

    /** Short wavy fibres, softened, drawn so they wrap round the tile's edges. */
    private fun fibres(n: Int, d: Float): FloatArray {
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.9f * d
            strokeCap = Paint.Cap.ROUND
            maskFilter = BlurMaskFilter(0.6f * d, BlurMaskFilter.Blur.NORMAL)
        }
        val rnd = Random(41)
        val count = (n.toFloat() * n / (40f * d * 40f * d) * 6).roundToInt().coerceAtLeast(4)
        repeat(count) {
            val x = rnd.nextFloat() * n
            val y = rnd.nextFloat() * n
            val angle = rnd.nextFloat() * PI.toFloat()
            val length = (4f + 6f * rnd.nextFloat()) * d
            val path = Path()
            val steps = 12
            for (s in 0..steps) {
                val t = length * s / steps
                val px = x + cos(angle) * t
                val py = y + sin(angle) * t + sin(t / (3f * d)) * d
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            paint.alpha = (150 + rnd.nextInt(105))
            for (oy in -1..1) for (ox in -1..1) {
                canvas.save()
                canvas.translate(ox * n.toFloat(), oy * n.toFloat())
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }
        val alpha = IntArray(n * n)
        bmp.getPixels(alpha, 0, n, 0, 0, n, n)
        bmp.recycle()
        return FloatArray(n * n) { ((alpha[it] ushr 24) and 0xFF) / 255f }
    }

    /** Lights the height map from the top left: highlights white, shadows dark, in one tile. */
    private fun shade(n: Int, look: Look): Bitmap {
        val h = look.height
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (v in h) {
            lo = min(lo, v)
            hi = max(hi, v)
        }
        val range = max(1e-6f, hi - lo)
        for (k in h.indices) h[k] = (h[k] - lo) / range
        val pixels = IntArray(n * n)
        for (y in 0 until n) {
            val up = ((y - 1 + n) % n) * n
            val down = ((y + 1) % n) * n
            for (x in 0 until n) {
                val left = (x - 1 + n) % n
                val right = (x + 1) % n
                val dx = h[y * n + right] - h[y * n + left]
                val dy = h[down + x] - h[up + x]
                val s = -(dx + dy) * look.relief * 2.2f + (h[y * n + x] - 0.5f) * 0.5f
                pixels[y * n + x] = if (s >= 0) {
                    (((s.coerceAtMost(1f) * look.light) * 255).toInt() shl 24) or 0xFFFFFF
                } else {
                    ((((-s).coerceAtMost(1f) * look.dark) * 255).toInt() shl 24)
                }
            }
        }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
    }
}

/** The cover material for [texture] as a repeating brush, at this screen's density. */
@Composable
fun rememberCoverTexture(texture: Texture): Brush? {
    val density = LocalDensity.current.density
    return remember(texture, density) {
        CoverTextures.get(texture, density)?.let { ShaderBrush(ImageShader(it.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)) }
    }
}
