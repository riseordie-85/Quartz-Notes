package com.quartz.notes.ui.notebook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.StoredImage
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.ImageData
import com.quartz.notes.data.model.VoiceData
import com.quartz.notes.data.model.encode
import kotlinx.coroutines.flow.Flow
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

    /** How far the page is scrolled, in page widths; new pictures land in view. */
    var scrollUnits = 0f

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

    // ---- Pictures and voice notes on pages ----

    private val elementFlows = HashMap<Long, Flow<List<PageElementEntity>>>()

    fun elements(pageId: Long): Flow<List<PageElementEntity>> = elementFlows.getOrPut(pageId) { repo.observeElements(pageId) }

    private var stagger = 0

    private fun placementY(pageId: Long): Float {
        val y = (sessions[pageId]?.scrollUnits ?: 0f) + 0.18f + (stagger % 5) * 0.06f
        stagger++
        return y
    }

    fun addImage(pageId: Long, image: StoredImage) {
        val width = 0.6f
        val x = (1f - width) / 2 + ((stagger % 5) - 2) * 0.03f
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.IMAGE, x = x, y = placementY(pageId), width = width,
                height = width * image.aspect, rotation = listOf(-3f, 2f, -1.5f, 3f, 0f)[stagger % 5],
                data = ImageData(path = image.path, aspect = image.aspect).encode(),
            ),
        )
    }

    fun addVoice(pageId: Long, path: String, durationMs: Long) {
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.VOICE, x = 0.1f, y = placementY(pageId), width = 0.7f,
                data = VoiceData(path = path, durationMs = durationMs).encode(),
            ),
        )
    }

    fun updateElement(element: PageElementEntity) = repo.updateElement(element)
    fun bringToFront(element: PageElementEntity) = repo.bringToFront(element)
    fun deleteElement(element: PageElementEntity) = repo.deleteElement(element)

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
