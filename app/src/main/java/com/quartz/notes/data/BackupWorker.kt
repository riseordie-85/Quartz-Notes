package com.quartz.notes.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.quartz.notes.QuartzApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Writes an automatic backup into the user's chosen folder. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as QuartzApp).container
        val settings = container.settings.settings.first()
        val folder = settings.backupFolder
        val forced = inputData.getBoolean(KEY_FORCE, false)
        if (folder == null || (!settings.autoBackup && !forced)) return Result.success()
        return try {
            // A forced backup always writes; a scheduled one is skipped when nothing changed.
            val fingerprint = container.backup.backupToFolder(
                Uri.parse(folder),
                settings.backupKeep,
                if (forced) null else settings.lastBackupFingerprint,
            )
            if (fingerprint != null) container.settings.recordBackup(System.currentTimeMillis(), fingerprint)
            Result.success()
        } catch (t: Throwable) {
            container.settings.recordBackupError(t.message ?: "Backup failed")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "auto-backup"
        private const val NOW = "backup-now"
        private const val KEY_FORCE = "force"

        /** Schedules (or cancels) automatic backups to match the current settings. */
        fun schedule(context: Context, settings: AppSettings) {
            val wm = WorkManager.getInstance(context)
            if (!settings.autoBackup || settings.backupFolder == null) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(settings.backupIntervalDays.toLong().coerceAtLeast(1), TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Runs a backup to the chosen folder right away, even if nothing changed. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(androidx.work.workDataOf(KEY_FORCE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
