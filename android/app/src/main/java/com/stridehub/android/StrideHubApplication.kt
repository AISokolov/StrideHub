package com.stridehub.android

import android.app.Application
import android.content.Context
import com.stridehub.android.data.*
import com.stridehub.android.sync.SyncScheduler
import com.stridehub.android.widget.StrideHubWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate

class StrideHubApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(this)
    }
}

val Context.appGraph get() = (applicationContext as StrideHubApplication).graph

class AppGraph(private val context: Context) {
    val local = LocalRepository(context)
    val health = HealthConnectRepository(context)
    val settings = SecureSettings(context)
    val sync = SyncRepository(local, settings, canUpload = { health.access().granted })
    val garmin = GarminRepository(local, settings, diagnostic = { android.util.Log.i("StrideHubGarmin", it) })
    val networkSync = SyncCoordinator(sync::upload, { garmin.refresh() },
        { StrideHubWidget().updateAll(context) },
        { local.update { it.copy(syncMessage = "Health upload failed. Cached data remains pending.") } })
    private val refreshMutex = Mutex()

    suspend fun refresh(selected: LocalDate = LocalDate.now(), background: Boolean = false) = refreshMutex.withLock {
        val today = LocalDate.now()
        // Re-read recent days to pick up delayed OHealth writes and corrections.
        val dates = syncDates(today) + selected
        val readings = health.readDates(dates, background)
        local.update { it.merge(readings, Instant.now().toString()).copy(backgroundMessage = null) }
        StrideHubWidget().updateAll(context)
    }

    suspend fun refreshGarmin(selected: LocalDate? = null): UploadResult = try {
        garmin.refresh(selected?.toString()).also { if (it == UploadResult.RETRY) SyncScheduler.upload(context) }
    } finally { StrideHubWidget().updateAll(context) }

    suspend fun syncNow(selected: LocalDate? = null): UploadResult {
        try {
            val access = health.access()
            if (!access.granted) {
                local.update { it.copy(syncMessage = "Synchronization skipped. Grant Health Connect steps and sleep permissions.") }
            } else refresh()
        } catch (e: CancellationException) { throw e
        } catch (_: SecurityException) {
            local.update { it.copy(syncMessage = "Health permissions changed. Grant access before synchronizing.") }
        } catch (_: Exception) {
            local.update { it.copy(backgroundMessage = "Health refresh failed. Uploading previously cached data only.") }
        }
        val coordinator = if (selected == null) networkSync else SyncCoordinator(sync::upload,
            { garmin.refresh(selected.toString()) }, { StrideHubWidget().updateAll(context) },
            { local.update { it.copy(syncMessage = "Health upload failed. Cached data remains pending.") } })
        return coordinator.run().also { if (it == UploadResult.RETRY) SyncScheduler.upload(context) }
    }
}
