package com.quartz.notes.ui.notebook

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.quartz.notes.data.db.PageElementEntity
import com.quartz.notes.data.model.ArrowData
import com.quartz.notes.data.model.ConnectorData
import com.quartz.notes.data.model.ElementKind
import com.quartz.notes.data.model.LineRoute
import com.quartz.notes.data.model.ShapeType
import com.quartz.notes.data.model.StrokeData
import com.quartz.notes.data.model.decodePayload
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What a touch on the page does. */
enum class PageTool { NONE, BRUSH, ERASER, CONNECT, LASSO }

/**
 * Sizes scale with the page so drawings look the same on a phone, a tablet or half of a spread.
 * [unitPx] is the width of a reference 360dp page, in px, scaled to this page.
 */
fun lineWidthPx(level: Int, pageWidthPx: Float): Float =
    listOf(1.5f, 2.5f, 4f, 6f, 9f)[(level - 1).coerceIn(0, 4)] * pageWidthPx / 360f

fun brushWidthPx(level: Int, pageWidthPx: Float): Float =
    listOf(2f, 3.5f, 6f, 10f, 16f)[(level - 1).coerceIn(0, 4)] * pageWidthPx / 360f

/** Resolves a stored color; 0 means "the page's ink". */
fun inkOr(argb: Int, ink: Color): Color = if (argb == 0) ink else Color(argb)

/** Outline path of a shape filling [size]. */
fun shapePath(type: ShapeType, size: Size): Path = Path().apply {
    val w = size.width
    val h = size.height
    when (type) {
        ShapeType.RECTANGLE -> addRect(Rect(0f, 0f, w, h))
        ShapeType.ROUNDED -> addRoundRect(
            androidx.compose.ui.geometry.RoundRect(Rect(0f, 0f, w, h), androidx.compose.ui.geometry.CornerRadius(min(w, h) * 0.18f)),
        )
        ShapeType.ELLIPSE -> addOval(Rect(0f, 0f, w, h))
        ShapeType.TRIANGLE -> {
            moveTo(w / 2, 0f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        ShapeType.DIAMOND -> {
            moveTo(w / 2, 0f)
            lineTo(w, h / 2)
            lineTo(w / 2, h)
            lineTo(0f, h / 2)
            close()
        }
        ShapeType.STAR -> {
            val cx = w / 2
            val cy = h / 2
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 0.5f else 0.21f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                val x = cx + (w * r * cos(a)).toFloat()
                val y = cy + (h * r * sin(a)).toFloat() * 1.05f
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }
}

/** Points of a connector between two boxes, following its route style. */
fun connectorPoints(a: Rect, b: Rect, route: LineRoute): List<Offset> {
    val dx = b.center.x - a.center.x
    val dy = b.center.y - a.center.y
    val vertical = abs(dy) >= abs(dx) * 0.6f
    val start: Offset
    val end: Offset
    if (vertical) {
        start = if (dy >= 0) Offset(a.center.x, a.bottom) else Offset(a.center.x, a.top)
        end = if (dy >= 0) Offset(b.center.x, b.top) else Offset(b.center.x, b.bottom)
    } else {
        start = if (dx >= 0) Offset(a.right, a.center.y) else Offset(a.left, a.center.y)
        end = if (dx >= 0) Offset(b.left, b.center.y) else Offset(b.right, b.center.y)
    }
    return when (route) {
        LineRoute.STRAIGHT, LineRoute.CURVE -> listOf(start, end)
        LineRoute.ELBOW -> if (vertical) {
            val midY = (start.y + end.y) / 2
            listOf(start, Offset(start.x, midY), Offset(end.x, midY), end)
        } else {
            val midX = (start.x + end.x) / 2
            listOf(start, Offset(midX, start.y), Offset(midX, end.y), end)
        }
    }
}

/** Builds the drawable path for connector points. Curves bend smoothly between the two ends. */
fun connectorPath(points: List<Offset>, route: LineRoute, vertical: Boolean = true): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    if (route == LineRoute.CURVE && points.size == 2) {
        val s = points[0]
        val e = points[1]
        val horizontalish = abs(e.x - s.x) > abs(e.y - s.y)
        if (horizontalish) {
            val mx = (s.x + e.x) / 2
            cubicTo(mx, s.y, mx, e.y, e.x, e.y)
        } else {
            val my = (s.y + e.y) / 2
            cubicTo(s.x, my, e.x, my, e.x, e.y)
        }
    } else {
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    }
}

/** Draws an arrowhead at [tip], pointing away from [from]. */
fun DrawScope.drawArrowHead(from: Offset, tip: Offset, color: Color, width: Float) {
    val angle = atan2(tip.y - from.y, tip.x - from.x)
    val len = max(width * 4f, 12f)
    val spread = Math.toRadians(26.0)
    val p1 = Offset(tip.x - len * cos(angle - spread).toFloat(), tip.y - len * sin(angle - spread).toFloat())
    val p2 = Offset(tip.x - len * cos(angle + spread).toFloat(), tip.y - len * sin(angle + spread).toFloat())
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(head, color)
}

/** Smooth path through brush points (in px). */
fun strokePath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    if (points.size == 1) {
        lineTo(points[0].x + 0.1f, points[0].y)
        return@apply
    }
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        val mid = Offset((prev.x + cur.x) / 2, (prev.y + cur.y) / 2)
        quadraticTo(prev.x, prev.y, mid.x, mid.y)
    }
    val last = points.last()
    lineTo(last.x, last.y)
}

