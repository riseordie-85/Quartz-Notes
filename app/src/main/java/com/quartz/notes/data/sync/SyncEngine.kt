package com.quartz.notes.data.sync

import androidx.room.withTransaction
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.db.SyncStateEntity
import com.quartz.notes.data.db.SyncTriggers
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.OwnerType
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.sync.SyncCodec.ATTACHMENTS
import com.quartz.notes.data.sync.SyncCodec.ELEMENTS
import com.quartz.notes.data.sync.SyncCodec.NOTEBOOKS
import com.quartz.notes.data.sync.SyncCodec.NOTES
import com.quartz.notes.data.sync.SyncCodec.PAGES
import com.quartz.notes.data.sync.SyncCodec.SHELVES
import com.quartz.notes.data.sync.SyncCodec.PREFS
import com.quartz.notes.data.sync.SyncCodec.bool
import com.quartz.notes.data.sync.SyncCodec.int
import com.quartz.notes.data.sync.SyncCodec.long
import com.quartz.notes.data.sync.SyncCodec.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.rich.InlineStyle
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.data.db.newUid
import java.io.File
import java.security.MessageDigest

/** What one sync did. */
data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    /** Losing versions of conflicting edits, kept as their own notes or pages. */
    val copies: Int = 0,
    val filesUploaded: Int = 0,
    val filesDownloaded: Int = 0,
)

/**
 * Keeps this device's database and the sync server in step.
 *
 * A sync downloads everything that changed on the server since last time, then uploads everything
 * that changed here. When both sides changed the same record, the more recent edit (by the
 * devices' clocks) wins. See docs/sync-plan.md.
 */
