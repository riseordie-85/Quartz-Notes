package com.quartz.notes.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.quartz.notes.domain.model.GradientSpec
import com.quartz.notes.domain.model.QuartzPresets
import com.quartz.notes.domain.model.ThemeMode
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentThemeMode: ThemeMode,
    currentGlobalGradient: GradientSpec,
    autoBackupEnabled: Boolean,
    autoBackupIntervalHours: Long,
    autoBackupUriString: String?,
    onThemeModeChange: (ThemeMode) -> Unit,
    onGlobalGradientChange: (GradientSpec) -> Unit,
    onExportManualZip: (Uri) -> Unit,
    onExportManualJson: (Uri) -> Unit,
    onConfigureAutoBackupFolder: (Uri) -> Unit,
    onToggleAutoBackup: (Boolean, Long) -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var customStartHexInput by remember { mutableStateOf("FF0F172A") }
    var customEndHexInput by remember { mutableStateOf("FF3B0764") }

    val exportZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(onExportManualZip) }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(onExportManualJson) }

    val autoBackupDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { uri ->
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            onConfigureAutoBackupFolder(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quartz Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 1. APPEARANCE MODE
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Appearance Mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = currentThemeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size)
                            ) {
                                Text(mode.name)
                            }
                        }
                    }
                }
            }

            // 2. PRESET & CUSTOM GRADIENTS
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Global App Gradient", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Applies across Quartz Notes unless overridden inside an individual note.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    QuartzPresets.all.forEach { preset ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentGlobalGradient.id == preset.id,
                                onClick = { onGlobalGradientChange(preset) }
                            )
                            Text(preset.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text("Custom Hex Gradient (ARGB)", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customStartHexInput,
                            onValueChange = { customStartHexInput = it },
                            label = { Text("Start Hex") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = customEndHexInput,
                            onValueChange = { customEndHexInput = it },
                            label = { Text("End Hex") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val start = customStartHexInput.toLongOrNull(16) ?: 0xFF0F172A
                            val end = customEndHexInput.toLongOrNull(16) ?: 0xFF3B0764
                            onGlobalGradientChange(GradientSpec("custom", "Custom Gradient", start, end))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply Custom Hex Gradient")
                    }
                }
            }

            // 3. MANUAL BACKUP EXPORT
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Manual Backup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Export all notes immediately as portable Markdown files (.zip) or a full database snapshot (.json).",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportZipLauncher.launch("QuartzNotes_Backup_$stamp.zip")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Export .ZIP")
                        }

                        OutlinedButton(
                            onClick = {
                                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                exportJsonLauncher.launch("QuartzNotes_Snapshot_$stamp.json")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export .JSON")
                        }
                    }
                }
            }

            // 4. AUTOMATIC SCHEDULED BACKUPS
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatic Backups", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "Saves timestamped .ZIP backups in the background via WorkManager.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = { enabled -> onToggleAutoBackup(enabled, autoBackupIntervalHours) }
                        )
                    }

                    OutlinedButton(
                        onClick = { autoBackupDirLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(if (autoBackupUriString == null) "Select Backup Folder" else "Change Backup Folder")
                    }

                    if (autoBackupUriString != null) {
                        Text(
                            text = "Target: $autoBackupUriString",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
