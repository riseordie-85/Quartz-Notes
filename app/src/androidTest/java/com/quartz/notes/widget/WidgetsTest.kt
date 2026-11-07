package com.quartz.notes.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.LaunchRequest
import com.quartz.notes.Launches
import com.quartz.notes.QuartzApp
import com.quartz.notes.data.db.QuickNoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The home screen widgets, drawn the way a launcher draws them (their views made from what they send),
 * at several sizes, with pictures for checking by eye and checks on what they show.
 */
@OptIn(ExperimentalGlanceApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetsTest {
    companion object {
        private val app get() = ApplicationProvider.getApplicationContext<QuartzApp>()
        private var shelf = 0L
        private val books = ArrayList<Long>()

        @JvmStatic
        @BeforeClass
        fun seed(): Unit = runBlocking {
            val repo = app.container.repository
            shelf = repo.addShelf("Widget shelf")
            val colors = listOf(0xFF0050D0, 0xFFD6004C, 0xFF00A8D6, 0xFFE68A1E, 0xFF2E9E5B, 0xFF6A3FD0, 0xFFD32F2F, 0xFF00897B, 0xFF795548)
            val textures = listOf("GLOSS", "LINEN", "LEATHER", "PAPER", "CANVAS", "NONE", "GLOSS", "GLOSS", "LINEN")
            val icons = listOf("travel", "book", "none", "travel", "book", "none", "travel", "book", "none")
            for (i in colors.indices) {
                books += repo.createNotebook(shelf, "Book ${i + 1}", colors[i].toInt(), textures[i], icons[i])
            }
            val dao = app.container.database.quickNoteDao()
            dao.insert(QuickNoteEntity(title = "Groceries", plainText = "Milk\nEggs\nBread", colorKey = "yellow", pinned = true))
            dao.insert(QuickNoteEntity(title = "Gift ideas", plainText = "Headphones\nA good book", colorKey = "purple"))
            dao.insert(QuickNoteEntity(title = "", plainText = "Call the dentist on Monday about the appointment.", colorKey = "default"))
            dao.insert(QuickNoteEntity(title = "Trip", plainText = "Book the train", colorKey = "blue"))
            Unit
        }
    }

    /** Runs the widget at [w]×[h] dp with [state], makes its views as a launcher would, and saves a picture. */
    private fun render(widget: GlanceAppWidget, name: String, w: Int, h: Int, state: Preferences? = null): View = runBlocking {
        val context = app
        // Not on a home screen, so its settings come in directly.
        val views = widget.compose(context, size = DpSize(w.dp, h.dp), state = state)
        val density = context.resources.displayMetrics.density
        val pw = (w * density).toInt()
        val ph = (h * density).toInt()
        var view: View? = null
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            val parent = FrameLayout(context)
            val v: View = views.apply(context, parent)
            parent.addView(v, FrameLayout.LayoutParams(pw, ph))
            parent.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, pw, ph)
            val bmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(0xFF2B3550.toInt()) // like dark glass
            parent.draw(canvas)
            // A launcher fills lists in as they're shown; here, draw their items in place by hand.
            lists(parent).forEach { list -> drawItems(list, parent, canvas) }
            val dir = File(context.getExternalFilesDir(null), "shots").apply { mkdirs() }
            File(dir, "widget_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            view = parent
            done.countDown()
        }
        done.await()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        view!!
    }

    private fun lists(v: View): List<android.widget.AdapterView<*>> = when (v) {
        is android.widget.AdapterView<*> -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { lists(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun drawItems(list: android.widget.AdapterView<*>, root: View, canvas: Canvas) {
        val adapter = list.adapter ?: return
        if (list.childCount > 0) return
        var x0 = 0
        var y0 = 0
        var v: View = list
        while (v !== root) {
            x0 += v.left
            y0 += v.top
            v = v.parent as View
        }
        val columns = (list as? android.widget.GridView)?.numColumns?.takeIf { it > 0 } ?: 1
        val cellW = list.width / columns
        var y = 0
        var rowH = 0
        for (i in 0 until adapter.count) {
            val item = adapter.getView(i, null, list)
            item.measure(View.MeasureSpec.makeMeasureSpec(cellW, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            item.layout(0, 0, item.measuredWidth, item.measuredHeight)
            val col = i % columns
            if (col == 0 && i > 0) {
                y += rowH
                rowH = 0
            }
            if (y > list.height) break
            canvas.save()
            canvas.clipRect(x0, y0, x0 + list.width, y0 + list.height)
            canvas.translate((x0 + col * cellW).toFloat(), (y0 + y).toFloat())
            item.draw(canvas)
            canvas.restore()
            rowH = maxOf(rowH, item.measuredHeight)
        }
    }

    private fun itemCount(v: View): Int = lists(v).sumOf { it.adapter?.count ?: 0 }

    private fun texts(v: View): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun images(v: View): List<String> = when (v) {
        is ImageView -> listOfNotNull(v.contentDescription?.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { images(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun shelfState() = mutablePreferencesOf().apply { this[WidgetKeys.SHELF] = shelf }

    @Test
    fun shelfFillsItsSizeWithTheFirstNotebooksInOrder() {
        for ((name, w, h) in listOf(Triple("shelf_one_row", 320, 110), Triple("shelf_two_rows", 320, 230), Triple("shelf_page", 400, 640), Triple("shelf_narrow", 150, 110))) {
            val v = render(ShelfWidget(), name, w, h, shelfState())
            val plan = ShelfPlan.of((w - 16).dp, (h - 16).dp)
            val expected = (1..minOf(9, plan.columns * plan.rows)).map { "Book $it" }
            assertEquals("$name: $plan", expected, images(v))
        }
    }

    @Test
    fun shelfCanBeHeldToAFewBooksAcross() {
        val fits = ShelfPlan.of(304.dp, 214.dp)
        assertTrue("$fits", fits.columns > 2 && fits.rows >= 2)
        val held = ShelfPlan.of(304.dp, 214.dp, maxAcross = 2)
        assertEquals(2, held.columns)
        assertEquals(fits.rows, held.rows)
        assertEquals(fits.coverWidth, held.coverWidth)
        // A limit above what fits changes nothing.
        assertEquals(fits, ShelfPlan.of(304.dp, 214.dp, maxAcross = 20))
        // The covers sit together, not strung out to the edges.
        assertTrue(gapFor(held, 304.dp) <= ShelfPlan.outerWidth(held.coverWidth))

        val state = shelfState().apply { this[WidgetKeys.MAX_ACROSS] = 2 }
        val v = render(ShelfWidget(), "shelf_two_across", 320, 230, state)
        assertEquals((1..minOf(9, 2 * held.rows)).map { "Book $it" }, images(v))
    }

    @Test
    fun shelfPlanAddsRowsAndColumnsAsItGrows() {
        val small = ShelfPlan.of(304.dp, 94.dp)
        val tall = ShelfPlan.of(304.dp, 214.dp)
        val page = ShelfPlan.of(384.dp, 624.dp)
        assertEquals(1, small.rows)
        assertTrue("$tall", tall.rows >= 2)
        assertTrue("$page", page.rows > tall.rows)
        assertTrue("$small", small.columns >= 4)
        assertTrue("$page", page.header && !small.header)
    }

    @Test
    fun notebookIsAnIconSmallAndShowsMoreBigger() {
        val state = mutablePreferencesOf().apply { this[WidgetKeys.NOTEBOOK] = books[0] }
        val icon = render(NotebookWidget(), "notebook_1x1", 64, 64, state)
        assertEquals(listOf("Book 1"), images(icon))
        assertTrue(texts(icon).isEmpty())
        val square = render(NotebookWidget(), "notebook_2x2", 150, 150, state)
        assertTrue(texts(square).toString(), "Book 1" in texts(square) && texts(square).any { it.endsWith("page") || it.endsWith("pages") })
        val wide = render(NotebookWidget(), "notebook_wide", 280, 120, state)
        assertTrue(texts(wide).toString(), "Book 1" in texts(wide))
        val none = render(NotebookWidget(), "notebook_unset", 64, 64, mutablePreferencesOf())
        assertTrue(texts(none).isNotEmpty())
    }

    @Test
    fun quickNotesAsAListAndAGrid() {
        // (The notes themselves are a scrolling list, which Android fills in only on a real home screen.)
        val list = render(QuickNotesWidget(), "notes_list", 300, 320, mutablePreferencesOf())
        assertTrue(texts(list).toString(), "Quick notes" in texts(list))
        val grid = render(QuickNotesWidget(), "notes_grid", 340, 360, mutablePreferencesOf().apply { this[WidgetKeys.GRID] = true })
        assertTrue(texts(grid).toString(), "Quick notes" in texts(grid))
        render(QuickNotesWidget(), "notes_panel_dark_text", 300, 320, mutablePreferencesOf().apply {
            this[WidgetKeys.PANEL] = true
            this[WidgetKeys.DARK_TEXT] = true
        })
    }

    @Test
    fun toolbarLabelsItsButtonsWhenThereIsRoom() {
        val wide = render(ToolbarWidget(), "toolbar_wide", 420, 64, mutablePreferencesOf())
        assertEquals(listOf("New notebook", "Notebooks", "Quick notes"), texts(wide))
        val phone = render(ToolbarWidget(), "toolbar_phone", 340, 64, mutablePreferencesOf())
        assertTrue(texts(phone).toString(), texts(phone).isEmpty())
        val small = render(ToolbarWidget(), "toolbar_small", 180, 56, mutablePreferencesOf())
        assertEquals(listOf("New notebook", "Notebooks", "Quick notes"), images(small))
        render(ToolbarWidget(), "toolbar_tall", 140, 220, mutablePreferencesOf())
    }

    @Test
    fun launchLinksRoundTrip() {
        val requests = listOf(
            LaunchRequest.OpenNotebook(42),
            LaunchRequest.OpenNote(7),
            LaunchRequest.NewNote,
            LaunchRequest.NewNotebook,
            LaunchRequest.Notebooks(3),
            LaunchRequest.Notebooks(null),
            LaunchRequest.QuickNotes,
        )
        for (r in requests) assertEquals(r, Launches.parse(Launches.intent(app, r)))
        // Different places are different intents, so their taps don't get mixed up.
        assertEquals(requests.size, requests.map { Launches.intent(app, it).data.toString() }.toSet().size)
    }
}
