package com.stridehub.android.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import javax.net.ssl.SSLException

enum class UploadResult { COMPLETE, RETRY, NEEDS_ATTENTION, NOT_CONFIGURED, SKIPPED }

fun BackendSettings.fingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest("$baseUrl\n$token".toByteArray()).joinToString("") { "%02x".format(it) }

class SyncRepository(
    private val local: HealthCacheStore,
    private val settings: BackendSettingsStore,
    private val canUpload: suspend () -> Boolean,
    private val post: suspend (BackendSettings, DailyHealth) -> UploadResponse = HealthUploadClient()::post,
    private val diagnostic: (Int) -> Unit = { status -> Log.w("StrideHubSync", "Upload rejected: HTTP $status; revision held until data or configuration changes.") },
) {
    private val mutex = Mutex()
    private val active = MutableStateFlow(false)
    val syncing = active.asStateFlow()

    suspend fun saveSettings(url: String, token: String) = mutex.withLock {
        val normalized = validateBackendUrl(url)
        require(normalized.isEmpty() || token.isNotBlank()) { "Enter a server-issued access token." }
        require(token.none { it.isISOControl() }) { "The token contains invalid characters." }
        val config = BackendSettings(normalized, if (normalized.isEmpty()) "" else token.trim())
        settings.save(config)
        local.update { cache ->
            if (cache.syncConfigId == config.fingerprint()) cache
            else cache.copy(days = cache.days.mapValues { (_, day) -> day.copy(pending = true) },
                syncConfigId = config.fingerprint(), authBlocked = false, rejectedUploads = emptyMap(),
                garmin = GarminCache(configId = config.fingerprint()),
                lastSync = null, syncMessage = if (normalized.isEmpty()) "Server sync is off." else "Settings saved. Data is waiting to sync.")
        }
    }

    suspend fun upload(): UploadResult = mutex.withLock {
        active.value = true
        try { uploadPending() } finally { active.value = false }
    }

    private suspend fun uploadPending(): UploadResult {
        if (!canUpload()) {
            local.update { it.copy(syncMessage = "Synchronization skipped. Grant Health Connect steps and sleep permissions; cached data is kept.") }
            return UploadResult.SKIPPED
        }
        val config = settings.read()
        if (!config.configured) {
            local.update { it.copy(syncMessage = "Configure your backend URL and access token in Settings.") }
            return UploadResult.NOT_CONFIGURED
        }
        validateBackendUrl(config.baseUrl)
        require(config.token.none { it.isISOControl() }) { "Invalid token configuration." }
        // Also detects a changed debug BuildConfig after installing an updated APK.
        local.update { cache ->
            if (cache.syncConfigId == config.fingerprint()) cache else cache.copy(
                syncConfigId = config.fingerprint(), authBlocked = false, rejectedUploads = emptyMap(),
                days = cache.days.mapValues { (_, day) -> day.copy(pending = true) }, lastSync = null)
        }
        val cache = local.snapshot()
        if (cache.authBlocked) {
            local.update { it.copy(syncMessage = "Authentication rejected. Change the URL or token in Settings before trying again.") }
            return UploadResult.NEEDS_ATTENTION
        }
        val pending = cache.days.values.filter { it.pending && cache.rejectedUploads[it.date] != it.revision }.sortedBy { it.date }
        var retry = false
        var problem: String? = null
        for (day in pending.take(10)) {
            val response = try { post(config, day) } catch (_: SSLException) {
                problem = "TLS certificate rejected. Check the server certificate or settings."
                local.update { it.copy(rejectedUploads = it.rejectedUploads + (day.date to day.revision)) }
                continue
            } catch (_: IOException) {
                problem = "Server unreachable. Data is saved; synchronization will retry when connected."
                retry = true
                continue
            }
            if (response.confirmed) {
                local.update { it.acknowledge(day, Instant.now().toString())
                    .copy(rejectedUploads = it.rejectedUploads - day.date) }
                continue
            }
            if (response.status == 401 || response.status == 403) {
                local.update { it.copy(authBlocked = true,
                    syncMessage = "Authentication rejected (HTTP ${response.status}). Change the URL or token in Settings.") }
                return UploadResult.NEEDS_ATTENTION
            }
            problem = when {
                response.status == 400 -> "A day's payload was rejected (HTTP 400). Other days can sync; rejected data waits for a change."
                response.status in 200..299 -> "Server did not confirm the saved data. Check the endpoint; data remains pending."
                retryableStatus(response.status) -> "Server temporarily unavailable (HTTP ${response.status}). Synchronization will retry."
                else -> "Server returned HTTP ${response.status}. Check the backend URL and configuration."
            }
            if (retryableStatus(response.status)) retry = true else {
                // Do not resubmit an unchanged invalid payload on every periodic refresh.
                diagnostic(response.status)
                local.update { it.copy(rejectedUploads = it.rejectedUploads + (day.date to day.revision)) }
            }
        }
        val remaining = local.snapshot()
        val eligible = remaining.days.values.any { it.pending && remaining.rejectedUploads[it.date] != it.revision }
        val rejected = remaining.days.values.any { it.pending && remaining.rejectedUploads[it.date] == it.revision }
        local.update { it.copy(syncMessage = problem ?: when {
            rejected -> "Some data was rejected. Check backend settings; unchanged rejected data is not retried."
            eligible -> "More cached data is waiting to sync."
            else -> "All cached data is synchronized."
        }) }
        return when {
            retry || eligible -> UploadResult.RETRY
            rejected -> UploadResult.NEEDS_ATTENTION
            else -> UploadResult.COMPLETE
        }
    }
}
