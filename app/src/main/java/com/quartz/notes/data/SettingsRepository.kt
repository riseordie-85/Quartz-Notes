package com.quartz.notes.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quartz.notes.data.model.enumOrDefault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

const val DEFAULT_ACCENT: Int = 0xFFD6004C.toInt()
const val DEFAULT_HIGHLIGHT: Int = 0xFFFFE14D.toInt()

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val accentArgb: Int = DEFAULT_ACCENT,
    /** 0 means use the theme's default background. */
    val backgroundArgb: Int = 0,
    val quickNotesGrid: Boolean = false,
    val selectedShelfId: Long = -1L,
    val compressImages: Boolean = true,
    /** Default ink color for text color and the brush. 0 means automatic (matches the page). */
    val inkArgb: Int = 0,
    val highlightArgb: Int = DEFAULT_HIGHLIGHT,
)

private val Context.dataStore by preferencesDataStore(name = "quartz_settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val accent = intPreferencesKey("accent")
        val background = intPreferencesKey("background")
        val grid = booleanPreferencesKey("quick_notes_grid")
        val shelf = longPreferencesKey("selected_shelf")
        val compress = booleanPreferencesKey("compress_images")
        val ink = intPreferencesKey("ink")
        val highlight = intPreferencesKey("highlight")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            themeMode = enumOrDefault(p[Keys.themeMode], ThemeMode.SYSTEM),
            dynamicColor = p[Keys.dynamicColor] ?: false,
            accentArgb = p[Keys.accent] ?: DEFAULT_ACCENT,
            backgroundArgb = p[Keys.background] ?: 0,
            quickNotesGrid = p[Keys.grid] ?: false,
            selectedShelfId = p[Keys.shelf] ?: -1L,
            compressImages = p[Keys.compress] ?: true,
            inkArgb = p[Keys.ink] ?: 0,
            highlightArgb = p[Keys.highlight] ?: DEFAULT_HIGHLIGHT,
        )
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    suspend fun setThemeMode(mode: ThemeMode) = set(Keys.themeMode, mode.name)
    suspend fun setDynamicColor(enabled: Boolean) = set(Keys.dynamicColor, enabled)
    suspend fun setAccent(argb: Int) = set(Keys.accent, argb)
    suspend fun setBackground(argb: Int) = set(Keys.background, argb)
    suspend fun setQuickNotesGrid(grid: Boolean) = set(Keys.grid, grid)
    suspend fun setSelectedShelf(id: Long) = set(Keys.shelf, id)
    suspend fun setCompressImages(enabled: Boolean) = set(Keys.compress, enabled)
    suspend fun setInk(argb: Int) = set(Keys.ink, argb)
    suspend fun setHighlight(argb: Int) = set(Keys.highlight, argb)
}
