package com.quartz.notes.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

data class FontInfo(
    val id: String,
    val name: String,
    val family: FontFamily,
    val builtIn: Boolean,
    val file: File? = null,
)

/** Built-in font choices. Imported fonts are added after these. */
val BuiltInFonts = listOf(
    FontInfo("sans", "Sans", FontFamily.SansSerif, builtIn = true),
    FontInfo("serif", "Serif", FontFamily.Serif, builtIn = true),
    FontInfo("mono", "Mono", FontFamily.Monospace, builtIn = true),
    FontInfo("cursive", "Script", FontFamily.Cursive, builtIn = true),
)

/** Manages user-imported .ttf/.otf fonts stored in the app's private files. */
class FontRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "fonts").apply { mkdirs() }
    private val _fonts = MutableStateFlow(load())
    val fonts: StateFlow<List<FontInfo>> = _fonts.asStateFlow()

    private fun load(): List<FontInfo> {
        val imported = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file ->
                runCatching {
                    FontInfo(
                        id = "file:${file.name}",
                        name = file.nameWithoutExtension,
                        family = FontFamily(Font(file)),
                        builtIn = false,
                        file = file,
                    )
                }.getOrNull()
            }
            .orEmpty()
        return BuiltInFonts + imported
    }

    /** Copies a font file into app storage after checking that Android can read it. */
    suspend fun import(uri: Uri): Result<FontInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "font.ttf"
            val ext = displayName.substringAfterLast('.', "ttf").lowercase().let { if (it == "otf") "otf" else "ttf" }
            val base = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Font" }
            var target = File(dir, "$base.$ext")
            var n = 2
            while (target.exists()) target = File(dir, "$base $n.$ext").also { n++ }

            val temp = File(dir, ".import.tmp")
            resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("Couldn't open that file")
            val typeface = runCatching { Typeface.Builder(temp).build() }.getOrNull()
            if (typeface == null) {
                temp.delete()
                error("That file isn't a font Android can read")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            _fonts.value = load()
            _fonts.value.first { it.file == target }
        }
    }

    /** Adds font files from a restored backup (existing names are kept). */
    suspend fun installFrom(files: List<File>) = withContext(Dispatchers.IO) {
        files.filter { it.extension.lowercase() in setOf("ttf", "otf") }.forEach { f ->
            val target = File(dir, f.name)
            if (!target.exists()) f.copyTo(target)
        }
        _fonts.value = load()
    }

    suspend fun delete(font: FontInfo) = withContext(Dispatchers.IO) {
        font.file?.delete()
        _fonts.value = load()
    }

    /** Picks up font files that arrived by sync. */
    suspend fun reload() = withContext(Dispatchers.IO) {
        _fonts.value = load()
    }
}
