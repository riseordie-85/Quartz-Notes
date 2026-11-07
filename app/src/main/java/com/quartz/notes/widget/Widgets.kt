package com.quartz.notes.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import com.quartz.notes.LaunchRequest
import com.quartz.notes.Launches
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.ui.theme.NoteColor
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.floor

private fun Context.repo() = (applicationContext as QuartzApp).container.repository

private fun open(context: Context, request: LaunchRequest): Action = actionStartActivity(Launches.intent(context, request))

/** Pixels for [dp] on this screen. */
private fun Context.px(dp: Dp): Int = (dp.value * resources.displayMetrics.density).toInt()

/** A widget's covers are no bigger than this, to stay well inside what Android lets a widget carry. */
private val MaxCoverWidth = 110.dp

// ---- Shelf ----

/** The first notebooks on a shelf, in shelf order: as many rows and as many covers per row as fit. */
class ShelfWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.repo()
        val hint = LauncherHint.of(context, id)
        val data = combine(repo.observeShelves(), repo.observeAllNotebooks()) { shelves, notebooks -> shelves to notebooks }
        // Drawn with what's there now, then kept up to date while the widget is showing.
        val start = data.first()
        provideContent {
            val prefs = currentState<Preferences>()
            val look = WidgetLook.of(prefs, hint)
            val loaded by data.collectAsState(initial = start)
            val (shelves, notebooks) = loaded
            val shelf = shelves.firstOrNull { it.id == prefs[WidgetKeys.SHELF] } ?: shelves.firstOrNull()
            ShelfContent(look, shelf, notebooks.filter { it.shelfId == shelf?.id })
        }
    }
}

/**
 * How a shelf's covers are laid out in the widget's size: as many rows as fit down (each at least
 * [RowMin] tall, so a widget one cell high is one shelf and two cells high is two), covers sized to
 * fill a row's height, and as many across as fit.
 */
internal data class ShelfPlan(val columns: Int, val rows: Int, val coverWidth: Dp, val header: Boolean) {
    companion object {
        private val Gap = 6.dp
        private val Header = 26.dp
        private val RowMin = 76.dp

        /** A cover's picture, shadow margin included, for a cover [w] wide. */
        fun outerWidth(w: Dp) = w * (1 + CoverBitmaps.MARGIN * 2)
        fun outerHeight(w: Dp) = w / CoverBitmaps.ASPECT + w * CoverBitmaps.MARGIN * 2

        /** [width] and [height] are inside the padding. */
        fun of(width: Dp, height: Dp): ShelfPlan {
            val header = height >= 260.dp
            val h = height - (if (header) Header else 0.dp)
            val rows = maxOf(1, floor(((h + Gap) / (RowMin + Gap)).toDouble()).toInt())
            val rowHeight = (h - Gap * (rows - 1)) / rows
            val fromHeight = rowHeight / (1 / CoverBitmaps.ASPECT + CoverBitmaps.MARGIN * 2)
            val coverW = minOf(fromHeight, MaxCoverWidth, width / (1 + CoverBitmaps.MARGIN * 2))
            val columns = maxOf(1, floor(((width + Gap) / (outerWidth(coverW) + Gap)).toDouble()).toInt())
            return ShelfPlan(columns, rows, coverW, header)
        }
    }
}

