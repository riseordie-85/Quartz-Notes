package com.quartz.notes

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
import kotlinx.coroutines.launch

class QuartzApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
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

    fun startBackgroundTasks(context: Context) {
        val app = context.applicationContext
        sync.start()
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
