package com.quartz.notes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * Deleting: rows that have never reached the sync server are erased outright ("eraseUnsynced");
 * the rest are kept as deleted markers ("markDeleted") so other devices hear about it. Every query
 * the app shows leaves deleted rows out.
 */

@Dao
interface ShelfDao {
    @Query("SELECT uid FROM shelves WHERE id = :id")
    suspend fun uidOf(id: Long): String?

    @Query("SELECT * FROM shelves WHERE deleted = 0 ORDER BY position, id")
    fun observeAll(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelves WHERE deleted = 0 ORDER BY position, id")
    suspend fun getAll(): List<ShelfEntity>

    @Insert
    suspend fun insert(shelf: ShelfEntity): Long

    @Update
    suspend fun update(shelf: ShelfEntity)

    @Query("SELECT COALESCE(MAX(position), -1) FROM shelves WHERE deleted = 0")
    suspend fun maxPosition(): Int

    @Query("SELECT * FROM shelves WHERE uid = :uid")
    suspend fun byUid(uid: String): ShelfEntity?

    @Query("SELECT id FROM shelves WHERE deleted = 0")
    suspend fun liveIds(): List<Long>

    @Query("DELETE FROM shelves WHERE id IN (:ids) AND uid NOT IN (SELECT uid FROM sync_state WHERE tbl = 'shelves' AND rev > 0)")
    suspend fun eraseUnsynced(ids: List<Long>)

    @Query("UPDATE shelves SET deleted = 1 WHERE id IN (:ids) AND deleted = 0")
    suspend fun markDeleted(ids: List<Long>)

    /** Gives a shelf another permanent id (merging a new device's shelf into the server's one). */
    @Query("UPDATE shelves SET uid = :uid WHERE id = :id")
    suspend fun setUid(id: Long, uid: String)
}

@Dao
interface NotebookDao {
    @Query("SELECT uid FROM notebooks WHERE id = :id")
    suspend fun uidOf(id: Long): String?

    @Query("SELECT * FROM notebooks WHERE shelfId = :shelfId AND deleted = 0 ORDER BY position, id")
    fun observeByShelf(shelfId: Long): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id AND deleted = 0")
    fun observe(id: Long): Flow<NotebookEntity?>

