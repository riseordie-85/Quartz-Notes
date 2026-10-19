package com.quartz.notes.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.AppSettings
import com.quartz.notes.data.DEFAULT_ACCENT
import com.quartz.notes.data.FontInfo
import com.quartz.notes.data.FontRepository
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.SettingsRepository
import com.quartz.notes.data.ThemeMode
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.ui.components.ColorPickerDialog
import com.quartz.notes.ui.components.ConfirmDialog
import com.quartz.notes.ui.components.DefaultSwatch
import com.quartz.notes.ui.components.ListRow
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.components.SettingsSectionTitle
import com.quartz.notes.ui.components.Swatch
import com.quartz.notes.ui.components.TextInputDialog
import com.quartz.notes.ui.quartzViewModel
import com.quartz.notes.ui.theme.AccentPresets
import com.quartz.notes.ui.theme.BackgroundPresets
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.SectionTitleStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: QuartzRepository,
    private val settingsRepo: SettingsRepository,
    private val fontRepo: FontRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val shelves: StateFlow<List<ShelfEntity>> = repo.observeShelves().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val fonts: StateFlow<List<FontInfo>> = fontRepo.fonts

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun setDynamic(enabled: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(enabled) }
    fun setAccent(argb: Int) = viewModelScope.launch { settingsRepo.setAccent(argb) }
    fun setBackground(argb: Int) = viewModelScope.launch { settingsRepo.setBackground(argb) }
    fun setCompress(enabled: Boolean) = viewModelScope.launch { settingsRepo.setCompressImages(enabled) }

    fun addShelf(name: String) = viewModelScope.launch { repo.addShelf(name) }
    fun renameShelf(shelf: ShelfEntity, name: String) = viewModelScope.launch { repo.renameShelf(shelf, name) }
    fun moveShelf(shelf: ShelfEntity, up: Boolean) = viewModelScope.launch { repo.moveShelf(shelf, up) }
    fun deleteShelf(shelf: ShelfEntity) = viewModelScope.launch {
        val target = shelves.value.firstOrNull { it.id != shelf.id } ?: return@launch
        repo.deleteShelf(shelf, target.id)
    }

    fun importFont(uri: android.net.Uri, onResult: (String) -> Unit) = viewModelScope.launch {
        fontRepo.import(uri)
            .onSuccess { onResult("Added ${it.name}") }
            .onFailure { onResult(it.message ?: "Couldn't import that font") }
    }

    fun deleteFont(font: FontInfo) = viewModelScope.launch { fontRepo.delete(font) }
}

private sealed interface SettingsDialog {
    data object NewShelf : SettingsDialog
    data class RenameShelf(val shelf: ShelfEntity) : SettingsDialog
    data class DeleteShelf(val shelf: ShelfEntity) : SettingsDialog
    data class DeleteFont(val font: FontInfo) : SettingsDialog
    data object AccentPicker : SettingsDialog
    data object BackgroundPicker : SettingsDialog
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = quartzViewModel { SettingsViewModel(it.repository, it.settings, it.fonts) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val shelves by vm.shelves.collectAsStateWithLifecycle()
    val fonts by vm.fonts.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    }

