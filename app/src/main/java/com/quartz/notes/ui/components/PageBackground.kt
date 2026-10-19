package com.quartz.notes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.quartz.notes.data.model.PageType

/**
 * Draws a page's background color, texture and ruling behind the content.
 * Rulings repeat every [spacingPx], starting [topOffsetPx] from the top, so text with a matching
 * line height sits on the lines.
 */
fun Modifier.pageBackground(
    background: Color,
    texture: Brush?,
    type: PageType,
    lineColor: Color,
    spacingPx: Float,
    topOffsetPx: Float,
    horizontalMargin: Dp = 18.dp,
): Modifier = drawBehind {
    drawRect(background)
    texture?.let { drawRect(it) }
    drawRuling(type, lineColor, spacingPx, topOffsetPx, horizontalMargin.toPx())
}

fun DrawScope.drawRuling(type: PageType, lineColor: Color, spacing: Float, topOffset: Float, margin: Float) {
    if (spacing <= 1f) return
    val stroke = 1.2.dp.toPx()
    when (type) {
        PageType.PLAIN -> Unit
        PageType.LINES -> {
            var y = topOffset + spacing
            while (y < size.height) {
                drawLine(lineColor, Offset(margin, y), Offset(size.width - margin, y), strokeWidth = stroke)
                y += spacing
            }
        }
        PageType.DOTTED -> {
            val r = 1.6.dp.toPx()
            var y = topOffset + spacing
            while (y < size.height) {
                var x = margin
                while (x <= size.width - margin + 0.5f) {
                    drawCircle(lineColor, radius = r, center = Offset(x, y))
                    x += spacing
                }
                y += spacing
            }
        }
        PageType.GRID -> {
            val grid = lineColor.copy(alpha = lineColor.alpha * 0.75f)
            var y = topOffset + spacing
            while (y < size.height) {
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke * 0.8f)
                y += spacing
            }
            var x = margin
            while (x < size.width) {
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke * 0.8f)
                x += spacing
            }
        }
    }
}

/** A tiny preview of a page ruling, used in pickers. */
@androidx.compose.runtime.Composable
fun PageTypePreview(type: PageType, background: Color, lineColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(background)
        drawRuling(type, lineColor, 9.dp.toPx(), 0f, 6.dp.toPx())
    }
}
