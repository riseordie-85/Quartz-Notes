package com.quartz.notes.data.rich

import com.quartz.notes.ui.editor.RichEditorState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The sample notes in shared/fixtures/richdoc (also used by the web app's tests) load, save and go
 * through the editor unchanged, so notes written on the web come back to the app exactly.
 */
class RichDocFixturesTest {
    private val dir = listOf(File("../shared/fixtures/richdoc"), File("shared/fixtures/richdoc")).first { it.isDirectory }
    private val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test
    fun samplesExist() = assertTrue(files.isNotEmpty())

    @Test
    fun samplesSaveExactlyAsWritten() {
        for (f in files) {
            val text = f.readText()
            val doc = RichDoc.decode(text)
            assertEquals(f.name, Json.parseToJsonElement(text), Json.parseToJsonElement(doc.encode()))
        }
    }

    @Test
    fun samplesComeThroughTheEditorUnchanged() {
        for (f in files) {
            val doc = RichDoc.decode(f.readText())
            assertEquals(f.name, doc, RichEditorState(doc).toDoc())
        }
    }
}
