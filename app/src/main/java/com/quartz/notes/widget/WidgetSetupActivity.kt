package com.quartz.notes.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.ThemeMode
import com.quartz.notes.data.model.Texture
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.ui.components.NotebookCover
import com.quartz.notes.ui.components.NotebookIcons
import com.quartz.notes.ui.components.QuartzCard
import com.quartz.notes.ui.components.SettingsSectionTitle
import com.quartz.notes.ui.theme.LocalQuartzColors
import com.quartz.notes.ui.theme.QuartzTheme
import kotlinx.coroutines.launch

/** Which of the widgets is being set up. */
internal enum class WidgetType(val title: String) {
    SHELF("Shelf widget"),
    NOTEBOOK("Notebook widget"),
    QUICK_NOTES("Quick notes widget"),
    TOOLBAR("Toolbar widget");

    fun widget(): GlanceAppWidget = when (this) {
        SHELF -> ShelfWidget()
        NOTEBOOK -> NotebookWidget()
        QUICK_NOTES -> QuickNotesWidget()
        TOOLBAR -> ToolbarWidget()
    }

    companion object {
        fun of(providerClass: String?): WidgetType? = when (providerClass) {
            ShelfWidgetReceiver::class.java.name -> SHELF
            NotebookWidgetReceiver::class.java.name -> NOTEBOOK
            QuickNotesWidgetReceiver::class.java.name -> QUICK_NOTES
            ToolbarWidgetReceiver::class.java.name -> TOOLBAR
            else -> null
        }
    }
}

/** The settings a widget is set up with, before they're saved. */
internal data class WidgetChoices(
    val shelf: Long? = null,
    val notebook: Long? = null,
    val grid: Boolean = false,
    val panel: Boolean = false,
    val darkText: Boolean = false,
    /** The most covers across a shelf widget; null for as many as fit. */
    val maxAcross: Int? = null,
)

/**
 * Shown when a widget is added (and from the launcher's "settings" for it afterwards): which shelf or
 * notebook it shows, list or grid, and how it sits on the home screen.
 */
class WidgetSetupActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out leaves the widget unadded.
        setResult(RESULT_CANCELED)
        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
        val type = WidgetType.of(provider)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || type == null) {
            finish()
            return
        }
        enableEdgeToEdge()
        val container = (application as QuartzApp).container
        val manager = GlanceAppWidgetManager(this)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val current = settings ?: return@setContent
            val dark = when (current.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val scope = rememberCoroutineScope()
            var initial by remember { mutableStateOf<WidgetChoices?>(null) }
            if (initial == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val glanceId = manager.getGlanceIdBy(appWidgetId)
                    val prefs = getAppWidgetState(this@WidgetSetupActivity, PreferencesGlanceStateDefinition, glanceId)
                    initial = WidgetChoices(
                        shelf = prefs[WidgetKeys.SHELF],
                        notebook = prefs[WidgetKeys.NOTEBOOK],
                        grid = prefs[WidgetKeys.GRID] ?: false,
                        panel = prefs[WidgetKeys.PANEL] ?: false,
                        darkText = prefs[WidgetKeys.DARK_TEXT] ?: false,
                        maxAcross = prefs[WidgetKeys.MAX_ACROSS],
                    )
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalAppContainer provides container) {
                QuartzTheme(settings = current, dark = dark) {
                    val start = initial
                    if (start != null) {
                        WidgetSetupScreen(
                            type = type,
                            initial = start,
                            onCancel = { finish() },
                            onSave = { choices ->
                                scope.launch {
                                    save(type, choices)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                    finish()
                                }
                            },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    }
                }
            }
        }
    }

    private suspend fun save(type: WidgetType, choices: WidgetChoices) {
        val glanceId = GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId)
        updateAppWidgetState(this, glanceId) { prefs ->
            choices.shelf?.let { prefs[WidgetKeys.SHELF] = it } ?: prefs.remove(WidgetKeys.SHELF)
            choices.notebook?.let { prefs[WidgetKeys.NOTEBOOK] = it } ?: prefs.remove(WidgetKeys.NOTEBOOK)
            prefs[WidgetKeys.GRID] = choices.grid
            prefs[WidgetKeys.PANEL] = choices.panel
            prefs[WidgetKeys.DARK_TEXT] = choices.darkText
            choices.maxAcross?.let { prefs[WidgetKeys.MAX_ACROSS] = it } ?: prefs.remove(WidgetKeys.MAX_ACROSS)
        }
        type.widget().update(this, glanceId)
    }
}

