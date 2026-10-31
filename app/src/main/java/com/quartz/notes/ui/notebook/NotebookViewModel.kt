package com.quartz.notes.ui.notebook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.StoredImage
import com.quartz.notes.data.db.NotebookEntity
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.PageSize
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

    /** A Freeform page (fixed size, scaled to fit), kept in step by the page view. */
    var freeform by mutableStateOf(page.layoutMode == "SCRAPBOOK")

    /** Freeform pages: zoom on top of "fit to the screen" (1 = fit) and how far it's moved, in screen pixels. */
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)

    /** Zoomed in, so a swipe moves around the page instead of turning it. */
    val zoomedIn: Boolean get() = freeform && zoom > 1.02f


    /** Selection, drawing and item layout on the page (kept here so keyboard shortcuts can reach it). */
    val canvas = PageCanvasState()

    /** The page's items as last shown (for shortcuts and copying). */
    var elements: List<PageElementEntity> = emptyList()

    /** Undo / redo for the page's items. */
    val history = ItemHistory()

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
        record(e.pageId)
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
        // Text pages: pictures go in line with the text (or in the strip at the bottom).
        val session = sessions[pageId]
        if (session != null && !session.freeform && !attachAtBottom) {
            session.editor.insertImageAtCursor(ImageData(path = image.path, aspect = image.aspect), 0.8f)
            saveIfChanged(pageId)
            return
        }
        val freeform = session?.freeform == true
        record(pageId)
        val width = 0.6f
        val x = (1f - width) / 2 + ((stagger % 5) - 2) * 0.03f
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.IMAGE, x = x, y = placementY(pageId), width = width,
                height = width * image.aspect,
                rotation = if (attachAtBottom && !freeform) 0f else listOf(-3f, 2f, -1.5f, 3f, 0f)[stagger % 5],
                data = ImageData(
                    path = image.path,
                    aspect = image.aspect,
                    placement = if (attachAtBottom && !freeform) ImagePlacement.ATTACHED else ImagePlacement.FLOAT,
                ).encode(),
            ),
        )
    }

    /** Places an existing picture at a given spot (used when an in-line picture is set to float). */
    fun addImageAt(pageId: Long, data: ImageData, x: Float, y: Float, width: Float) {
        record(pageId)
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
    /** The size new Freeform pages get in this notebook. */
    val defaultPageSize: PageSize get() = PageSize.decode(notebook.value?.pageSize) ?: PageSize.DEFAULT

    /** Makes a page a text page, or a Freeform page of [size] (its text moves into a text box). */
    fun setLayoutMode(page: PageEntity, scrapbook: Boolean, size: PageSize? = null) {
        val session = session(page)
        viewModelScope.launch {
            if (scrapbook && page.layoutMode != "SCRAPBOOK" && !session.editor.isBlank()) {
                val doc = session.editor.toDoc()
                addTextBox(page.id, 0.05f, 0.24f, 0.9f, doc)
                session.editor.reset()
                saveIfChanged(page.id)
            }
            repo.setPageType(page.id, if (scrapbook) "SCRAPBOOK" else "INLINE", size ?: defaultPageSize)
        }
    }

    /** Adds a text page or a Freeform page (of [size]) after position [afterIndex]. */
    fun addPageOfType(afterIndex: Int, freeform: Boolean, size: PageSize?, onCreated: (Long) -> Unit) = viewModelScope.launch {
        onCreated(repo.insertPageOfType(notebookId, afterIndex, if (freeform) "SCRAPBOOK" else "INLINE", size ?: defaultPageSize))
    }

    fun addVoice(pageId: Long, path: String, durationMs: Long) {
        record(pageId)
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.VOICE, x = 0.1f, y = placementY(pageId), width = 0.7f,
                // Text pages keep recordings in the strip under the text; Freeform pages place them.
                data = VoiceData(path = path, durationMs = durationMs, attached = sessions[pageId]?.freeform != true).encode(),
            ),
        )
    }

    // ---- Text boxes, shapes, arrows, connectors, drawings ----

    /** Adds a text box at (x, y) in page widths and returns its id. */
    suspend fun addTextBox(pageId: Long, x: Float, y: Float, width: Float, doc: RichDoc = RichDoc()): Long {
        record(pageId)
        return repo.insertElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.TEXT, x = x, y = y, width = width,
                data = TextBoxData(doc.encode()).encodePayload(),
            ),
        )
    }

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
        // A run of typing is one step.
        record(element.pageId, key = "text:${element.id}")
        repo.updateElementData(element, TextBoxData(state.toDoc().encode()).encodePayload())
        session.textBoxSaved[element.id] = state.version
    }

    private var shapeCount = 0

    /** Adds a shape and returns its id (so it can be typed in straight away). */
    suspend fun addShape(pageId: Long, type: ShapeType): Long {
        val fills = listOf(0xFF00A8D6.toInt(), 0xFFD6004C.toInt(), 0xFFFFD600.toInt(), 0xFF2E9E5B.toInt())
        val w = if (type == ShapeType.RECTANGLE || type == ShapeType.ROUNDED) 0.36f else 0.3f
        val h = if (type == ShapeType.RECTANGLE || type == ShapeType.ROUNDED) 0.24f else 0.3f
        record(pageId)
        return repo.insertElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.SHAPE, x = (1f - w) / 2 + ((shapeCount % 3) - 1) * 0.05f,
                y = placementY(pageId), width = w, height = h,
                data = ShapeData(shape = type, fill = fills[shapeCount % fills.size]).encodePayload(),
            ),
        ).also { shapeCount++ }
    }

    fun addArrow(pageId: Long) {
        record(pageId)
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.ARROW, x = 0.3f, y = placementY(pageId) + 0.1f, width = 0f,
                data = ArrowData(dx = 0.4f, dy = 0f).encodePayload(),
            ),
        )
    }

    fun addStroke(pageId: Long, pointsUnits: List<Float>, color: Int, tool: String, size: Float) {
        if (pointsUnits.size < 2) return
        val xs = pointsUnits.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = pointsUnits.filterIndexed { i, _ -> i % 2 == 1 }
        record(pageId)
        repo.addElement(
            PageElementEntity(
                pageId = pageId, kind = ElementKind.STROKE, x = xs.min(), y = ys.min(),
                width = xs.max() - xs.min(), height = ys.max() - ys.min(),
                data = StrokeData(points = pointsUnits, color = color, tool = tool, size = size).encodePayload(),
            ),
        )
    }

    fun addConnector(pageId: Long, from: Long, to: Long) {
        if (from == to) return
        record(pageId)
        repo.addElement(
            PageElementEntity(pageId = pageId, kind = ElementKind.CONNECTOR, data = ConnectorData(from = from, to = to).encodePayload()),
        )
    }

    fun replaceElement(element: PageElementEntity) {
        record(element.pageId)
        repo.replaceElement(element)
    }

    fun moveElement(element: PageElementEntity) {
        record(element.pageId)
        repo.moveElement(element)
    }

    /** Changes an item's look or words; quick runs of changes to one item (picking colors) are one step. */
    fun updateElementData(element: PageElementEntity, data: String, undoable: Boolean = true) {
        if (undoable) record(element.pageId, key = "data:${element.id}")
        repo.updateElementData(element, data)
    }

    fun bringToFront(element: PageElementEntity) {
        record(element.pageId)
        repo.bringToFront(element)
    }

    fun sendToBack(element: PageElementEntity) {
        record(element.pageId)
        repo.sendToBack(element)
    }

    /** Removes an item. [undoable] is false for tidying up (an empty text box nobody typed in). */
    fun deleteElement(element: PageElementEntity, undoable: Boolean = true, key: String? = null) {
        if (undoable) record(element.pageId, key)
        repo.deleteElement(element)
    }

    /** A pen has been used on this screen: from now on fingers move the page instead of drawing (palm rejection). */
    var penSeen by mutableStateOf(false)

    // ---- Undo / redo ----

    private fun history(pageId: Long): ItemHistory = sessions[pageId]?.history ?: orphanHistory.getOrPut(pageId) { ItemHistory() }
    private val orphanHistory = HashMap<Long, ItemHistory>()

    /**
     * Remembers how a page's items are before a change, so it can be undone. Changes with the same
     * [key] close together (a run of typing, dragging a color) make one step.
     */
    private fun record(pageId: Long, key: String? = null) {
        val h = history(pageId)
        val now = System.currentTimeMillis()
        synchronized(h) {
            val sameRun = key != null && key == h.lastKey && now - h.lastAt < 1500
            h.lastKey = key
            h.lastAt = now
            if (sameRun) return
            h.redo.clear()
            h.update()
        }
        repo.snapshotElements(pageId) { items ->
            synchronized(h) {
                h.push(h.undo, items)
                h.update()
            }
        }
    }

    fun undoItems(pageId: Long) = step(pageId, undo = true)
    fun redoItems(pageId: Long) = step(pageId, undo = false)

    private fun step(pageId: Long, undo: Boolean) {
        val h = history(pageId)
        val target = synchronized(h) {
            h.lastKey = null
            (if (undo) h.undo else h.redo).removeLastOrNull().also { h.update() }
        } ?: return
        sessions[pageId]?.canvas?.clearSelection()
        repo.restoreElements(
            pageId,
            target,
            onBefore = { current ->
                synchronized(h) {
                    h.push(if (undo) h.redo else h.undo, current)
                    h.update()
                }
            },
            onDone = { items -> viewModelScope.launch { showTextBoxes(pageId, items) } },
        )
    }

    /** Text boxes being shown take the words they were put back to. */
    private fun showTextBoxes(pageId: Long, items: List<PageElementEntity>) {
        val session = sessions[pageId] ?: return
        items.filter { it.kind == ElementKind.TEXT }.forEach { e ->
            val state = session.textBoxes[e.id] ?: return@forEach
            val doc = RichDoc.decode(decodePayload<TextBoxData>(e.data)?.doc)
            if (doc != state.toDoc()) state.replaceFromRemote(doc)
            session.textBoxSaved[e.id] = state.version
        }
    }

    /** Undo: the text being typed, the page's text (text pages), or the last change to its items. */
    fun undo(page: PageEntity) {
        val s = session(page)
        val editor = s.activeEditor ?: s.editor.takeIf { !s.freeform }
        if (editor != null && editor.canUndo) editor.undo() else undoItems(page.id)
    }

    fun redo(page: PageEntity) {
        val s = session(page)
        val editor = s.activeEditor ?: s.editor.takeIf { !s.freeform }
        if (editor != null && editor.canRedo) editor.redo() else redoItems(page.id)
    }

    fun canUndo(s: PageSession): Boolean = (s.activeEditor ?: s.editor.takeIf { !s.freeform })?.canUndo == true || s.history.canUndo
    fun canRedo(s: PageSession): Boolean = (s.activeEditor ?: s.editor.takeIf { !s.freeform })?.canRedo == true || s.history.canRedo

    // ---- Several items at once: groups, copy and paste ----

    /** The current words of any text boxes among [items] (typing may not be saved yet). */
    private fun withCurrentText(pageId: Long, items: List<PageElementEntity>): List<PageElementEntity> {
        val session = sessions[pageId] ?: return items
        return items.map { e ->
            val state = if (e.kind == ElementKind.TEXT) session.textBoxes[e.id] else null
            if (state != null) e.copy(data = TextBoxData(state.toDoc().encode()).encodePayload()) else e
        }
    }

    /** Moves items by (dx, dy) page widths, as one step. */
    fun moveItems(pageId: Long, items: List<PageElementEntity>, dx: Float, dy: Float) {
        val moved = items.filter { it.kind != ElementKind.CONNECTOR }.map { it.shiftedBy(dx, dy) }
        if (moved.isEmpty() || (dx == 0f && dy == 0f)) return
        record(pageId, key = if (moved.size == 1 && kotlin.math.abs(dx) < 0.05f && kotlin.math.abs(dy) < 0.05f) "nudge:${moved.first().id}" else null)
        repo.replaceElements(withCurrentText(pageId, moved))
    }

    fun deleteItems(pageId: Long, items: List<PageElementEntity>) {
        if (items.isEmpty()) return
        record(pageId)
        sessions[pageId]?.let { s -> items.forEach { e -> s.textBoxes.remove(e.id)?.let { if (s.activeEditor === it) s.activeEditor = null } } }
        repo.deleteElements(items)
    }

    fun itemsToFront(pageId: Long, items: List<PageElementEntity>) {
        if (items.isEmpty()) return
        record(pageId)
        items.sortedBy { it.z }.forEach(repo::bringToFront)
    }

    fun itemsToBack(pageId: Long, items: List<PageElementEntity>) {
        if (items.isEmpty()) return
        record(pageId)
        items.sortedByDescending { it.z }.forEach(repo::sendToBack)
    }

    /** Copies items (and lines joining them) for pasting. */
    fun copyItems(pageId: Long, all: List<PageElementEntity>, ids: Set<Long>) {
        val items = withJoiningLines(all, ids)
        if (items.isNotEmpty()) ItemClipboard.items = withCurrentText(pageId, items)
    }

    private var pasteCount = 0

    /**
     * Pastes the copied items onto a page and returns their new ids. With [at] (page widths) they go
     * there; otherwise a little below and to the right of where they came from.
     */
    suspend fun pasteItems(pageId: Long, at: androidx.compose.ui.geometry.Offset? = null, items: List<PageElementEntity> = ItemClipboard.items): List<Long> {
        if (items.isEmpty()) return emptyList()
        val box = items.mapNotNull { it.unitBounds() }
        val left = box.minOfOrNull { it.left } ?: 0f
        val top = box.minOfOrNull { it.top } ?: 0f
        val (dx, dy) = if (at != null) {
            pasteCount = 0
            at.x - left to at.y - top
        } else {
            pasteCount++
            0.04f * pasteCount to 0.04f * pasteCount
        }
        record(pageId)
        return repo.insertCopies(pageId, items.map { it.shiftedBy(dx, dy) })
    }

    /** Copies items straight onto the same page, a little offset. */
    suspend fun duplicateItems(pageId: Long, all: List<PageElementEntity>, ids: Set<Long>): List<Long> {
        val items = withCurrentText(pageId, withJoiningLines(all, ids))
        if (items.isEmpty()) return emptyList()
        record(pageId)
        return repo.insertCopies(pageId, items.map { it.shiftedBy(0.04f, 0.04f) })
    }

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
