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
import com.quartz.notes.data.model.AttachmentKind
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.OwnerType
import com.quartz.notes.data.model.VoiceData
import com.quartz.notes.data.model.elementMediaPath
import com.quartz.notes.data.model.encode
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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

    // ---- Shelves ----

    fun observeShelves(): Flow<List<ShelfEntity>> = shelves.observeAll()

    suspend fun addShelf(name: String): Long =
        shelves.insert(ShelfEntity(name = name.trim(), position = shelves.maxPosition() + 1))

    suspend fun renameShelf(shelf: ShelfEntity, name: String) =
        shelves.update(shelf.copy(name = name.trim()))

    /** Deletes a shelf, moving its notebooks to [moveNotebooksTo] first. */
    suspend fun deleteShelf(shelf: ShelfEntity, moveNotebooksTo: Long) = db.withTransaction {
        notebooks.moveAll(shelf.id, moveNotebooksTo)
        shelves.delete(shelf)
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
    ): Long = db.withTransaction {
        val id = notebooks.insert(
            NotebookEntity(
                shelfId = shelfId,
                title = title.trim(),
                colorArgb = colorArgb,
                texture = texture,
                icon = icon,
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

    suspend fun deleteNotebook(notebook: NotebookEntity) = notebooks.delete(notebook)

    // ---- Pages ----

    fun observePages(notebookId: Long): Flow<List<PageEntity>> = pages.observeByNotebook(notebookId)

    fun observeRecentPages(limit: Int = 60): Flow<List<PageHit>> = pages.observeRecent(limit)

    /** Inserts a page after [afterIndex], copying the style of the page it follows. Returns the new id. */
    suspend fun insertPage(notebookId: Long, afterIndex: Int): Long = inOrder { insertPageNow(notebookId, afterIndex) }

    private suspend fun insertPageNow(notebookId: Long, afterIndex: Int): Long = db.withTransaction {
        val list = pages.getByNotebook(notebookId).toMutableList()
        val template = list.getOrNull(afterIndex) ?: list.lastOrNull()
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

    suspend fun getPage(id: Long): PageEntity? = inOrder { pages.get(id) }

    fun savePageContent(pageId: Long, doc: RichDoc) = enqueue {
        pages.updateContent(pageId, doc.encode(), doc.plainText(), now())
    }

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
                updatedAt = now(),
            ),
        )
    }

    fun applyStyleToAllPages(source: PageEntity) = enqueue {
        db.withTransaction {
            val list = pages.getByNotebook(source.notebookId)
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
            pages.delete(page)
            renumber(list.filter { it.id != page.id }.toMutableList())
        }
    }

    fun movePage(notebookId: Long, from: Int, to: Int) = enqueue {
        db.withTransaction {
            val list = pages.getByNotebook(notebookId).toMutableList()
            if (from !in list.indices || to !in list.indices) return@withTransaction
            list.add(to, list.removeAt(from))
            renumber(list)
        }
    }

    private suspend fun renumber(list: MutableList<PageEntity>) {
        val changed = list.mapIndexedNotNull { i, p -> if (p.position != i) p.copy(position = i) else null }
        if (changed.isNotEmpty()) pages.updateAll(changed)
    }

    // ---- Quick notes ----

    fun observeNotes(): Flow<List<QuickNoteEntity>> = notes.observeAll()

    suspend fun getNote(id: Long): QuickNoteEntity? = inOrder { notes.get(id) }

    suspend fun createNote(): Long = inOrder { notes.insert(QuickNoteEntity(content = RichDoc().encode())) }

    fun saveNote(id: Long, title: String, doc: RichDoc, colorKey: String) = enqueue {
        notes.updateContent(id, title, doc.encode(), doc.plainText(), colorKey, now())
    }

    fun deleteNote(id: Long) = enqueue {
        db.withTransaction {
            attachments.deleteFor(OwnerType.NOTE.name, id)
            notes.deleteById(id)
        }
    }

    /** Deletes the note if it has no title and no text (checked after all earlier saves land). */
    fun discardIfBlank(id: Long) = enqueue {
        val note = notes.get(id) ?: return@enqueue
        val hasAttachments = attachments.getFor(OwnerType.NOTE.name, id).isNotEmpty()
        if (note.title.isBlank() && note.plainText.isBlank() && !hasAttachments) notes.deleteById(id)
    }

    /** Removes blank notes left behind (e.g. the app was closed with an empty note open). */
    fun sweepBlankNotes(except: Long?) = enqueue {
        notes.deleteBlank(except ?: -1L)
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

    fun deleteAttachment(attachment: AttachmentEntity) = enqueue { attachments.delete(attachment) }

    // ---- Page elements (pictures and voice notes placed on pages) ----

    fun observeElements(pageId: Long): Flow<List<PageElementEntity>> = elements.observeFor(pageId)

    /** Adds an element on top of everything else on the page. */
    fun addElement(element: PageElementEntity) = enqueue {
        elements.insert(element.copy(z = elements.maxZ(element.pageId) + 1))
        pages.touch(element.pageId, now())
    }

    fun updateElement(element: PageElementEntity) = enqueue { elements.update(element) }

    fun bringToFront(element: PageElementEntity) = enqueue {
        elements.update(element.copy(z = elements.maxZ(element.pageId) + 1))
    }

    fun deleteElement(element: PageElementEntity) = enqueue { elements.deleteById(element.id) }

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
            val template = list.lastOrNull()
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

    // ---- Media bookkeeping ----

    /** Every media path still referenced by a note attachment or page element. */
    suspend fun referencedMedia(): Set<String> = inOrder {
        attachments.deleteOrphans()
        val fromAttachments = attachments.getAll().map { it.path }
        val fromElements = elements.getAll().mapNotNull { elementMediaPath(it.kind, it.data) }
        (fromAttachments + fromElements).toSet()
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

    /** Changes whenever notes, pages, notebooks or their media change. */
    suspend fun fingerprint(): String = inOrder {
        listOf(
            shelves.getAll().joinToString(",") { "${it.id}${it.name}${it.position}" }.hashCode().toString(),
            notebooks.fingerprint(),
            pages.fingerprint(),
            notes.fingerprint(),
            attachments.fingerprint(),
            elements.fingerprint(),
        ).joinToString("|")
    }

    /**
     * Imports a backup. With [replace], everything currently in the app is removed first; otherwise the
     * backup is added alongside (shelves with the same name are merged). Ids are re-assigned.
     */
    suspend fun importBackup(data: BackupData, replace: Boolean): ImportSummary = inOrder {
        db.withTransaction {
            if (replace) {
                attachments.deleteAll()
                notes.deleteAll()
                shelves.deleteAll()
            }
            val existingShelves = shelves.getAll()
            val shelfMap = HashMap<Long, Long>()
            var nextShelfPos = shelves.maxPosition() + 1
            data.shelves.sortedBy { it.position }.forEach { s ->
                val match = if (replace) null else existingShelves.firstOrNull { it.name.equals(s.name, ignoreCase = true) }
                shelfMap[s.id] = match?.id ?: shelves.insert(s.copy(id = 0, position = nextShelfPos++))
            }
            val fallbackShelf = shelves.getAll().firstOrNull()?.id
                ?: shelves.insert(ShelfEntity(name = "Notebooks", position = 0))

            val notebookMap = HashMap<Long, Long>()
            data.notebooks.sortedBy { it.position }.forEach { n ->
                val shelfId = shelfMap[n.shelfId] ?: fallbackShelf
                notebookMap[n.id] = notebooks.insert(n.copy(id = 0, shelfId = shelfId, position = notebooks.maxPosition(shelfId) + 1))
            }

            val pageMap = HashMap<Long, Long>()
            data.pages.forEach { p ->
                val nb = notebookMap[p.notebookId] ?: return@forEach
                pageMap[p.id] = pages.insert(p.copy(id = 0, notebookId = nb))
            }
            data.elements.forEach { e ->
                val page = pageMap[e.pageId] ?: return@forEach
                elements.insert(e.copy(id = 0, pageId = page))
            }

            val noteMap = HashMap<Long, Long>()
            data.notes.forEach { n -> noteMap[n.id] = notes.insert(n.copy(id = 0)) }
            data.attachments.forEach { a ->
                if (a.ownerType != OwnerType.NOTE.name) return@forEach
                val owner = noteMap[a.ownerId] ?: return@forEach
                attachments.insert(a.copy(id = 0, ownerId = owner))
            }
            ImportSummary(notebooks = notebookMap.size, pages = pageMap.size, notes = noteMap.size)
        }
    }

    // ---- Search ----

    suspend fun search(query: String, notebookId: Long? = null): SearchResults {
        val pattern = likePattern(query)
        val noteHits = if (notebookId == null) notes.search(pattern) else emptyList()
        return SearchResults(noteHits, pages.search(pattern, notebookId))
    }

    private fun likePattern(q: String): String =
        "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
