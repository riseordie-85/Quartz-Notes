package com.quartz.notes.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/** Each widget's own settings (chosen in [WidgetSetupActivity]). */
object WidgetKeys {
    /** The shelf a shelf widget shows (none: the first one). */
    val SHELF = longPreferencesKey("shelf")

    /** The notebook a notebook widget shows. */
    val NOTEBOOK = longPreferencesKey("notebook")

    /** Quick notes as a grid instead of a list. */
    val GRID = booleanPreferencesKey("grid")

    /** Draw a Quartz panel behind it (for launchers that don't put their own behind widgets). */
    val PANEL = booleanPreferencesKey("panel")

    /** Dark writing, for light glass or a light panel. */
    val DARK_TEXT = booleanPreferencesKey("darkText")
}

/**
 * What Quartz Launcher can tell a widget through its options: that it draws its glass behind the widget,
 * and the colour its own writing uses on that glass. Other launchers say nothing, and the widget's own
 * settings apply.
 */
internal class LauncherHint(val glass: Boolean?, val ink: Int?) {
    companion object {
        const val GLASS = "com.quartz.launcher.GLASS"
        const val INK = "com.quartz.launcher.INK"

        suspend fun of(context: Context, id: GlanceId): LauncherHint {
            val options = runCatching {
                val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
                AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            }.getOrNull()
            return LauncherHint(
                glass = options?.takeIf { it.containsKey(GLASS) }?.getBoolean(GLASS),
                ink = options?.takeIf { it.containsKey(INK) }?.getInt(INK),
            )
        }
    }
}

/** The colours and spacing a widget draws with. */
internal class WidgetLook(
    /** The writing colour. */
    val ink: Color,
    /** A Quartz panel behind the widget, or none (the launcher's glass shows through). */
    val panel: Color?,
) {
    val light: Boolean get() = ink.red + ink.green + ink.blue > 1.5f
    val inkDim: Color get() = ink.copy(alpha = 0.7f)

    /** A faint tile on the glass, for buttons and plain notes. */
    val tile: Color get() = if (light) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)

    /** Space round the content: a little more inside a panel of its own. */
    val pad: Dp get() = if (panel != null) 12.dp else 8.dp

    fun text(size: TextUnit, bold: Boolean = false, color: Color = ink) =
        TextStyle(color = ColorProvider(color), fontSize = size, fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal)

    /** The panel (if any) and the padding inside it. */
    fun frame(modifier: GlanceModifier): GlanceModifier {
        val withPanel = if (panel != null) modifier.background(panel).cornerRadius(24.dp) else modifier
        return withPanel.padding(pad)
    }

    val inkArgb: Int get() = ink.toArgb()

    companion object {
        @Composable
        fun of(prefs: Preferences, hint: LauncherHint): WidgetLook {
            val darkText = prefs[WidgetKeys.DARK_TEXT] ?: false
            val ink = hint.ink?.let { Color(it) } ?: if (darkText) Color(0xFF15141C) else Color.White
            val ownPanel = when (hint.glass) {
                true -> false
                else -> prefs[WidgetKeys.PANEL] ?: false
            }
            val panel = if (!ownPanel) null else if (ink.red + ink.green + ink.blue > 1.5f) Color(0xE61E1B2E) else Color(0xEBF4F4F7)
            return WidgetLook(ink, panel)
        }
    }
}
