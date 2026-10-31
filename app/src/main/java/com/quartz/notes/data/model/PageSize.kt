package com.quartz.notes.data.model

import kotlin.math.roundToInt

/**
 * The size of a Freeform page, in page units (1 unit = 1 dp on screen at 100%, 1 point on paper,
 * 1 CSS pixel on the web). Everything on a Freeform page is laid out at this size and then scaled
 * to fit the screen, so it looks the same on every device.
 */
data class PageSize(val key: String, val width: Float, val height: Float) {
    val landscape: Boolean get() = width > height

    fun rotated(): PageSize = copy(width = height, height = width)

    fun portrait(): PageSize = if (landscape) rotated() else this

    /** Stored on notebooks as their default Freeform size. */
    fun encode(): String = "$key|$width|$height"

    /** "A4", "Cover screen", "Pocket notebook" … */
    val label: String get() = labelOf(key)

    /** "595 × 842" for screens, "210 × 297 mm" for paper. */
    val detail: String
        get() = when (key) {
            COVER, UNFOLDED, SCREEN -> "${width.roundToInt()} × ${height.roundToInt()}"
            LETTER, POCKET, LARGE -> "${inches(width)} × ${inches(height)} in"
            else -> "${mm(width)} × ${mm(height)} mm"
        }

    companion object {
        const val COVER = "COVER"
        const val UNFOLDED = "UNFOLDED"
        const val SCREEN = "SCREEN"
        const val A3 = "A3"
        const val A4 = "A4"
        const val A5 = "A5"
        const val LETTER = "LETTER"
        const val POCKET = "POCKET"
        const val LARGE = "LARGE"

        /** Paper sizes, portrait, in points (1/72 in). */
        val PAPER: List<PageSize> = listOf(
            PageSize(A3, 842f, 1191f),
            PageSize(A4, 595f, 842f),
            PageSize(A5, 420f, 595f),
            PageSize(LETTER, 612f, 792f),
            PageSize(POCKET, 252f, 396f),
            PageSize(LARGE, 360f, 594f),
        )

        val DEFAULT: PageSize = PAPER[1]

        fun labelOf(key: String?): String = when (key) {
            COVER -> "Cover screen"
            UNFOLDED -> "Unfolded screen"
            SCREEN -> "This screen"
            A3 -> "A3"
            A4 -> "A4"
            A5 -> "A5"
            LETTER -> "Letter"
            POCKET -> "Pocket notebook"
            LARGE -> "Large notebook"
            else -> "Custom"
        }

        fun decode(raw: String?): PageSize? {
            val parts = raw?.split('|') ?: return null
            if (parts.size != 3) return null
            val w = parts[1].toFloatOrNull() ?: return null
            val h = parts[2].toFloatOrNull() ?: return null
            if (w <= 0f || h <= 0f) return null
            return PageSize(parts[0], w, h)
        }

        private fun inches(units: Float): String {
            val v = units / 72f
            val rounded = (v * 4).roundToInt() / 4f
            return if (rounded % 1f == 0f) rounded.roundToInt().toString() else rounded.toString()
        }

        private fun mm(units: Float): Int = (units / 72f * 25.4f).roundToInt()
    }
}

/** How a page's background picture is laid on the paper. */
object BackgroundFit {
    /** As wide as the page, from the top (text pages; the default). */
    const val WIDTH = "WIDTH"

    /** Repeated down the page (text pages). */
    const val REPEAT = "REPEAT"

    /** The whole picture, keeping its shape (Freeform pages; the default there). */
    const val FIT = "FIT"

    /** Covers the page, cropping the edges. */
    const val FILL = "FILL"

    /** Covers the page exactly, changing the picture's shape. */
    const val STRETCH = "STRETCH"

    /** Moved and zoomed by hand: "MANUAL:x:y:scale" (offsets in page widths, scale relative to Fit). */
    const val MANUAL = "MANUAL"

    data class Manual(val x: Float, val y: Float, val scale: Float) {
        fun encode() = "$MANUAL:$x:$y:$scale"
    }

    fun manual(raw: String?): Manual? {
        val parts = raw?.split(':') ?: return null
        if (parts.size != 4 || parts[0] != MANUAL) return null
        return Manual(parts[1].toFloatOrNull() ?: 0f, parts[2].toFloatOrNull() ?: 0f, parts[3].toFloatOrNull() ?: 1f)
    }

    fun mode(raw: String?, freeform: Boolean): String {
        val m = raw?.substringBefore(':')
        return when {
            freeform && m in listOf(FIT, FILL, STRETCH, MANUAL) -> m!!
            !freeform && m in listOf(WIDTH, REPEAT) -> m!!
            freeform -> FIT
            else -> WIDTH
        }
    }
}
