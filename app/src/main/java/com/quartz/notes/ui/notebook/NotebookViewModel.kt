package com.quartz.notes.ui.notebook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.rich.RichDoc
import com.quartz.notes.ui.editor.RichEditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Live editing state for one page, kept in the ViewModel so swiping away and back never reloads stale text. */
@Stable
class PageSession(page: PageEntity) {
    val id: Long = page.id
    val editor = RichEditorState(RichDoc.decode(page.content))
    var title by mutableStateOf(page.title)

    private var savedVersion = editor.version
    private var savedTitle = page.title

    val contentDirty: Boolean get() = editor.version != savedVersion
    val titleDirty: Boolean get() = title != savedTitle

    internal fun markContentSaved() {
        savedVersion = editor.version
    }

    internal fun markTitleSaved() {
        savedTitle = title
    }
}

class NotebookViewModel(
    val notebookId: Long,
    private val repo: QuartzRepository,
    private val appScope: CoroutineScope,
) : ViewModel() {

    val notebook: StateFlow<NotebookEntity?> =
        repo.observeNotebook(notebookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Null until the first load completes. */
    val pages: StateFlow<List<PageEntity>?> =
        repo.observePages(notebookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val shelves: StateFlow<List<ShelfEntity>> =
        repo.observeShelves().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Plain map: sessions are created on first display and are not observable state themselves.
    private val sessions = HashMap<Long, PageSession>()

    fun session(page: PageEntity): PageSession = sessions.getOrPut(page.id) { PageSession(page) }

    /** Saves a page's text and title if either changed. Opening or swiping past a page writes nothing. */
    fun saveIfChanged(pageId: Long) {
        val s = sessions[pageId] ?: return
        if (s.contentDirty) {
            repo.savePageContent(pageId, s.editor.toDoc())
            s.markContentSaved()
        }
        if (s.titleDirty) {
            repo.savePageTitle(pageId, s.title)
            s.markTitleSaved()
        }
    }

    fun updateStyle(page: PageEntity) = repo.updatePageStyle(page)

    fun applyStyleToAll(page: PageEntity) = repo.applyStyleToAllPages(page)

    fun addPage(afterIndex: Int, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.insertPage(notebookId, afterIndex))
    }

    /** Deletes a page; on a notebook's only page, clears it instead. */
    fun deletePage(page: PageEntity, isOnlyPage: Boolean) {
        if (isOnlyPage) {
            val s = session(page)
            s.title = ""
            s.editor.reset()
            saveIfChanged(page.id)
        } else {
            sessions.remove(page.id)
            repo.deletePage(page)
        }
    }

    fun movePage(from: Int, to: Int) = repo.movePage(notebookId, from, to)

    fun updateNotebook(notebook: NotebookEntity) = viewModelScope.launch { repo.updateNotebook(notebook) }

    fun deleteNotebook(notebook: NotebookEntity, onDone: () -> Unit) {
        sessions.clear()
        appScope.launch { repo.deleteNotebook(notebook) }
        onDone()
    }

    override fun onCleared() {
        sessions.keys.toList().forEach(::saveIfChanged)
    }
}