    val s = settings ?: return

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text("Settings", style = SectionTitleStyle, color = MaterialTheme.colorScheme.onBackground)
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                modifier = Modifier.fillMaxSize().widthIn(max = 720.dp).align(Alignment.CenterHorizontally),
            ) {
                // ---- Appearance ----
                item { SettingsSectionTitle("Appearance") }
                item {
                    QuartzCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Theme", fontSize = 16.sp)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                ThemeMode.entries.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = s.themeMode == mode,
                                        onClick = { vm.setTheme(mode) },
                                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                    ) { Text(mode.label) }
                                }
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            HorizontalDivider(color = LocalQuartzColors.current.divider)
                            ListRow(
                                title = "Use wallpaper colors",
                                subtitle = "Match your phone's Material You colors",
                                onClick = { vm.setDynamic(!s.dynamicColor) },
                                trailing = { Switch(checked = s.dynamicColor, onCheckedChange = { vm.setDynamic(it) }) },
                            )
                        }
                        HorizontalDivider(color = LocalQuartzColors.current.divider)
                        Column(Modifier.padding(18.dp)) {
                            Text("Interface color", fontSize = 16.sp)
                            if (s.dynamicColor) {
                                Text("Turn off wallpaper colors to use your own.", fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
                            }
                            FlowRow(
                                Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AccentPresets.forEach { c ->
                                    Swatch(c, selected = s.accentArgb == c.toArgb(), onClick = { vm.setAccent(c.toArgb()) })
                                }
                                CustomSwatch { dialog = SettingsDialog.AccentPicker }
                            }
                        }
                        HorizontalDivider(color = LocalQuartzColors.current.divider)
                        Column(Modifier.padding(18.dp)) {
                            Text("Background color", fontSize = 16.sp)
                            FlowRow(
                                Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                DefaultSwatch(selected = s.backgroundArgb == 0, onClick = { vm.setBackground(0) })
                                BackgroundPresets.forEach { c ->
                                    Swatch(c, selected = s.backgroundArgb == c.toArgb(), onClick = { vm.setBackground(c.toArgb()) })
                                }
                                CustomSwatch { dialog = SettingsDialog.BackgroundPicker }
                            }
                        }
                    }
                }

                // ---- Shelves ----
                item { SettingsSectionTitle("Shelves") }
                item {
                    QuartzCard(Modifier.fillMaxWidth()) {
                        shelves.forEachIndexed { i, shelf ->
                            if (i > 0) HorizontalDivider(color = LocalQuartzColors.current.divider)
                            ListRow(
                                title = shelf.name,
                                trailing = {
                                    Row {
                                        IconButton(enabled = i > 0, onClick = { vm.moveShelf(shelf, up = true) }) {
                                            Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                                        }
                                        IconButton(enabled = i < shelves.lastIndex, onClick = { vm.moveShelf(shelf, up = false) }) {
                                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                                        }
                                        IconButton(onClick = { dialog = SettingsDialog.RenameShelf(shelf) }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "Rename")
                                        }
                                        IconButton(enabled = shelves.size > 1, onClick = { dialog = SettingsDialog.DeleteShelf(shelf) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete")
                                        }
                                    }
                                },
                            )
                        }
                        HorizontalDivider(color = LocalQuartzColors.current.divider)
                        ListRow(
                            title = "New shelf",
                            onClick = { dialog = SettingsDialog.NewShelf },
                            leading = { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                }

                // ---- Fonts ----
                item { SettingsSectionTitle("Fonts") }
                item {
                    QuartzCard(Modifier.fillMaxWidth()) {
                        fonts.forEachIndexed { i, font ->
                            if (i > 0) HorizontalDivider(color = LocalQuartzColors.current.divider)
                            ListRow(
                                title = font.name,
                                subtitle = if (font.builtIn) "Built in" else "Imported",
                                leading = {
                                    Text("Aa", fontSize = 22.sp, fontFamily = font.family, modifier = Modifier.widthIn(min = 36.dp))
                                },
                                trailing = if (font.builtIn) null else {
                                    {
                                        IconButton(onClick = { dialog = SettingsDialog.DeleteFont(font) }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove font")
                                        }
                                    }
                                },
                            )
                        }
                        HorizontalDivider(color = LocalQuartzColors.current.divider)
                        ListRow(
                            title = "Import font",
                            subtitle = ".ttf or .otf file",
                            onClick = {
                                fontPicker.launch(
                                    arrayOf(
                                        "font/*",
                                        "application/x-font-ttf",
                                        "application/x-font-otf",
                                        "application/font-sfnt",
                                        "application/vnd.ms-opentype",
                                        "application/octet-stream",
                                    ),
                                )
                            },
                            leading = { Icon(Icons.Rounded.FileUpload, null, tint = MaterialTheme.colorScheme.primary) },
                        )
                    }
                }

                // ---- Images ----
                item { SettingsSectionTitle("Images") }
                item {
                    QuartzCard(Modifier.fillMaxWidth()) {
                        ListRow(
                            title = "Compress images when adding",
                            subtitle = "Saves space; photos are resized to a sensible size",
                            onClick = { vm.setCompress(!s.compressImages) },
                            trailing = { Switch(checked = s.compressImages, onCheckedChange = { vm.setCompress(it) }) },
                        )
                    }
                }

                item {
                    Text(
                        "Quartz Notes · version 0.1.0",
                        fontSize = 13.sp,
                        color = LocalQuartzColors.current.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (val d = dialog) {
        null -> Unit
        SettingsDialog.NewShelf -> TextInputDialog(
            title = "New shelf",
            confirmLabel = "Create",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.addShelf(it)
                dialog = null
            },
        )
        is SettingsDialog.RenameShelf -> TextInputDialog(
            title = "Rename shelf",
            initial = d.shelf.name,
            onDismiss = { dialog = null },
            onConfirm = {
                vm.renameShelf(d.shelf, it)
                dialog = null
            },
        )
        is SettingsDialog.DeleteShelf -> {
            val target = shelves.firstOrNull { it.id != d.shelf.id }?.name ?: "another shelf"
            ConfirmDialog(
                title = "Delete \"${d.shelf.name}\"?",
                message = "Its notebooks will move to \"$target\". Nothing is deleted.",
                confirmLabel = "Delete shelf",
                onDismiss = { dialog = null },
                onConfirm = {
                    vm.deleteShelf(d.shelf)
                    dialog = null
                },
            )
        }
        is SettingsDialog.DeleteFont -> ConfirmDialog(
            title = "Remove ${d.font.name}?",
            message = "Text using this font will switch back to the default font.",
            confirmLabel = "Remove",
            onDismiss = { dialog = null },
            onConfirm = {
                vm.deleteFont(d.font)
                dialog = null
            },
        )
        SettingsDialog.AccentPicker -> ColorPickerDialog(
            title = "Interface color",
            initial = Color(s.accentArgb),
            presets = AccentPresets,
            onDismiss = { dialog = null },
            onPick = {
                vm.setAccent(it?.toArgb() ?: DEFAULT_ACCENT)
                dialog = null
            },
        )
        SettingsDialog.BackgroundPicker -> ColorPickerDialog(
            title = "Background color",
            initial = if (s.backgroundArgb != 0) Color(s.backgroundArgb) else null,
            presets = BackgroundPresets,
            allowDefault = true,
            onDismiss = { dialog = null },
            onPick = {
                vm.setBackground(it?.toArgb() ?: 0)
                dialog = null
            },
        )
    }
}

@Composable
private fun CustomSwatch(onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Colorize, contentDescription = "Custom color", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
