package com.quartz.notes.data.local.entity

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
data class ChecklistItem(
    val id: String,
    val text: String,
    val isChecked: Boolean
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentFolderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentFolderId")]
)
@Serializable
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconName: String = "Folder",
    val parentFolderId: Long? = null,
    val isSystemFolder: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("folderId"), Index("updatedAt"), Index("isPinned"), Index("isTrashed")]
)
@Serializable
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long? = null,
    val title: String = "",
    val contentMarkdown: String = "",
    val checklistJson: String = "[]",
    val isPinned: Boolean = false,
    val isLocked: Boolean = false,
    val isTrashed: Boolean = false,
    val trashedAt: Long? = null,
    val customGradientId: String? = null,
    val customStartColorHex: Long? = null,
    val customEndColorHex: Long? = null,
    val customGradientAngle: Float = 135f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tags")
@Serializable
data class TagEntity(
    @PrimaryKey val name: String
)

@Entity(
    tableName = "note_tag_cross_ref",
    primaryKeys = ["noteId", "tagName"],
    indices = [Index("tagName")]
)
@Serializable
data class NoteTagCrossRef(
    val noteId: Long,
    val tagName: String
)