    @Query("SELECT * FROM notebooks WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): NotebookEntity?

    @Insert
    suspend fun insert(notebook: NotebookEntity): Long

    @Update
    suspend fun update(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks WHERE deleted = 0 ORDER BY shelfId, position, id")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE deleted = 0")
    suspend fun getAll(): List<NotebookEntity>

    @Query("SELECT COALESCE(MAX(position), -1) FROM notebooks WHERE shelfId = :shelfId AND deleted = 0")
    suspend fun maxPosition(shelfId: Long): Int

    @Query("UPDATE notebooks SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE notebooks SET shelfId = :toShelf WHERE shelfId = :fromShelf AND deleted = 0")
    suspend fun moveAll(fromShelf: Long, toShelf: Long)

    @Query("SELECT * FROM notebooks WHERE uid = :uid")
    suspend fun byUid(uid: String): NotebookEntity?

    @Query("SELECT id FROM notebooks WHERE shelfId IN (:shelfIds) AND deleted = 0")
    suspend fun liveIdsIn(shelfIds: List<Long>): List<Long>

    @Query("DELETE FROM notebooks WHERE id IN (:ids) AND uid NOT IN (SELECT uid FROM sync_state WHERE tbl = 'notebooks' AND rev > 0)")
    suspend fun eraseUnsynced(ids: List<Long>)

    @Query("UPDATE notebooks SET deleted = 1 WHERE id IN (:ids) AND deleted = 0")
    suspend fun markDeleted(ids: List<Long>)
}

@Dao
interface PageDao {
    @Query("SELECT uid FROM pages WHERE id = :id")
    suspend fun uidOf(id: Long): String?

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND deleted = 0 ORDER BY position, id")
    fun observeByNotebook(notebookId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND deleted = 0 ORDER BY position, id")
    suspend fun getByNotebook(notebookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): PageEntity?

    @Query("SELECT * FROM pages WHERE deleted = 0")
    suspend fun getAll(): List<PageEntity>

    @Insert
    suspend fun insert(page: PageEntity): Long

    @Update
    suspend fun update(page: PageEntity)

    @Update
    suspend fun updateAll(pages: List<PageEntity>)

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

    @Query("UPDATE pages SET title = :title, content = :content, plainText = :plainText, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSectionText(id: Long, title: String, content: String, plainText: String, updatedAt: Long)

    @Query("UPDATE pages SET backgroundArgb = :color, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSectionColor(id: Long, color: Int, updatedAt: Long)

    @Query("UPDATE pages SET sectionIcon = :icon, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSectionIcon(id: Long, icon: String?, updatedAt: Long)

    @Query("UPDATE pages SET sectionImage = :image, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setSectionImage(id: Long, image: String?, updatedAt: Long)

    @Query(
        """
        SELECT p.id AS pageId, p.notebookId AS notebookId, p.title AS title, p.plainText AS plainText,
               p.updatedAt AS updatedAt, n.title AS notebookTitle, n.colorArgb AS notebookColor
        FROM pages p INNER JOIN notebooks n ON n.id = p.notebookId
        WHERE p.deleted = 0 AND n.deleted = 0
          AND (p.title LIKE :pattern ESCAPE '\' OR p.plainText LIKE :pattern ESCAPE '\')
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
        WHERE p.deleted = 0 AND n.deleted = 0
        ORDER BY p.updatedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<PageHit>>

    @Query("SELECT * FROM pages WHERE uid = :uid")
    suspend fun byUid(uid: String): PageEntity?

    @Query("SELECT id FROM pages WHERE notebookId IN (:notebookIds) AND deleted = 0")
    suspend fun liveIdsIn(notebookIds: List<Long>): List<Long>

    @Query("DELETE FROM pages WHERE id IN (:ids) AND uid NOT IN (SELECT uid FROM sync_state WHERE tbl = 'pages' AND rev > 0)")
    suspend fun eraseUnsynced(ids: List<Long>)

    @Query("UPDATE pages SET deleted = 1 WHERE id IN (:ids) AND deleted = 0")
    suspend fun markDeleted(ids: List<Long>)
}

@Dao
interface QuickNoteDao {
    @Query("SELECT uid FROM quick_notes WHERE id = :id")
    suspend fun uidOf(id: Long): String?

    /** Notes in the quick notes list: pinned ones first, archived ones left out. */
    @Query("SELECT * FROM quick_notes WHERE archived = 0 AND deleted = 0 ORDER BY pinned DESC, createdAt DESC")
    fun observeAll(): Flow<List<QuickNoteEntity>>

    @Query("SELECT * FROM quick_notes WHERE archived = 1 AND deleted = 0 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<QuickNoteEntity>>

    @Query("UPDATE quick_notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /** Archiving also unpins, so a restored note comes back in its normal place. */
    @Query("UPDATE quick_notes SET archived = :archived, pinned = 0, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("SELECT * FROM quick_notes WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): QuickNoteEntity?

    @Query("SELECT * FROM quick_notes WHERE deleted = 0")
    suspend fun getAll(): List<QuickNoteEntity>

    @Insert
    suspend fun insert(note: QuickNoteEntity): Long

    @Update
    suspend fun update(note: QuickNoteEntity)

    @Query(
        """
        UPDATE quick_notes SET title = :title, content = :content, plainText = :plainText,
            colorKey = :colorKey, updatedAt = :updatedAt WHERE id = :id
        """,
    )
    suspend fun updateContent(id: Long, title: String, content: String, plainText: String, colorKey: String, updatedAt: Long)

    @Query("UPDATE quick_notes SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    /** Blank notes (no title, no text, no attachments), apart from [except]. */
    @Query(
        """
        SELECT id FROM quick_notes
        WHERE deleted = 0 AND id != :except AND TRIM(title) = '' AND TRIM(plainText, ' ' || char(9) || char(10) || char(13)) = ''
          AND id NOT IN (SELECT ownerId FROM attachments WHERE ownerType = 'NOTE' AND deleted = 0)
        """,
    )
    suspend fun blankIds(except: Long): List<Long>

    @Query(
        """
        SELECT * FROM quick_notes
        WHERE archived = 0 AND deleted = 0 AND (title LIKE :pattern ESCAPE '\' OR plainText LIKE :pattern ESCAPE '\')
        ORDER BY createdAt DESC
        LIMIT 200
        """,
    )
    suspend fun search(pattern: String): List<QuickNoteEntity>

    @Query("SELECT * FROM quick_notes WHERE uid = :uid")
    suspend fun byUid(uid: String): QuickNoteEntity?

    @Query("SELECT id FROM quick_notes WHERE deleted = 0")
    suspend fun liveIds(): List<Long>

    @Query("DELETE FROM quick_notes WHERE id IN (:ids) AND uid NOT IN (SELECT uid FROM sync_state WHERE tbl = 'quick_notes' AND rev > 0)")
    suspend fun eraseUnsynced(ids: List<Long>)

    @Query("UPDATE quick_notes SET deleted = 1 WHERE id IN (:ids) AND deleted = 0")
    suspend fun markDeleted(ids: List<Long>)
}

@Dao
interface AttachmentDao {
    @Query("SELECT uid FROM attachments WHERE id = :id")
    suspend fun uidOf(id: Long): String?

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId AND deleted = 0 ORDER BY createdAt, id")
    fun observeFor(ownerType: String, ownerId: Long): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId AND deleted = 0 ORDER BY createdAt, id")
    suspend fun getFor(ownerType: String, ownerId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND deleted = 0 ORDER BY createdAt, id")
    fun observeAllOf(ownerType: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE deleted = 0")
    suspend fun getAll(): List<AttachmentEntity>

    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    @Update
    suspend fun update(attachment: AttachmentEntity)

    @Query("SELECT id FROM attachments WHERE ownerType = :ownerType AND ownerId IN (:ownerIds) AND deleted = 0")
    suspend fun liveIdsFor(ownerType: String, ownerIds: List<Long>): List<Long>

    @Query("SELECT id FROM attachments WHERE deleted = 0")
    suspend fun liveIds(): List<Long>

    /** Note attachments whose note is gone. */
    @Query(
        """
        SELECT id FROM attachments WHERE deleted = 0 AND ownerType = 'NOTE'
          AND ownerId NOT IN (SELECT id FROM quick_notes WHERE deleted = 0)
        """,
    )
    suspend fun orphanIds(): List<Long>

    @Query("SELECT * FROM attachments WHERE uid = :uid")
    suspend fun byUid(uid: String): AttachmentEntity?

    @Query("DELETE FROM attachments WHERE id IN (:ids) AND uid NOT IN (SELECT uid FROM sync_state WHERE tbl = 'attachments' AND rev > 0)")
    suspend fun eraseUnsynced(ids: List<Long>)

    @Query("UPDATE attachments SET deleted = 1 WHERE id IN (:ids) AND deleted = 0")
    suspend fun markDeleted(ids: List<Long>)
}

@Dao
interface PageElementDao {
    @Query("SELECT uid FROM page_elements WHERE id = :id")
    suspend fun uidOf(id: Long): String?

    @Query("SELECT * FROM page_elements WHERE pageId = :pageId AND deleted = 0 ORDER BY z, id")
    fun observeFor(pageId: Long): Flow<List<PageElementEntity>>

    @Query("SELECT * FROM page_elements WHERE deleted = 0")
    suspend fun getAll(): List<PageElementEntity>

    @Query("SELECT * FROM page_elements WHERE pageId = :pageId AND deleted = 0 ORDER BY z, id")
    suspend fun getForPage(pageId: Long): List<PageElementEntity>

    /** Pictures and recordings on every page of a notebook (for its index). */
    @Query(
        """
        SELECT e.* FROM page_elements e INNER JOIN pages p ON p.id = e.pageId
        WHERE p.notebookId = :notebookId AND e.deleted = 0 AND p.deleted = 0 AND e.kind IN ('IMAGE', 'VOICE', 'TEXT')
        ORDER BY p.position, e.y, e.x
        """,
    )
    fun observeMediaForNotebook(notebookId: Long): Flow<List<PageElementEntity>>

    @Query("SELECT COALESCE(MAX(z), 0) FROM page_elements WHERE pageId = :pageId AND deleted = 0")
    suspend fun maxZ(pageId: Long): Int

    @Insert
    suspend fun insert(element: PageElementEntity): Long

    @Update
    suspend fun update(element: PageElementEntity)

    @Query("UPDATE page_elements SET x = :x, y = :y, width = :width, height = :height, rotation = :rotation WHERE id = :id")
    suspend fun updateGeometry(id: Long, x: Float, y: Float, width: Float, height: Float, rotation: Float)

    @Query("SELECT * FROM page_elements WHERE pageId = :pageId AND kind = :kind AND deleted = 0")
    suspend fun getKind(pageId: Long, kind: String): List<PageElementEntity>

    @Query("SELECT * FROM page_elements WHERE id = :id AND deleted = 0")
    suspend fun get(id: Long): PageElementEntity?

    @Query("UPDATE page_elements SET data = :data WHERE id = :id")
    suspend fun updateData(id: Long, data: String)

    @Query("UPDATE page_elements SET z = :z WHERE id = :id")
    suspend fun updateZ(id: Long, z: Int)

    @Query("SELECT * FROM page_elements WHERE uid = :uid")
    suspend fun byUid(uid: String): PageElementEntity?

    @Query("SELECT id FROM page_elements WHERE pageId IN (:pageIds) AND deleted = 0")
    suspend fun liveIdsIn(pageIds: List<Long>): List<Long>

    @Query("DELETE FROM page_elements WHERE id IN (:ids) AND uid NOT IN (SELECT uid FROM sync_state WHERE tbl = 'page_elements' AND rev > 0)")
    suspend fun eraseUnsynced(ids: List<Long>)

    @Query("UPDATE page_elements SET deleted = 1 WHERE id IN (:ids) AND deleted = 0")
    suspend fun markDeleted(ids: List<Long>)
}

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state WHERE tbl = :tbl AND uid = :uid")
    suspend fun state(tbl: String, uid: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE dirty = 1 ORDER BY changedAt")
    suspend fun dirty(): List<SyncStateEntity>

    @Query("SELECT COUNT(*) FROM sync_state WHERE dirty = 1")
    fun observeDirtyCount(): Flow<Int>

    /** The newest local change waiting to upload (changes on every edit). */
    @Query("SELECT MAX(changedAt) FROM sync_state WHERE dirty = 1")
    fun observeNewestChange(): Flow<Long?>

    @Query("SELECT MAX(changedAt) FROM sync_state WHERE dirty = 1")
    suspend fun newestChange(): Long?

    @Query("SELECT mode FROM sync_flags WHERE id = 1")
    suspend fun mode(): Int

    /** Sets who's writing; see [SyncFlagsEntity]. */
    @Query("UPDATE sync_flags SET mode = :mode WHERE id = 1")
    suspend fun setMode(mode: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)

    @Query("UPDATE sync_state SET rev = :rev WHERE tbl = :tbl AND uid = :uid")
    suspend fun setRev(tbl: String, uid: String, rev: Long)

    /** Marks a row uploaded, unless it changed again while it was being sent. */
    @Query("UPDATE sync_state SET dirty = 0 WHERE tbl = :tbl AND uid = :uid AND changedAt = :changedAt")
    suspend fun clearDirty(tbl: String, uid: String, changedAt: Long)

    /** After signing out: everything uploads again to whichever server comes next. */
    @Query("UPDATE sync_state SET dirty = 1, rev = 0")
    suspend fun resetAll()

    @Query("DELETE FROM sync_state WHERE tbl = :tbl AND uid = :uid")
    suspend fun forget(tbl: String, uid: String)
}
