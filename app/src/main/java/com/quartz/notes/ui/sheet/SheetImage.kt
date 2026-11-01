package com.quartz.notes.ui.sheet

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.sheet.SheetCalc
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetGrid
import java.io.File
import kotlin.math.ceil

/**
 * Draws the used part of a sheet (with its title above) as a picture, at [scale] pixels per page
 * unit (made smaller for very big sheets). Used for sharing and exporting.
 */
fun renderSheetBitmap(
    data: SheetData,
    title: String,
    paper: Color,
    ink: Color,
    fonts: FontFamily.Resolver,
    scale: Float = 2f,
): ImageBitmap {
    val calc = SheetCalc(data)
    val hidden = SheetGrid.hiddenRows(data, calc)
    val (usedRows, usedCols) = data.usedSize
    val rows = maxOf(usedRows, 1)
    val cols = maxOf(usedCols, 1)

    fun layout(unit: Float): Triple<SheetLook, TextMeasurer, SheetGeometry> {
        val density = Density(unit, 1f)
        val measurer = TextMeasurer(fonts, density, LayoutDirection.Ltr)
        val look = SheetLook(paper = paper, ink = ink, accent = ink, unit = unit, fontSp = SheetSizes.FONT)
        return Triple(look, measurer, sheetGeometry(data, calc, hidden, rows, cols, look, measurer))
    }

    var unit = scale
    var (look, measurer, g) = layout(unit)
    // Keeps the picture to a size phones can hold.
    val biggest = maxOf(g.width, g.height)
    if (biggest > 8000f) {
        unit *= 8000f / biggest
        val again = layout(unit)
        look = again.first
        measurer = again.second
        g = again.third
    }
    val margin = 16f * unit
    val titleLayout = title.trim().takeIf { it.isNotEmpty() }?.let {
        measurer.measure(
            it,
            TextStyle(color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold),
            constraints = Constraints(maxWidth = ceil(g.width).toInt().coerceAtLeast(1)),
        )
    }
    val titleH = titleLayout?.let { it.size.height + 10f * unit } ?: 0f
    val w = ceil(g.width + 2 * margin).toInt()
    val h = ceil(g.height + 2 * margin + titleH).toInt()
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(Density(unit, 1f), LayoutDirection.Ltr, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        drawRect(paper)
        titleLayout?.let { drawText(it, topLeft = Offset(margin, margin)) }
        translate(margin, margin + titleH) {
            drawCells(g, data, calc, measurer, look, 0f, 0f, 0, rows - 1, 0, cols - 1)
            // An outline around the cells.
            drawRect(look.gridLine, Offset.Zero, Size(g.width, g.height), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
        }
    }
    return bitmap
}

/** The sheet as a PNG in the shared cache folder, for sharing. */
fun sheetPng(context: Context, data: SheetData, title: String, paper: Color, ink: Color, fonts: FontFamily.Resolver): File {
    val bitmap = renderSheetBitmap(data, title, paper, ink, fonts).asAndroidBitmap()
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val name = title.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).trim().ifEmpty { "Sheet" }
    val file = File(dir, "$name ${System.currentTimeMillis() / 1000}.png")
    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