fun DrawScope.drawInkStroke(points: List<Offset>, color: Color, width: Float) {
    drawPath(strokePath(points), color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Distance from [p] to the segment [a]–[b]. */
fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val len2 = abx * abx + aby * aby
    if (len2 == 0f) return hypot(p.x - a.x, p.y - a.y)
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / len2).coerceIn(0f, 1f)
    return hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby))
}

fun distanceToPolyline(p: Offset, points: List<Offset>): Float {
    if (points.size == 1) return hypot(p.x - points[0].x, p.y - points[0].y)
    var best = Float.MAX_VALUE
    for (i in 1 until points.size) best = min(best, distanceToSegment(p, points[i - 1], points[i]))
    return best
}

/** Endpoints of an arrow element, in px. */
fun arrowEnds(e: PageElementEntity, data: ArrowData, pageWidthPx: Float): Pair<Offset, Offset> {
    val s = Offset(e.x * pageWidthPx, e.y * pageWidthPx)
    return s to Offset((e.x + data.dx) * pageWidthPx, (e.y + data.dy) * pageWidthPx)
}

/** Brush points of a stroke element, in px. */
fun strokePoints(data: StrokeData, pageWidthPx: Float): List<Offset> =
    data.points.chunked(2).filter { it.size == 2 }.map { Offset(it[0] * pageWidthPx, it[1] * pageWidthPx) }

/** A line-like element (arrow or connector) close to [p], if any, preferring the topmost. */
fun lineHit(
    p: Offset,
    elements: List<PageElementEntity>,
    bounds: Map<Long, Rect>,
    pageWidthPx: Float,
    tolerancePx: Float,
): PageElementEntity? {
    for (e in elements.sortedByDescending { it.z }) {
        when (e.kind) {
            ElementKind.ARROW -> {
                val d = decodePayload<ArrowData>(e.data) ?: continue
                val (s, t) = arrowEnds(e, d, pageWidthPx)
                if (distanceToSegment(p, s, t) <= tolerancePx) return e
            }
            ElementKind.CONNECTOR -> {
                val d = decodePayload<ConnectorData>(e.data) ?: continue
                val a = bounds[d.from] ?: continue
                val b = bounds[d.to] ?: continue
                if (distanceToPolyline(p, connectorPoints(a, b, d.route)) <= tolerancePx) return e
            }
        }
    }
    return null
}
