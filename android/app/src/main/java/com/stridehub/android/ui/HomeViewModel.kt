package com.stridehub.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stridehub.android.appGraph
import com.stridehub.android.data.*
import com.stridehub.android.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeState(
    val cache: HealthCache = HealthCache(),
    val selected: LocalDate = LocalDate.now(),
    val access: HealthAccess? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.appGraph
    private val mutable = MutableStateFlow(HomeState())
    val state = mutable.asStateFlow()
    private var lastToday = LocalDate.now()

    init {
        viewModelScope.launch {
            graph.local.cache.catch { mutable.update { it.copy(message = "The local cache could not be read. Restart the app and try again.") } }
                .collect { cache ->
                    val today = cache.garmin.today()
                    mutable.update { it.copy(cache = cache, selected = if (it.selected == lastToday) today else it.selected) }
                    lastToday = today
                }
        }
    }

    fun onOpen() {
        val today = mutable.value.cache.garmin.today()
        if (today != lastToday) {
            mutable.update { it.copy(selected = if (it.selected == lastToday) today else it.selected) }
            lastToday = today
        }
        refresh()
    }

    fun select(date: LocalDate) { mutable.update { it.copy(selected = date) }; refresh() }

    fun refresh() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            val selected = mutable.value.selected
            // Start independently: denied/slow Health Connect cannot block Garmin.
            val download = launch {
                try { graph.refreshGarmin(selected.takeUnless { it == mutable.value.cache.garmin.today() }) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutable.update { it.copy(message = "Could not refresh Garmin. Check Settings; cached data is kept.") } }
            }
            try {
                val access = graph.health.access()
                mutable.update { it.copy(access = access) }
                if (access.granted) {
                    graph.refresh(selected)
                }
            } catch (e: CancellationException) { throw e
            } catch (_: SecurityException) {
                mutable.update { it.copy(message = "Health access was denied or revoked. Grant steps and sleep permissions, then refresh.",
                    access = it.access?.copy(granted = false)) }
            } catch (_: Exception) {
                mutable.update { it.copy(message = "Could not refresh Health Connect. Check its permissions and try again. Cached data is shown.") }
            } finally {
                download.join()
                SyncScheduler.upload(getApplication())
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun sync() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            try {
                when (graph.syncNow(mutable.value.selected.takeUnless { it == mutable.value.cache.garmin.today() })) {
                    UploadResult.NOT_CONFIGURED -> mutable.update { it.copy(message = "Open Settings to enter your backend URL and access token.") }
                    else -> Unit
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                mutable.update { it.copy(message = "Sync failed. Check backend settings. Your data remains saved locally.") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
