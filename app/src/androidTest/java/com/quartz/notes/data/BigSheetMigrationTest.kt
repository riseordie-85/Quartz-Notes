package com.quartz.notes.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.sheet.SheetCell
import com.quartz.notes.data.sheet.SheetData
import com.quartz.notes.data.sheet.SheetStyle
import com.quartz.notes.data.sheet.addr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A sheet page over Android's 2 MB row window made its whole notebook unreadable. The 8 → 9
 * migration compresses it so the notebook opens again, with the sheet intact.
 */
@RunWith(AndroidJUnit4::class)
class BigSheetMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, QuartzDatabase::class.java)

    @Test
    fun oversizedSheetIsCompressedAndItsNotebookOpens() {
        val name = "big-sheet-migration.db"
        context.deleteDatabase(name)
        val cells = LinkedHashMap<String, SheetCell>()
        for (r in 0 until 400) for (c in 0 until 200) cells[addr(r, c)] = SheetCell(if (c == 0) "row $r" else null, SheetStyle(fill = "#FFEAF1FD", border = "all"))
        val sheet = SheetData(rows = 400, cols = 200, cells = cells)
        val json = sheet.encode()
        assertTrue(json.length > 2_500_000)
        var notebook = 0L
        helper.createDatabase(name, 8).apply {
            fun insert(table: String, vararg pairs: Pair<String, Any?>): Long {
                val values = ContentValues()
                for ((k, v) in pairs) when (v) {
                    null -> values.putNull(k)
                    is Int -> values.put(k, v)
                    is Long -> values.put(k, v)
                    is String -> values.put(k, v)
                    else -> error("unsupported $v")
                }
                return insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
            }
            val shelf = insert("shelves", "name" to "Notebooks", "position" to 0, "createdAt" to 1L, "uid" to "s".repeat(32), "deleted" to 0)
            notebook = insert(
                "notebooks", "shelfId" to shelf, "title" to "Budget", "colorArgb" to 1, "texture" to "GLOSS", "icon" to "none",
                "position" to 0, "createdAt" to 1L, "updatedAt" to 1L, "twoPage" to 0, "showIndex" to 0, "sectionTabs" to 1,
                "attachAtBottom" to 0, "kind" to "NOTEBOOK", "pageLayout" to "SCROLL", "uid" to "n".repeat(32), "deleted" to 0,
            )
            for ((i, text) in listOf("small" to """{"v":1,"cells":{"A1":{"v":"1"}}}""", "big" to json).withIndex()) {
                insert(
                    "pages", "notebookId" to notebook, "title" to text.first, "position" to i, "pageType" to "BLANK",
                    "texture" to "NONE", "layoutMode" to "SHEET", "content" to "", "plainText" to "x".repeat(if (i == 1) 300_000 else 1),
                    "createdAt" to 1L, "updatedAt" to 1L, "locked" to 0, "spread" to 0, "isSection" to 0,
                    "sheet" to text.second, "uid" to "$i".repeat(32), "deleted" to 0,
                )
            }
            close()
        }

        helper.runMigrationsAndValidate(name, 9, true, QuartzDatabase.MIGRATION_8_9).close()

        val reopened = QuartzDatabase.build(context, name)
        runBlocking {
            val pages = reopened.pageDao().getByNotebook(notebook)
            assertEquals(listOf("small", "big"), pages.map { it.title })
            assertEquals("""{"v":1,"cells":{"A1":{"v":"1"}}}""", pages[0].sheet)
            assertTrue(pages[1].sheet!!.startsWith("gz:"))
            assertEquals(sheet, SheetData.decode(pages[1].sheet))
            assertEquals(100_000, pages[1].plainText.length)
        }
        reopened.close()
        context.deleteDatabase(name)
    }
}
