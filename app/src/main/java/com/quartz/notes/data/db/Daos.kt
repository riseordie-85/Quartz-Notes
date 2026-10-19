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

    @Query("SELECT COALESCE(MAX(position), -1) FROM notebooks WHERE shelfId = :shelfId")
    suspend fun maxPosition(shelfId: Long): Int

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

    @Query(
        """
        DELETE FROM quick_notes
        WHERE id != :except AND TRIM(title) = '' AND TRIM(plainText, ' ' || char(9) || char(10) || char(13)) = ''
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
    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt")
    fun observeFor(ownerType: String, ownerId: Long): Flow<List<AttachmentEntity>>

    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Delete
    suspend fun delete(attachment: AttachmentEntity)
}
