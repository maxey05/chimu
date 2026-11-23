package com.chimu

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The background trigger: WorkManager runs [SyncManager.syncNow] about every 30 minutes while the
 * phone has a network connection, even when the app isn't open. If nobody is signed in it does nothing.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ChimuApp
        return when (app.sync.syncNow()) {
            SyncOutcome.RETRY -> Result.retry()
            // A refused row won't be accepted on a quicker retry; the next period tries again anyway.
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "chimu-sync"
        private const val PERIOD_MINUTES = 30L

        /** Safe to call on every launch: an existing schedule is kept as it is. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