@Composable
private fun ShelfContent(look: WidgetLook, shelf: ShelfEntity?, notebooks: List<NotebookEntity>) {
    val context = LocalContext.current
    val size = LocalSize.current
    Box(look.frame(GlanceModifier.fillMaxSize()), contentAlignment = Alignment.Center) {
        val plan = ShelfPlan.of(size.width - look.pad * 2, size.height - look.pad * 2)
        val shown = notebooks.take(plan.columns * plan.rows)
        Column(GlanceModifier.fillMaxSize()) {
            if (plan.header) {
                Text(
                    shelf?.name ?: "Notebooks",
                    style = look.text(14.sp, bold = true),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth().height(26.dp).padding(start = 4.dp)
                        .clickable(open(context, LaunchRequest.Notebooks(shelf?.id))),
                )
            }
            if (shown.isEmpty()) {
                Box(GlanceModifier.fillMaxSize().clickable(open(context, LaunchRequest.NewNotebook)), contentAlignment = Alignment.Center) {
                    Text("No notebooks on this shelf yet. Tap to add one.", style = look.text(13.sp, color = look.inkDim))
                }
                return@Column
            }
            val coverPx = context.px(plan.coverWidth)
            // Rows share the height evenly, covers sit at the bottom of each (like books on a shelf).
            shown.chunked(plan.columns).forEach { row ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.Bottom, horizontalAlignment = Alignment.Start) {
                    row.forEachIndexed { i, nb ->
                        if (i > 0) Spacer(GlanceModifier.width(gapFor(plan, size.width - look.pad * 2)))
                        Cover(nb, plan.coverWidth, coverPx, open(context, LaunchRequest.OpenNotebook(nb.id)))
                    }
                }
            }
        }
    }
}

/** Spreads a row's covers across the width. */
private fun gapFor(plan: ShelfPlan, width: Dp): Dp {
    if (plan.columns <= 1) return 0.dp
    return ((width - ShelfPlan.outerWidth(plan.coverWidth) * plan.columns) / (plan.columns - 1)).coerceAtLeast(0.dp)
}

/** One cover, with room for its shadow, opening the notebook when tapped. */
@Composable
private fun Cover(nb: NotebookEntity, width: Dp, widthPx: Int, onTap: Action, title: Boolean = true) {
    val bitmap = remember(nb.id, nb.title, nb.colorArgb, nb.texture, nb.icon, widthPx, title) {
        CoverBitmaps.render(nb, widthPx.coerceAtMost(MAX_COVER_PX), title)
    }
    // The picture is the cover plus its shadow margin all round.
    val margin = width * CoverBitmaps.MARGIN
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = nb.title.ifBlank { "Notebook" },
        contentScale = ContentScale.Fit,
        modifier = GlanceModifier.size(width + margin * 2, width / CoverBitmaps.ASPECT + margin * 2).clickable(onTap),
    )
}

/** The largest cover picture drawn, in pixels. */
private const val MAX_COVER_PX = 330

class ShelfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShelfWidget()
}

// ---- One notebook ----

/** One notebook: its cover at icon size, with its title and page count once there's room. */
class NotebookWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.repo()
        val hint = LauncherHint.of(context, id)
        fun data(notebookId: Long) = combine(repo.observeNotebook(notebookId), repo.observePageCount(notebookId)) { nb, n -> nb to n }
        provideContent {
            val prefs = currentState<Preferences>()
            val look = WidgetLook.of(prefs, hint)
            val notebookId = prefs[WidgetKeys.NOTEBOOK] ?: -1L
            val flow = remember(notebookId) { data(notebookId) }
            // Drawn with the notebook as it is now (widgets compose off the main thread), then kept up to date.
            val start = remember(notebookId) { runBlocking { flow.first() } }
            val loaded by flow.collectAsState(initial = start)
            NotebookContent(look, loaded.first, loaded.second, true, notebookId)
        }
    }
}

