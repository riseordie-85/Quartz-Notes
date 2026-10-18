package com.quartz.notes.data.backup

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.quartz.notes.data.local.QuartzDatabase
import com.quartz.notes.data.preferences.ThemePreferences
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = ThemePreferences(applicationContext)
        val uriString = prefs.autoBackupDirectoryUri.first() ?: return Result.failure()
        val db = QuartzDatabase.getInstance(applicationContext)
        val backupManager = BackupManager(applicationContext, db.noteDao())

        val success = backupManager.performScheduledDirectoryBackup(Uri.parse(uriString))
        return if (success) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "quartz_notes_auto_backup"

        fun schedule(context: Context, intervalHours: Long) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
