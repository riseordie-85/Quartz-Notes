package com.quartz.notes.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quartz.notes.data.model.PageSize
import com.quartz.notes.data.model.enumOrDefault
import com.quartz.notes.data.sync.Appearance
import com.quartz.notes.data.sync.AppearanceStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    val autoBackup: Boolean = false,
    /** Persisted tree Uri of the folder automatic backups are written to. */
    val backupFolder: String? = null,
    val backupIntervalDays: Int = 1,
    val backupKeep: Int = 7,
    val lastBackupAt: Long = 0L,
    val lastBackupError: String? = null,
    internal val lastBackupFingerprint: String? = null,
    /** Formatting toolbar and page buttons under the title bar instead of at the bottom. */
    val toolbarOnTop: Boolean = false,
    /** On wide screens (tablet, unfolded phone), quick notes open beside the list. */
    val splitView: Boolean = true,
    /** The space a page gets on this device's narrow (phone / cover) and wide (unfolded / tablet) screens, "WxH" in dp. */
    val screenNarrow: String? = null,
    val screenWide: String? = null,
) {
    /** Freeform page sizes to offer: this device's screens first, then paper sizes. */
    val pageSizes: List<PageSize>
        get() {
            fun parse(key: String, raw: String?): PageSize? {
                val (w, h) = raw?.split('x')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 2 } ?: return null
                return PageSize(key, w, h)
            }
            val both = screenNarrow != null && screenWide != null
            val screens = listOfNotNull(
                parse(if (both) PageSize.COVER else PageSize.SCREEN, screenNarrow),
                parse(if (both) PageSize.UNFOLDED else PageSize.SCREEN, screenWide),
            )
            return screens + PageSize.PAPER
        }
}

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
        val autoBackup = booleanPreferencesKey("auto_backup")
        val backupFolder = stringPreferencesKey("backup_folder")
        val backupInterval = intPreferencesKey("backup_interval_days")
        val backupKeep = intPreferencesKey("backup_keep")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val lastBackupError = stringPreferencesKey("last_backup_error")
        val lastBackupFingerprint = stringPreferencesKey("last_backup_fingerprint")
        val toolbarOnTop = booleanPreferencesKey("toolbar_on_top")
        val splitView = booleanPreferencesKey("split_view")
        val appearanceChangedAt = longPreferencesKey("appearance_changed_at")
        val screenNarrow = stringPreferencesKey("screen_narrow")
        val screenWide = stringPreferencesKey("screen_wide")
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
            autoBackup = p[Keys.autoBackup] ?: false,
            backupFolder = p[Keys.backupFolder],
            backupIntervalDays = p[Keys.backupInterval] ?: 1,
            backupKeep = p[Keys.backupKeep] ?: 7,
            lastBackupAt = p[Keys.lastBackupAt] ?: 0L,
            lastBackupError = p[Keys.lastBackupError],
            lastBackupFingerprint = p[Keys.lastBackupFingerprint],
            toolbarOnTop = p[Keys.toolbarOnTop] ?: false,
            splitView = p[Keys.splitView] ?: true,
            screenNarrow = p[Keys.screenNarrow],
            screenWide = p[Keys.screenWide],
        )
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    suspend fun setThemeMode(mode: ThemeMode) = set(Keys.themeMode, mode.name)
    suspend fun setDynamicColor(enabled: Boolean) = set(Keys.dynamicColor, enabled)
    suspend fun setAccent(argb: Int) = store.edit {
        it[Keys.accent] = argb
        it[Keys.appearanceChangedAt] = System.currentTimeMillis()
    }
    suspend fun setBackground(argb: Int) = store.edit {
        it[Keys.background] = argb
        it[Keys.appearanceChangedAt] = System.currentTimeMillis()
    }

    // ---- Appearance shared through sync (accent and background) ----

    suspend fun appearance(): Appearance {
        val p = store.data.first()
        val accent = p[Keys.accent] ?: DEFAULT_ACCENT
        val background = p[Keys.background] ?: 0
        // Colours picked before syncing them existed count as set, but older than any new change.
        val changedAt = p[Keys.appearanceChangedAt] ?: if (accent != DEFAULT_ACCENT || background != 0) 1L else 0L
        return Appearance(accent, background, changedAt)
    }

    suspend fun applyRemoteAppearance(a: Appearance) = store.edit {
        it[Keys.accent] = a.accentArgb
        it[Keys.background] = a.backgroundArgb
        it[Keys.appearanceChangedAt] = a.changedAt
    }

    fun appearanceChanges(): Flow<Any> =
        settings.map { it.accentArgb to it.backgroundArgb }.distinctUntilChanged()

    val appearanceStore: AppearanceStore = object : AppearanceStore {
        override suspend fun local() = appearance()
        override suspend fun applyRemote(appearance: Appearance) = applyRemoteAppearance(appearance).let { }
        override fun changes() = appearanceChanges()
    }
    suspend fun setQuickNotesGrid(grid: Boolean) = set(Keys.grid, grid)
    suspend fun setSelectedShelf(id: Long) = set(Keys.shelf, id)
    suspend fun setCompressImages(enabled: Boolean) = set(Keys.compress, enabled)
    suspend fun setToolbarOnTop(onTop: Boolean) = set(Keys.toolbarOnTop, onTop)
    suspend fun setSplitView(enabled: Boolean) = set(Keys.splitView, enabled)

    /**
     * Remembers the space a page gets (in dp): a narrow portrait one (phone, cover screen) or a
     * wide one (unfolded, tablet). Phones turned sideways are too short to count as wide.
     */
    suspend fun rememberScreen(width: Float, height: Float) {
        val key = when {
            width < 600f && height > width -> Keys.screenNarrow
            width >= 600f && height >= 480f -> Keys.screenWide
            else -> return
        }
        val value = "${kotlin.math.round(width).toInt()}x${kotlin.math.round(height).toInt()}"
        store.edit { if (it[key] != value) it[key] = value }
    }
    suspend fun setInk(argb: Int) = set(Keys.ink, argb)
    suspend fun setHighlight(argb: Int) = set(Keys.highlight, argb)
    suspend fun setAutoBackup(enabled: Boolean) = set(Keys.autoBackup, enabled)
    suspend fun setBackupFolder(uri: String) = set(Keys.backupFolder, uri)
    suspend fun setBackupInterval(days: Int) = set(Keys.backupInterval, days)
    suspend fun setBackupKeep(count: Int) = set(Keys.backupKeep, count)

    suspend fun recordBackup(at: Long, fingerprint: String?) = store.edit {
        it[Keys.lastBackupAt] = at
        if (fingerprint != null) it[Keys.lastBackupFingerprint] = fingerprint
        it.remove(Keys.lastBackupError)
    }

    suspend fun recordBackupError(message: String) = set(Keys.lastBackupError, message)

    /** Forces the next automatic backup to run even if nothing changed. */
    suspend fun clearBackupFingerprint() = store.edit { it.remove(Keys.lastBackupFingerprint) }
}