@Composable
private fun NotebookContent(look: WidgetLook, nb: NotebookEntity?, pages: Int, loaded: Boolean, notebookId: Long) {
    val context = LocalContext.current
    val size = LocalSize.current
    val inner = look.frame(GlanceModifier.fillMaxSize())
    if (!loaded) {
        Box(inner) {}
        return
    }
    if (nb == null) {
        Box(inner.clickable(open(context, LaunchRequest.Notebooks(null))), contentAlignment = Alignment.Center) {
            Text(if (notebookId < 0) "Choose a notebook in this widget's settings" else "This notebook was deleted", style = look.text(12.sp, color = look.inkDim))
        }
        return
    }
    val tap = open(context, LaunchRequest.OpenNotebook(nb.id))
    val w = size.width - look.pad * 2
    val h = size.height - look.pad * 2
    val detail = if (pages == 1) "1 page" else "$pages pages"
    when {
        // Icon size: just the cover.
        w < 120.dp || h < 100.dp && w < 200.dp -> Box(inner.clickable(tap), contentAlignment = Alignment.Center) {
            val coverW = min(w, h * CoverBitmaps.ASPECT) / (1 + CoverBitmaps.MARGIN * 2)
            Cover(nb, coverW, context.px(coverW), tap, title = coverW >= 52.dp)
        }
        // Wide: the cover with its title and page count beside it.
        w >= h * 1.15f -> Row(inner.clickable(tap), verticalAlignment = Alignment.CenterVertically) {
            val coverW = min(w * 0.45f, h * CoverBitmaps.ASPECT) / (1 + CoverBitmaps.MARGIN * 2)
            Cover(nb, coverW, context.px(coverW), tap)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(nb.title.ifBlank { "Untitled notebook" }, style = look.text(16.sp, bold = true), maxLines = 2)
                Text(detail, style = look.text(13.sp, color = look.inkDim), maxLines = 1)
            }
        }
        // Square or tall: the cover, its title under it.
        else -> Column(inner.clickable(tap), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            val coverW = min(w * 0.8f, (h - 40.dp) * CoverBitmaps.ASPECT) / (1 + CoverBitmaps.MARGIN * 2)
            Cover(nb, coverW, context.px(coverW), tap, title = false)
            Spacer(GlanceModifier.height(4.dp))
            Text(nb.title.ifBlank { "Untitled notebook" }, style = look.text(14.sp, bold = true), maxLines = 1)
            Text(detail, style = look.text(12.sp, color = look.inkDim), maxLines = 1)
        }
    }
}

class NotebookWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotebookWidget()
}

// ---- Quick notes ----

/** The quick notes, pinned first, as a list or a grid, with a button for a new one. */
class QuickNotesWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.repo()
        val hint = LauncherHint.of(context, id)
        val data = repo.observeNotes()
        val start = data.first()
        provideContent {
            val prefs = currentState<Preferences>()
            val look = WidgetLook.of(prefs, hint)
            val notes by data.collectAsState(initial = start)
            QuickNotesContent(look, notes, prefs[WidgetKeys.GRID] ?: false)
        }
    }
}

