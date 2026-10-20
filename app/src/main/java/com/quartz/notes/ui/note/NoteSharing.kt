package com.quartz.notes.ui.note

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.ui.graphics.toArgb
import com.quartz.notes.data.rich.Align
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.theme.NoteColor
import com.quartz.notes.ui.theme.contentColorFor
import com.quartz.notes.ui.theme.parseHexColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** Copies a rendered note picture into the gallery (Pictures/Quartz Notes). No permission needed. */
suspend fun saveImageToGallery(context: Context, file: File, displayName: String): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$displayName.png")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_PICTURES}/Quartz Notes")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val uri = resolver.insert(collection, values) ?: return@withContext false
    try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("No output")
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (t: Throwable) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Plain-text version of a note, with bullets and checkboxes. */
fun noteAsText(title: String, doc: RichDoc): String = buildString {
    if (title.isNotBlank()) {
        append(title.trim())
        append("\n\n")
    }
    doc.blocks.forEach { b ->
        append(
            when (b.type) {
                BlockType.BULLET -> "• ${b.text}"
                BlockType.TASK -> (if (b.checked) "☑ " else "☐ ") + b.text
                else -> b.text
            },
        )
        append('\n')
    }
}.trimEnd()

fun shareText(context: Context, title: String, text: String, chooserTitle: String = "Share note") {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

fun shareFile(context: Context, file: File, mime: String, authority: String, chooserTitle: String = "Share note") {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooserTitle))
}

/**
 * Draws a note as a picture: its color, title, formatted text and pictures, like a card.
 * Returns a PNG file in the shared cache folder.
 */
suspend fun renderNoteImage(
    context: Context,
    title: String,
    doc: RichDoc,
    colorKey: String,
    pictures: List<File>,
): File = withContext(Dispatchers.Default) {
    val width = 1080
    val pad = 72f
    val contentWidth = (width - pad * 2).toInt()
    val bg = NoteColor.of(colorKey).light
    val ink = contentColorFor(bg).toArgb()
    val density = context.resources.displayMetrics.scaledDensity.coerceAtLeast(2.5f)

    fun paint(sizeSp: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textSize = sizeSp * density
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun layout(text: CharSequence, p: TextPaint, w: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(text, 0, text.length, p, w).setAlignment(align).setLineSpacing(0f, 1.15f).build()

    // Lay everything out first to know the height.
    data class Piece(val layout: StaticLayout, val indent: Float, val marker: String?, val markerPaint: TextPaint?, val gapAfter: Float)
    val pieces = ArrayList<Piece>()
    if (title.isNotBlank()) pieces += Piece(layout(title.trim(), paint(26f, bold = true), contentWidth), 0f, null, null, 28f)
    doc.blocks.forEach { b ->
        val (p, indent, marker) = when (b.type) {
            BlockType.HEADING -> Triple(paint(23f, bold = true), 0f, null)
            BlockType.SUBHEADING -> Triple(paint(19f, bold = true), 0f, null)
            BlockType.BULLET -> Triple(paint(16f), 56f, "•")
            BlockType.TASK -> Triple(paint(16f), 64f, if (b.checked) "☑" else "☐")
            BlockType.BODY, BlockType.IMAGE -> Triple(paint(16f), 0f, null)
        }
        val text = SpannableStringBuilder(b.text.ifEmpty { " " })
        b.spans.forEach { sp ->
            val s = sp.start.coerceIn(0, b.text.length)
            val e = sp.end.coerceIn(0, b.text.length)
            if (s >= e) return@forEach
            val span: Any? = when (sp.style) {
                InlineStyle.BOLD -> StyleSpan(Typeface.BOLD)
                InlineStyle.ITALIC -> StyleSpan(Typeface.ITALIC)
                InlineStyle.UNDERLINE -> UnderlineSpan()
                InlineStyle.STRIKE -> StrikethroughSpan()
                InlineStyle.HIGHLIGHT -> BackgroundColorSpan((parseHexColor(sp.value) ?: androidx.compose.ui.graphics.Color(0xFFFFE14D)).toArgb())
                InlineStyle.COLOR -> parseHexColor(sp.value)?.let { ForegroundColorSpan(it.toArgb()) }
                InlineStyle.FONT -> null
            }
            if (span != null) text.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (b.type == BlockType.TASK && b.checked) {
            text.setSpan(StrikethroughSpan(), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan((ink and 0x00FFFFFF) or (0x80 shl 24)), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val align = when (b.align) {
            Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            Align.END -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        pieces += Piece(layout(text, p, (contentWidth - indent).toInt(), align), indent, marker, if (marker != null) p else null, 10f)
    }

    // Pictures: decoded down to the card width, at most six.
    // ImageDecoder applies camera (EXIF) rotation, unlike BitmapFactory.
    val images = pictures.take(6).mapNotNull { f ->
        runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(f)) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                if (w > contentWidth) {
                    decoder.setTargetSize(contentWidth, (info.size.height.toLong() * contentWidth / w).toInt().coerceAtLeast(1))
                }
            }
        }.getOrNull()
    }
    val imageHeights = images.map { bmp -> min(contentWidth * bmp.height / bmp.width, 1400) }

    val textHeight = pieces.sumOf { (it.layout.height + it.gapAfter).toDouble() }.toFloat()
    val imagesHeight = imageHeights.sumOf { it + 36 }.toFloat()
    val height = (pad * 2 + textHeight + (if (images.isNotEmpty()) 24f else 0f) + imagesHeight).toInt().coerceAtMost(12_000)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(bg.toArgb())
    var y = pad
    pieces.forEach { piece ->
        if (piece.marker != null && piece.markerPaint != null) {
            val baseline = y + piece.layout.getLineBaseline(0)
            canvas.drawText(piece.marker, pad + 6f, baseline, piece.markerPaint)
        }
        canvas.save()
        canvas.translate(pad + piece.indent, y)
        piece.layout.draw(canvas)
        canvas.restore()
        y += piece.layout.height + piece.gapAfter
    }
    if (images.isNotEmpty()) y += 24f
    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    images.forEachIndexed { i, bmp ->
        val h = imageHeights[i]
        val dest = RectF(pad, y, pad + contentWidth, y + h)
        // Center-crop very tall pictures to the capped height.
        val srcH = min(bmp.height, bmp.width * h / contentWidth)
        val srcTop = (bmp.height - srcH) / 2
        canvas.drawBitmap(bmp, android.graphics.Rect(0, srcTop, bmp.width, srcTop + srcH), dest, imagePaint)
        y += h + 36
        bmp.recycle()
    }

    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000) it.delete() }
    val file = File(dir, "${title.ifBlank { "Note" }.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).trim().ifEmpty { "Note" }}.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    file
}
