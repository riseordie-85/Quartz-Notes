package com.quartz.notes.ui.notes

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.backup.AutoBackupWorker
import com.quartz.notes.data.backup.BackupManager
import com.quartz.notes.data.local.QuartzDatabase
import com.quartz.notes.data.local.entity.NoteEntity
import com.quartz.notes.data.preferences.ThemePreferences
import com.quartz.notes.domain.model.GradientSpec
import com.quartz.notes.domain.model.QuartzPresets
import com.quartz.notes.domain.model.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val db = QuartzDatabase.getInstance(application)
    private val dao = db.noteDao()
    private val prefs = ThemePreferences(application)
    private val backupManager = BackupManager(application, dao)

    val searchQuery = MutableStateFlow("")

    val notes: StateFlow<List<NoteEntity>> = searchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) dao.getActiveNotes(null)
            else dao.searchNotes(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val themeMode: StateFlow<ThemeMode> = prefs.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.DARK)

    val globalGradient: StateFlow<GradientSpec> = prefs.globalGradient
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QuartzPresets.ObsidianQuartz)

    val autoBackupEnabled: StateFlow<Boolean> = prefs.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoBackupIntervalHours: StateFlow<Long> = prefs.autoBackupIntervalHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 24L)

    val autoBackupUri: StateFlow<String?> = prefs.autoBackupDirectoryUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun updateSearch(query: String) {
        searchQuery.value = query
    }

    fun saveNote(note: NoteEntity) {
        viewModelScope.launch {
            if (note.title.isNotBlank() || note.contentMarkdown.isNotBlank() || note.checklistJson != "[]") {
                dao.upsertNote(note)
            }
        }
    }

    fun deleteNoteToTrash(noteId: Long) {
        viewModelScope.launch {
            dao.moveToTrash(noteId)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setGlobalGradient(spec: GradientSpec) {
        viewModelScope.launch { prefs.setGlobalGradient(spec) }
    }

    fun exportManualZip(uri: Uri) {
        viewModelScope.launch {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                backupManager.exportMarkdownZipToStream(stream)
            }
        }
    }

    fun exportManualJson(uri: Uri) {
        viewModelScope.launch {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                backupManager.exportJsonSnapshotToStream(stream)
            }
        }
    }

    fun setAutoBackupFolder(uri: Uri) {
        viewModelScope.launch {
            prefs.setAutoBackupDirectory(uri.toString())
        }
    }

    fun setAutoBackupSchedule(enabled: Boolean, intervalHours: Long) {
        viewModelScope.launch {
            prefs.setAutoBackupSchedule(enabled, intervalHours)
            if (enabled) {
                AutoBackupWorker.schedule(getApplication(), intervalHours)
            } else {
                AutoBackupWorker.cancel(getApplication())
            }
        }
    }
}
