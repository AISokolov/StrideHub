package com.stridehub.android

import android.app.Application
import android.content.Context
import com.stridehub.android.data.*
import com.stridehub.android.sync.SyncScheduler
import com.stridehub.android.widget.StrideHubWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val sync = SyncRepository(local, settings)
    private val refreshMutex = Mutex()

    suspend fun refresh(selected: LocalDate = LocalDate.now(), background: Boolean = false) = refreshMutex.withLock {
        val today = LocalDate.now()
        // Re-read recent days to pick up delayed OHealth writes and corrections.
        val dates = setOf(today, today.minusDays(1), today.minusDays(2), selected)
        val readings = health.readDates(dates, background)
        local.update { it.merge(readings, Instant.now().toString()).copy(backgroundMessage = null) }
        StrideHubWidget().updateAll(context)
    }
}
