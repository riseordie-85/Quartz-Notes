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
import com.quartz.notes.data.sync.SyncCodec.bool
import com.quartz.notes.data.sync.SyncCodec.long
import com.quartz.notes.data.sync.SyncCodec.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one sync did. */
data class SyncResult(val uploaded: Int, val downloaded: Int, val conflicts: Int)

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
                purgeOldDeleted()
                store.recordResult(now(), null)
                SyncResult(counts.uploaded, counts.downloaded, counts.conflicts)
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
        db.withTransaction {
            sync.setMode(1)
            for (r in ordered) {
                when (runCatching { apply(collection, r, force = false) }.getOrDefault(Applied.SKIPPED)) {
                    Applied.WRITTEN -> counts.downloaded++
                    Applied.REPLACED_LOCAL -> {
                        counts.downloaded++
                        counts.conflicts++
                    }
                    Applied.KEPT_LOCAL -> counts.conflicts++
                    Applied.SKIPPED -> Unit
                }
            }
            sync.setMode(0)
        }
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
    private suspend fun apply(collection: String, r: JsonObject, force: Boolean): Applied {
        val uid = r.str("id")
        val rev = r.long("rev")
        val editedAt = r.long("editedAt")
        val deleted = r.bool("deleted")
        val state = sync.state(collection, uid)
        if (!force && state != null && rev <= state.rev) return Applied.SKIPPED
        if (!force && state != null && state.dirty && editedAt <= state.changedAt) {
            // Changed here more recently: keep it, and upload it on top of the server's version.
            sync.setRev(collection, uid, rev)
            return Applied.KEPT_LOCAL
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
                apply(st.tbl, server, force = true)
                sync.setMode(0)
            }
            counts.conflicts++
        } else {
            uploaded(st, client.update(auth, st.tbl, st.uid, server.long("rev"), record).long("rev"))
            counts.uploaded++
        }
    }

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
    }
}
