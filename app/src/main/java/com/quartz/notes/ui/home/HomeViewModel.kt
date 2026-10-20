package com.quartz.notes.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.SettingsRepository
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.model.AttachmentKind
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.dateLabel
import com.quartz.notes.ui.toLocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NoteUi(
    val id: Long,
    val title: String,
    val preview: String,
    val colorKey: String,
    val createdAt: Long,
    /** Up to three picture paths, for thumbnails. */
    val images: List<String> = emptyList(),
    val imageCount: Int = 0,
    val voiceCount: Int = 0,
)

data class NoteGroup(val label: String, val notes: List<NoteUi>)

data class HomeState(
    val loaded: Boolean = false,
    val shelves: List<ShelfEntity> = emptyList(),
    val selectedShelf: ShelfEntity? = null,
    val notebooks: List<NotebookEntity> = emptyList(),
    val groups: List<NoteGroup> = emptyList(),
    val grid: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repo: QuartzRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val shelves = repo.observeShelves()

    private val selectedShelf = combine(shelves, settings.settings) { list, s ->
        list.firstOrNull { it.id == s.selectedShelfId } ?: list.firstOrNull()
    }

    private val notebooks = selectedShelf
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeNotebooks(id) }

    private val groups = combine(repo.observeNotes(), repo.observeAllNoteAttachments()) { notes, attachments ->
        groupNotes(notes, attachments.groupBy { it.ownerId })
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<HomeState> = combine(shelves, selectedShelf, notebooks, groups, settings.settings) { sh, sel, nb, gr, s ->
        HomeState(loaded = true, shelves = sh, selectedShelf = sel, notebooks = nb, groups = gr, grid = s.quickNotesGrid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    fun selectShelf(id: Long) = viewModelScope.launch { settings.setSelectedShelf(id) }

    fun setGrid(grid: Boolean) = viewModelScope.launch { settings.setQuickNotesGrid(grid) }

    fun addShelf(name: String) = viewModelScope.launch {
        val id = repo.addShelf(name)
        settings.setSelectedShelf(id)
    }

    fun createNotebook(
        shelfId: Long,
        title: String,
        color: Int,
        texture: String,
        icon: String,
        twoPage: Boolean = true,
        onCreated: (Long) -> Unit = {},
    ) =
        viewModelScope.launch {
            onCreated(repo.createNotebook(shelfId, title, color, texture, icon, twoPage))
        }

    fun updateNotebook(notebook: NotebookEntity) = viewModelScope.launch { repo.updateNotebook(notebook) }

    fun deleteNotebook(notebook: NotebookEntity) = viewModelScope.launch { repo.deleteNotebook(notebook) }

    fun createNote(onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.createNote())
    }

    fun discardIfBlank(noteId: Long) = repo.discardIfBlank(noteId)

    fun sweepBlankNotes(except: Long?) = repo.sweepBlankNotes(except)

    private fun groupNotes(notes: List<QuickNoteEntity>, attachments: Map<Long, List<AttachmentEntity>>): List<NoteGroup> {
        if (notes.isEmpty()) return emptyList()
        return notes
            .groupBy { it.createdAt.toLocalDate() }
            .map { (date, list) -> NoteGroup(dateLabel(date), list.map { toUi(it, attachments[it.id].orEmpty()) }) }
    }

    private fun toUi(note: QuickNoteEntity, attachments: List<AttachmentEntity>): NoteUi {
        val lines = RichDoc.decode(note.content).previewLines()
        val title: String
        val preview: String
        if (note.title.isNotBlank()) {
            title = note.title.trim()
            preview = lines.joinToString("\n")
        } else {
            title = lines.firstOrNull()?.removePrefix("• ")?.removePrefix("☐ ")?.removePrefix("☑ ") ?: ""
            preview = lines.drop(1).joinToString("\n")
        }
        val images = attachments.filter { it.kind == AttachmentKind.IMAGE.name }
        return NoteUi(
            id = note.id,
            title = title,
            preview = preview.take(400),
            colorKey = note.colorKey,
            createdAt = note.createdAt,
            images = images.take(3).map { it.path },
            imageCount = images.size,
            voiceCount = attachments.count { it.kind == AttachmentKind.AUDIO.name },
        )
    }
}
