package com.quartz.notes.data.local.dao

import androidx.room.*
import com.quartz.notes.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("""
        SELECT * FROM notes 
        WHERE isTrashed = 0 
        AND (:folderId IS NULL OR folderId = :folderId)
        ORDER BY isPinned DESC, updatedAt DESC
    """)
    fun getActiveNotes(folderId: Long? = null): Flow<List<NoteEntity>>

    @Query("""
        SELECT * FROM notes 
        WHERE isTrashed = 0 
        AND (title LIKE '%' || :query || '%' OR contentMarkdown LIKE '%' || :query || '%')
        ORDER BY isPinned DESC, updatedAt DESC
    """)
    fun searchNotes(query: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isTrashed = 1 ORDER BY trashedAt DESC")
    fun getTrashedNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NoteEntity?

    @Upsert
    suspend fun upsertNote(note: NoteEntity): Long

    @Query("UPDATE notes SET isTrashed = 1, isPinned = 0, trashedAt = :timestamp WHERE id = :noteId")
    suspend fun moveToTrash(noteId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isTrashed = 0, trashedAt = NULL WHERE id = :noteId")
    suspend fun restoreFromTrash(noteId: Long)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deletePermanently(noteId: Long)

    @Query("SELECT * FROM folders ORDER BY isSystemFolder DESC, name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Upsert
    suspend fun upsertFolder(folder: FolderEntity): Long

    @Query("SELECT * FROM notes")
    suspend fun getAllNotesSync(): List<NoteEntity>

    @Query("SELECT * FROM folders")
    suspend fun getAllFoldersSync(): List<FolderEntity>
}
