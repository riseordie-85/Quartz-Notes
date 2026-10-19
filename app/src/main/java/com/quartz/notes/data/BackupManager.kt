package com.quartz.notes.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.model.elementMediaPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Everything in a backup's data.json. */
@Serializable
data class BackupData(
    val format: Int = 1,
    val exportedAt: Long,
    val shelves: List<ShelfEntity>,
    val notebooks: List<NotebookEntity>,
    val pages: List<PageEntity>,
    val elements: List<PageElementEntity>,
    val notes: List<QuickNoteEntity>,
    val attachments: List<AttachmentEntity>,
)

data class ImportSummary(val notebooks: Int, val pages: Int, val notes: Int) {
    fun describe(): String = buildList {
        add(plural(notes, "quick note"))
        add(plural(notebooks, "notebook"))
        add(plural(pages, "page"))
    }.joinToString(", ")

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
}

/** Info about the newest backup found in the automatic backup folder. */
data class FoundBackup(val uri: Uri, val name: String, val modified: Long)

/**
 * Backups are plain .zip files: data.json plus the media and fonts folders. They can be restored
 * after a reinstall or on another device.
 */
class BackupManager(
    context: Context,
    private val repo: QuartzRepository,
    private val media: MediaRepository,
    private val fonts: FontRepository,
) {
    private val appContext = context.applicationContext
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Writes a complete backup zip to [out]. */
    suspend fun export(out: OutputStream) = withContext(Dispatchers.IO) {
        val data = repo.snapshot()
        val mediaPaths = (
            data.attachments.map { it.path } +
                data.elements.flatMap { QuartzRepository.elementMediaPaths(it.kind, it.data) } +
                data.pages.flatMap { com.quartz.notes.data.rich.RichDoc.decode(it.content).mediaPaths() }
            ).toSet()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(json.encodeToString(data).toByteArray())
            zip.closeEntry()
            mediaPaths.forEach { path ->
                val f = media.file(path)
                if (f.isFile) {
                    zip.putNextEntry(ZipEntry(path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            fonts.fonts.value.mapNotNull { it.file }.forEach { f ->
                zip.putNextEntry(ZipEntry("fonts/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Restores a backup zip. See [QuartzRepository.importBackup] for [replace]. */
    suspend fun restore(input: InputStream, replace: Boolean): ImportSummary = withContext(Dispatchers.IO) {
        val staging = File(appContext.cacheDir, "restore").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            var data: BackupData? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        entry.isDirectory -> Unit
                        name == DATA_ENTRY -> data = json.decodeFromString<BackupData>(zip.readBytes().decodeToString())
                        SAFE_ENTRY.matches(name) -> {
                            val target = File(staging, name)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val backup = data ?: error("That file isn't a Quartz Notes backup")

            // Media and font files have unique names; copy any we don't already have.
            File(staging, MediaRepository.MEDIA_DIR).listFiles()?.forEach { f ->
                val target = media.file("${MediaRepository.MEDIA_DIR}/${f.name}")
                if (!target.exists()) f.copyTo(target)
            }
            val fontFiles = File(staging, "fonts").listFiles().orEmpty().toList()
            val summary = repo.importBackup(backup, replace)
            fonts.installFrom(fontFiles)
            summary
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun restore(uri: Uri, replace: Boolean): ImportSummary =
        appContext.contentResolver.openInputStream(uri)?.use { restore(it, replace) } ?: error("Couldn't open that file")

    suspend fun export(uri: Uri) {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { export(it) } ?: error("Couldn't write to that file")
    }

    /**
     * Writes a dated backup into the automatic backup folder and keeps only the newest [keep] backups.
     * Skips the write when nothing changed since [lastFingerprint]. Returns the new fingerprint, or null if skipped.
     */
    suspend fun backupToFolder(treeUri: Uri, keep: Int, lastFingerprint: String?): String? = withContext(Dispatchers.IO) {
        val fingerprint = fingerprint()
        if (fingerprint == lastFingerprint) return@withContext null
        val folder = DocumentFile.fromTreeUri(appContext, treeUri) ?: error("Backup folder is no longer available")
        if (!folder.canWrite()) error("Quartz Notes can't write to the backup folder any more")
        val name = "$AUTO_PREFIX${SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())}.zip"
        folder.findFile(name)?.delete()
        val file = folder.createFile("application/zip", name) ?: error("Couldn't create a file in the backup folder")
        try {
            appContext.contentResolver.openOutputStream(file.uri, "wt")?.use { export(it) } ?: error("Couldn't write the backup")
        } catch (t: Throwable) {
            // Never leave a half-written zip that would look like the newest backup.
            file.delete()
            throw t
        }
        folder.listFiles()
            .filter { it.name?.startsWith(AUTO_PREFIX) == true && it.name?.endsWith(".zip") == true }
            .sortedByDescending { it.name }
            .drop(keep.coerceAtLeast(1))
            .forEach { it.delete() }
        fingerprint
    }

    /** Changes whenever anything that goes into a backup changes. */
    private suspend fun fingerprint(): String {
        val data = repo.snapshot().copy(exportedAt = 0)
        val fontNames = fonts.fonts.value.mapNotNull { it.file?.name }.sorted().joinToString(",")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(json.encodeToString(data).toByteArray())
        digest.update(fontNames.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The newest automatic backup in [treeUri], if any. */
    suspend fun newestIn(treeUri: Uri): FoundBackup? = withContext(Dispatchers.IO) {
        val folder = DocumentFile.fromTreeUri(appContext, treeUri) ?: return@withContext null
        folder.listFiles()
            .filter { it.name?.startsWith(AUTO_PREFIX) == true && it.name?.endsWith(".zip") == true }
            .maxByOrNull { it.name.orEmpty() }
            ?.let { FoundBackup(it.uri, it.name.orEmpty(), it.lastModified()) }
    }

    companion object {
        private const val DATA_ENTRY = "data.json"
        const val AUTO_PREFIX = "QuartzNotes-"
        private val SAFE_ENTRY = Regex("^(media|fonts)/[A-Za-z0-9 _-][A-Za-z0-9 ._-]*$")

        fun suggestedFileName(): String =
            "Quartz Notes backup ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.zip"
    }
}
