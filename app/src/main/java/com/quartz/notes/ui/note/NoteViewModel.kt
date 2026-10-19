package com.quartz.notes.ui.note

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.editor.RichEditorState
import kotlinx.coroutines.flow.MutableStateFlow
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

    init {
        viewModelScope.launch {
            // Queued behind any pending saves, so this always reads the newest content.
            _load.value = repo.getNote(noteId)?.let { NoteLoad.Ready(NoteSession(it)) } ?: NoteLoad.Missing
        }
    }

    private val session: NoteSession? get() = (_load.value as? NoteLoad.Ready)?.session

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
        if (s.isBlank) {
            finished = true
            repo.deleteNote(s.id)
        } else {
            saveIfChanged()
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
