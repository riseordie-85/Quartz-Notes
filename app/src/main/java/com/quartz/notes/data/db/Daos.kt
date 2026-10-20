package com.quartz.notes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfDao {
    @Query("SELECT * FROM shelves ORDER BY position, id")
    fun observeAll(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves ORDER BY position, id")
    suspend fun getAll(): List<ShelfEntity>

    @Insert
    suspend fun insert(shelf: ShelfEntity): Long

    @Update
    suspend fun update(shelf: ShelfEntity)

    @Delete
    suspend fun delete(shelf: ShelfEntity)

    @Query("SELECT COALESCE(MAX(position), -1) FROM shelves")
    suspend fun maxPosition(): Int

    /** Also removes every notebook, page and page element (foreign keys cascade). */
    @Query("DELETE FROM shelves")
    suspend fun deleteAll()
}

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks WHERE shelfId = :shelfId ORDER BY position, id")
    fun observeByShelf(shelfId: Long): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    fun observe(id: Long): Flow<NotebookEntity?>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun get(id: Long): NotebookEntity?

    @Insert
    suspend fun insert(notebook: NotebookEntity): Long

    @Update
    suspend fun update(notebook: NotebookEntity)

    @Delete
    suspend fun delete(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks ORDER BY shelfId, position, id")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks")
    suspend fun getAll(): List<NotebookEntity>

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), 0) FROM notebooks")
    suspend fun fingerprint(): String

    @Insert
    suspend fun insertAll(notebooks: List<NotebookEntity>): List<Long>

    @Query("SELECT COALESCE(MAX(position), -1) FROM notebooks WHERE shelfId = :shelfId")
    suspend fun maxPosition(shelfId: Long): Int

    @Query("UPDATE notebooks SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE notebooks SET shelfId = :toShelf WHERE shelfId = :fromShelf")
    suspend fun moveAll(fromShelf: Long, toShelf: Long)
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position, id")
    fun observeByNotebook(notebookId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY position, id")
    suspend fun getByNotebook(notebookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun get(id: Long): PageEntity?

    @Query("SELECT * FROM pages")
    suspend fun getAll(): List<PageEntity>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Update
    suspend fun update(page: PageEntity)

    @Update
    suspend fun updateAll(pages: List<PageEntity>)

    @Delete
    suspend fun delete(page: PageEntity)

    @Query("UPDATE pages SET content = :content, plainText = :plainText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, plainText: String, updatedAt: Long)

    @Query("UPDATE pages SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE pages SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE pages SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("UPDATE pages SET spread = :spread WHERE id = :id")
    suspend fun setSpread(id: Long, spread: Boolean)

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), 0) FROM pages")
    suspend fun fingerprint(): String

    @Query(
        """
        SELECT p.id AS pageId, p.notebookId AS notebookId, p.title AS title, p.plainText AS plainText,
               p.updatedAt AS updatedAt, n.title AS notebookTitle, n.colorArgb AS notebookColor
        FROM pages p INNER JOIN notebooks n ON n.id = p.notebookId
        WHERE (p.title LIKE :pattern ESCAPE '\' OR p.plainText LIKE :pattern ESCAPE '\')
          AND (:notebookId IS NULL OR p.notebookId = :notebookId)
        ORDER BY p.updatedAt DESC
        LIMIT 200
        """,
    )
    suspend fun search(pattern: String, notebookId: Long?): List<PageHit>

    @Query(
        """
        SELECT p.id AS pageId, p.notebookId AS notebookId, p.title AS title, p.plainText AS plainText,
               p.updatedAt AS updatedAt, n.title AS notebookTitle, n.colorArgb AS notebookColor
        FROM pages p INNER JOIN notebooks n ON n.id = p.notebookId
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<PageHit>>
}

@Dao
interface QuickNoteDao {
    @Query("SELECT * FROM quick_notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<QuickNoteEntity>>

    @Query("SELECT * FROM quick_notes WHERE id = :id")
    suspend fun get(id: Long): QuickNoteEntity?

    @Query("SELECT * FROM quick_notes")
    suspend fun getAll(): List<QuickNoteEntity>

    @Insert
    suspend fun insert(note: QuickNoteEntity): Long

    @Query(
        """
        UPDATE quick_notes SET title = :title, content = :content, plainText = :plainText,
            colorKey = :colorKey, updatedAt = :updatedAt WHERE id = :id
        """,
    )
    suspend fun updateContent(id: Long, title: String, content: String, plainText: String, colorKey: String, updatedAt: Long)

    @Query("DELETE FROM quick_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE quick_notes SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM quick_notes")
    suspend fun deleteAll()

    /** Cheap change fingerprint: count and newest edit time. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updatedAt), 0) FROM quick_notes")
    suspend fun fingerprint(): String

    @Query(
        """
        DELETE FROM quick_notes
        WHERE id != :except AND TRIM(title) = '' AND TRIM(plainText, ' ' || char(9) || char(10) || char(13)) = ''
          AND id NOT IN (SELECT ownerId FROM attachments WHERE ownerType = 'NOTE')
        """,
    )
    suspend fun deleteBlank(except: Long)

    @Query(
        """
        SELECT * FROM quick_notes
        WHERE title LIKE :pattern ESCAPE '\' OR plainText LIKE :pattern ESCAPE '\'
        ORDER BY createdAt DESC
        LIMIT 200
        """,
    )
    suspend fun search(pattern: String): List<QuickNoteEntity>
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt, id")
    fun observeFor(ownerType: String, ownerId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt, id")
    suspend fun getFor(ownerType: String, ownerId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType ORDER BY createdAt, id")
    fun observeAllOf(ownerType: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments")
    suspend fun getAll(): List<AttachmentEntity>

    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Delete
    suspend fun delete(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteFor(ownerType: String, ownerId: Long)

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(id), 0) FROM attachments")
    suspend fun fingerprint(): String

    /** Removes note attachments whose note no longer exists. */
    @Query("DELETE FROM attachments WHERE ownerType = 'NOTE' AND ownerId NOT IN (SELECT id FROM quick_notes)")
    suspend fun deleteOrphans()
}

@Dao
interface PageElementDao {
    @Query("SELECT * FROM page_elements WHERE pageId = :pageId ORDER BY z, id")
    fun observeFor(pageId: Long): Flow<List<PageElementEntity>>

    @Query("SELECT * FROM page_elements")
    suspend fun getAll(): List<PageElementEntity>

    @Query("SELECT * FROM page_elements WHERE pageId = :pageId ORDER BY z, id")
    suspend fun getForPage(pageId: Long): List<PageElementEntity>

    @Query("SELECT COUNT(*) || ':' || COALESCE(SUM(x + y + width + rotation), 0) || ':' || COALESCE(SUM(LENGTH(data)), 0) FROM page_elements")
    suspend fun fingerprint(): String

    @Query("SELECT COALESCE(MAX(z), 0) FROM page_elements WHERE pageId = :pageId")
    suspend fun maxZ(pageId: Long): Int

    @Insert
    suspend fun insert(element: PageElementEntity): Long

    @Update
    suspend fun update(element: PageElementEntity)

    @Query("UPDATE page_elements SET x = :x, y = :y, width = :width, height = :height, rotation = :rotation WHERE id = :id")
    suspend fun updateGeometry(id: Long, x: Float, y: Float, width: Float, height: Float, rotation: Float)

    @Query("SELECT * FROM page_elements WHERE pageId = :pageId AND kind = :kind")
    suspend fun getKind(pageId: Long, kind: String): List<PageElementEntity>

    @Query("SELECT * FROM page_elements WHERE id = :id")
    suspend fun get(id: Long): PageElementEntity?

    @Query("UPDATE page_elements SET data = :data WHERE id = :id")
    suspend fun updateData(id: Long, data: String)

    @Query("UPDATE page_elements SET z = :z WHERE id = :id")
    suspend fun updateZ(id: Long, z: Int)

    @Query("DELETE FROM page_elements WHERE id = :id")
    suspend fun deleteById(id: Long)
}
