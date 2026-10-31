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
import com.quartz.notes.data.model.ImagePlacement
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodeVoiceData
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

    /** A section page's description (sections keep it as plain text). */
    var description by mutableStateOf(if (page.isSection) page.plainText else "")

    private var savedVersion = editor.version
    private var savedTitle = page.title
    private var savedDescription = description

    val sectionDirty: Boolean get() = title != savedTitle || description != savedDescription

    internal fun markSectionSaved() {
        savedTitle = title
        savedDescription = description
    }

    val contentDirty: Boolean get() = editor.version != savedVersion
    val titleDirty: Boolean get() = title != savedTitle

    internal fun markContentSaved() {
        savedVersion = editor.version
    }

    internal fun markTitleSaved() {
        savedTitle = title
    }

    val anyDirty: Boolean get() = contentDirty || titleDirty || sectionDirty

    /** True when [page] says the same as what's showing. */
    fun matches(page: PageEntity): Boolean =
        page.title == title && RichDoc.decode(page.content) == editor.toDoc() &&
            (!page.isSection || page.plainText == description)

    /** Shows another device's newer version (only when nothing here is unsaved). */
    fun adopt(page: PageEntity) {
        editor.replaceFromRemote(RichDoc.decode(page.content))
        title = page.title
        if (page.isSection) description = page.plainText
        markContentSaved()
        markSectionSaved()
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

    init {
        // Pages and text boxes changed on another device: show the new version where nothing is
        // unsaved here; otherwise keep the other version of the page as a copy.
        viewModelScope.launch {
            repo.remoteChanges.collect { change ->
                when (change.table) {
                    "pages" -> {
                        val page = repo.pageByUidNow(change.uid) ?: return@collect
                        val session = sessions[page.id] ?: return@collect
                        if (session.matches(page)) return@collect
                        if (session.anyDirty) repo.keepPageCopy(page) else session.adopt(page)
                    }
                    "page_elements" -> {
                        val element = repo.elementByUidNow(change.uid) ?: return@collect
                        if (element.kind != ElementKind.TEXT) return@collect
                        val session = sessions[element.pageId] ?: return@collect
                        val state = session.textBoxes[element.id] ?: return@collect
                        if (session.textBoxSaved[element.id] != state.version) return@collect
                        val doc = RichDoc.decode(decodePayload<TextBoxData>(element.data)?.doc)
                        if (doc == state.toDoc()) return@collect
                        state.replaceFromRemote(doc)
                        session.textBoxSaved[element.id] = state.version
                    }
                }
            }
        }
    }

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

    // ---- Sections ----

    /** Saves a section page's title and description (its look is saved separately, field by field). */
    fun saveSection(page: PageEntity) {
        val s = session(page)
        repo.saveSectionText(page.id, s.title, s.description)
        s.markSectionSaved()
    }

    fun setSectionColor(page: PageEntity, color: Int) = repo.setSectionColor(page.id, color)

    fun setSectionIcon(page: PageEntity, icon: String?) = repo.setSectionIcon(page.id, icon)

    fun setSectionImage(page: PageEntity, image: String?) = repo.setSectionImage(page.id, image)

    fun saveSectionIfChanged(page: PageEntity) {
        val s = sessions[page.id] ?: return
        if (s.sectionDirty) saveSection(page)
    }

    /** Adds a new section page at [position] (0-based) and reports its id. */
    fun addSection(position: Int, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val count = pages.value.orEmpty().count { it.isSection }
        onCreated(repo.insertSection(notebookId, position, nextSectionColor(count)))
    }

    /** Pictures, recordings and text boxes across the notebook, for the index page. */
    val notebookMedia: StateFlow<List<PageElementEntity>> =
        repo.observeNotebookMedia(notebookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- Pictures and voice notes on pages ----

    /** Whether new pictures and recordings go in the strip at the bottom of the page. */
    private val attachAtBottom: Boolean get() = notebook.value?.attachAtBottom == true

    /** Moves a picture or recording from the bottom strip onto the page itself. */
    fun placeAttachment(e: PageElementEntity) {
        val y = placementY(e.pageId)
        when (e.kind) {
            ElementKind.IMAGE -> {
                val d = decodeImageData(e.data) ?: return
                val w = 0.6f
                repo.replaceElement(
                    e.copy(x = (1f - w) / 2, y = y, width = w, height = w * d.aspect, data = d.copy(placement = ImagePlacement.FLOAT).encode()),
                )
            }
            ElementKind.VOICE -> {
                val d = decodeVoiceData(e.data) ?: return
                repo.replaceElement(e.copy(x = 0.1f, y = y, width = 0.7f, data = d.copy(attached = false).encode()))
            }
        }
    }

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
                height = width * image.aspect,
                rotation = if (attachAtBottom) 0f else listOf(-3f, 2f, -1.5f, 3f, 0f)[stagger % 5],
                data = ImageData(
                    path = image.path,
                    aspect = image.aspect,
                    placement = if (attachAtBottom) ImagePlacement.ATTACHED else ImagePlacement.FLOAT,
                ).encode(),
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
                data = VoiceData(path = path, durationMs = durationMs, attached = attachAtBottom).encode(),
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

    /** Saves a page (with any unsaved typing) as a template called [name]. */
    fun saveAsTemplate(pageId: Long, name: String, onSaved: () -> Unit) = viewModelScope.launch {
        saveIfChanged(pageId)
        knownTextBoxes.values.filter { it.pageId == pageId }.forEach(::saveTextBox)
        repo.savePageAsTemplate(pageId, name)
        onSaved()
    }

    fun addPageFromTemplate(afterIndex: Int, templateId: Long, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.insertPageFromTemplate(notebookId, afterIndex, templateId))
    }

    fun addPageFromStarter(afterIndex: Int, starter: com.quartz.notes.data.templates.StarterPage, onCreated: (Long) -> Unit) =
        viewModelScope.launch { onCreated(repo.insertPageFromStarter(notebookId, afterIndex, starter)) }

    /** Deletes a page; on a notebook's only page, clears it instead. */
    fun deletePage(page: PageEntity, isOnlyPage: Boolean) {
        if (isOnlyPage) {
            val s = session(page)
            s.title = ""
            s.editor.reset()
            if (page.isSection) {
                s.description = ""
                saveSection(page)
            } else {
                saveIfChanged(page.id)
            }
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
