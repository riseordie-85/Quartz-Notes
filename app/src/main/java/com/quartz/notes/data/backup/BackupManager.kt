package com.quartz.notes.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.quartz.notes.data.local.dao.NoteDao
import com.quartz.notes.data.local.entity.FolderEntity
import com.quartz.notes.data.local.entity.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class QuartzFullBackupPayload(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val folders: List<FolderEntity>,
    val notes: List<NoteEntity>
)

class BackupManager(
    private val context: Context,
    private val noteDao: NoteDao
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportJsonSnapshotToStream(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val payload = QuartzFullBackupPayload(
            folders = noteDao.getAllFoldersSync(),
            notes = noteDao.getAllNotesSync()
        )
        outputStream.bufferedWriter().use { writer ->
            writer.write(json.encodeToString(payload))
        }
    }

    suspend fun exportMarkdownZipToStream(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val folders = noteDao.getAllFoldersSync().associateBy { it.id }
        val notes = noteDao.getAllNotesSync()

        ZipOutputStream(outputStream).use { zip ->
            notes.forEach { note ->
                val folderName = folders[note.folderId]?.name?.replace(Regex("[^a-zA-Z0-9_-]"), "_") ?: "Notes"
                val safeTitle = note.title.ifBlank { "Untitled_${note.id}" }
                    .replace(Regex("[^a-zA-Z0-9_ -]"), "")
                    .take(50)

                val entryPath = "$folderName/${safeTitle}_${note.id}.md"
                zip.putNextEntry(ZipEntry(entryPath))

                val markdownFileContent = buildString {
                    appendLine("---")
                    appendLine("id: ${note.id}")
                    appendLine("title: \"${note.title.replace("\"", "\\\"")}\"")
                    appendLine("pinned: ${note.isPinned}")
                    appendLine("gradient: ${note.customGradientId ?: "default"}")
                    appendLine("updated_at: ${note.updatedAt}")
                    appendLine("---")
                    appendLine()
                    appendLine("# ${note.title}")
                    appendLine()
                    appendLine(note.contentMarkdown)
                }
                zip.write(markdownFileContent.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            zip.putNextEntry(ZipEntry("quartz_database_snapshot.json"))
            val payload = QuartzFullBackupPayload(folders = folders.values.toList(), notes = notes)
            zip.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    suspend fun performScheduledDirectoryBackup(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val rootDir = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
        if (!rootDir.canWrite()) return@withContext false

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val fileName = "QuartzNotes_AutoBackup_$timestamp.zip"

        val backupFile = rootDir.createFile("application/zip", fileName) ?: return@withContext false
        context.contentResolver.openOutputStream(backupFile.uri)?.use { stream ->
            exportMarkdownZipToStream(stream)
        }
        true
    }
}
