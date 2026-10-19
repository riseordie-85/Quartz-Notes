package com.quartz.notes

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.quartz.notes.data.FontRepository
import com.quartz.notes.data.QuartzRepository
import com.quartz.notes.data.SettingsRepository
import com.quartz.notes.data.db.QuartzDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class QuartzApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Simple manual dependency container shared by the whole app. */
class AppContainer(context: Context) {
    /** Outlives screens, so saves started when a screen closes still finish. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = QuartzDatabase.build(context)
    val repository = QuartzRepository(database)
    val settings = SettingsRepository(context)
    val fonts = FontRepository(context)
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