@Composable
private fun QuickNotesContent(look: WidgetLook, notes: List<QuickNoteEntity>?, grid: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    Column(look.frame(GlanceModifier.fillMaxSize())) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Quick notes",
                style = look.text(15.sp, bold = true),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(open(context, LaunchRequest.QuickNotes)),
            )
            IconButton(look, Icons.Rounded.Add, "New quick note", open(context, LaunchRequest.NewNote))
        }
        val list = notes ?: return@Column
        if (list.isEmpty()) {
            Box(GlanceModifier.fillMaxSize().clickable(open(context, LaunchRequest.NewNote)), contentAlignment = Alignment.Center) {
                Text("No quick notes yet. Tap + to jot one down.", style = look.text(13.sp, color = look.inkDim))
            }
            return@Column
        }
        if (grid) {
            val columns = maxOf(1, ((size.width - look.pad * 2).value / 150f).toInt())
            LazyVerticalGrid(GridCells.Fixed(columns), GlanceModifier.fillMaxSize()) {
                items(list, itemId = { it.id }) { note ->
                    Box(GlanceModifier.padding(3.dp)) { NoteCard(look, note, previewLines = 5) }
                }
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(list, itemId = { it.id }) { note ->
                    Box(GlanceModifier.padding(vertical = 3.dp)) { NoteCard(look, note, previewLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(look: WidgetLook, note: QuickNoteEntity, previewLines: Int) {
    val context = LocalContext.current
    val color = NoteColor.of(note.colorKey)
    val bg = if (color == NoteColor.DEFAULT) look.tile else color.background(isDark = look.light)
    val title = note.title.trim()
    val text = note.plainText.trim().replace(Regex("\\s*\n\\s*"), " · ")
    Column(
        GlanceModifier.fillMaxWidth().background(bg).cornerRadius(14.dp).padding(horizontal = 12.dp, vertical = 9.dp)
            .clickable(open(context, LaunchRequest.OpenNote(note.id))),
    ) {
        if (title.isNotEmpty()) Text((if (note.pinned) "📌 " else "") + title, style = look.text(14.sp, bold = true), maxLines = 1)
        if (text.isNotEmpty()) Text(text, style = look.text(13.sp, color = look.inkDim), maxLines = previewLines)
        if (title.isEmpty() && text.isEmpty()) Text("Empty note", style = look.text(13.sp, color = look.inkDim), maxLines = 1)
    }
}

class QuickNotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickNotesWidget()
}

// ---- Toolbar ----

/** New notebook, the notebooks, the quick notes: labelled when there's room, icons alone when not. */
class ToolbarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val hint = LauncherHint.of(context, id)
        provideContent {
            val prefs = currentState<Preferences>()
            ToolbarContent(WidgetLook.of(prefs, hint))
        }
    }
}

@Composable
private fun ToolbarContent(look: WidgetLook) {
    val context = LocalContext.current
    val size = LocalSize.current
    val buttons = listOf(
        Triple(Icons.Rounded.LibraryAdd, "New notebook", LaunchRequest.NewNotebook),
        Triple(Icons.AutoMirrored.Rounded.LibraryBooks, "Notebooks", LaunchRequest.Notebooks(null)),
        Triple(Icons.AutoMirrored.Rounded.Notes, "Quick notes", LaunchRequest.QuickNotes),
    )
    val w = size.width - look.pad * 2
    val h = size.height - look.pad * 2
    // Side by side when it's wider than tall, stacked when it's tall.
    val across = w >= h
    val labels = if (across) w / 3 >= 96.dp || h >= 80.dp else h / 3 >= 44.dp && w >= 120.dp
    val content: @Composable (Triple<ImageVector, String, LaunchRequest>, GlanceModifier) -> Unit = { (icon, label, request), modifier ->
        ToolButton(look, icon, label, open(context, request), labels, stacked = across && h >= 80.dp, modifier)
    }
    if (across) {
        Row(look.frame(GlanceModifier.fillMaxSize()), verticalAlignment = Alignment.CenterVertically) {
            buttons.forEachIndexed { i, b ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                content(b, GlanceModifier.defaultWeight())
            }
        }
    } else {
        Column(look.frame(GlanceModifier.fillMaxSize()), horizontalAlignment = Alignment.CenterHorizontally) {
            buttons.forEachIndexed { i, b ->
                if (i > 0) Spacer(GlanceModifier.height(6.dp))
                content(b, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun ToolButton(
    look: WidgetLook,
    icon: ImageVector,
    label: String,
    onTap: Action,
    showLabel: Boolean,
    stacked: Boolean,
    modifier: GlanceModifier,
) {
    val context = LocalContext.current
    val bitmap = iconBitmap(context, icon, look.inkArgb, 24.dp)
    val base = modifier.fillMaxSize().background(look.tile).cornerRadius(16.dp).clickable(onTap)
    if (stacked && showLabel) {
        Column(base, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(bitmap), contentDescription = label, modifier = GlanceModifier.size(24.dp))
            Spacer(GlanceModifier.height(4.dp))
            Text(label, style = look.text(13.sp, bold = true), maxLines = 1)
        }
    } else {
        Row(base, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(bitmap), contentDescription = label, modifier = GlanceModifier.size(22.dp))
            if (showLabel) {
                Spacer(GlanceModifier.width(8.dp))
                Text(label, style = look.text(13.sp, bold = true), maxLines = 1)
            }
        }
    }
}

/** A small round button with an icon (the quick notes widget's +). */
@Composable
private fun IconButton(look: WidgetLook, icon: ImageVector, label: String, onTap: Action) {
    val context = LocalContext.current
    Box(
        GlanceModifier.size(34.dp).background(look.tile).cornerRadius(17.dp).clickable(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(iconBitmap(context, icon, look.inkArgb, 22.dp)), contentDescription = label, modifier = GlanceModifier.size(22.dp))
    }
}

@Composable
private fun iconBitmap(context: Context, icon: ImageVector, tint: Int, size: Dp): Bitmap =
    remember(icon, tint, size) { CoverBitmaps.icon(icon, context.px(size), tint) }

class ToolbarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ToolbarWidget()
}
