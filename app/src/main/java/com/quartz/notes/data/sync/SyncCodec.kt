package com.quartz.notes.data.sync

import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.model.ElementKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Turns app rows into sync server records and back (the field list is in docs/sync-plan.md).
 * Links between rows are local numeric ids in the app and permanent ids (uids) on the server, so
 * each direction takes a lookup for them.
 */
object SyncCodec {
    const val SHELVES = "shelves"
    const val NOTEBOOKS = "notebooks"
    const val PAGES = "pages"
    const val ELEMENTS = "page_elements"
    const val NOTES = "quick_notes"
    const val ATTACHMENTS = "attachments"
    /** Appearance settings: one record per account, not part of the row sync. */
    const val PREFS = "prefs"

    /** Fields every record carries, whatever the table. */
    fun common(uid: String, deleted: Boolean, device: String, editedAt: Long): JsonObject = buildJsonObject {
        put("id", uid)
        put("deleted", deleted)
        put("device", device)
        put("editedAt", editedAt)
    }

    // ---- App → server ----

    fun shelf(s: ShelfEntity) = buildJsonObject {
        put("name", s.name)
        put("position", s.position)
        put("createdAt", s.createdAt)
    }

    fun notebook(n: NotebookEntity, shelfUid: String) = buildJsonObject {
        put("shelf", shelfUid)
        put("title", n.title)
        put("colorArgb", n.colorArgb)
        put("texture", n.texture)
        put("icon", n.icon)
        put("position", n.position)
        put("createdAt", n.createdAt)
        put("updatedAt", n.updatedAt)
        put("twoPage", n.twoPage)
        put("showIndex", n.showIndex)
        put("sectionTabs", n.sectionTabs)
        put("attachAtBottom", n.attachAtBottom)
    }

