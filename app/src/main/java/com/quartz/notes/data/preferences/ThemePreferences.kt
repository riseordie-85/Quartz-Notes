package com.quartz.notes.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.quartz.notes.domain.model.GradientSpec
import com.quartz.notes.domain.model.QuartzPresets
import com.quartz.notes.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "quartz_settings")

class ThemePreferences(private val context: Context) {

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_GLOBAL_GRADIENT_ID = stringPreferencesKey("global_gradient_id")
        private val KEY_CUSTOM_START_HEX = longPreferencesKey("custom_start_hex")
        private val KEY_CUSTOM_END_HEX = longPreferencesKey("custom_end_hex")
        private val KEY_AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
        private val KEY_AUTO_BACKUP_INTERVAL = longPreferencesKey("auto_backup_interval_hours")
        private val KEY_AUTO_BACKUP_URI = stringPreferencesKey("auto_backup_uri")
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[KEY_THEME_MODE] ?: ThemeMode.DARK.name) }
            .getOrDefault(ThemeMode.DARK)
    }

    val globalGradient: Flow<GradientSpec> = context.dataStore.data.map { prefs ->
        val id = prefs[KEY_GLOBAL_GRADIENT_ID] ?: QuartzPresets.ObsidianQuartz.id
        if (id == "custom") {
            val start = prefs[KEY_CUSTOM_START_HEX] ?: 0xFF0F172A
            val end = prefs[KEY_CUSTOM_END_HEX] ?: 0xFF311042
            GradientSpec("custom", "Custom Gradient", start, end)
        } else {
            QuartzPresets.findById(id) ?: QuartzPresets.ObsidianQuartz
        }
    }

    val autoBackupEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_BACKUP_ENABLED] ?: false
    }

    val autoBackupIntervalHours: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_BACKUP_INTERVAL] ?: 24L
    }

    val autoBackupDirectoryUri: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_BACKUP_URI]
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    suspend fun setGlobalGradient(spec: GradientSpec) {
        context.dataStore.edit {
            it[KEY_GLOBAL_GRADIENT_ID] = spec.id
            if (spec.id == "custom") {
                it[KEY_CUSTOM_START_HEX] = spec.startColorHex
                it[KEY_CUSTOM_END_HEX] = spec.endColorHex
            }
        }
    }

    suspend fun setAutoBackupDirectory(uriString: String) {
        context.dataStore.edit { it[KEY_AUTO_BACKUP_URI] = uriString }
    }

    suspend fun setAutoBackupSchedule(enabled: Boolean, intervalHours: Long) {
        context.dataStore.edit {
            it[KEY_AUTO_BACKUP_ENABLED] = enabled
            it[KEY_AUTO_BACKUP_INTERVAL] = intervalHours
        }
    }
}