class SyncEngine(
    private val db: QuartzDatabase,
    private val client: SyncClient,
    private val store: SyncStore,
    /** The app's files folder, holding media/ (pictures, recordings) and fonts/. */
    private val files: File,
    /** Called after files arrive; true when fonts were among them. */
    private val onFilesArrived: (fontsChanged: Boolean) -> Unit = {},
    /** Called after a change from the server is written here (so open editors can show it). */
    private val onRemoteChange: (table: String, uid: String) -> Unit = { _, _ -> },
    /** Accent and background colours, shared with the other devices and the web app. */
    private val appearance: AppearanceStore? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val sync = db.syncDao()
    private val shelves = db.shelfDao()
    private val notebooks = db.notebookDao()
    private val pages = db.pageDao()
    private val elements = db.pageElementDao()
    private val notes = db.quickNoteDao()
    private val attachments = db.attachmentDao()

    /** Signs in and remembers the account. Throws [SyncException] on failure. */
    suspend fun signIn(serverUrl: String, email: String, password: String): SyncAuth {
        val auth = client.signIn(SyncClient.normalizeUrl(serverUrl), email, password)
        mutex.withLock {
            store.setAuth(auth)
            store.clearCursors()
        }
        return auth
    }

    /**
     * Forgets the account. The notes stay on this device; everything is marked to upload again to
     * whichever server is used next, and deleted markers (which only mattered to the old server)
     * are cleared out.
     */
    suspend fun signOut() = mutex.withLock {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                val raw = db.openHelper.writableDatabase
                for (t in SyncTriggers.TABLES.reversed()) raw.execSQL("DELETE FROM $t WHERE deleted = 1")
                sync.resetAll()
            }
        }
        store.clearCursors()
        store.setAuth(null)
    }

    /** Renews the sign-in so it doesn't expire. */
    suspend fun refreshSignIn() = mutex.withLock {
        val auth = store.auth() ?: return@withLock
        store.setAuth(client.refresh(auth))
    }

    /** Downloads then uploads. Throws [SyncException] if it can't finish. */
    suspend fun sync(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val auth = store.auth() ?: throw SyncException.SignedOut("Not signed in")
            try {
                val counts = Counts()
                for (c in SyncTriggers.TABLES) pull(auth, c, counts)
                push(auth, store.deviceId(), counts)
                syncFiles(auth, counts)
                syncAppearance(auth, store.deviceId())
                purgeOldDeleted()
                store.recordResult(now(), null)
                SyncResult(counts.uploaded, counts.downloaded, counts.conflicts, counts.copies, counts.filesUp, counts.filesDown)
            } catch (e: SyncException) {
                store.recordResult(now(), e.message)
                throw e
            }
        }
    }

    private class Counts {
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var copies = 0
        var filesUp = 0
        var filesDown = 0
    }

    // ---- Download ----

    private suspend fun pull(auth: SyncAuth, collection: String, counts: Counts) {
        val since = store.cursor(collection)
        var newest = since
        var page = 1
        while (true) {
            val result = client.changes(auth, collection, since, page)
            if (result.items.isNotEmpty()) {
                applyAll(collection, result.items, counts)
                result.items.maxOf { it.str("updated") }.let { if (it > newest) newest = it }
            }
            if (page >= result.totalPages) break
            page++
        }
        if (newest != since) store.setCursor(collection, newest)
    }

    /** Writes downloaded records, without marking them for upload. */
    private suspend fun applyAll(collection: String, records: List<JsonObject>, counts: Counts) {
        // Connectors point at other items, so place those first.
        val ordered = if (collection == ELEMENTS) records.sortedBy { it.str("kind") == ElementKind.CONNECTOR } else records
        val changed = ArrayList<String>()
        db.withTransaction {
            sync.setMode(1)
            for (r in ordered) {
                when (runCatching { apply(collection, r, force = false, counts) }.getOrDefault(Applied.SKIPPED)) {
                    Applied.WRITTEN -> {
                        counts.downloaded++
                        changed += r.str("id")
                    }
                    Applied.REPLACED_LOCAL -> {
                        counts.downloaded++
                        counts.conflicts++
                        changed += r.str("id")
                    }
                    Applied.KEPT_LOCAL -> counts.conflicts++
                    Applied.SKIPPED -> Unit
                }
            }
            sync.setMode(0)
        }
        changed.forEach { onRemoteChange(collection, it) }
    }

    private enum class Applied {
        WRITTEN,

        /** Written over a local edit that was older. */
        REPLACED_LOCAL,

        /** Left alone: the local edit is newer and will be uploaded. */
        KEPT_LOCAL,
        SKIPPED,
    }

    /**
     * Writes one server record into the database. Must run in a transaction with the sync mode on.
     * Unless [force], a record this device already has, or one that lost to a newer local edit, is
     * left alone.
     */
    private suspend fun apply(collection: String, r: JsonObject, force: Boolean, counts: Counts): Applied {
        val uid = r.str("id")
        val rev = r.long("rev")
        val editedAt = r.long("editedAt")
        val deleted = r.bool("deleted")
        val state = sync.state(collection, uid)
        if (!force && state != null && rev <= state.rev) return Applied.SKIPPED
        if (!force && state != null && state.dirty && editedAt <= state.changedAt) {
            // Changed here more recently: keep it, and upload it on top of the server's version,
            // which is kept as a copy.
            if (keepCopy(collection, r, Loser.SERVER)) counts.copies++
            sync.setRev(collection, uid, rev)
            return Applied.KEPT_LOCAL
        }
        if (!force && state != null && state.dirty) {
            // The server's edit is newer; this device's edit is kept as a copy.
            if (keepCopy(collection, r, Loser.LOCAL)) counts.copies++
        }
        val written = when (collection) {
            SHELVES -> {
                var local = shelves.byUid(uid)
                if (local == null && !deleted) {
                    // A new device's own shelf with the same name (like the starting "Notebooks"
                    // shelf) becomes this one instead of a duplicate.
                    val twin = shelves.getAll().firstOrNull {
                        it.name.equals(r.str("name"), ignoreCase = true) && (sync.state(SHELVES, it.uid)?.rev ?: 0L) == 0L
                    }
                    if (twin != null) {
                        sync.forget(SHELVES, twin.uid)
                        shelves.setUid(twin.id, uid)
                        local = twin.copy(uid = uid)
                    }
                }
                when {
                    local != null -> shelves.update(SyncCodec.shelf(r, local.id)).let { true }
                    deleted -> false
                    else -> shelves.insert(SyncCodec.shelf(r, 0)).let { true }
                }
            }
            NOTEBOOKS -> {
                val local = notebooks.byUid(uid)
                val shelfId = shelves.byUid(r.str("shelf"))?.id ?: local?.shelfId ?: shelves.getAll().firstOrNull()?.id
                when {
                    shelfId == null -> false
                    local != null -> notebooks.update(SyncCodec.notebook(r, local.id, shelfId)).let { true }
                    deleted -> false
                    else -> notebooks.insert(SyncCodec.notebook(r, 0, shelfId)).let { true }
                }
            }
            PAGES -> {
                val local = pages.byUid(uid)
                val notebookId = notebooks.byUid(r.str("notebook"))?.id
                when {
                    notebookId == null -> false
                    local != null -> pages.update(SyncCodec.page(r, local.id, notebookId)).let { true }
                    deleted -> false
                    else -> pages.insert(SyncCodec.page(r, 0, notebookId)).let { true }
                }
            }
            ELEMENTS -> {
                val local = elements.byUid(uid)
                val pageId = pages.byUid(r.str("page"))?.id
                val ends = HashMap<String, Long>()
                (r["data"] as? JsonObject)?.let { data ->
                    for (key in listOf("from", "to")) {
                        val v = data[key] as? JsonPrimitive ?: continue
                        if (v.isString) elements.byUid(v.content)?.let { ends[v.content] = it.id }
                    }
                }
                when {
                    pageId == null -> false
                    local != null -> elements.update(SyncCodec.element(r, local.id, pageId) { ends[it] }).let { true }
                    deleted -> false
                    else -> elements.insert(SyncCodec.element(r, 0, pageId) { ends[it] }).let { true }
                }
            }
            NOTES -> {
                val local = notes.byUid(uid)
                when {
                    local != null -> notes.update(SyncCodec.note(r, local.id)).let { true }
                    deleted -> false
                    else -> notes.insert(SyncCodec.note(r, 0)).let { true }
                }
            }
            ATTACHMENTS -> {
                val local = attachments.byUid(uid)
                val ownerUid = r.str("ownerUid")
                val ownerId = when (r.str("ownerType")) {
                    OwnerType.NOTE.name -> notes.byUid(ownerUid)?.id
                    OwnerType.PAGE.name -> pages.byUid(ownerUid)?.id
                    else -> null
                }
                when {
                    ownerId == null -> false
                    local != null -> attachments.update(SyncCodec.attachment(r, local.id, ownerId)).let { true }
                    deleted -> false
                    else -> attachments.insert(SyncCodec.attachment(r, 0, ownerId)).let { true }
                }
            }
            else -> false
        }
        if (!written) return Applied.SKIPPED
        sync.put(SyncStateEntity(collection, uid, dirty = false, rev = rev, changedAt = editedAt))
        return if (state?.dirty == true) Applied.REPLACED_LOCAL else Applied.WRITTEN
    }

    // ---- Upload ----

    private suspend fun push(auth: SyncAuth, device: String, counts: Counts) {
        val order = SyncTriggers.TABLES
        val dirty = sync.dirty().sortedWith(compareBy<SyncStateEntity>({ order.indexOf(it.tbl) }, { it.changedAt }))
        for (st in dirty) {
            val record = recordFor(st, device) ?: continue
            try {
                val saved = if (st.rev == 0L) {
                    client.create(auth, st.tbl, record)
                } else {
                    client.update(auth, st.tbl, st.uid, st.rev, record)
                }
                uploaded(st, saved.long("rev"))
                counts.uploaded++
            } catch (e: SyncException.Conflict) {
                resolve(auth, st, record, counts)
            } catch (e: SyncException.NotFound) {
                // Gone from the server (removed from its dashboard): put it back.
                uploaded(st, client.create(auth, st.tbl, record).long("rev"))
                counts.uploaded++
            }
        }
    }

    /** The server's copy changed since this device last saw it: the more recent edit wins. */
    private suspend fun resolve(auth: SyncAuth, st: SyncStateEntity, record: JsonObject, counts: Counts) {
        val server = client.get(auth, st.tbl, st.uid)
        if (server.long("editedAt") > st.changedAt) {
            db.withTransaction {
                sync.setMode(1)
                if (keepCopy(st.tbl, server, Loser.LOCAL)) counts.copies++
                apply(st.tbl, server, force = true, counts)
                sync.setMode(0)
            }
            counts.conflicts++
            onRemoteChange(st.tbl, st.uid)
        } else {
            db.withTransaction { if (keepCopy(st.tbl, server, Loser.SERVER)) counts.copies++ }
            uploaded(st, client.update(auth, st.tbl, st.uid, server.long("rev"), record).long("rev"))
            counts.uploaded++
            counts.conflicts++
        }
    }

    /** The newer of this device's and the server's appearance settings wins. */
    private suspend fun syncAppearance(auth: SyncAuth, device: String) {
        val store = appearance ?: return
        val local = store.local()
        val body = { a: Appearance ->
            buildJsonObject {
                put("accentArgb", a.accentArgb)
                put("backgroundArgb", a.backgroundArgb)
                put("editedAt", a.changedAt)
                put("device", device)
            }
        }
        val remote = client.list(auth, PREFS).firstOrNull()
        if (remote == null) {
            // First device to sync them. Another may have just done the same: then try again next time.
            try {
                client.create(auth, PREFS, body(local))
            } catch (e: SyncException.Server) {
                if (e.status != 400) throw e
            } catch (_: SyncException.Conflict) {
            }
            return
        }
        val theirs = Appearance(remote.int("accentArgb"), remote.int("backgroundArgb"), remote.long("editedAt"))
        when {
            local.changedAt > theirs.changedAt -> client.patch(auth, PREFS, remote.str("id"), body(local))
            theirs != local -> store.applyRemote(theirs)
        }
    }

    private enum class Loser { LOCAL, SERVER }

    /**
     * When two devices changed the same quick note or page, keeps the losing version (this device's
     * or the server's) as a new note or page next to it, so no writing is lost. Does nothing when
     * the two versions say the same thing, or for deletions. Must run in a transaction; returns
     * whether a copy was made.
     */
    private suspend fun keepCopy(collection: String, server: JsonObject, loser: Loser): Boolean {
        if (server.bool("deleted")) return false
        val uid = server.str("id")
        val stamp = now()
        val mode = sync.mode()
        when (collection) {
            NOTES -> {
                val local = notes.byUid(uid) ?: return false
                if (local.deleted) return false
                val theirs = SyncCodec.note(server, local.id)
                if (local.title == theirs.title && RichDoc.decode(local.content) == RichDoc.decode(theirs.content)) return false
                val source = if (loser == Loser.LOCAL) local else theirs
                sync.setMode(0)
                notes.insert(
                    source.copy(
                        id = 0, uid = newUid(), deleted = false, title = copyTitle(source.title), pinned = false,
                        createdAt = stamp, updatedAt = stamp,
                    ),
                )
                sync.setMode(mode)
                return true
            }
            PAGES -> {
                val local = pages.byUid(uid) ?: return false
                if (local.deleted) return false
                val theirs = SyncCodec.page(server, local.id, local.notebookId)
                if (local.title == theirs.title && RichDoc.decode(local.content) == RichDoc.decode(theirs.content) &&
                    local.plainText == theirs.plainText
                ) return false
                val source = if (loser == Loser.LOCAL) local else theirs
                val position = (pages.getByNotebook(local.notebookId).maxOfOrNull { it.position } ?: -1) + 1
                sync.setMode(0)
                pages.insert(
                    source.copy(
                        id = 0, uid = newUid(), deleted = false, title = copyTitle(source.title), position = position,
                        locked = false, createdAt = stamp, updatedAt = stamp,
                    ),
                )
                sync.setMode(mode)
                return true
            }
            else -> return false
        }
    }

    private fun copyTitle(title: String) = if (title.isBlank()) "Conflict copy" else "$title (conflict copy)"

    private suspend fun uploaded(st: SyncStateEntity, rev: Long) {
        sync.setRev(st.tbl, st.uid, rev)
        sync.clearDirty(st.tbl, st.uid, st.changedAt)
    }

    /** The server record for a row, or null if the row is gone. */
    private suspend fun recordFor(st: SyncStateEntity, device: String): JsonObject? {
        val (body, deleted) = when (st.tbl) {
            SHELVES -> {
                val r = shelves.byUid(st.uid) ?: return null
                SyncCodec.shelf(r) to r.deleted
            }
            NOTEBOOKS -> {
                val r = notebooks.byUid(st.uid) ?: return null
                SyncCodec.notebook(r, shelves.uidOf(r.shelfId).orEmpty()) to r.deleted
            }
            PAGES -> {
                val r = pages.byUid(st.uid) ?: return null
                SyncCodec.page(r, notebooks.uidOf(r.notebookId).orEmpty()) to r.deleted
            }
            ELEMENTS -> {
                val r = elements.byUid(st.uid) ?: return null
                val ends = HashMap<Long, String>()
                if (r.kind == ElementKind.CONNECTOR) {
                    decodePayload<ConnectorData>(r.data)?.let { cd ->
                        for (id in listOf(cd.from, cd.to)) elements.uidOf(id)?.let { ends[id] = it }
                    }
                }
                SyncCodec.element(r, pages.uidOf(r.pageId).orEmpty()) { ends[it] } to r.deleted
            }
            NOTES -> {
                val r = notes.byUid(st.uid) ?: return null
                SyncCodec.note(r) to r.deleted
            }
            ATTACHMENTS -> {
                val r = attachments.byUid(st.uid) ?: return null
                val owner = when (r.ownerType) {
                    OwnerType.NOTE.name -> notes.uidOf(r.ownerId)
                    OwnerType.PAGE.name -> pages.uidOf(r.ownerId)
                    else -> null
                }
                SyncCodec.attachment(r, owner.orEmpty()) to r.deleted
            }
            else -> return null
        }
        return JsonObject(SyncCodec.common(st.uid, deleted, device, st.changedAt) + body)
    }

    // ---- Files ----

    /**
     * Pictures, recordings and fonts: uploads the ones the server doesn't have, and downloads the
     * ones the notes here use but this device doesn't have. Fonts go up whenever they're imported
     * and come down when a note uses them.
     */
    private suspend fun syncFiles(auth: SyncAuth, counts: Counts) {
        val remote = client.files(auth)
        val used = usedFiles()
        val localFonts = File(files, FONTS_DIR).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
            ?.map { "$FONTS_DIR/${it.name}" }
            .orEmpty()
        for (path in (used + localFonts).toSortedSet()) {
            if (path in remote || !safePath(path)) continue
            val file = File(files, path)
            if (!file.isFile) continue
            client.upload(auth, path, file, sha256(file))
            counts.filesUp++
        }
        var fonts = false
        for (path in used.toSortedSet()) {
            val r = remote[path] ?: continue
            if (!safePath(path)) continue
            val file = File(files, path)
            if (file.exists()) continue
            try {
                client.download(auth, r, file)
                counts.filesDown++
                if (path.startsWith("$FONTS_DIR/")) fonts = true
            } catch (e: SyncException.Server) {
                // One broken file shouldn't stop the rest; it's tried again next time.
            }
        }
        if (counts.filesDown > 0) onFilesArrived(fonts)
    }

    /** Every file the notes on this device use: pictures, recordings, section photos and fonts. */
    private suspend fun usedFiles(): Set<String> {
        val out = HashSet<String>()
        attachments.getAll().forEach { out += it.path }
        fun fontsIn(raw: String?) {
            RichDoc.decode(raw).blocks.forEach { b ->
                b.spans.forEach { s ->
                    val v = s.value
                    if (s.style == InlineStyle.FONT && v != null && v.startsWith("file:")) out += "$FONTS_DIR/${v.removePrefix("file:")}"
                }
            }
        }
        elements.getAll().forEach { e ->
            out += QuartzRepository.elementMediaPaths(e.kind, e.data)
            if (e.kind == ElementKind.TEXT) fontsIn(decodePayload<TextBoxData>(e.data)?.doc)
        }
        pages.getAll().forEach { p ->
            out += QuartzRepository.pageMediaPaths(p)
            fontsIn(p.content)
        }
        notes.getAll().forEach { n ->
            out += RichDoc.decode(n.content).mediaPaths()
            fontsIn(n.content)
        }
        return out
    }

    private fun safePath(path: String) = SAFE_PATH.matches(path)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Tidying ----

    /** Deleted markers every device has had a month to hear about. */
    private fun purgeOldDeleted() {
        val before = now() - PURGE_AFTER_MS
        val raw = db.openHelper.writableDatabase
        for (t in SyncTriggers.TABLES.reversed()) {
            raw.execSQL(
                "DELETE FROM $t WHERE deleted = 1 AND uid IN " +
                    "(SELECT uid FROM sync_state WHERE tbl = '$t' AND dirty = 0 AND rev > 0 AND changedAt < $before)",
            )
        }
    }

    companion object {
        const val PURGE_AFTER_MS = 30L * 24 * 60 * 60 * 1000
        private const val FONTS_DIR = "fonts"
        private val SAFE_PATH = Regex("^(media|fonts)/[A-Za-z0-9 ._-]+$")
    }
}
