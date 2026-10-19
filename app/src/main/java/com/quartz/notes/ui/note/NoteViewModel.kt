package com.quartz.notes.ui.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.QuickNoteEntity
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NoteLoad {
    data object Loading : NoteLoad
    data object Missing : NoteLoad
    data class Ready(val note: QuickNoteEntity) : NoteLoad
}

class NoteViewModel(
    private val noteId: Long,
    private val repo: QuartzRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    private val _load = MutableStateFlow<NoteLoad>(NoteLoad.Loading)
    val load: StateFlow<NoteLoad> = _load.asStateFlow()

    @Volatile private var deleted = false

    init {
        viewModelScope.launch {
            _load.value = repo.getNote(noteId)?.let { NoteLoad.Ready(it) } ?: NoteLoad.Missing
        }
    }

    fun save(title: String, doc: RichDoc, colorKey: String) {
        if (deleted) return
        appScope.launch { repo.saveNote(noteId, title, doc, colorKey) }
    }

    /** Called when the editor closes. Notes left completely empty are discarded. */
    fun saveOnClose(title: String, doc: RichDoc, colorKey: String) {
        if (deleted) return
        appScope.launch {
            if (title.isBlank() && doc.isBlank()) repo.deleteNote(noteId) else repo.saveNote(noteId, title, doc, colorKey)
        }
    }

    fun delete(onDone: () -> Unit) {
        deleted = true
        appScope.launch { repo.deleteNote(noteId) }
        onDone()
    }
}
