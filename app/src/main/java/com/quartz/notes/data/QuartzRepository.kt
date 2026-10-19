package com.quartz.notes.data

import androidx.room.withTransaction
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.PageHit
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SearchResults(
    val notes: List<QuickNoteEntity>,
    val pages: List<PageHit>,
) {
    val isEmpty: Boolean get() = notes.isEmpty() && pages.isEmpty()
}

class QuartzRepository(private val db: QuartzDatabase) {
    private val shelves = db.shelfDao()
    private val notebooks = db.notebookDao()
    private val pages = db.pageDao()
    private val notes = db.quickNoteDao()

    /** Serializes content saves so a late debounced save can't overwrite a newer one. */
    private val writeLock = Mutex()

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
    suspend fun insertPage(notebookId: Long, afterIndex: Int): Long = db.withTransaction {
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

    suspend fun savePageContent(pageId: Long, doc: RichDoc) = writeLock.withLock {
        pages.updateContent(pageId, doc.encode(), doc.plainText(), now())
    }

    suspend fun savePageTitle(pageId: Long, title: String) = writeLock.withLock {
        pages.updateTitle(pageId, title, now())
    }

    /** Updates a page's style fields (type, colors, texture, layout). */
    suspend fun updatePageStyle(page: PageEntity) = writeLock.withLock {
        val current = pages.get(page.id) ?: return@withLock
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

    suspend fun applyStyleToAllPages(source: PageEntity) = writeLock.withLock {
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

    /** Deletes a page; a notebook always keeps at least one page. */
    suspend fun deletePage(page: PageEntity) = db.withTransaction {
        val list = pages.getByNotebook(page.notebookId)
        if (list.size <= 1) {
            pages.update(page.copy(title = "", content = "", plainText = "", updatedAt = now()))
            return@withTransaction
        }
        pages.delete(page)
        renumber(list.filter { it.id != page.id }.toMutableList())
    }

    suspend fun movePage(notebookId: Long, from: Int, to: Int) = db.withTransaction {
        val list = pages.getByNotebook(notebookId).toMutableList()
        if (from !in list.indices || to !in list.indices) return@withTransaction
        list.add(to, list.removeAt(from))
        renumber(list)
    }

    private suspend fun renumber(list: MutableList<PageEntity>) {
        val changed = list.mapIndexedNotNull { i, p -> if (p.position != i) p.copy(position = i) else null }
        if (changed.isNotEmpty()) pages.updateAll(changed)
    }

    // ---- Quick notes ----

    fun observeNotes(): Flow<List<QuickNoteEntity>> = notes.observeAll()

    suspend fun getNote(id: Long): QuickNoteEntity? = notes.get(id)

    suspend fun createNote(): Long = notes.insert(QuickNoteEntity(content = RichDoc().encode()))

    suspend fun saveNote(id: Long, title: String, doc: RichDoc, colorKey: String) = writeLock.withLock {
        notes.updateContent(id, title, doc.encode(), doc.plainText(), colorKey, now())
    }

    suspend fun deleteNote(id: Long) = writeLock.withLock { notes.deleteById(id) }

    // ---- Search ----

    suspend fun search(query: String, notebookId: Long? = null): SearchResults {
        val pattern = likePattern(query)
        val noteHits = if (notebookId == null) notes.search(pattern) else emptyList()
        return SearchResults(noteHits, pages.search(pattern, notebookId))
    }

    private fun likePattern(q: String): String =
        "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
