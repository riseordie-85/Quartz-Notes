package com.quartz.notes.ui.sheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.quartz.notes.data.sheet.SheetCalc
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetStyle
import com.quartz.notes.data.sheet.SheetValue
import com.quartz.notes.data.sheet.TABLE_STYLES
import com.quartz.notes.ui.theme.contentColorFor
import kotlin.math.ceil

/** Sizes in page units (dp at zoom 1). */
internal object SheetSizes {
    const val ROW = 30f
    const val HEADER_H = 26f
    const val HEADER_W = 44f
    const val PAD = 6f
    const val FONT = 14f
}

/** Where each column and row starts, in pixels from the grid's top-left (the last entry is the end). */
internal class SheetGeometry(val colX: FloatArray, val rowY: FloatArray) {
    val cols: Int get() = colX.size - 1
    val rows: Int get() = rowY.size - 1
    val width: Float get() = colX.last()
    val height: Float get() = rowY.last()

    fun colAt(x: Float): Int = indexAt(colX, x)
    fun rowAt(y: Float): Int = indexAt(rowY, y)

    /** The last index whose start is at or before [v] (so rows of no height are passed over). */
    private fun indexAt(edges: FloatArray, v: Float): Int {
        var lo = 0
        var hi = edges.size - 2
        if (v <= edges[0]) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (edges[mid] <= v) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** Colors and type for drawing a sheet. */
internal class SheetLook(
    val paper: Color,
    val ink: Color,
    val accent: Color,
    /** Pixels per page unit. */
    val unit: Float,
    /** Font size in sp for the cell text. */
    val fontSp: Float,
) {
    val gridLine = ink.copy(alpha = 0.13f)
    val headerFill = ink.copy(alpha = 0.05f)
    val headerText = ink.copy(alpha = 0.55f)
    val pad = SheetSizes.PAD * unit
}

internal fun parseHex(s: String?): Color? {
    if (s == null || !s.startsWith("#") || s.length != 7) return null
    val v = s.substring(1).toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

internal fun Color.hex(): String {
    val argb = (red * 255).toInt() shl 16 or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
    return "#" + argb.toString(16).padStart(6, '0').uppercase()
}

/** What a cell looks like once its own style, the table look and the page are taken into account. */
internal class CellLook(val fill: Color?, val text: Color, val bold: Boolean)

internal fun cellLook(data: SheetData, r: Int, c: Int, s: SheetStyle, look: SheetLook): CellLook {
    val table = data.table?.let { TABLE_STYLES[it] }
    val used = data.valuedSize
    val inTable = table != null && r < used.first && c < used.second
    val header = inTable && r == 0
    val tableFill = when {
        !inTable -> null
        header -> Color(table!!.header)
        r % 2 == 0 -> Color(table!!.band)
        else -> Color.White
    }
    val fill = parseHex(s.fill) ?: tableFill
    val text = parseHex(s.color) ?: when {
        header && s.fill == null -> Color.White
        fill != null -> contentColorFor(fill)
        else -> look.ink
    }
    return CellLook(fill, text, s.b || header)
}

internal fun textStyleFor(s: SheetStyle, cell: CellLook, look: SheetLook): TextStyle = TextStyle(
    color = cell.text,
    fontSize = androidx.compose.ui.unit.TextUnit(look.fontSp, androidx.compose.ui.unit.TextUnitType.Sp),
    fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (s.i) FontStyle.Italic else FontStyle.Normal,
    textDecoration = if (s.u) TextDecoration.Underline else null,
)

/** Which way a value sits in its cell when no alignment is set: numbers right, true/false and errors centered. */
internal fun alignOf(s: SheetStyle, v: SheetValue): String = s.align ?: when (v) {
    is SheetValue.Num -> "right"
    is SheetValue.Bool, is SheetValue.Err -> "center"
    else -> "left"
}

/** Lays out the rows and columns: widths from the sheet, row heights grown for wrapped text, filtered rows hidden. */
internal fun sheetGeometry(
    data: SheetData,
    calc: SheetCalc,
    hidden: Set<Int>,
    rows: Int,
    cols: Int,
    look: SheetLook,
    measurer: TextMeasurer,
    widthOverride: Pair<Int, Float>? = null,
): SheetGeometry {
    val unit = look.unit
    val colX = FloatArray(cols + 1)
    for (c in 0 until cols) {
        val w = if (widthOverride?.first == c) widthOverride.second else data.width(c)
        colX[c + 1] = colX[c] + w * unit
    }
    val heights = FloatArray(rows) { SheetSizes.ROW * unit }
    for ((key, cell) in data.cells) {
        if (!cell.s.wrap || cell.v == null) continue
        val at = com.quartz.notes.data.sheet.parseAddr(key) ?: continue
        val (r, c) = at
        if (r >= rows || c >= cols) continue
        val text = calc.display(r, c)
        if (text.isEmpty()) continue
        val width = (colX[c + 1] - colX[c] - 2 * look.pad).coerceAtLeast(1f)
        val style = textStyleFor(cell.s, cellLook(data, r, c, cell.s, look), look)
        val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = width.toInt()))
        heights[r] = maxOf(heights[r], layout.size.height + 2 * look.pad)
    }
    val rowY = FloatArray(rows + 1)
    for (r in 0 until rows) rowY[r + 1] = rowY[r] + if (r in hidden) 0f else heights[r]
    return SheetGeometry(colX, rowY)
}

/**
 * Draws cells [r0]..[r1], [c0]..[c1] with the grid's point ([left], [top]) at the drawing's origin:
 * fills, grid lines, text (long text runs on into empty cells) and borders.
 */
internal fun DrawScope.drawCells(
    g: SheetGeometry,
    data: SheetData,
    calc: SheetCalc,
    measurer: TextMeasurer,
    look: SheetLook,
    left: Float,
    top: Float,
    r0: Int,
    r1: Int,
    c0: Int,
    c1: Int,
    gridLines: Boolean = true,
) {
    fun x(c: Int) = g.colX[c] - left
    fun y(r: Int) = g.rowY[r] - top

    // Fills.
    for (r in r0..r1) {
        val h = g.rowY[r + 1] - g.rowY[r]
        if (h <= 0f) continue
        for (c in c0..c1) {
            val s = data.cell(r, c)?.s ?: SheetStyle.NONE
            val cl = cellLook(data, r, c, s, look)
            if (cl.fill != null) drawRect(cl.fill, Offset(x(c), y(r)), Size(g.colX[c + 1] - g.colX[c], h))
        }
    }
    // Grid lines.
    if (gridLines) {
        val stroke = maxOf(1f, look.unit * 0.6f)
        val bottom = y(r1 + 1)
        val right = x(c1 + 1)
        for (c in c0..c1 + 1) drawLine(look.gridLine, Offset(x(c), y(r0)), Offset(x(c), bottom), stroke)
        for (r in r0..r1 + 1) {
            if (r <= r1 && g.rowY[r + 1] == g.rowY[r]) continue
            drawLine(look.gridLine, Offset(x(c0), y(r)), Offset(right, y(r)), stroke)
        }
    }
    // Text.
    val pad = look.pad
    for (r in r0..r1) {
        val top0 = y(r)
        val h = g.rowY[r + 1] - g.rowY[r]
        if (h <= 0f) continue
        for (c in c0..c1) {
            val text = calc.display(r, c)
            if (text.isEmpty()) continue
            val s = data.cell(r, c)?.s ?: SheetStyle.NONE
            val v = calc.value(r, c)
            val cl = cellLook(data, r, c, s, look)
            val style = textStyleFor(s, cl, look)
            val cellLeft = x(c)
            val cellW = g.colX[c + 1] - g.colX[c]
            if (s.wrap) {
                val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = (cellW - 2 * pad).coerceAtLeast(1f).toInt()))
                val tx = alignedX(alignOf(s, v), cellLeft, cellW, layout, pad)
                clipRect(cellLeft, top0, cellLeft + cellW, top0 + h) {
                    drawText(layout, topLeft = Offset(tx, top0 + (h - layout.size.height) / 2))
                }
                continue
            }
            val layout = measurer.measure(text, style, softWrap = false, maxLines = 1, overflow = TextOverflow.Clip)
            val align = alignOf(s, v)
            // Text that's too long runs on into empty cells beside it (not numbers).
            var clipRight = cellLeft + cellW
            if (align == "left" && v !is SheetValue.Num && layout.size.width + pad > cellW) {
                var next = c + 1
                while (next < g.cols && calc.display(r, next).isEmpty() && clipRight < cellLeft + layout.size.width + 2 * pad) {
                    clipRight = x(next + 1)
                    next++
                }
            }
            val tx = alignedX(align, cellLeft, cellW, layout, pad)
            clipRect(cellLeft, top0, clipRight, top0 + h) {
                drawText(layout, topLeft = Offset(tx, top0 + (h - layout.size.height) / 2))
            }
        }
    }
    // Borders.
    val bw = maxOf(1.5f, look.unit * 1.2f)
    for (r in r0..r1) {
        if (g.rowY[r + 1] == g.rowY[r]) continue
        for (c in c0..c1) {
            val sides = data.cell(r, c)?.s?.border ?: continue
            val l = x(c)
            val t = y(r)
            val rr = x(c + 1)
            val b = y(r + 1)
            if ('t' in sides) drawLine(look.ink, Offset(l, t), Offset(rr, t), bw)
            if ('b' in sides) drawLine(look.ink, Offset(l, b), Offset(rr, b), bw)
            if ('l' in sides) drawLine(look.ink, Offset(l, t), Offset(l, b), bw)
            if ('r' in sides) drawLine(look.ink, Offset(rr, t), Offset(rr, b), bw)
        }
    }
}

private fun alignedX(align: String, left: Float, width: Float, layout: TextLayoutResult, pad: Float): Float = when (align) {
    "right" -> left + width - pad - layout.size.width
    "center" -> left + (width - layout.size.width) / 2
    else -> left + pad
}

/** The used part of a sheet drawn as a picture (for sharing and export): its size and how to draw it. */
internal fun sheetPictureSize(g: SheetGeometry, data: SheetData): Size {
    val (rows, cols) = data.usedSize
    return Size(ceil(g.colX[maxOf(cols, 1)]), ceil(g.rowY[maxOf(rows, 1)]))
}
