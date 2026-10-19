package com.quartz.notes.data.rich

/** Pure helpers for manipulating style span lists. All ranges are [start, end). */
object SpanOps {

    /** Merges overlapping or touching spans of the same style and value; drops empty spans. */
    fun normalize(spans: List<StyleSpan>): List<StyleSpan> {
        if (spans.isEmpty()) return spans
        val out = ArrayList<StyleSpan>(spans.size)
        spans.filter { it.end > it.start }
            .groupBy { it.style to it.value }
            .values
            .forEach { group ->
                val sorted = group.sortedBy { it.start }
                var current = sorted.first()
                for (i in 1 until sorted.size) {
                    val next = sorted[i]
                    current = if (next.start <= current.end) {
                        current.copy(end = maxOf(current.end, next.end))
                    } else {
                        out += current
                        next
                    }
                }
                out += current
            }
        return out.sortedWith(compareBy<StyleSpan>({ it.start }, { it.style.ordinal }))
    }

    /** Removes [style] (any value) from [start, end). */
    fun remove(spans: List<StyleSpan>, style: InlineStyle, start: Int, end: Int): List<StyleSpan> {
        if (start >= end) return spans
        val out = ArrayList<StyleSpan>(spans.size + 1)
        for (s in spans) {
            if (s.style != style || s.end <= start || s.start >= end) {
                out += s
                continue
            }
            if (s.start < start) out += s.copy(end = start)
            if (s.end > end) out += s.copy(start = end)
        }
        return out
    }

    /** Applies [style] to [start, end). Valued styles replace any existing value in that range. */
    fun add(spans: List<StyleSpan>, style: InlineStyle, value: String?, start: Int, end: Int): List<StyleSpan> {
        if (start >= end) return spans
        val base = if (style.valued) remove(spans, style, start, end) else spans
        return normalize(base + StyleSpan(start, end, style, value))
    }

    /** True if every character in [start, end) carries [style]. */
    fun covers(spans: List<StyleSpan>, style: InlineStyle, start: Int, end: Int): Boolean {
        if (start >= end) return false
        var pos = start
        for (s in spans.filter { it.style == style }.sortedBy { it.start }) {
            if (s.end <= pos) continue
            if (s.start > pos) return false
            pos = s.end
            if (pos >= end) return true
        }
        return pos >= end
    }

    /** The span of [style] that text typed at a collapsed cursor [pos] would join. */
    fun activeAt(spans: List<StyleSpan>, style: InlineStyle, pos: Int): StyleSpan? =
        spans.firstOrNull {
            it.style == style && ((it.start < pos && pos <= it.end) || (pos == 0 && it.start == 0 && it.end > 0))
        }

    /** The value of [style] at the start of a selection, if any. */
    fun valueAt(spans: List<StyleSpan>, style: InlineStyle, start: Int, end: Int): String? =
        if (start < end) {
            spans.firstOrNull { it.style == style && it.start <= start && it.end > start }?.value
        } else {
            activeAt(spans, style, start)?.value
        }

    /** Spans within [start, end), re-based so [start] becomes 0. */
    fun slice(spans: List<StyleSpan>, start: Int, end: Int): List<StyleSpan> = spans.mapNotNull {
        val s = maxOf(it.start, start)
        val e = minOf(it.end, end)
        if (s < e) it.copy(start = s - start, end = e - start) else null
    }

    fun shift(spans: List<StyleSpan>, by: Int): List<StyleSpan> =
        spans.map { it.copy(start = it.start + by, end = it.end + by) }

    fun clip(spans: List<StyleSpan>, length: Int): List<StyleSpan> = spans.mapNotNull {
        val s = it.start.coerceIn(0, length)
        val e = it.end.coerceIn(0, length)
        if (s < e) it.copy(start = s, end = e) else null
    }

    /**
     * Re-maps spans after an edit that replaced old text [p, removedEnd) with [insertedLength] new characters.
     * Text typed at the end of a span joins it; text typed at the start of a span does not.
     */
    fun remap(spans: List<StyleSpan>, p: Int, removedEnd: Int, insertedLength: Int): List<StyleSpan> {
        val delta = insertedLength - (removedEnd - p)
        return spans.mapNotNull { sp ->
            val ns = when {
                sp.start < p -> sp.start
                sp.start >= removedEnd -> sp.start + delta
                else -> p
            }
            val ne = when {
                sp.end < p -> sp.end
                sp.end > removedEnd -> sp.end + delta
                else -> p + insertedLength
            }
            if (ns < ne) sp.copy(start = ns, end = ne) else null
        }
    }
}
