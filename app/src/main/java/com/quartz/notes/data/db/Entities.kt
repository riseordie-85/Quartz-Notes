package com.quartz.notes.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.UUID

/** A new permanent id for a synced row. Ids made by the database migration are 32 hex digits instead. */
fun newUid(): String = UUID.randomUUID().toString()

@Entity(tableName = "shelves", indices = [Index("uid", unique = true)])
@Serializable
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Permanent id, the same on every device and on the sync server. */
    val uid: String = newUid(),
    /** Deleted, kept as a marker until every device has heard (see [SyncStateEntity]). */
    val deleted: Boolean = false,
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
    indices = [Index("shelfId"), Index("uid", unique = true)],
)
@Serializable
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
    /** Show pages side by side when the screen is wide (unfolded / tablet). */
    val twoPage: Boolean = true,
    /** An index page at the front: every page, then every picture and recording. */
    val showIndex: Boolean = false,
    /** Section pages show as tabs along the page edges. */
    val sectionTabs: Boolean = true,
    /** New pictures and recordings go in a strip at the bottom of the page instead of anywhere on it. */
    val attachAtBottom: Boolean = false,
    val uid: String = newUid(),
    val deleted: Boolean = false,
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
    indices = [Index("notebookId"), Index("uid", unique = true)],
)
@Serializable
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
    /** Items on the page are frozen in place. */
    val locked: Boolean = false,
    /** Scrapbook page that spans both sides of an open spread. */
    val spread: Boolean = false,
    /**
     * A section's opening page: its title, a description (kept in [content]/[plainText]) and an icon or
     * photo, on a bold color ([backgroundArgb]). Sections also show as notebook tabs.
     */
    val isSection: Boolean = false,
    /** Icon catalog key for a section page. */
    val sectionIcon: String? = null,
    /** Media path of a section page's photo (used instead of the icon). */
    val sectionImage: String? = null,
    val uid: String = newUid(),
    val deleted: Boolean = false,
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
    indices = [Index("pageId"), Index("uid", unique = true)],
)
@Serializable
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
    val uid: String = newUid(),
    val deleted: Boolean = false,
)

@Entity(tableName = "quick_notes", indices = [Index("uid", unique = true)])
@Serializable
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
    /** Put away: hidden from the quick notes list, found under Settings → Archive. */
    val archived: Boolean = false,
    val uid: String = newUid(),
    val deleted: Boolean = false,
)

@Entity(tableName = "attachments", indices = [Index("ownerType", "ownerId"), Index("uid", unique = true)])
@Serializable
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
    val uid: String = newUid(),
    val deleted: Boolean = false,
)

/**
 * Sync bookkeeping for one row of a synced table, kept apart from the row itself so ordinary saves
 * (which write whole rows) can never overwrite it. Database triggers keep it up to date: any local
 * insert or change marks the row [dirty]. See [SyncTriggers].
 */
@Entity(tableName = "sync_state", primaryKeys = ["tbl", "uid"])
data class SyncStateEntity(
    /** Table name, e.g. "pages". */
    val tbl: String,
    val uid: String,
    /** Changed here since it was last sent to the server. */
    val dirty: Boolean,
    /** The server's revision of this row when this device last saw it; 0 if it has never been synced. */
    val rev: Long,
    /** When it last changed on this device (ms). */
    val changedAt: Long,
)

/**
 * One row (id 1). [mode] tells the triggers who is writing: 0 = the app (changes are marked for
 * upload; deleted rows can't come back), 1 = the sync engine applying server changes (nothing is
 * marked), 2 = restoring a backup over existing data (changes are marked; deleted rows can come back).
 */
@Entity(tableName = "sync_flags")
data class SyncFlagsEntity(
    @PrimaryKey val id: Int = 1,
    val mode: Int = 0,
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
