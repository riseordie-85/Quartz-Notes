package com.quartz.notes.data

import androidx.room.withTransaction
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.PageHit
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
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

    fun deleteNote(id: Long) = enqueue { notes.deleteById(id) }

    /** Deletes the note if it has no title and no text (checked after all earlier saves land). */
    fun discardIfBlank(id: Long) = enqueue {
        val note = notes.get(id) ?: return@enqueue
        if (note.title.isBlank() && note.plainText.isBlank()) notes.deleteById(id)
    }

    /** Removes blank notes left behind (e.g. the app was closed with an empty note open). */
    fun sweepBlankNotes(except: Long?) = enqueue {
        notes.deleteBlank(except ?: -1L)
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
