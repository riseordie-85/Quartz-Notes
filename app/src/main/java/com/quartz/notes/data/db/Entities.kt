package com.quartz.notes.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "notebooks",
    foreignKeys = [
        ForeignKey(
            entity = ShelfEntity::class,
            parentColumns = ["id"],
            childColumns = ["shelfId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("shelfId")],
)
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shelfId: Long,
    val title: String,
    val colorArgb: Int,
    /** [com.quartz.notes.data.model.Texture] name. */
    val texture: String = "GLOSS",
    /** Key into the notebook icon catalog, or "none". */
    val icon: String = "none",
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("notebookId")],
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: Long,
    val title: String = "",
    val position: Int = 0,
    /** [com.quartz.notes.data.model.PageType] name. */
    val pageType: String = "LINES",
    /** ARGB page color, or null for the theme's default page color. */
    val backgroundArgb: Int? = null,
    /** [com.quartz.notes.data.model.Texture] name. */
    val texture: String = "NONE",
    /** [com.quartz.notes.data.model.LayoutMode] name. */
    val layoutMode: String = "INLINE",
    /** Serialized [com.quartz.notes.data.rich.RichDoc]. */
    val content: String = "",
    /** Flattened text used for search. */
    val plainText: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * A freely placed item on a scrapbook page (shape, arrow, connector, image, voice note, ink stroke).
 * The table exists now so later phases don't need a schema migration to add it.
 */
@Entity(
    tableName = "page_elements",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class PageElementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val kind: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val rotation: Float = 0f,
    val z: Int = 0,
    /** Kind-specific JSON payload. */
    val data: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "quick_notes")
data class QuickNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    /** Serialized [com.quartz.notes.data.rich.RichDoc]. */
    val content: String = "",
    val plainText: String = "",
    /** Key into the note color palette. */
    val colorKey: String = "default",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "attachments", indices = [Index("ownerType", "ownerId")])
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [com.quartz.notes.data.model.OwnerType] name. */
    val ownerType: String,
    val ownerId: Long,
    /** [com.quartz.notes.data.model.AttachmentKind] name. */
    val kind: String,
    /** Path relative to the app's files directory. */
    val path: String,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** A page search hit or recent page, joined with its notebook. */
data class PageHit(
    val pageId: Long,
    val notebookId: Long,
    val title: String,
    val plainText: String,
    val updatedAt: Long,
    val notebookTitle: String,
    val notebookColor: Int,
)
