package com.quartz.notes.ui.note

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.StoredImage
import com.quartz.notes.data.db.AttachmentEntity
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.editor.RichEditorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live editing state for one note. It lives in the ViewModel, not the composable, so the editor
 * survives the pane being rebuilt (switching notes on a tablet, visiting Settings and coming back).
 */
@Stable
class NoteSession(note: QuickNoteEntity) {
    val id: Long = note.id
    val uid: String = note.uid
    val editor = RichEditorState(RichDoc.decode(note.content))
    var title by mutableStateOf(note.title)
    var colorKey by mutableStateOf(note.colorKey)

    private var savedVersion = editor.version
    private var savedTitle = note.title
    private var savedColor = note.colorKey

    val isDirty: Boolean
        get() = editor.version != savedVersion || title != savedTitle || colorKey != savedColor

    val isBlank: Boolean get() = title.isBlank() && editor.isBlank()

    internal fun markSaved() {
        savedVersion = editor.version
        savedTitle = title
        savedColor = colorKey
    }

    /** True when [note] says the same as what's showing. */
    fun matches(note: QuickNoteEntity): Boolean =
        note.title == title && note.colorKey == colorKey && RichDoc.decode(note.content) == editor.toDoc()

    /** Shows another device's newer version (only when nothing here is unsaved). */
    fun adopt(note: QuickNoteEntity) {
        editor.replaceFromRemote(RichDoc.decode(note.content))
        title = note.title
        colorKey = note.colorKey
        markSaved()
    }
}

sealed interface NoteLoad {
    data object Loading : NoteLoad
    data object Missing : NoteLoad
    data class Ready(val session: NoteSession) : NoteLoad
}

class NoteViewModel(
    private val noteId: Long,
    private val repo: QuartzRepository,
) : ViewModel() {

    private val _load = MutableStateFlow<NoteLoad>(NoteLoad.Loading)
    val load: StateFlow<NoteLoad> = _load.asStateFlow()

    private var finished = false

    val attachments: StateFlow<List<AttachmentEntity>> =
        repo.observeNoteAttachments(noteId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addImage(image: StoredImage) = repo.addNoteImage(noteId, image.path, image.width, image.height)

    fun addVoice(path: String, durationMs: Long) = repo.addNoteVoice(noteId, path, durationMs)

    fun removeAttachment(attachment: AttachmentEntity) = repo.deleteAttachment(attachment)

    /** Saves any pending edits, then copies the note into a notebook. Returns the new page id. */
    suspend fun sendToNotebook(notebookId: Long): Long {
        saveIfChanged()
        return repo.copyNoteToNotebook(noteId, notebookId)
    }

    init {
        viewModelScope.launch {
            // Queued behind any pending saves, so this always reads the newest content.
            _load.value = repo.getNote(noteId)?.let { NoteLoad.Ready(NoteSession(it)) } ?: NoteLoad.Missing
            val session = (_load.value as? NoteLoad.Ready)?.session ?: return@launch
            // Another device changed this note: show it, or, if there are edits here that haven't
            // been saved yet, keep the other version as a copy so neither is lost.
            repo.remoteChanges.filter { it.table == "quick_notes" && it.uid == session.uid }.collect {
                val fresh = repo.noteNow(noteId) ?: return@collect
                if (session.matches(fresh)) return@collect
                if (session.isDirty) repo.keepNoteCopy(fresh) else session.adopt(fresh)
            }
        }
    }

    private val session: NoteSession? get() = (_load.value as? NoteLoad.Ready)?.session

    /** Saves the note (with any unsaved typing) as a template called [name]. */
    fun saveAsTemplate(name: String, onSaved: () -> Unit) {
        val s = session ?: return
        if (!finished && s.isDirty) {
            repo.saveNote(s.id, s.title, s.editor.toDoc(), s.colorKey)
            s.markSaved()
        }
        viewModelScope.launch {
            repo.saveNoteAsTemplate(noteId, name)
            onSaved()
        }
    }

    /** Saves if anything changed since the last save. Safe to call often. */
    fun saveIfChanged() {
        val s = session ?: return
        if (finished || !s.isDirty) return
        repo.saveNote(s.id, s.title, s.editor.toDoc(), s.colorKey)
        s.markSaved()
    }

    /** The user closed the note on purpose: save it, or discard it if it was left empty. */
    fun close() {
        val s = session ?: return
        if (finished) return
        saveIfChanged()
        if (s.isBlank) {
            // Checked against the database after the save lands, so pictures or voice notes
            // that haven't shown up here yet still keep the note.
            finished = true
            repo.discardIfBlank(s.id)
        }
    }

    fun delete() {
        finished = true
        repo.deleteNote(noteId)
    }

    override fun onCleared() {
        saveIfChanged()
    }
}
