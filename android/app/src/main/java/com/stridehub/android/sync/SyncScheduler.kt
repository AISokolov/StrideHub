package com.stridehub.android.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(30, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("stridehub-health-refresh",
            ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun upload(context: Context) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("stridehub-upload", ExistingWorkPolicy.KEEP, request)
    }
}
