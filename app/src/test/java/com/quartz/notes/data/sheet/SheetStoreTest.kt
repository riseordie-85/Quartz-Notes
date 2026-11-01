package com.quartz.notes.data.sheet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Big sheets are kept compressed in the database and read back unchanged. */
class SheetStoreTest {
    /** A sheet with a fill on every cell of a big area: the kind that once outgrew a database row. */
    private fun bigSheet(): SheetData {
        val cells = LinkedHashMap<String, SheetCell>()
        for (r in 0 until 400) for (c in 0 until 200) cells[addr(r, c)] = SheetCell(if (c == 0) "row $r" else null, SheetStyle(fill = "#FFEAF1FD", border = "all"))
        return SheetData(rows = 400, cols = 200, cells = cells)
    }

    @Test
    fun smallSheetsStayPlain() {
        val sheet = SheetData(cells = mapOf("A1" to SheetCell("1")))
        val stored = SheetStore.store(sheet)
        assertTrue(stored.startsWith("{"))
        assertEquals(sheet, SheetData.decode(stored))
    }

    @Test
    fun bigSheetsAreCompressedAndReadBack() {
        val sheet = bigSheet()
        val json = sheet.encode()
        assertTrue("the plain JSON is over 2 MB", json.length > 2_000_000)
        val stored = SheetStore.store(sheet)
        assertTrue(stored.startsWith("gz:"))
        assertTrue("stored well under the 2 MB row window: ${stored.length}", stored.length < 500_000)
        assertEquals(json, SheetStore.unpack(stored))
        assertEquals(sheet, SheetData.decode(stored))
        // Packing twice changes nothing.
        assertEquals(stored, SheetStore.pack(stored))
    }

    @Test
    fun brokenStoredTextReadsAsAnEmptySheet() {
        assertEquals(SheetData(), SheetData.decode("gz:not base64!"))
    }

    @Test
    fun searchTextIsCapped() {
        assertTrue(SheetStore.searchText(bigSheet()).length <= SheetStore.MAX_SEARCH_TEXT)
    }
}