    fun page(p: PageEntity, notebookUid: String) = buildJsonObject {
        put("notebook", notebookUid)
        put("title", p.title)
        put("position", p.position)
        put("pageType", p.pageType)
        put("backgroundArgb", p.backgroundArgb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("texture", p.texture)
        put("layoutMode", p.layoutMode)
        put("content", parseOrNull(p.content))
        put("plainText", p.plainText)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
        put("locked", p.locked)
        put("spread", p.spread)
        put("isSection", p.isSection)
        put("sectionIcon", p.sectionIcon.orEmpty())
        put("sectionImage", p.sectionImage.orEmpty())
    }

    /** [uidOfElement] turns a connector's local element ids into uids. */
    fun element(e: PageElementEntity, pageUid: String, uidOfElement: (Long) -> String?) = buildJsonObject {
        put("page", pageUid)
        put("kind", e.kind)
        put("x", e.x)
        put("y", e.y)
        put("width", e.width)
        put("height", e.height)
        put("rotation", e.rotation)
        put("z", e.z)
        put("data", elementData(e.kind, e.data, uidOfElement))
        put("createdAt", e.createdAt)
    }

    fun note(n: QuickNoteEntity) = buildJsonObject {
        put("title", n.title)
        put("content", parseOrNull(n.content))
        put("plainText", n.plainText)
        put("colorKey", n.colorKey)
        put("pinned", n.pinned)
        put("createdAt", n.createdAt)
        put("updatedAt", n.updatedAt)
        put("archived", n.archived)
    }

    fun attachment(a: AttachmentEntity, ownerUid: String) = buildJsonObject {
        put("ownerType", a.ownerType)
        put("ownerUid", ownerUid)
        put("kind", a.kind)
        put("path", a.path)
        put("mimeType", a.mimeType.orEmpty())
        put("durationMs", a.durationMs?.let { JsonPrimitive(it) } ?: JsonNull)
        put("width", a.width?.let { JsonPrimitive(it) } ?: JsonNull)
        put("height", a.height?.let { JsonPrimitive(it) } ?: JsonNull)
        put("createdAt", a.createdAt)
    }

    // ---- Server → app ----
    // [id] is the existing local row's id (0 for a new row).

    fun shelf(r: JsonObject, id: Long) = ShelfEntity(
        id = id,
        uid = r.str("id"),
        deleted = r.bool("deleted"),
        name = r.str("name"),
        position = r.int("position"),
        createdAt = r.long("createdAt"),
    )

    fun notebook(r: JsonObject, id: Long, shelfId: Long) = NotebookEntity(
        id = id,
        uid = r.str("id"),
        deleted = r.bool("deleted"),
        shelfId = shelfId,
        title = r.str("title"),
        colorArgb = r.int("colorArgb"),
        texture = r.str("texture").ifEmpty { "GLOSS" },
        icon = r.str("icon").ifEmpty { "none" },
        position = r.int("position"),
        createdAt = r.long("createdAt"),
        updatedAt = r.long("updatedAt"),
        twoPage = r.bool("twoPage"),
        showIndex = r.bool("showIndex"),
        sectionTabs = r.bool("sectionTabs"),
        attachAtBottom = r.bool("attachAtBottom"),
    )

    fun page(r: JsonObject, id: Long, notebookId: Long) = PageEntity(
        id = id,
        uid = r.str("id"),
        deleted = r.bool("deleted"),
        notebookId = notebookId,
        title = r.str("title"),
        position = r.int("position"),
        pageType = r.str("pageType").ifEmpty { "LINES" },
        backgroundArgb = r.longOrNull("backgroundArgb")?.toInt(),
        texture = r.str("texture").ifEmpty { "NONE" },
        layoutMode = r.str("layoutMode").ifEmpty { "INLINE" },
        content = stringify(r["content"]),
        plainText = r.str("plainText"),
        createdAt = r.long("createdAt"),
        updatedAt = r.long("updatedAt"),
        locked = r.bool("locked"),
        spread = r.bool("spread"),
        isSection = r.bool("isSection"),
        sectionIcon = r.str("sectionIcon").ifEmpty { null },
        sectionImage = r.str("sectionImage").ifEmpty { null },
    )

    /** [idOfElement] turns a connector's element uids back into local ids. */
    fun element(r: JsonObject, id: Long, pageId: Long, idOfElement: (String) -> Long?) = PageElementEntity(
        id = id,
        uid = r.str("id"),
        deleted = r.bool("deleted"),
        pageId = pageId,
        kind = r.str("kind"),
        x = r.double("x").toFloat(),
        y = r.double("y").toFloat(),
        width = r.double("width").toFloat(),
        height = r.double("height").toFloat(),
        rotation = r.double("rotation").toFloat(),
        z = r.int("z"),
        data = elementDataFromServer(r.str("kind"), r["data"], idOfElement),
        createdAt = r.long("createdAt"),
    )

    fun note(r: JsonObject, id: Long) = QuickNoteEntity(
        id = id,
        uid = r.str("id"),
        deleted = r.bool("deleted"),
        title = r.str("title"),
        content = stringify(r["content"]),
        plainText = r.str("plainText"),
        colorKey = r.str("colorKey").ifEmpty { "default" },
        pinned = r.bool("pinned"),
        createdAt = r.long("createdAt"),
        updatedAt = r.long("updatedAt"),
        archived = r.bool("archived"),
    )

    fun attachment(r: JsonObject, id: Long, ownerId: Long) = AttachmentEntity(
        id = id,
        uid = r.str("id"),
        deleted = r.bool("deleted"),
        ownerType = r.str("ownerType"),
        ownerId = ownerId,
        kind = r.str("kind"),
        path = r.str("path"),
        mimeType = r.str("mimeType").ifEmpty { null },
        durationMs = r.longOrNull("durationMs"),
        width = r.longOrNull("width")?.toInt(),
        height = r.longOrNull("height")?.toInt(),
        createdAt = r.long("createdAt"),
    )

    // ---- Payloads ----

    /** Element payloads go up as JSON; a connector's ends become element uids. */
    internal fun elementData(kind: String, raw: String, uidOfElement: (Long) -> String?): JsonElement {
        val parsed = parseOrNull(raw)
        if (kind != ElementKind.CONNECTOR || parsed !is JsonObject) return parsed
        fun end(key: String): JsonElement {
            val local = (parsed[key] as? JsonPrimitive)?.longOrNull ?: return parsed[key] ?: JsonNull
            return uidOfElement(local)?.let { JsonPrimitive(it) } ?: JsonNull
        }
        return JsonObject(parsed + mapOf("from" to end("from"), "to" to end("to")))
    }

    internal fun elementDataFromServer(kind: String, data: JsonElement?, idOfElement: (String) -> Long?): String {
        if (kind == ElementKind.CONNECTOR && data is JsonObject) {
            fun end(key: String): JsonElement {
                val v = data[key] as? JsonPrimitive ?: return JsonPrimitive(-1)
                if (!v.isString) return v
                return JsonPrimitive(idOfElement(v.content) ?: -1L)
            }
            return JsonObject(data + mapOf("from" to end("from"), "to" to end("to"))).toString()
        }
        return stringify(data)
    }

    /** App text holding JSON → a JSON value (null for empty; a JSON string if it isn't JSON). */
    internal fun parseOrNull(raw: String): JsonElement {
        if (raw.isBlank()) return JsonNull
        return runCatching { Json.parseToJsonElement(raw) }.getOrElse { JsonPrimitive(raw) }
    }

    /** A JSON value → app text ("" for null; a JSON string's own text). */
    internal fun stringify(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> if (e.isString) e.content else e.toString()
        else -> e.toString()
    }

    // ---- Reading record fields ----

    internal fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
    internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    internal fun JsonObject.double(key: String): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
    internal fun JsonObject.longOrNull(key: String): Long? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.longOrNull ?: p.doubleOrNull?.toLong()
    }
    internal fun JsonObject.long(key: String): Long = longOrNull(key) ?: 0L
    internal fun JsonObject.int(key: String): Int = long(key).toInt()
}
