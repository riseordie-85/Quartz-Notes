package com.quartz.notes.ui.export

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.data.export.ExportFormat
import com.quartz.notes.ui.components.QuartzSheet
import com.quartz.notes.ui.theme.LocalQuartzColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What to export as, and where it goes. */
data class ExportChoice(
    val format: ExportFormat,
    /** Several pages: a file for each (in a .zip) instead of one document. */
    val perPage: Boolean,
    /** Pictures attached to the pages (or note) go along, named after their page. */
    val attachments: Boolean,
    /** Save to a folder (otherwise share). */
    val save: Boolean,
)

/**
 * Choosing how to export: the format, one document or a file per page (for several pages), whether
 * attached pictures go along, then Share or Save.
 */
@OptIn(ExperimentalLayoutApi::class)
@androidx.compose.runtime.Composable
fun ExportSheet(
    title: String,
    formats: List<ExportFormat>,
    /** More than one page is being exported. */
    pages: Boolean,
    hasAttachments: Boolean,
    onExport: (ExportChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by rememberSaveable { mutableStateOf(formats.first()) }
    var perPage by rememberSaveable { mutableStateOf(false) }
    var attachments by rememberSaveable { mutableStateOf(false) }
    val muted = LocalQuartzColors.current.textSecondary
    QuartzSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("Format", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = muted, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                formats.forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { format = f },
                        label = { Text(if (f == ExportFormat.TEXT || f == ExportFormat.CSV) "${f.label} (.${f.ext})" else f.label) },
                    )
                }
            }
            if (pages) {
                if (format.combines) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        SegmentedButton(selected = !perPage, onClick = { perPage = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("One document") }
                        SegmentedButton(selected = perPage, onClick = { perPage = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("A file per page") }
                    }
                }
                val note = when {
                    format == ExportFormat.PNG || format == ExportFormat.JPG -> "Each page becomes a picture named after the page, together in a .zip file."
                    format == ExportFormat.EXCEL || format == ExportFormat.CSV -> "Each sheet page becomes a file named after the page."
                    perPage -> "Each page becomes a file named after the page, together in a .zip file."
                    else -> "All the pages in one file, each starting on a new page."
                }
                Text(note, fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 8.dp))
            }
            if (hasAttachments) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Attached pictures", fontSize = 16.sp)
                        Text("In a .zip file with the rest, each named after its page", fontSize = 13.sp, color = muted)
                    }
                    Switch(checked = attachments, onCheckedChange = { attachments = it })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onExport(ExportChoice(format, perPage, attachments, save = true)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.SaveAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save to…")
                }
                Button(onClick = { onExport(ExportChoice(format, perPage, attachments, save = false)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }
}

/** Shown while an export is being made. */
@Composable
fun ExportProgressDialog(message: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        title = { Text("Exporting") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        },
    )
}

/** A folder in the shared cache for one export's files (old ones are cleared away). */
fun exportFolder(context: Context): File {
    val shared = File(context.cacheDir, "shared").apply { mkdirs() }
    shared.listFiles()?.forEach { if (it.name.startsWith("export-") && System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000) it.deleteRecursively() }
    return File(shared, "export-${System.currentTimeMillis()}").apply { mkdirs() }
}

/** Shares a finished export. */
fun shareExport(context: Context, file: File, mime: String) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}

/** "Save to…": the system's file picker, for a name and type. */
private class CreateFile : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** Saves files where the person picks (through the system's file picker). */
class FileSaver internal constructor(private val launch: (File, String, String) -> Unit) {
    fun save(file: File, name: String, mime: String) = launch(file, name, mime)
}

@Composable
fun rememberFileSaver(): FileSaver {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateOf<File?>(null) }
    val launcher = rememberLauncherForActivityResult(CreateFile()) { uri ->
        val file = pending.value
        pending.value = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("No output")
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Saved ${file.name}" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
        }
    }
    return remember(launcher) {
        FileSaver { file, name, mime ->
            pending.value = file
            launcher.launch(name to mime)
        }
    }
}
