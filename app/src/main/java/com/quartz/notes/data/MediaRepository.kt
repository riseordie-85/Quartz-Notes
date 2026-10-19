package com.quartz.notes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** An image copied into app storage. [path] is relative to the app's files directory. */
data class StoredImage(val path: String, val width: Int, val height: Int) {
    val aspect: Float get() = if (width > 0) height.toFloat() / width else 1f
}

/**
 * Owns the app's media folder (pictures and voice recordings). Files are stored under
 * filesDir/media and referenced everywhere by their relative path, e.g. "media/abc.jpg".
 */
class MediaRepository(context: Context) {
    private val appContext = context.applicationContext
    val filesDir: File = appContext.filesDir
    private val mediaDir = File(filesDir, MEDIA_DIR).apply { mkdirs() }

    fun file(path: String): File = File(filesDir, path)

    /** Copies an image into app storage, optionally downscaling and re-encoding it to save space. */
    suspend fun importImage(uri: Uri, compress: Boolean): Result<StoredImage> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val mime = resolver.getType(uri).orEmpty()
            if (compress) {
                val source = ImageDecoder.createSource(resolver, uri)
                var hasAlpha = false
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val scale = MAX_EDGE.toFloat() / max(w, h)
                    if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt().coerceAtLeast(1), (h * scale).roundToInt().coerceAtLeast(1))
                    hasAlpha = mime == "image/png" || mime == "image/webp"
                }
                val keepAlpha = hasAlpha && bitmap.hasAlpha()
                val ext = if (keepAlpha) "png" else "jpg"
                val name = "$MEDIA_DIR/${UUID.randomUUID()}.$ext"
                File(filesDir, name).outputStream().use { out ->
                    bitmap.compress(if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
                }
                StoredImage(name, bitmap.width, bitmap.height).also { bitmap.recycle() }
            } else {
                val ext = when {
                    mime.contains("png") -> "png"
                    mime.contains("webp") -> "webp"
                    mime.contains("gif") -> "gif"
                    mime.contains("heic") || mime.contains("heif") -> "heic"
                    else -> "jpg"
                }
                val name = "$MEDIA_DIR/${UUID.randomUUID()}.$ext"
                val target = File(filesDir, name)
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("Couldn't open that picture")
                val (w, h) = imageSize(target)
                StoredImage(name, w, h)
            }
        }
    }

    /** Pixel size of a stored image, accounting for EXIF rotation. */
    fun imageSize(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var w = opts.outWidth
        var h = opts.outHeight
        val rotation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        if (rotation == ExifInterface.ORIENTATION_ROTATE_90 || rotation == ExifInterface.ORIENTATION_ROTATE_270 ||
            rotation == ExifInterface.ORIENTATION_TRANSPOSE || rotation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            val t = w
            w = h
            h = t
        }
        return (w.coerceAtLeast(1)) to (h.coerceAtLeast(1))
    }

    /** A new, empty file for a voice recording. */
    fun newRecordingPath(): String = "$MEDIA_DIR/${UUID.randomUUID()}.m4a"

    /** A temporary file the camera app can write a photo into, plus a shareable Uri for it. */
    fun newCameraTarget(): Pair<File, Uri> {
        val dir = File(appContext.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        return file to uriFor(file)
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)

    fun durationMs(path: String): Long = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(file(path).absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    fun delete(path: String) {
        runCatching { file(path).delete() }
    }

    /** Deletes media files nothing refers to any more. Recent files are kept in case they're mid-save. */
    suspend fun deleteUnreferenced(referenced: Set<String>) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        mediaDir.listFiles()?.forEach { f ->
            val rel = "$MEDIA_DIR/${f.name}"
            if (rel !in referenced && f.lastModified() < cutoff) f.delete()
        }
        File(appContext.cacheDir, "camera").listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    fun allMediaFiles(): List<File> = mediaDir.listFiles()?.filter { it.isFile }.orEmpty()

    companion object {
        const val MEDIA_DIR = "media"
        private const val MAX_EDGE = 2048
    }
}
