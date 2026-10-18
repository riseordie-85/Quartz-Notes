package com.quartz.notes.domain.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class GradientSpec(
    val id: String,
    val name: String,
    val startColorHex: Long,
    val endColorHex: Long,
    val angleDegrees: Float = 135f
) {
    fun toBrush(): Brush {
        val rad = Math.toRadians(angleDegrees.toDouble())
        val x = cos(rad).toFloat()
        val y = sin(rad).toFloat()
        return Brush.linearGradient(
            colors = listOf(Color(startColorHex), Color(endColorHex)),
            start = Offset.Zero,
            end = Offset(x * 1200f, y * 1200f)
        )
    }
}

object QuartzPresets {
    val None = GradientSpec("none", "Default Material Surface", 0x00000000, 0x00000000)
    val ObsidianQuartz = GradientSpec("obsidian", "Obsidian Quartz", 0xFF0F172A, 0xFF1E1B4B)
    val RoseQuartz = GradientSpec("rose", "Rose Quartz", 0xFF4A1D33, 0xFF1F122B)
    val CyberAmethyst = GradientSpec("amethyst", "Cyber Amethyst", 0xFF2E1065, 0xFF090D16)
    val EmeraldMist = GradientSpec("emerald", "Emerald Mist", 0xFF064E3B, 0xFF0F172A)
    val SunsetAmber = GradientSpec("amber", "Sunset Amber", 0xFF451A03, 0xFF1E1B4B)
    val DaylightFrost = GradientSpec("frost", "Daylight Frost (Light)", 0xFFF8FAFC, 0xFFE0E7FF)
    val WarmParchment = GradientSpec("parchment", "Warm Apple Notes (Light)", 0xFFFEFCE8, 0xFFFEF3C7)

    val all = listOf(
        None, ObsidianQuartz, RoseQuartz, CyberAmethyst,
        EmeraldMist, SunsetAmber, DaylightFrost, WarmParchment
    )

    fun findById(id: String?): GradientSpec? = all.find { it.id == id }
}
