package com.quartz.notes

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.quartz.notes.data.AudioPlayer
import com.quartz.notes.data.BackupManager
import com.quartz.notes.data.BackupWorker
import com.quartz.notes.data.FontRepository
import com.quartz.notes.data.MediaRepository
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.SettingsRepository
import com.quartz.notes.data.db.QuartzDatabase
import com.quartz.notes.data.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class QuartzApp : Application() {
    lateinit var container: AppContainer
        private set

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Text fields use the phone's selection bar through LocalTextToolbar, where the editor makes
        // sure it offers Cut and Copy for selected text (the newer menu can keep showing the bar for
        // a bare cursor after a double-tap selects a word).
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        container = AppContainer(this)
        container.startBackgroundTasks(this)
    }
}

/** Simple manual dependency container shared by the whole app. */
class AppContainer(context: Context) {
    /** Outlives screens, so saves started when a screen closes still finish. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = QuartzDatabase.build(context)
    val repository = QuartzRepository(database, appScope)
    val settings = SettingsRepository(context)
    val fonts = FontRepository(context)
    val media = MediaRepository(context)
    val audio = AudioPlayer(media, appScope)
    val backup = BackupManager(context, repository, media, fonts)
    val sync = SyncManager(
        context,
        database,
        appScope,
        onFilesArrived = { fontsChanged ->
            media.notifyArrived()
            if (fontsChanged) appScope.launch { fonts.reload() }
        },
        onRemoteChange = repository::notifyRemoteChange,
        appearance = settings.appearanceStore,
    )

    /** Updates the app from the sync server. */
    val updater = com.quartz.notes.data.update.AppUpdater(
        context.applicationContext,
        appScope,
        serverUrl = { sync.prefs.first().takeIf { it.signedIn }?.serverUrl },
        http = com.quartz.notes.data.sync.SyncClient.defaultHttp,
    )

    fun startBackgroundTasks(context: Context) {
        val app = context.applicationContext
        sync.start()
        // Home screen widgets follow the notes.
        appScope.launch { com.quartz.notes.widget.WidgetUpdates.follow(app, repository) }
        // Look for a newer build now and then (and whenever Settings opens).
        appScope.launch {
            delay(5_000)
            while (true) {
                updater.check()
                delay(6 * 60 * 60 * 1000L)
            }
        }
        // Keep the automatic backup schedule in step with the settings.
        appScope.launch {
            settings.settings
                .distinctUntilChangedBy { Triple(it.autoBackup, it.backupFolder, it.backupIntervalDays) }
                .collect { BackupWorker.schedule(app, it) }
        }
        // Tidy up media files nothing uses any more (deleted notes, removed pictures).
        appScope.launch {
            delay(15_000)
            runCatching { media.deleteUnreferenced(repository.referencedMedia()) }
        }
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
