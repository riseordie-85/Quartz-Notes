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
import com.quartz.notes.data.model.ArrowData
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ShapeData
import com.quartz.notes.data.model.ShapeType
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.TextBoxData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encode
import com.quartz.notes.data.model.encodePayload
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

    /** Editors for this page's text boxes, by element id. */
    val textBoxes = HashMap<Long, RichEditorState>()

    /** Editor version last saved for each text box, so unsaved typing is never dropped. */
    val textBoxSaved = HashMap<Long, Int>()

    /** The text box being typed in, if any; the toolbar formats it instead of the page text. */
    var activeEditor by mutableStateOf<RichEditorState?>(null)

    /** Set while the page is on screen: its words and a picture of it, for sharing. */
    var exporter: PageExporter? = null

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

/** Shares what's on a page that's currently showing. */
class PageExporter(
    val text: () -> String,
    /** Draws the page (without selection handles) to a PNG and returns the file. */
    val image: suspend () -> java.io.File,
)

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

    /** Places an existing picture at a given spot (used when an in-line picture is set to float). */
    fun addImageAt(pageId: Long, data: ImageData, x: Float, y: Float, width: Float) {
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.IMAGE, x = x, y = y, width = width,
                height = width * data.aspect, data = data.encode(),
            ),
        )
    }

    /**
     * Switches a page between flowing text and scrapbook. Going to scrapbook, the page's text moves
     * into a text box so nothing is lost.
     */
    fun setLayoutMode(page: PageEntity, scrapbook: Boolean) {
        val session = session(page)
        viewModelScope.launch {
            if (scrapbook && !session.editor.isBlank()) {
                val doc = session.editor.toDoc()
                addTextBox(page.id, 0.05f, 0.24f, 0.9f, doc)
                session.editor.reset()
                saveIfChanged(page.id)
            }
            repo.updatePageStyle(page.copy(layoutMode = if (scrapbook) "SCRAPBOOK" else "INLINE"))
        }
    }

    fun addVoice(pageId: Long, path: String, durationMs: Long) {
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.VOICE, x = 0.1f, y = placementY(pageId), width = 0.7f,
                data = VoiceData(path = path, durationMs = durationMs).encode(),
            ),
        )
    }

    // ---- Text boxes, shapes, arrows, connectors, drawings ----

    /** Adds a text box at (x, y) in page widths and returns its id. */
    suspend fun addTextBox(pageId: Long, x: Float, y: Float, width: Float, doc: RichDoc = RichDoc()): Long =
        repo.insertElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.TEXT, x = x, y = y, width = width,
                data = TextBoxData(doc.encode()).encodePayload(),
            ),
        )

    fun textEditor(element: PageElementEntity): RichEditorState {
        knownTextBoxes[element.id] = element
        fun fresh() = RichEditorState(RichDoc.decode(decodePayload<TextBoxData>(element.data)?.doc))
        val map = sessions[element.pageId]?.textBoxes ?: return fresh()
        return map.getOrPut(element.id) {
            fresh().also { sessions[element.pageId]?.textBoxSaved?.set(element.id, it.version) }
        }
    }

    /** Saves a text box if it changed since its last save. */
    fun saveTextBox(element: PageElementEntity) {
        val session = sessions[element.pageId] ?: return
        val state = session.textBoxes[element.id] ?: return
        if (session.textBoxSaved[element.id] == state.version) return
        repo.updateElementData(element, TextBoxData(state.toDoc().encode()).encodePayload())
        session.textBoxSaved[element.id] = state.version
    }

    private var shapeCount = 0

    fun addShape(pageId: Long, type: ShapeType) {
        val fills = listOf(0xFF00A8D6.toInt(), 0xFFD6004C.toInt(), 0xFFFFD600.toInt(), 0xFF2E9E5B.toInt())
        val w = if (type == ShapeType.RECTANGLE || type == ShapeType.ROUNDED) 0.36f else 0.3f
        val h = if (type == ShapeType.RECTANGLE || type == ShapeType.ROUNDED) 0.24f else 0.3f
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.SHAPE, x = (1f - w) / 2 + ((shapeCount % 3) - 1) * 0.05f,
                y = placementY(pageId), width = w, height = h,
                data = ShapeData(shape = type, fill = fills[shapeCount % fills.size]).encodePayload(),
            ),
        )
        shapeCount++
    }

    fun addArrow(pageId: Long) {
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.ARROW, x = 0.3f, y = placementY(pageId) + 0.1f, width = 0f,
                data = ArrowData(dx = 0.4f, dy = 0f).encodePayload(),
            ),
        )
    }

    fun addStroke(pageId: Long, pointsUnits: List<Float>, color: Int, level: Int) {
        if (pointsUnits.size < 2) return
        val xs = pointsUnits.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = pointsUnits.filterIndexed { i, _ -> i % 2 == 1 }
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.STROKE, x = xs.min(), y = ys.min(),
                width = xs.max() - xs.min(), height = ys.max() - ys.min(),
                data = StrokeData(points = pointsUnits, color = color, level = level).encodePayload(),
            ),
        )
    }

    fun addConnector(pageId: Long, from: Long, to: Long) {
        if (from == to) return
        repo.addElement(
            PageElementEntity(pageId = pageId, kind = ElementKind.CONNECTOR, data = ConnectorData(from = from, to = to).encodePayload()),
        )
    }

    fun replaceElement(element: PageElementEntity) = repo.replaceElement(element)

    fun moveElement(element: PageElementEntity) = repo.moveElement(element)
    fun updateElementData(element: PageElementEntity, data: String) = repo.updateElementData(element, data)
    fun bringToFront(element: PageElementEntity) = repo.bringToFront(element)
    fun deleteElement(element: PageElementEntity) = repo.deleteElement(element)

    fun updateStyle(page: PageEntity) = repo.updatePageStyle(page)

    /** Copies a page (saving any unsaved typing first) to the end of a notebook. */
    suspend fun copyPageTo(pageId: Long, notebookId: Long): Long {
        saveIfChanged(pageId)
        knownTextBoxes.values.filter { it.pageId == pageId }.forEach(::saveTextBox)
        return repo.copyPageToNotebook(pageId, notebookId)
    }

    fun setPageLocked(page: PageEntity, locked: Boolean) = repo.setPageLocked(page.id, locked)

    fun setPageSpread(page: PageEntity, spread: Boolean) = repo.setPageSpread(page.id, spread)

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

    /** Text box element rows, so unsaved boxes can be written when the screen closes. */
    val knownTextBoxes = HashMap<Long, PageElementEntity>()

    override fun onCleared() {
        sessions.keys.toList().forEach(::saveIfChanged)
        knownTextBoxes.values.forEach(::saveTextBox)
    }
}
