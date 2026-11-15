package com.stridehub.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.glance.appwidget.updateAll
import com.stridehub.android.appGraph
import com.stridehub.android.data.UploadResult
import com.stridehub.android.widget.StrideHubWidget
import kotlinx.coroutines.CancellationException

class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.appGraph
        var retry = false
        try {
            val access = graph.health.access()
            if (access.granted && access.backgroundGranted) graph.refresh(background = true)
            else graph.local.update { it.copy(backgroundMessage = access.backgroundMessage) }
        } catch (e: CancellationException) { throw e
        } catch (_: SecurityException) {
            graph.local.update { it.copy(backgroundMessage = "Health permissions changed. Open StrideHub to reconnect.") }
        } catch (_: Exception) {
            graph.local.update { it.copy(backgroundMessage = "Health refresh failed. Cached data is still available.") }
            retry = true
        }
        // Network constraints belong only to upload: offline local reads must still run.
        SyncScheduler.upload(applicationContext)
        StrideHubWidget().updateAll(applicationContext)
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        when (applicationContext.appGraph.sync.upload()) {
            UploadResult.RETRY -> Result.retry()
            else -> Result.success()
        }
    } catch (e: CancellationException) { throw e
    } catch (_: Exception) {
        applicationContext.appGraph.local.update { it.copy(syncMessage = "Could not synchronize. Check backend settings; data remains pending.") }
        Result.failure()
    }
}
