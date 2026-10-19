package com.quartz.notes.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.AppContainer
import com.quartz.notes.data.AppSettings
import com.quartz.notes.data.BackupManager
import com.quartz.notes.data.BackupWorker
import com.quartz.notes.data.FoundBackup
import com.quartz.notes.ui.components.ListRow
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.shortTime
import com.quartz.notes.ui.theme.LocalQuartzColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class BackupViewModel(private val container: AppContainer) : ViewModel() {
    private val _busy = MutableStateFlow<String?>(null)
    /** Non-null while an export or restore is running; the text describes it. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    fun export(uri: Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        _busy.value = "Exporting…"
        runCatching { container.backup.export(uri) }
            .onSuccess { onResult("Backup saved") }
            .onFailure { onResult(it.message ?: "Export failed") }
        _busy.value = null
    }

    fun restore(uri: Uri, replace: Boolean, onResult: (String) -> Unit) = viewModelScope.launch {
        _busy.value = "Restoring…"
        runCatching { container.backup.restore(uri, replace) }
            .onSuccess { onResult("Restored ${it.describe()}") }
            .onFailure { onResult(it.message ?: "Restore failed") }
        _busy.value = null
    }

    fun setAutoBackup(enabled: Boolean) = viewModelScope.launch { container.settings.setAutoBackup(enabled) }
    fun setInterval(days: Int) = viewModelScope.launch { container.settings.setBackupInterval(days) }
    fun setKeep(count: Int) = viewModelScope.launch { container.settings.setBackupKeep(count) }

    /** Saves the chosen folder, turns automatic backups on, and reports any backup already in it. */
    fun chooseFolder(uri: Uri, onFound: (FoundBackup) -> Unit) = viewModelScope.launch {
        container.settings.setBackupFolder(uri.toString())
        container.settings.setAutoBackup(true)
        container.settings.clearBackupFingerprint()
        runCatching { container.backup.newestIn(uri) }.getOrNull()?.let(onFound)
    }
}

private sealed interface BackupDialog {
    data class ChooseRestoreMode(val uri: Uri) : BackupDialog
    data class FoundInFolder(val backup: FoundBackup) : BackupDialog
}

/** Settings card for export, restore and automatic backups. */
@Composable
fun BackupSection(settings: AppSettings, snackbar: SnackbarHostState) {
    val vm = quartzViewModel { BackupViewModel(it) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    val show: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri, show)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialog = BackupDialog.ChooseRestoreMode(uri)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.chooseFolder(uri) { found -> dialog = BackupDialog.FoundInFolder(found) }
        }
    }
    val folderName = remember(settings.backupFolder) {
        settings.backupFolder?.let { runCatching { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name }.getOrNull() }
    }
    val divider = LocalQuartzColors.current.divider

    QuartzCard(Modifier.fillMaxWidth()) {
        ListRow(
            title = "Export backup",
            subtitle = "Save everything (notes, notebooks, pictures, voice notes, fonts) to one file",
            onClick = { if (busy == null) exportLauncher.launch(BackupManager.suggestedFileName()) },
            leading = { Icon(Icons.Rounded.Backup, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = if (busy != null) ({ CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }) else null,
        )
        HorizontalDivider(color = divider)
        ListRow(
            title = "Restore from backup",
            subtitle = "Open a Quartz Notes backup file",
            onClick = { if (busy == null) restoreLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            leading = { Icon(Icons.Rounded.Restore, null, tint = MaterialTheme.colorScheme.primary) },
        )
        HorizontalDivider(color = divider)
        ListRow(
            title = "Automatic backups",
            subtitle = if (settings.backupFolder == null) "Choose a folder to back up to" else "Saved to a folder on your device",
            onClick = {
                if (settings.backupFolder == null) folderLauncher.launch(null) else vm.setAutoBackup(!settings.autoBackup)
            },
            leading = { Icon(Icons.Rounded.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) },
            trailing = {
                Switch(
                    checked = settings.autoBackup && settings.backupFolder != null,
                    onCheckedChange = { on ->
                        if (on && settings.backupFolder == null) folderLauncher.launch(null) else vm.setAutoBackup(on)
                    },
                )
            },
        )
        if (settings.backupFolder != null) {
            HorizontalDivider(color = divider)
            ListRow(
                title = "Backup folder",
                subtitle = folderName ?: "Tap to choose again",
                onClick = { folderLauncher.launch(null) },
                leading = { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary) },
            )
            if (settings.autoBackup) {
                HorizontalDivider(color = divider)
                Column(Modifier.padding(18.dp)) {
                    Text("How often", fontSize = 16.sp)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        listOf(1 to "Daily", 7 to "Weekly").forEachIndexed { i, (days, label) ->
                            SegmentedButton(
                                selected = settings.backupIntervalDays == days,
                                onClick = { vm.setInterval(days) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                    Text("Keep", fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        listOf(3, 7, 14).forEachIndexed { i, n ->
                            SegmentedButton(
                                selected = settings.backupKeep == n,
                                onClick = { vm.setKeep(n) },
                                shape = SegmentedButtonDefaults.itemShape(i, 3),
                            ) { Text("$n backups") }
                        }
                    }
                    Text(
                        "Backups go to a folder you pick, so they stay even if the app is uninstalled. " +
                            "To keep a copy in the cloud, sync that folder with any sync app. A backup is only made when something changed.",
                        fontSize = 13.sp,
                        color = LocalQuartzColors.current.textSecondary,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            HorizontalDivider(color = divider)
            ListRow(
                title = "Back up now",
                subtitle = when {
                    settings.lastBackupError != null -> "Last attempt failed: ${settings.lastBackupError}"
                    settings.lastBackupAt > 0 -> "Last backup ${shortTime(settings.lastBackupAt)}"
                    else -> "No backups yet"
                },
                onClick = {
                    BackupWorker.runNow(context)
                    show("Backing up…")
                },
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        is BackupDialog.ChooseRestoreMode -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "Add the backup's notes and notebooks alongside what you have now, " +
                        "or replace everything in the app with the backup?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    vm.restore(d.uri, replace = false, onResult = show)
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    vm.restore(d.uri, replace = true, onResult = show)
                }) { Text("Replace everything", color = MaterialTheme.colorScheme.error) }
            },
        )
        is BackupDialog.FoundInFolder -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Backup found") },
            text = { Text("This folder has a backup from ${shortTime(d.backup.modified)}. Restore it now?") },
            confirmButton = {
                TextButton(onClick = { dialog = BackupDialog.ChooseRestoreMode(d.backup.uri) }) { Text("Restore…") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Not now") } },
        )
    }
}
