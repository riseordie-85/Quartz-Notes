package com.quartz.notes.widget

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.TextureCache

/**
 * Notebook covers as pictures, for widgets (which can't run the app's drawing code): the same board,
 * texture, spine, sheen, title and icon as [com.quartz.notes.ui.components.NotebookCover].
 */
object CoverBitmaps {
    /** Width over height, as in the app. */
    const val ASPECT = 0.685f

    /** Room left round the cover for its shadow, as a share of the cover's width. */
    const val MARGIN = 0.05f

    /**
     * A cover [widthPx] wide (plus its shadow margin on every side). [showTitle] false leaves the title off,
     * for when it's written beside the cover instead.
     */
    fun render(nb: NotebookEntity, widthPx: Int, showTitle: Boolean = true): Bitmap =
        render(
            title = if (showTitle) nb.title else "",
            color = nb.colorArgb,
            texture = enumOrDefault(nb.texture, Texture.GLOSS),
            icon = NotebookIcons.of(nb.icon),
            widthPx = widthPx,
        )

    fun render(title: String, color: Int, texture: Texture, icon: ImageVector?, widthPx: Int): Bitmap {
        val w = widthPx.coerceIn(24, 600).toFloat()
        val h = w / ASPECT
        val m = w * MARGIN
        val bmp = Bitmap.createBitmap((w + m * 2).toInt(), (h + m * 2).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(m, m)

        // The board: square-ish at the spine, rounded at the open edge, with a soft shadow.
        val small = w * 0.04f
        val big = w * 0.2f
        val shape = Path().apply {
            addRoundRect(RectF(0f, 0f, w, h), floatArrayOf(small, small, big, big, big, big, small, small), Path.Direction.CW)
        }
        c.drawPath(
            shape,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                setShadowLayer(m * 0.8f, 0f, m * 0.35f, 0x55000000)
            },
        )
        c.save()
        c.clipPath(shape)
        c.drawColor(color)
        TextureCache.get(texture, whiteInk = false)?.asAndroidBitmap()?.let { tile ->
            c.drawRect(0f, 0f, w, h, Paint().apply { shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) })
        }
        val spine = w * 0.075f
        c.drawRect(0f, 0f, spine, h, Paint().apply { this.color = 0x24000000 })
        val line = (w / 110f).coerceAtLeast(1f)
        c.drawRect(spine, 0f, spine + line, h, Paint().apply { this.color = 0x38FFFFFF })

        // The sheen across the top, stronger on gloss.
        val sheen = Path().apply {
            moveTo(spine, 0f)
            lineTo(w, 0f)
            lineTo(w, h * 0.46f)
            lineTo(spine, h * 0.62f)
            close()
        }
        val strength = if (texture == Texture.GLOSS) 0.9f else 0.35f
        c.save()
        c.clipPath(sheen)
        c.drawRect(
            0f, 0f, w, h,
            Paint().apply {
                shader = RadialGradient(w * 0.5f, h * 0.52f, w * 0.8f, intArrayOf(white(strength), white(0f)), null, Shader.TileMode.CLAMP)
            },
        )
        c.restore()
        if (texture == Texture.GLOSS) {
            c.drawLine(spine, h * 0.62f, w, h * 0.46f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = white(0.35f); strokeWidth = line })
        }

        // Title and icon, centered on the board (right of the spine).
        val left = w * 0.1f
        val right = w * 0.96f
        var y = w * 0.3f
        if (title.isNotBlank()) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.WHITE
                textSize = (w * 0.12f).coerceAtLeast(9f)
                typeface = Typeface.create(Typeface.DEFAULT, 500, false)
                setShadowLayer(w * 0.03f, 0f, w * 0.012f, 0x59000000)
            }
            val width = (right - left).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.05f)
                .build()
            c.save()
            c.translate(left, y)
            layout.draw(c)
            c.restore()
            y += layout.height + w * 0.05f
        }
        if (icon != null) {
            val size = w * 0.3f
            c.save()
            c.translate(left + (right - left - size) / 2, y)
            drawVector(c, icon, size, android.graphics.Color.WHITE)
            c.restore()
        }
        c.restore()
        return bmp
    }

    /** Draws an icon [size] square at the canvas origin, every path filled with [tint]. */
    fun drawVector(c: Canvas, icon: ImageVector, size: Float, tint: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }
        c.save()
        c.scale(size / icon.viewportWidth, size / icon.viewportHeight)
        drawGroup(c, icon.root, paint)
        c.restore()
    }

    /** An icon on its own, for widget buttons. */
    fun icon(icon: ImageVector, sizePx: Int, tint: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx.coerceAtLeast(1), sizePx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        drawVector(Canvas(bmp), icon, sizePx.toFloat(), tint)
        return bmp
    }

    private fun drawGroup(c: Canvas, group: VectorGroup, paint: Paint) {
        c.save()
        c.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        c.rotate(group.rotation)
        c.scale(group.scaleX, group.scaleY)
        c.translate(-group.pivotX, -group.pivotY)
        for (node in group) {
            when (node) {
                is VectorGroup -> drawGroup(c, node, paint)
                is VectorPath -> {
                    if (node.fill == null && node.stroke == null) continue
                    val path = node.pathData.toPath().asAndroidPath()
                    path.fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                    val alpha = (node.fillAlpha * 255).toInt().coerceIn(0, 255)
                    if (node.fill != null) {
                        paint.style = Paint.Style.FILL
                        paint.alpha = alpha
                        c.drawPath(path, paint)
                    }
                    if (node.stroke != null && node.strokeLineWidth > 0f) {
                        val stroke = Paint(paint).apply {
                            style = Paint.Style.STROKE
                            strokeWidth = node.strokeLineWidth
                            this.alpha = (node.strokeAlpha * 255).toInt().coerceIn(0, 255)
                        }
                        c.drawPath(path, stroke)
                    }
                    paint.alpha = 255
                }
            }
        }
        c.restore()
    }

    private fun white(alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0xFFFFFF
}
