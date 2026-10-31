package com.quartz.notes.ui.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ArrowData
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.decodeImageData
import com.quartz.notes.data.model.decodePayload
import com.quartz.notes.data.model.encodePayload
import com.quartz.notes.ui.media.heightFactor
import kotlin.math.max
import kotlin.math.min

/**
 * Undo and redo for one page's items: each step is how all of them were before a change. Steps are
 * filled in from the save queue, so they always match what was saved.
 */
class ItemHistory {
    internal val undo = ArrayDeque<List<PageElementEntity>>()
    internal val redo = ArrayDeque<List<PageElementEntity>>()
    internal var lastKey: String? = null
    internal var lastAt = 0L

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    internal fun update() {
        canUndo = undo.isNotEmpty()
        canRedo = redo.isNotEmpty()
    }

    internal fun push(stack: ArrayDeque<List<PageElementEntity>>, items: List<PageElementEntity>) {
        if (stack.lastOrNull() == items) return
        stack.addLast(items)
        if (stack.size > MaxSteps) stack.removeFirst()
    }

    private companion object {
        const val MaxSteps = 100
    }
}

/** Items copied or cut, ready to paste on any page (kept while the app is open). */
object ItemClipboard {
    var items by mutableStateOf<List<PageElementEntity>>(emptyList())
}

/** The same item moved by (dx, dy) page widths; drawings and arrows move all their points. */
fun PageElementEntity.shiftedBy(dx: Float, dy: Float): PageElementEntity = when (kind) {
    ElementKind.CONNECTOR -> this
    ElementKind.STROKE -> {
        val d = decodePayload<StrokeData>(data)
        val points = d?.points?.mapIndexed { i, v -> if (i % 2 == 0) v + dx else v + dy }
        copy(x = x + dx, y = y + dy, data = if (d != null && points != null) d.copy(points = points).encodePayload() else data)
    }
    else -> copy(x = x + dx, y = y + dy)
}

/** Rough box around an item in page widths (from its saved row; no layout needed). */
fun PageElementEntity.unitBounds(): Rect? = when (kind) {
    ElementKind.CONNECTOR -> null
    ElementKind.ARROW -> {
        val d = decodePayload<ArrowData>(data) ?: return null
        Rect(min(x, x + d.dx), min(y, y + d.dy), max(x, x + d.dx), max(y, y + d.dy))
    }
    ElementKind.STROKE -> Rect(x, y, x + width, y + height)
    ElementKind.IMAGE -> Rect(x, y, x + width, y + width * (decodeImageData(data)?.heightFactor() ?: 1f))
    ElementKind.VOICE -> Rect(x, y, x + width, y + 0.12f)
    else -> Rect(x, y, x + width, y + max(height, 0.08f))
}

/** Items in [ids] plus the lines joining two of them (what copying or a group move carries along). */
fun withJoiningLines(all: List<PageElementEntity>, ids: Set<Long>): List<PageElementEntity> =
    all.filter { it.id in ids && it.kind != ElementKind.CONNECTOR } +
        all.filter { c ->
            c.kind == ElementKind.CONNECTOR && decodePayload<ConnectorData>(c.data)?.let { it.from in ids && it.to in ids } == true
        }

/** Box around a group of items on screen, in px (laid-out boxes where there are some). */
fun groupBoundsPx(items: List<PageElementEntity>, bounds: Map<Long, Rect>, widthPx: Float, inkWidthPx: (StrokeData) -> Float): Rect? {
    var out: Rect? = null
    for (e in items) {
        val r = when (e.kind) {
            ElementKind.STROKE -> {
                val d = decodePayload<StrokeData>(e.data) ?: continue
                val pts = strokePoints(d, widthPx)
                if (pts.isEmpty()) continue
                val pad = inkWidthPx(d) / 2
                Rect(pts.minOf { it.x } - pad, pts.minOf { it.y } - pad, pts.maxOf { it.x } + pad, pts.maxOf { it.y } + pad)
            }
            ElementKind.CONNECTOR -> continue
            ElementKind.ARROW -> e.unitBounds()?.let { Rect(it.left * widthPx, it.top * widthPx, it.right * widthPx, it.bottom * widthPx) } ?: continue
            else -> bounds[e.id] ?: e.unitBounds()?.let { Rect(it.left * widthPx, it.top * widthPx, it.right * widthPx, it.bottom * widthPx) } ?: continue
        }
        out = out?.let { Rect(min(it.left, r.left), min(it.top, r.top), max(it.right, r.right), max(it.bottom, r.bottom)) } ?: r
    }
    return out
}

/** Items the lasso loop [loop] (px) goes around: mostly inside it, or their middle is. */
fun itemsInLoop(loop: List<Offset>, items: List<PageElementEntity>, bounds: Map<Long, Rect>, widthPx: Float): Set<Long> {
    if (loop.size < 3) return emptySet()
    val out = HashSet<Long>()
    for (e in items) {
        val inside = when (e.kind) {
            ElementKind.CONNECTOR -> false
            ElementKind.STROKE -> {
                val pts = decodePayload<StrokeData>(e.data)?.let { strokePoints(it, widthPx) }.orEmpty()
                pts.isNotEmpty() && pts.count { insidePolygon(it, loop) } * 2 >= pts.size
            }
            ElementKind.ARROW -> {
                val d = decodePayload<ArrowData>(e.data) ?: continue
                insidePolygon(Offset((e.x + d.dx / 2) * widthPx, (e.y + d.dy / 2) * widthPx), loop)
            }
            else -> (bounds[e.id] ?: e.unitBounds()?.let { Rect(it.left * widthPx, it.top * widthPx, it.right * widthPx, it.bottom * widthPx) })
                ?.let { insidePolygon(it.center, loop) } == true
        }
        if (inside) out += e.id
    }
    return out
}

/** Even-odd test: is [p] inside the closed loop [poly]? */
fun insidePolygon(p: Offset, poly: List<Offset>): Boolean {
    var inside = false
    var j = poly.lastIndex
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[j]
        if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/**
 * Where a moving box should snap: lines up its left, middle or right (and top, middle, bottom)
 * with the page's middle and other items' edges and middles, when within [reach] px. Returns the
 * nudge to apply and the guide lines to show.
 */
fun snapBox(
    box: Rect,
    others: Collection<Rect>,
    page: androidx.compose.ui.geometry.Size?,
    pageWidthPx: Float,
    reach: Float,
): Triple<Offset, List<Float>, List<Float>> {
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    xs += (page?.width ?: pageWidthPx) / 2
    page?.let { ys += it.height / 2 }
    others.forEach { r ->
        xs += r.left; xs += r.center.x; xs += r.right
        ys += r.top; ys += r.center.y; ys += r.bottom
    }
    fun best(edges: List<Float>, lines: List<Float>): Pair<Float, Float>? {
        var bestD = reach
        var found: Pair<Float, Float>? = null
        for (e in edges) for (l in lines) {
            val d = kotlin.math.abs(l - e)
            if (d < bestD) {
                bestD = d
                found = (l - e) to l
            }
        }
        return found
    }
    val sx = best(listOf(box.left, box.center.x, box.right), xs)
    val sy = best(listOf(box.top, box.center.y, box.bottom), ys)
    return Triple(Offset(sx?.first ?: 0f, sy?.first ?: 0f), listOfNotNull(sx?.second), listOfNotNull(sy?.second))
}
