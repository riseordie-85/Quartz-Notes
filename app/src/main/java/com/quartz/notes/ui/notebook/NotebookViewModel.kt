package com.quartz.notes.ui.notebook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageEntity
import com.quartz.notes.data.db.ShelfEntity
import com.quartz.notes.data.rich.RichDoc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    fun savePage(pageId: Long, doc: RichDoc) {
        appScope.launch { repo.savePageContent(pageId, doc) }
    }

    fun saveTitle(pageId: Long, title: String) {
        appScope.launch { repo.savePageTitle(pageId, title) }
    }

    fun updateStyle(page: PageEntity) {
        appScope.launch { repo.updatePageStyle(page) }
    }

    fun applyStyleToAll(page: PageEntity) {
        appScope.launch { repo.applyStyleToAllPages(page) }
    }

    fun addPage(afterIndex: Int, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.insertPage(notebookId, afterIndex))
    }

    fun deletePage(page: PageEntity) = viewModelScope.launch { repo.deletePage(page) }

    fun movePage(from: Int, to: Int) = viewModelScope.launch { repo.movePage(notebookId, from, to) }

    fun updateNotebook(notebook: NotebookEntity) = viewModelScope.launch { repo.updateNotebook(notebook) }

    fun deleteNotebook(notebook: NotebookEntity, onDone: () -> Unit) {
        appScope.launch { repo.deleteNotebook(notebook) }
        onDone()
    }
}
