package com.stridehub.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.LocalDate
import javax.net.ssl.SSLException

class GarminRepository(
    private val local: HealthCacheStore, private val settings: BackendSettingsStore,
    private val diagnostic: (String) -> Unit = {},
    private val get: suspend (BackendSettings, String?) -> GarminResponse = GarminClient()::get,
) {
    private val mutex = Mutex()
    // Null lets the backend choose today using its configured calendar timezone.
    suspend fun refresh(date: String? = null): UploadResult = mutex.withLock {
        val config = settings.read()
        val fingerprint = config.fingerprint()
        local.update { if (it.garmin.configId == fingerprint) it else it.copy(garmin = GarminCache(configId = fingerprint)) }
        val requestedDate = date ?: local.snapshot().garmin.today().toString()
        suspend fun failure(text: String, kind: GarminFailure): UploadResult {
            if (settings.read().fingerprint() != fingerprint) return UploadResult.SKIPPED
            local.update {
                if (it.garmin.configId != fingerprint) it else it.copy(garmin = it.garmin.copy(
                    message = "$requestedDate · $text", failed = true, failure = kind,
                    attemptedDate = requestedDate, authBlocked = kind == GarminFailure.AUTHENTICATION))
            }
            diagnostic("date=$requestedDate outcome=${kind.name}")
            return if (kind == GarminFailure.NETWORK) UploadResult.RETRY else UploadResult.NEEDS_ATTENTION
        }
        try {
            if (date != null) require(LocalDate.parse(date).toString() == date)
            if (!config.configured) {
                failure("Configure the backend URL and token in Settings.", GarminFailure.CONFIGURATION)
                return@withLock UploadResult.NOT_CONFIGURED
            }
            if (local.snapshot().garmin.authBlocked) return@withLock UploadResult.NEEDS_ATTENTION
            val response = get(config, date)
            if (settings.read().fingerprint() != fingerprint) return@withLock UploadResult.SKIPPED
            val day = response.day
            if (response.status in 200..299) {
                if (day == null || (date != null && day.date != date))
                    return@withLock failure("Invalid Garmin response. Cached workouts are kept.", GarminFailure.INVALID_RESPONSE)
                local.update { cache -> if (cache.garmin.configId != fingerprint) cache else cache.copy(garmin = cache.garmin.copy(
                    days = (cache.garmin.days + (day.date to day)).toList().sortedByDescending { it.first }.take(31).toMap(),
                    message = "${day.date} · Downloaded ${day.activities.size} Garmin workout(s).",
                    failed = false, failure = null, attemptedDate = day.date, authBlocked = false)) }
                diagnostic("date=${day.date} http=${response.status} count=${day.activities.size} zone=${day.timeZone}")
                return@withLock UploadResult.COMPLETE
            }
            val auth = response.status == 401 || response.status == 403
            val result = failure(if (auth) "Garmin authentication rejected. Update the URL or token in Settings."
                else "Garmin backend error (HTTP ${response.status}). Cached workouts are kept.",
                if (auth) GarminFailure.AUTHENTICATION else GarminFailure.BACKEND)
            if (result == UploadResult.SKIPPED) result
            else if (retryableStatus(response.status)) UploadResult.RETRY else result
        } catch (e: CancellationException) { throw e
        } catch (_: GarminPayloadException) {
            failure("Invalid Garmin response. Cached workouts are kept.", GarminFailure.INVALID_RESPONSE)
        } catch (_: SSLException) {
            failure("Garmin TLS certificate rejected. Check the backend certificate.", GarminFailure.TLS)
        } catch (_: IOException) {
            failure("Network unavailable. Cached workouts are kept; download will retry.", GarminFailure.NETWORK)
        } catch (_: Exception) {
            failure("Garmin could not refresh. Check backend settings; cached workouts are kept.", GarminFailure.CONFIGURATION)
        }
    }
}
