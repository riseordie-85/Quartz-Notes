package com.quartz.notes.data

import androidx.room.withTransaction
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.PageHit
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.db.newUid
import com.quartz.notes.data.model.AttachmentKind
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.OwnerType
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.model.VoiceData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.data.model.elementMediaPath
import com.quartz.notes.data.model.encode
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A row that sync just changed with a newer version from another device. */
data class RemoteChange(val table: String, val uid: String)

data class SearchResults(
    val notes: List<QuickNoteEntity>,
    val pages: List<PageHit>,
) {
    val isEmpty: Boolean get() = notes.isEmpty() && pages.isEmpty()
}

class QuartzRepository(private val db: QuartzDatabase, scope: CoroutineScope) {
    private val shelves = db.shelfDao()
    private val notebooks = db.notebookDao()
    private val pages = db.pageDao()
    private val notes = db.quickNoteDao()
    private val attachments = db.attachmentDao()
    private val elements = db.pageElementDao()

    /**
     * Page and note content goes through one queue, run strictly in the order it was submitted.
     * A save queued before another can never land after it, and a read queued after a save sees it.
     */
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in queue) runCatching { task() }
        }
    }

    /** Queues [block] behind every earlier queued operation. Returns immediately. */
    private fun enqueue(block: suspend () -> Unit) {
        queue.trySend(block)
    }

    /** Queues [block] and waits for its result. */
    private suspend fun <T> inOrder(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        queue.trySend {
            try {
                result.complete(block())
            } catch (t: Throwable) {
                result.completeExceptionally(t)
            }
        }
        return result.await()
    }

    private fun now() = System.currentTimeMillis()

    // ---- Changes from other devices ----

    private val _remoteChanges = MutableSharedFlow<RemoteChange>(extraBufferCapacity = 256)

    /** Rows sync just updated from the server, so open editors can show the new version. */
    val remoteChanges: SharedFlow<RemoteChange> = _remoteChanges.asSharedFlow()

    fun notifyRemoteChange(table: String, uid: String) {
        _remoteChanges.tryEmit(RemoteChange(table, uid))
    }

    /** The row as it is right now (not waiting for queued saves). */
    suspend fun noteNow(id: Long): QuickNoteEntity? = notes.get(id)

    suspend fun pageByUidNow(uid: String): PageEntity? = pages.byUid(uid)?.takeIf { !it.deleted }

    suspend fun elementByUidNow(uid: String): PageElementEntity? = elements.byUid(uid)?.takeIf { !it.deleted }

    /**
     * Keeps another device's version of a note that's being edited here as its own note, so the
     * edits here (saved over it) don't lose it.
     */
    suspend fun keepNoteCopy(version: QuickNoteEntity) {
        val stamp = now()
        notes.insert(
            version.copy(
                id = 0, uid = newUid(), deleted = false, pinned = false, createdAt = stamp, updatedAt = stamp,
                title = if (version.title.isBlank()) "Conflict copy" else "${version.title} (conflict copy)",
            ),
        )
    }

    /** The same for a notebook page: the other version goes at the end of the notebook. */
    suspend fun keepPageCopy(version: PageEntity) = db.withTransaction {
        val stamp = now()
        val position = (pages.getByNotebook(version.notebookId).maxOfOrNull { it.position } ?: -1) + 1
        pages.insert(
            version.copy(
                id = 0, uid = newUid(), deleted = false, locked = false, position = position, createdAt = stamp, updatedAt = stamp,
                title = if (version.title.isBlank()) "Conflict copy" else "${version.title} (conflict copy)",
            ),
        )
    }

    // ---- Deleting ----
    // Rows that never reached the sync server are erased; synced ones become deleted markers so the
    // other devices hear about it. Deleting something also deletes what's inside it. Call these in a
    // transaction.

    private suspend fun removeElements(ids: List<Long>) {
        if (ids.isEmpty()) return
        elements.eraseUnsynced(ids)
        elements.markDeleted(ids)
    }

    private suspend fun removeAttachments(ids: List<Long>) {
        if (ids.isEmpty()) return
        attachments.eraseUnsynced(ids)
        attachments.markDeleted(ids)
    }

    private suspend fun removePages(ids: List<Long>) {
        if (ids.isEmpty()) return
        removeElements(elements.liveIdsIn(ids))
        removeAttachments(attachments.liveIdsFor(OwnerType.PAGE.name, ids))
        pages.eraseUnsynced(ids)
        pages.markDeleted(ids)
    }

    private suspend fun removeNotebooks(ids: List<Long>) {
        if (ids.isEmpty()) return
        removePages(pages.liveIdsIn(ids))
        notebooks.eraseUnsynced(ids)
        notebooks.markDeleted(ids)
    }

    private suspend fun removeShelves(ids: List<Long>) {
        if (ids.isEmpty()) return
        removeNotebooks(notebooks.liveIdsIn(ids))
        shelves.eraseUnsynced(ids)
        shelves.markDeleted(ids)
    }

    private suspend fun removeNotes(ids: List<Long>) {
        if (ids.isEmpty()) return
        removeAttachments(attachments.liveIdsFor(OwnerType.NOTE.name, ids))
        notes.eraseUnsynced(ids)
        notes.markDeleted(ids)
    }

    // ---- Shelves ----

    fun observeShelves(): Flow<List<ShelfEntity>> = shelves.observeAll()

    suspend fun addShelf(name: String): Long =
        shelves.insert(ShelfEntity(name = name.trim(), position = shelves.maxPosition() + 1))

    suspend fun renameShelf(shelf: ShelfEntity, name: String) =
        shelves.update(shelf.copy(name = name.trim()))

    /** Deletes a shelf, moving its notebooks to [moveNotebooksTo] first. */
    suspend fun deleteShelf(shelf: ShelfEntity, moveNotebooksTo: Long) = db.withTransaction {
        notebooks.moveAll(shelf.id, moveNotebooksTo)
        removeShelves(listOf(shelf.id))
    }

    suspend fun moveShelf(shelf: ShelfEntity, up: Boolean) = db.withTransaction {
        val all = shelves.getAll().toMutableList()
        val index = all.indexOfFirst { it.id == shelf.id }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in all.indices) return@withTransaction
        val item = all.removeAt(index)
        all.add(target, item)
        all.forEachIndexed { i, s -> if (s.position != i) shelves.update(s.copy(position = i)) }
    }

    // ---- Notebooks ----

    fun observeNotebooks(shelfId: Long): Flow<List<NotebookEntity>> = notebooks.observeByShelf(shelfId)

    fun observeNotebook(id: Long): Flow<NotebookEntity?> = notebooks.observe(id)

    suspend fun createNotebook(
        shelfId: Long,
        title: String,
        colorArgb: Int,
        texture: String,
        icon: String,
        twoPage: Boolean = true,
    ): Long = db.withTransaction {
        val id = notebooks.insert(
            NotebookEntity(
                shelfId = shelfId,
                title = title.trim(),
                colorArgb = colorArgb,
                texture = texture,
                icon = icon,
                twoPage = twoPage,
                position = notebooks.maxPosition(shelfId) + 1,
            ),
        )
        pages.insert(PageEntity(notebookId = id, position = 0))
        id
    }

    suspend fun updateNotebook(notebook: NotebookEntity) {
        val existing = notebooks.get(notebook.id)
        val moved = existing != null && existing.shelfId != notebook.shelfId
        val position = if (moved) notebooks.maxPosition(notebook.shelfId) + 1 else notebook.position
        notebooks.update(notebook.copy(title = notebook.title.trim(), position = position, updatedAt = now()))
    }

    suspend fun deleteNotebook(notebook: NotebookEntity) = db.withTransaction { removeNotebooks(listOf(notebook.id)) }

    // ---- Pages ----

    fun observePages(notebookId: Long): Flow<List<PageEntity>> = pages.observeByNotebook(notebookId)

    fun observeRecentPages(limit: Int = 60): Flow<List<PageHit>> = pages.observeRecent(limit)

    /** Inserts a page after [afterIndex], copying the style of the page it follows. Returns the new id. */
    suspend fun insertPage(notebookId: Long, afterIndex: Int): Long = inOrder { insertPageNow(notebookId, afterIndex) }

    private suspend fun insertPageNow(notebookId: Long, afterIndex: Int): Long = db.withTransaction {
        val list = pages.getByNotebook(notebookId).toMutableList()
        // Copy the look of the nearest ordinary page before it (section pages have their own colors).
        // After a section page, match the section's own pages (the ones following it).
        val after = list.getOrNull(afterIndex)
        val template = (if (after?.isSection == true) list.drop(afterIndex + 1).takeWhile { !it.isSection }.firstOrNull() else null)
            ?: list.take((afterIndex + 1).coerceAtLeast(0)).lastOrNull { !it.isSection }
            ?: list.firstOrNull { !it.isSection }
        val insertAt = (afterIndex + 1).coerceIn(0, list.size)
        val newPage = PageEntity(
            notebookId = notebookId,
            pageType = template?.pageType ?: "LINES",
            backgroundArgb = template?.backgroundArgb,
            texture = template?.texture ?: "NONE",
            layoutMode = template?.layoutMode ?: "INLINE",
            position = insertAt,
        )
        val id = pages.insert(newPage)
        list.add(insertAt, newPage.copy(id = id))
        renumber(list)
        id
    }

    /** Adds a section's opening page at [insertAt] (0-based position) and returns its id. */
    suspend fun insertSection(notebookId: Long, insertAt: Int, colorArgb: Int): Long = inOrder {
        db.withTransaction {
            val list = pages.getByNotebook(notebookId).toMutableList()
            val at = insertAt.coerceIn(0, list.size)
            val page = PageEntity(
                notebookId = notebookId,
                title = "New section",
                position = at,
                pageType = "PLAIN",
                backgroundArgb = colorArgb,
                isSection = true,
            )
            val id = pages.insert(page)
            list.add(at, page.copy(id = id))
            renumber(list)
            id
        }
    }

    /** Saves a section page's title and description. */
    fun saveSectionText(pageId: Long, title: String, description: String) = enqueue {
        val doc = RichDoc.fromPlainText(description)
        pages.updateSectionText(pageId, title, doc.encode(), description, now())
    }

    fun setSectionColor(pageId: Long, colorArgb: Int) = enqueue { pages.setSectionColor(pageId, colorArgb, now()) }

    fun setSectionIcon(pageId: Long, icon: String?) = enqueue { pages.setSectionIcon(pageId, icon, now()) }

    fun setSectionImage(pageId: Long, image: String?) = enqueue { pages.setSectionImage(pageId, image, now()) }

    /** Pictures, recordings and text boxes on every page of a notebook, for its index. */
    fun observeNotebookMedia(notebookId: Long): Flow<List<PageElementEntity>> = elements.observeMediaForNotebook(notebookId)

    suspend fun getPage(id: Long): PageEntity? = inOrder { pages.get(id) }

    fun savePageContent(pageId: Long, doc: RichDoc) = enqueue {
        pages.updateContent(pageId, doc.encode(), doc.plainText(), now())
    }

    fun setPageLocked(pageId: Long, locked: Boolean) = enqueue { pages.setLocked(pageId, locked) }

    fun setPageSpread(pageId: Long, spread: Boolean) = enqueue { pages.setSpread(pageId, spread) }

    fun savePageTitle(pageId: Long, title: String) = enqueue {
        pages.updateTitle(pageId, title, now())
    }

    /** Updates a page's style fields (type, colors, texture, layout). */
    fun updatePageStyle(page: PageEntity) = enqueue {
        val current = pages.get(page.id) ?: return@enqueue
        pages.update(
            current.copy(
                pageType = page.pageType,
                backgroundArgb = page.backgroundArgb,
                texture = page.texture,
                layoutMode = page.layoutMode,
                // Only scrapbook pages can span both sides.
                spread = current.spread && page.layoutMode == "SCRAPBOOK",
                updatedAt = now(),
            ),
        )
    }

    fun applyStyleToAllPages(source: PageEntity) = enqueue {
        db.withTransaction {
            // Section pages keep their own look.
            val list = pages.getByNotebook(source.notebookId).filter { !it.isSection }
            pages.updateAll(
                list.map {
                    it.copy(
                        pageType = source.pageType,
                        backgroundArgb = source.backgroundArgb,
                        texture = source.texture,
                    )
                },
            )
        }
    }

    /** Deletes a page. A notebook always keeps at least one page, so the last one is left alone. */
    fun deletePage(page: PageEntity) = enqueue {
        db.withTransaction {
            val list = pages.getByNotebook(page.notebookId)
            if (list.size <= 1) return@withTransaction
            removePages(listOf(page.id))
            renumber(list.filter { it.id != page.id }.toMutableList())
        }
    }

    fun movePage(notebookId: Long, from: Int, to: Int) = enqueue {
        db.withTransaction {
            val list = pages.getByNotebook(notebookId).toMutableList()
            if (from !in list.indices || to !in list.indices) return@withTransaction
            list.add(to, list.removeAt(from))
            renumber(list)
            pages.touch(list[to].id, now())
        }
    }

    private suspend fun renumber(list: MutableList<PageEntity>) {
        val changed = list.mapIndexedNotNull { i, p -> if (p.position != i) p.copy(position = i) else null }
        if (changed.isNotEmpty()) pages.updateAll(changed)
    }

    // ---- Quick notes ----

    fun observeNotes(): Flow<List<QuickNoteEntity>> = notes.observeAll()

    fun observeArchivedNotes(): Flow<List<QuickNoteEntity>> = notes.observeArchived()

    fun setNotePinned(id: Long, pinned: Boolean) = enqueue { notes.setPinned(id, pinned) }

    fun setNoteArchived(id: Long, archived: Boolean) = enqueue { notes.setArchived(id, archived, now()) }

    suspend fun getNote(id: Long): QuickNoteEntity? = inOrder { notes.get(id) }

    suspend fun createNote(): Long = inOrder { notes.insert(QuickNoteEntity(content = RichDoc().encode())) }

    fun saveNote(id: Long, title: String, doc: RichDoc, colorKey: String) = enqueue {
        notes.updateContent(id, title, doc.encode(), doc.plainText(), colorKey, now())
    }

    fun deleteNote(id: Long) = enqueue {
        db.withTransaction { removeNotes(listOf(id)) }
    }

    /** Deletes the note if it has no title and no text (checked after all earlier saves land). */
    fun discardIfBlank(id: Long) = enqueue {
        val note = notes.get(id) ?: return@enqueue
        val hasAttachments = attachments.getFor(OwnerType.NOTE.name, id).isNotEmpty()
        if (note.title.isBlank() && note.plainText.isBlank() && !hasAttachments) db.withTransaction { removeNotes(listOf(id)) }
    }

    /** Removes blank notes left behind (e.g. the app was closed with an empty note open). */
    fun sweepBlankNotes(except: Long?) = enqueue {
        db.withTransaction { removeNotes(notes.blankIds(except ?: -1L)) }
    }

    // ---- Note attachments ----

    fun observeNoteAttachments(noteId: Long): Flow<List<AttachmentEntity>> =
        attachments.observeFor(OwnerType.NOTE.name, noteId)

    fun observeAllNoteAttachments(): Flow<List<AttachmentEntity>> = attachments.observeAllOf(OwnerType.NOTE.name)

    fun addNoteImage(noteId: Long, path: String, width: Int, height: Int) = enqueue {
        attachments.insert(
            AttachmentEntity(
                ownerType = OwnerType.NOTE.name, ownerId = noteId, kind = AttachmentKind.IMAGE.name,
                path = path, width = width, height = height,
            ),
        )
        notes.touch(noteId, now())
    }

    fun addNoteVoice(noteId: Long, path: String, durationMs: Long) = enqueue {
        attachments.insert(
            AttachmentEntity(
                ownerType = OwnerType.NOTE.name, ownerId = noteId, kind = AttachmentKind.AUDIO.name,
                path = path, mimeType = "audio/mp4", durationMs = durationMs,
            ),
        )
        notes.touch(noteId, now())
    }

    fun deleteAttachment(attachment: AttachmentEntity) = enqueue { db.withTransaction { removeAttachments(listOf(attachment.id)) } }

    // ---- Page elements (pictures and voice notes placed on pages) ----

    fun observeElements(pageId: Long): Flow<List<PageElementEntity>> = elements.observeFor(pageId)

    /** Adds an element on top of everything else on the page. */
    fun addElement(element: PageElementEntity) = enqueue {
        elements.insert(element.copy(z = elements.maxZ(element.pageId) + 1))
        pages.touch(element.pageId, now())
    }

    /** Saves only position, size and rotation, so it can't undo a decoration change. */
    fun moveElement(element: PageElementEntity) = enqueue {
        elements.updateGeometry(element.id, element.x, element.y, element.width, element.height, element.rotation)
        pages.touch(element.pageId, now())
    }

    /** Saves only the payload (frame, tape, caption...), so it can't undo a move. */
    fun updateElementData(element: PageElementEntity, data: String) = enqueue {
        elements.updateData(element.id, data)
        pages.touch(element.pageId, now())
    }

    /** Writes the whole row (used for arrows, whose position and shape change together). */
    fun replaceElement(element: PageElementEntity) = enqueue {
        elements.update(element)
        pages.touch(element.pageId, now())
    }

    fun bringToFront(element: PageElementEntity) = enqueue {
        elements.updateZ(element.id, elements.maxZ(element.pageId) + 1)
        pages.touch(element.pageId, now())
    }

    /** Removes an element, plus any connector lines attached to it. */
    fun deleteElement(element: PageElementEntity) = enqueue {
        db.withTransaction {
            val attached = elements.getKind(element.pageId, ElementKind.CONNECTOR).filter { c ->
                val data = decodePayload<ConnectorData>(c.data)
                data != null && (data.from == element.id || data.to == element.id)
            }
            removeElements(listOf(element.id) + attached.map { it.id })
            pages.touch(element.pageId, now())
        }
    }

    /** Adds an element on top and returns its id (used when the new item must get focus). */
    suspend fun insertElement(element: PageElementEntity): Long = inOrder {
        val id = elements.insert(element.copy(z = elements.maxZ(element.pageId) + 1))
        pages.touch(element.pageId, now())
        id
    }

    // ---- Sharing ----

    /** Notebooks with their shelf, for the "send to notebook" picker. */
    fun observeAllNotebooks(): Flow<List<NotebookEntity>> = notebooks.observeAll()

    /**
     * Copies a quick note onto a new last page of [notebookId]. Pictures and voice notes are placed
     * below the text, where they can be moved around. Returns the new page id.
     */
    suspend fun copyNoteToNotebook(noteId: Long, notebookId: Long): Long = inOrder {
        db.withTransaction {
            val note = notes.get(noteId) ?: error("Note not found")
            val list = pages.getByNotebook(notebookId)
            val template = list.lastOrNull { !it.isSection }
            val pageId = pages.insert(
                PageEntity(
                    notebookId = notebookId,
                    title = note.title,
                    position = list.size,
                    pageType = template?.pageType ?: "LINES",
                    backgroundArgb = template?.backgroundArgb,
                    texture = template?.texture ?: "NONE",
                    content = note.content,
                    plainText = note.plainText,
                ),
            )
            // Element positions are in page-width units; start a little below the estimated end of the text.
            val lineCount = RichDoc.decode(note.content).blocks.size
            var y = 0.32f + lineCount * 0.075f
            var z = 1
            attachments.getFor(OwnerType.NOTE.name, noteId).forEach { a ->
                if (a.kind == AttachmentKind.IMAGE.name) {
                    val aspect = if ((a.width ?: 0) > 0) (a.height ?: 1).toFloat() / a.width!! else 0.75f
                    val width = 0.56f
                    elements.insert(
                        PageElementEntity(
                            pageId = pageId, kind = ElementKind.IMAGE, x = 0.08f, y = y, width = width,
                            height = width * aspect, z = z++, data = ImageData(path = a.path, aspect = aspect).encode(),
                        ),
                    )
                    y += width * aspect + 0.12f
                } else {
                    elements.insert(
                        PageElementEntity(
                            pageId = pageId, kind = ElementKind.VOICE, x = 0.08f, y = y, width = 0.62f,
                            height = 0f, z = z++, data = VoiceData(path = a.path, durationMs = a.durationMs ?: 0).encode(),
                        ),
                    )
                    y += 0.2f
                }
            }
            pageId
        }
    }

    /**
     * Copies a notebook page, with everything placed on it, to the end of another notebook.
     * Pictures and recordings are shared by path (media is only removed once nothing uses it).
     * Returns the new page id.
     */
    suspend fun copyPageToNotebook(pageId: Long, notebookId: Long): Long = inOrder {
        db.withTransaction {
            val page = pages.get(pageId) ?: error("Page not found")
            val position = pages.getByNotebook(notebookId).size
            val stamp = now()
            val newId = pages.insert(
                page.copy(
                    id = 0, uid = newUid(), notebookId = notebookId, position = position, locked = false,
                    createdAt = stamp, updatedAt = stamp,
                ),
            )
            val idMap = HashMap<Long, Long>()
            val connectors = ArrayList<PageElementEntity>()
            elements.getForPage(pageId).forEach { e ->
                if (e.kind == ElementKind.CONNECTOR) connectors += e else idMap[e.id] = elements.insert(e.copy(id = 0, uid = newUid(), pageId = newId))
            }
            // Connectors point at items by id, which are new on the copy.
            connectors.forEach { c ->
                val cd = decodePayload<ConnectorData>(c.data) ?: return@forEach
                val from = idMap[cd.from] ?: return@forEach
                val to = idMap[cd.to] ?: return@forEach
                elements.insert(c.copy(id = 0, uid = newUid(), pageId = newId, data = cd.copy(from = from, to = to).encodePayload()))
            }
            notebooks.touch(notebookId, stamp)
            newId
        }
    }

    // ---- Media bookkeeping ----

    /** Every media path still referenced by a note attachment or page element. */
    suspend fun referencedMedia(): Set<String> = inOrder {
        db.withTransaction { removeAttachments(attachments.orphanIds()) }
        val fromAttachments = attachments.getAll().map { it.path }
        val fromElements = elements.getAll().flatMap { elementMediaPaths(it.kind, it.data) }
        val inline = pages.getAll().flatMap(::pageMediaPaths)
        (fromAttachments + fromElements + inline).toSet()
    }

    // ---- Backup ----

    /** A consistent copy of everything, read after all queued saves have landed. */
    suspend fun snapshot(): BackupData = inOrder {
        db.withTransaction {
            BackupData(
                exportedAt = now(),
                shelves = shelves.getAll(),
                notebooks = notebooks.getAll(),
                pages = pages.getAll(),
                elements = elements.getAll(),
                notes = notes.getAll(),
                attachments = attachments.getAll(),
            )
        }
    }

    /**
     * Imports a backup. With [replace], everything currently in the app is deleted first and the
     * backup's notes come back with their own permanent ids (so a synced note restored from a backup
     * updates the server's copy instead of making a duplicate). Otherwise the backup is added
     * alongside (shelves with the same name are merged), and anything already here gets a new id.
     */
    suspend fun importBackup(data: BackupData, replace: Boolean): ImportSummary = inOrder {
        db.withTransaction {
            val sync = db.syncDao()
            // Restoring may bring back rows deleted here; changes are still marked for upload.
            if (replace) sync.setMode(2)

            /**
             * Writes one row and returns its local id. Replacing: reuse the row with the same
             * permanent id if there is one (even a deleted one). Adding: give a new permanent id if
             * that one is already taken.
             */
            suspend fun <T> place(
                uid: String,
                find: suspend (String) -> T?,
                idOf: (T) -> Long,
                insert: suspend (String) -> Long,
                overwrite: suspend (Long) -> Unit,
            ): Long {
                val existing = find(uid)
                return when {
                    existing == null -> insert(uid)
                    replace -> idOf(existing).also { overwrite(it) }
                    else -> insert(newUid())
                }
            }

            if (replace) {
                removeNotes(notes.liveIds())
                removeAttachments(attachments.liveIds())
                removeShelves(shelves.liveIds())
            }
            val existingShelves = shelves.getAll()
            val shelfMap = HashMap<Long, Long>()
            var nextShelfPos = shelves.maxPosition() + 1
            data.shelves.sortedBy { it.position }.forEach { s ->
                val match = if (replace) null else existingShelves.firstOrNull { it.name.equals(s.name, ignoreCase = true) }
                shelfMap[s.id] = match?.id ?: run {
                    val row = s.copy(position = nextShelfPos++, deleted = false)
                    place(
                        s.uid, { shelves.byUid(it) }, { it.id },
                        insert = { uid -> shelves.insert(row.copy(id = 0, uid = uid)) },
                        overwrite = { id -> shelves.update(row.copy(id = id)) },
                    )
                }
            }
            val fallbackShelf = shelves.getAll().firstOrNull()?.id
                ?: shelves.insert(ShelfEntity(name = "Notebooks", position = 0))

            val notebookMap = HashMap<Long, Long>()
            data.notebooks.sortedBy { it.position }.forEach { n ->
                val shelfId = shelfMap[n.shelfId] ?: fallbackShelf
                val row = n.copy(shelfId = shelfId, position = notebooks.maxPosition(shelfId) + 1, deleted = false)
                notebookMap[n.id] = place(
                    n.uid, { notebooks.byUid(it) }, { it.id },
                    insert = { uid -> notebooks.insert(row.copy(id = 0, uid = uid)) },
                    overwrite = { id -> notebooks.update(row.copy(id = id)) },
                )
            }

            val pageMap = HashMap<Long, Long>()
            data.pages.forEach { p ->
                val nb = notebookMap[p.notebookId] ?: return@forEach
                val row = p.copy(notebookId = nb, deleted = false)
                pageMap[p.id] = place(
                    p.uid, { pages.byUid(it) }, { it.id },
                    insert = { uid -> pages.insert(row.copy(id = 0, uid = uid)) },
                    overwrite = { id -> pages.update(row.copy(id = id)) },
                )
            }

            fun placeElement(e: PageElementEntity, row: PageElementEntity): suspend () -> Long = {
                place(
                    e.uid, { elements.byUid(it) }, { it.id },
                    insert = { uid -> elements.insert(row.copy(id = 0, uid = uid)) },
                    overwrite = { id -> elements.update(row.copy(id = id)) },
                )
            }
            val elementMap = HashMap<Long, Long>()
            val connectors = ArrayList<PageElementEntity>()
            data.elements.forEach { e ->
                val page = pageMap[e.pageId] ?: return@forEach
                if (e.kind == ElementKind.CONNECTOR) {
                    connectors += e.copy(pageId = page)
                } else {
                    elementMap[e.id] = placeElement(e, e.copy(pageId = page, deleted = false))()
                }
            }
            // Connectors point at other elements by id, which have all been re-numbered.
            connectors.forEach { c ->
                val cd = decodePayload<ConnectorData>(c.data) ?: return@forEach
                val from = elementMap[cd.from] ?: return@forEach
                val to = elementMap[cd.to] ?: return@forEach
                placeElement(c, c.copy(data = cd.copy(from = from, to = to).encodePayload(), deleted = false))()
            }

            val noteMap = HashMap<Long, Long>()
            data.notes.forEach { n ->
                val row = n.copy(deleted = false)
                noteMap[n.id] = place(
                    n.uid, { notes.byUid(it) }, { it.id },
                    insert = { uid -> notes.insert(row.copy(id = 0, uid = uid)) },
                    overwrite = { id -> notes.update(row.copy(id = id)) },
                )
            }
            data.attachments.forEach { a ->
                if (a.ownerType != OwnerType.NOTE.name) return@forEach
                val owner = noteMap[a.ownerId] ?: return@forEach
                val row = a.copy(ownerId = owner, deleted = false)
                place(
                    a.uid, { attachments.byUid(it) }, { it.id },
                    insert = { uid -> attachments.insert(row.copy(id = 0, uid = uid)) },
                    overwrite = { id -> attachments.update(row.copy(id = id)) },
                )
            }
            if (replace) sync.setMode(0)
            ImportSummary(notebooks = notebookMap.size, pages = pageMap.size, notes = noteMap.size)
        }
    }

    // ---- Search ----

    companion object {
        /** Media files a page itself uses: in-line pictures and a section page's photo. */
        fun pageMediaPaths(page: PageEntity): List<String> =
            RichDoc.decode(page.content).mediaPaths() + listOfNotNull(page.sectionImage)

        /** Media files an element uses: its picture or recording, or pictures in line inside a text box. */

        fun elementMediaPaths(kind: String, data: String): List<String> = when (kind) {
            ElementKind.TEXT -> RichDoc.decode(decodePayload<TextBoxData>(data)?.doc).mediaPaths()
            else -> listOfNotNull(elementMediaPath(kind, data))
        }
    }

    suspend fun search(query: String, notebookId: Long? = null): SearchResults {
        val pattern = likePattern(query)
        val noteHits = if (notebookId == null) notes.search(pattern) else emptyList()
        return SearchResults(noteHits, pages.search(pattern, notebookId))
    }

    private fun likePattern(q: String): String =
        "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
