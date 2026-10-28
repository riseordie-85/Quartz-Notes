package com.quartz.notes.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.quartz.notes.QuartzApp
import java.util.concurrent.TimeUnit

/** Syncs every 15 minutes while the app is in the background. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = (applicationContext as QuartzApp).container.sync
        if (!sync.signedIn()) return Result.success()
        return when (sync.syncNow()) {
            null -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "sync"

        fun schedule(context: Context, signedIn: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!signedIn) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
