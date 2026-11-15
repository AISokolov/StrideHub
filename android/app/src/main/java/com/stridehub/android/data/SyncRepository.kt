package com.stridehub.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.net.ssl.SSLException

enum class UploadResult { COMPLETE, RETRY, NEEDS_ATTENTION, NOT_CONFIGURED }
fun retryableStatus(status: Int) = status == 408 || status == 429 || status in 500..599

class SyncRepository(private val local: LocalRepository, private val settings: SecureSettings) {
    private val mutex = Mutex()

    suspend fun saveSettings(url: String, token: String) = mutex.withLock {
        val normalized = validateBackendUrl(url)
        require(normalized.isEmpty() || token.isNotBlank()) { "Enter a server-issued access token." }
        require(token.none { it.isISOControl() }) { "The token contains invalid characters." }
        settings.save(BackendSettings(normalized, if (normalized.isEmpty()) "" else token.trim()))
        // Saving a destination/account makes all cached days eligible for that account.
        local.update { it.copy(days = it.days.mapValues { (_, d) -> d.copy(pending = true) },
            lastSync = null, syncMessage = if (normalized.isEmpty()) "Server sync is off." else "Settings saved. Data is waiting to sync.") }
    }

    suspend fun upload(): UploadResult = mutex.withLock {
        val config = settings.read()
        if (!config.configured) return@withLock UploadResult.NOT_CONFIGURED
        validateBackendUrl(config.baseUrl)
        val pending = local.snapshot().days.values.filter { it.pending }.sortedBy { it.date }
        if (pending.isEmpty()) {
            local.update { it.copy(syncMessage = "All cached data is synchronized.") }
            return@withLock UploadResult.COMPLETE
        }
        // Bound each worker run; further batches use WorkManager's retry mechanism.
        for (day in pending.take(10)) {
            val status = try { post(config, day) } catch (_: SSLException) {
                local.update { it.copy(syncMessage = "TLS certificate rejected. Check the server certificate; data is still pending.") }
                return@withLock UploadResult.NEEDS_ATTENTION
            } catch (_: IOException) {
                local.update { it.copy(syncMessage = "Server unreachable. Data is saved; synchronization will retry.") }
                return@withLock UploadResult.RETRY
            }
            if (status !in 200..299) {
                val message = when (status) {
                    401, 403 -> "Server rejected authentication. Check your token."
                    404 -> "The /api/health/sync endpoint is not available yet."
                    in 300..399 -> "Server redirected the request. Enter the final HTTPS origin."
                    else -> "Server returned HTTP $status."
                }
                local.update { it.copy(syncMessage = "$message Data remains pending.") }
                return@withLock if (retryableStatus(status)) UploadResult.RETRY else UploadResult.NEEDS_ATTENTION
            }
            local.update { it.acknowledge(day, Instant.now().toString()) }
        }
        val more = local.snapshot().days.values.any { it.pending }
        local.update { it.copy(syncMessage = if (more) "More cached data is waiting to sync." else "All cached data is synchronized.") }
        if (more) UploadResult.RETRY else UploadResult.COMPLETE
    }

    private suspend fun post(config: BackendSettings, day: DailyHealth): Int = withContext(Dispatchers.IO) {
        val connection = URL("${config.baseUrl}/api/health/sync").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false // Never forward bearer credentials to a redirect.
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", "${day.date}-${day.revision}")
            val payload = CacheJson.payload(day).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            connection.responseCode
        } finally { connection.disconnect() }
    }
}
