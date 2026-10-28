package com.quartz.notes.data.sync

import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.data.rich.Block
import com.quartz.notes.data.rich.BlockType
import com.quartz.notes.data.rich.RichDoc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App rows survive the trip to a server record and back. */
class SyncCodecTest {

    /** What the server sends back: the record as sent plus the server's own fields, through real JSON. */
    private fun serverCopy(uid: String, body: JsonObject, deleted: Boolean = false): JsonObject {
        val record = JsonObject(SyncCodec.common(uid, deleted, "device-1", 1234) + body)
        val extra = mapOf("rev" to JsonPrimitive(3), "updated" to JsonPrimitive("2026-10-28 05:00:00.000Z"))
        return Json.parseToJsonElement(JsonObject(record + extra).toString()).jsonObject
    }

    @Test
    fun shelfRoundTrip() {
        val s = ShelfEntity(id = 7, name = "Work", position = 2, createdAt = 111, uid = "u-shelf")
        assertEquals(s, SyncCodec.shelf(serverCopy(s.uid, SyncCodec.shelf(s)), 7))
    }

    @Test
    fun notebookRoundTrip() {
        val n = NotebookEntity(
            id = 3, shelfId = 7, title = "Travel", colorArgb = -16777216, texture = "LEATHER", icon = "plane", position = 1,
            createdAt = 5, updatedAt = 6, twoPage = false, showIndex = true, sectionTabs = false, attachAtBottom = true, uid = "u-nb",
        )
        val record = serverCopy(n.uid, SyncCodec.notebook(n, "u-shelf"))
        assertEquals("u-shelf", (record["shelf"] as JsonPrimitive).content)
        assertEquals(n, SyncCodec.notebook(record, 3, 7))
    }

    @Test
    fun pageRoundTripKeepsContentAndNulls() {
        val doc = RichDoc(listOf(Block(type = BlockType.HEADING, text = "Hello"), Block(type = BlockType.DIVIDER)))
        val p = PageEntity(
            id = 9, notebookId = 3, title = "Day 1", position = 4, pageType = "GRID", backgroundArgb = null, texture = "NONE",
            layoutMode = "SCRAPBOOK", content = doc.encode(), plainText = "Hello", createdAt = 1, updatedAt = 2, locked = true,
            spread = true, isSection = false, sectionIcon = null, sectionImage = null, uid = "u-page",
        )
        val record = serverCopy(p.uid, SyncCodec.page(p, "u-nb"))
        assertTrue(record["content"] is JsonObject)
        assertEquals(JsonNull, record["backgroundArgb"])
        val back = SyncCodec.page(record, 9, 3)
        assertEquals(doc, RichDoc.decode(back.content))
        assertEquals(p.copy(content = back.content), back)

        val colored = p.copy(backgroundArgb = 0, isSection = true, sectionIcon = "star", sectionImage = "media/s.jpg", content = "")
        val back2 = SyncCodec.page(serverCopy(p.uid, SyncCodec.page(colored, "u-nb")), 9, 3)
        assertEquals(colored, back2)
    }

    @Test
    fun connectorsPointAtElementsByUid() {
        val data = ConnectorData(from = 11, to = 12).encodePayload()
        val e = PageElementEntity(id = 13, pageId = 9, kind = ElementKind.CONNECTOR, data = data, z = 3, uid = "u-conn")
        val uids = mapOf(11L to "u-a", 12L to "u-b")
        val record = serverCopy(e.uid, SyncCodec.element(e, "u-page") { uids[it] })
        val sentData = record["data"] as JsonObject
        assertEquals("u-a", (sentData["from"] as JsonPrimitive).content)
        assertEquals("u-b", (sentData["to"] as JsonPrimitive).content)

        // On another device the same items have other local ids.
        val ids = mapOf("u-a" to 101L, "u-b" to 102L)
        val back = SyncCodec.element(record, 0, 90) { ids[it] }
        val cd = decodePayload<ConnectorData>(back.data)!!
        assertEquals(101L, cd.from)
        assertEquals(102L, cd.to)
        assertEquals(90L, back.pageId)
    }

    @Test
    fun otherElementsKeepTheirPayload() {
        val e = PageElementEntity(
            id = 1, pageId = 9, kind = ElementKind.SHAPE, x = 0.25f, y = 1.5f, width = 0.4f, height = 0.3f, rotation = 15f, z = 2,
            data = ShapeData(text = "Hi", fill = null).encodePayload(), createdAt = 4, uid = "u-shape",
        )
        val back = SyncCodec.element(serverCopy(e.uid, SyncCodec.element(e, "u-page") { null }), 1, 9) { null }
        assertEquals(e.copy(data = back.data), back)
        assertEquals(decodePayload<ShapeData>(e.data), decodePayload<ShapeData>(back.data))
    }

    @Test
    fun quickNoteAndAttachmentRoundTrip() {
        val n = QuickNoteEntity(
            id = 5, title = "Groceries", content = RichDoc.fromPlainText("milk\neggs").encode(), plainText = "milk\neggs",
            colorKey = "mint", pinned = true, createdAt = 1, updatedAt = 2, archived = true, uid = "u-note",
        )
        val back = SyncCodec.note(serverCopy(n.uid, SyncCodec.note(n)), 5)
        assertEquals(RichDoc.decode(n.content), RichDoc.decode(back.content))
        assertEquals(n.copy(content = back.content), back)

        val a = AttachmentEntity(
            id = 2, ownerType = "NOTE", ownerId = 5, kind = "AUDIO", path = "media/v.m4a", mimeType = "audio/mp4",
            durationMs = 4200, width = null, height = null, createdAt = 9, uid = "u-att",
        )
        val record = serverCopy(a.uid, SyncCodec.attachment(a, "u-note"))
        assertEquals("u-note", (record["ownerUid"] as JsonPrimitive).content)
        assertEquals(a, SyncCodec.attachment(record, 2, 5))
    }

    @Test
    fun deletedFlagTravels() {
        val s = ShelfEntity(id = 1, name = "Old", uid = "u-old", deleted = true)
        assertEquals(true, SyncCodec.shelf(serverCopy(s.uid, SyncCodec.shelf(s), deleted = true), 1).deleted)
    }

    @Test
    fun serverAddressesAreTidied() {
        assertEquals("https://rise.example.ts.net:8443", SyncClient.normalizeUrl(" rise.example.ts.net:8443/ "))
        assertEquals("http://10.0.2.2:8090", SyncClient.normalizeUrl("http://10.0.2.2:8090"))
    }
}