@Composable
internal fun WidgetSetupScreen(
    type: WidgetType,
    initial: WidgetChoices,
    onCancel: () -> Unit,
    onSave: (WidgetChoices) -> Unit,
) {
    val repo = LocalAppContainer.current.repository
    val shelves by repo.observeShelves().collectAsStateWithLifecycle(initialValue = emptyList())
    val notebooks by repo.observeAllNotebooks().collectAsStateWithLifecycle(initialValue = emptyList())
    var choices by remember { mutableStateOf(initial) }
    val colors = LocalQuartzColors.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Text(type.title, fontSize = 24.sp, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            when (type) {
                WidgetType.SHELF -> {
                    item { SettingsSectionTitle("Shelf") }
                    item {
                        QuartzCard(Modifier.fillMaxWidth()) {
                            shelves.forEachIndexed { i, shelf ->
                                val chosen = (choices.shelf ?: shelves.firstOrNull()?.id) == shelf.id
                                Choice(shelf.name, chosen, Modifier.testTag("shelf-$i")) { choices = choices.copy(shelf = shelf.id) }
                            }
                        }
                    }
                    item { SettingsSectionTitle("Books across") }
                    item {
                        QuartzCard(Modifier.fillMaxWidth()) {
                            Choice(
                                "As many as fit",
                                choices.maxAcross == null,
                                Modifier.testTag("across-fit"),
                                subtitle = "More books as the widget gets wider",
                            ) { choices = choices.copy(maxAcross = null) }
                            ACROSS_CHOICES.forEach { n ->
                                Choice("At most $n", choices.maxAcross == n, Modifier.testTag("across-$n")) { choices = choices.copy(maxAcross = n) }
                            }
                        }
                    }
                }
                WidgetType.NOTEBOOK -> {
                    item { SettingsSectionTitle("Notebook") }
                    val byShelf = shelves.map { s -> s to notebooks.filter { it.shelfId == s.id } }.filter { it.second.isNotEmpty() }
                    byShelf.forEach { (shelf, list) ->
                        item { Text(shelf.name, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 6.dp)) }
                        items(list, key = { it.id }) { nb ->
                            val chosen = choices.notebook == nb.id
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent, MaterialTheme.shapes.large)
                                    .clickable { choices = choices.copy(notebook = nb.id) }
                                    .padding(8.dp)
                                    .testTag("notebook-${nb.id}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NotebookCover(
                                    title = "",
                                    color = Color(nb.colorArgb),
                                    texture = enumOrDefault(nb.texture, Texture.GLOSS),
                                    icon = NotebookIcons.of(nb.icon),
                                    modifier = Modifier.width(40.dp),
                                    onClick = { choices = choices.copy(notebook = nb.id) },
                                )
                                Text(nb.title.ifBlank { "Untitled notebook" }, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
                                RadioButton(selected = chosen, onClick = { choices = choices.copy(notebook = nb.id) })
                            }
                        }
                    }
                    if (byShelf.isEmpty()) item { Text("No notebooks yet.", color = colors.textSecondary, modifier = Modifier.padding(8.dp)) }
                }
                WidgetType.QUICK_NOTES -> {
                    item { SettingsSectionTitle("Style") }
                    item {
                        QuartzCard(Modifier.fillMaxWidth()) {
                            Choice("List", !choices.grid) { choices = choices.copy(grid = false) }
                            Choice("Grid", choices.grid) { choices = choices.copy(grid = true) }
                        }
                    }
                }
                WidgetType.TOOLBAR -> Unit
            }
            item { SettingsSectionTitle("On the home screen") }
            item {
                QuartzCard(Modifier.fillMaxWidth()) {
                    Choice(
                        "No background",
                        !choices.panel,
                        subtitle = "For Quartz Launcher: turn on \"Glass behind it\" for the widget and it frames it",
                    ) { choices = choices.copy(panel = false) }
                    Choice("Quartz panel", choices.panel, subtitle = "Its own rounded panel, for other launchers") { choices = choices.copy(panel = true) }
                }
            }
            item { SettingsSectionTitle("Writing") }
            item {
                QuartzCard(Modifier.fillMaxWidth()) {
                    Choice("Light", !choices.darkText, subtitle = "For dark glass and wallpapers") { choices = choices.copy(darkText = false) }
                    Choice("Dark", choices.darkText, subtitle = "For light glass and wallpapers") { choices = choices.copy(darkText = true) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            val ready = type != WidgetType.NOTEBOOK || choices.notebook != null
            Button(onClick = { onSave(choices) }, enabled = ready, modifier = Modifier.testTag("widget-save")) { Text("Done") }
        }
    }
}

/** The limits offered for books across a shelf widget. */
private val ACROSS_CHOICES = listOf(2, 3, 4, 5, 6, 8)

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 6.dp)) {
            Text(label, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = LocalQuartzColors.current.textSecondary)
        }
    }
}
