package com.quartz.notes.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/** Background colors for quick notes, with light/dark variants and a dot color for the inline list. */
enum class NoteColor(val key: String, val label: String, val light: Color, val dark: Color, val dot: Color) {
    DEFAULT("default", "Default", Color.White, Color(0xFF1C1C21), Color.Transparent),
    YELLOW("yellow", "Yellow", Color(0xFFFFF4B8), Color(0xFF4A4220), Color(0xFFF2C500)),
    ORANGE("orange", "Orange", Color(0xFFFFE0C2), Color(0xFF4D3320), Color(0xFFF08A1E)),
    RED("red", "Red", Color(0xFFFFD3D3), Color(0xFF4D2424), Color(0xFFE53935)),
    PINK("pink", "Pink", Color(0xFFFFD6E8), Color(0xFF4A2234), Color(0xFFD6004C)),
    PURPLE("purple", "Purple", Color(0xFFE6DAFF), Color(0xFF34284D), Color(0xFF7C4DFF)),
    BLUE("blue", "Blue", Color(0xFFD5E6FF), Color(0xFF213350), Color(0xFF1E6FE8)),
    TEAL("teal", "Teal", Color(0xFFCDF1EC), Color(0xFF1C3F3A), Color(0xFF00A896)),
    GREEN("green", "Green", Color(0xFFDDF3CF), Color(0xFF2A4022), Color(0xFF43A047)),
    GRAY("gray", "Gray", Color(0xFFE7E7EB), Color(0xFF34343A), Color(0xFF8A8A92));

    fun background(isDark: Boolean): Color = if (isDark) dark else light

    companion object {
        fun of(key: String?): NoteColor = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

val NotebookColorPresets = listOf(
    Color(0xFF0050D0), Color(0xFFD6004C), Color(0xFF00A8D6), Color(0xFFE68A1E),
    Color(0xFF2E9E5B), Color(0xFF6A3FD0), Color(0xFFD32F2F), Color(0xFF00897B),
    Color(0xFF795548), Color(0xFF455A64), Color(0xFF212121), Color(0xFFC9A227),
)

val HighlightPresets = listOf(
    Color(0xFFFFE14D), Color(0xFFB7F59A), Color(0xFFA8D8FF),
    Color(0xFFFFB3D1), Color(0xFFFFC48A), Color(0xFFD7B8FF),
)

val InkPresets = listOf(
    Color(0xFF111114), Color(0xFFFFFFFF), Color(0xFFD6004C), Color(0xFF1E6FE8),
    Color(0xFF2E9E5B), Color(0xFFE68A1E), Color(0xFF7C4DFF), Color(0xFF8A8A92),
)

val PageColorPresets = listOf(
    Color(0xFFFFFFFF), Color(0xFFFFF8E7), Color(0xFFEFFAF3), Color(0xFFEEF6FF),
    Color(0xFFFFF0F3), Color(0xFFF4F0FF), Color(0xFFE9D8BE), Color(0xFF2A2A2E), Color(0xFF162033),
)

val AccentPresets = listOf(
    Color(0xFFD6004C), Color(0xFF0050D0), Color(0xFF00A8D6), Color(0xFFE68A1E),
    Color(0xFF2E9E5B), Color(0xFF6A3FD0), Color(0xFF00897B), Color(0xFF455A64),
)

val BackgroundPresets = listOf(
    Color(0xFFEFEFF1), Color(0xFFF6F1E9), Color(0xFFE9F0F6), Color(0xFFEEF3EC),
    Color(0xFF101013), Color(0xFF0E1726), Color(0xFF1A1420), Color(0xFF121A16),
)

/** Text color that reads well on [background]. */
fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.45f) Color(0xFF111114) else Color(0xFFF2F2F5)

fun Color.toHex(): String = Integer.toHexString(toArgb()).uppercase().padStart(8, '0')

fun parseHexColor(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.removePrefix("#")
    val full = when (clean.length) {
        6 -> "FF$clean"
        8 -> clean
        else -> return null
    }
    return full.toLongOrNull(16)?.let { Color(it.toInt()) }
}
