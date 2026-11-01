package com.quartz.notes.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.export.DocPart
import com.quartz.notes.data.export.ExportBuilder
import com.quartz.notes.data.export.ExportFormat
import com.quartz.notes.data.export.ExportPage
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Export files: PDF pages, JPGs, a .zip named after the pages, and sheets as Excel and CSV. */
@RunWith(AndroidJUnit4::class)
class ExportBuilderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(dir: File, name: String, w: Int, h: Int, color: Int): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        return File(dir, name).also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    private fun page(dir: File, title: String, tall: Boolean = false, sheet: SheetData? = null, attachments: List<File> = emptyList()) = ExportPage(
        title = title,
        fallback = "Page",
        text = "$title\n\nSome words.",
        parts = listOf(DocPart.Title(title), DocPart.Rich(RichDoc.fromPlainText("Some words."))),
        picture = picture(dir, "$title-pic.png", 600, if (tall) 2400 else 840, Color.rgb(46, 158, 91)),
        sheet = sheet,
        attachments = attachments,
    )

    private fun pdfPages(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { it.pageCount }
    }

    @Test
    fun combinedPdfAndPerPageZip() {
        val src = File(context.cacheDir, "export-test-src").apply { deleteRecursively(); mkdirs() }
        val out = File(context.cacheDir, "export-test-out").apply { deleteRecursively(); mkdirs() }
        val pages = listOf(page(src, "Kyoto"), page(src, "Kyoto"), page(src, "Long notes", tall = true))
        val (pdf, mime) = ExportBuilder.build(out, "Travel", pages, ExportFormat.PDF, perPage = false, attachments = false) { null }
        assertEquals("application/pdf", mime)
        assertEquals("Travel.pdf", pdf.name)
        // Two page-shaped pages, and a long one over three A4 pages.
        assertEquals(5, pdfPages(pdf))

        val out2 = File(context.cacheDir, "export-test-out2").apply { deleteRecursively(); mkdirs() }
        val photo = picture(src, "photo.jpg", 50, 50, Color.RED)
        val withPhoto = listOf(page(src, "Kyoto", attachments = listOf(photo)), page(src, "Kyoto"), page(src, ""))
        val (zip, zipMime) = ExportBuilder.build(out2, "Travel", withPhoto, ExportFormat.JPG, perPage = true, attachments = true) { null }
        assertEquals("application/zip", zipMime)
        val names = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertEquals(listOf("Kyoto.jpg", "Kyoto (2).jpg", "Page.jpg", "Kyoto (3).jpg"), names)
        ZipFile(zip).use { z ->
            val jpg = z.getInputStream(z.getEntry("Kyoto.jpg")).readBytes()
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpg, 0, jpg.size, opts)
            assertEquals("image/jpeg", opts.outMimeType)
        }
    }

    @Test
    fun wordTextAndSheets() {
        val src = File(context.cacheDir, "export-test-src3").apply { deleteRecursively(); mkdirs() }
        val out = File(context.cacheDir, "export-test-out3").apply { deleteRecursively(); mkdirs() }
        var sheet = SheetData()
        sheet = SheetGrid.typeInto(sheet, 0, 0, "Cost")
        sheet = SheetGrid.typeInto(sheet, 1, 0, "12.5")
        val pages = listOf(page(src, "Notes"), page(src, "Budget", sheet = sheet))
        val (docx, _) = ExportBuilder.build(out, "Book", pages, ExportFormat.WORD, perPage = false, attachments = false) { null }
        assertTrue(ZipFile(docx).use { it.getEntry("word/document.xml") != null })
        val (txts, _) = ExportBuilder.build(out, "Book", pages, ExportFormat.TEXT, perPage = true, attachments = false) { null }
        assertEquals(listOf("Notes.txt", "Budget.txt"), ZipFile(txts).use { z -> z.entries().toList().map { it.name } })
        val (xlsx, xlsxMime) = ExportBuilder.build(out, "Book", pages, ExportFormat.EXCEL, perPage = true, attachments = false) { null }
        assertEquals("Budget.xlsx", xlsx.name)
        assertTrue(xlsxMime.contains("spreadsheetml"))
        val (csv, _) = ExportBuilder.build(out, "Book", pages, ExportFormat.CSV, perPage = true, attachments = false) { null }
        assertEquals("﻿Cost\r\n12.5\r\n", csv.readText())
    }
}
