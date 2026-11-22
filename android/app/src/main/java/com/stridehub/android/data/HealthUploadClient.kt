package com.stridehub.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UploadResponse(val status: Int, val confirmed: Boolean = false)
fun retryableStatus(status: Int) = status == 408 || status == 429 || status in 500..599

/** No response bodies, credentials, or health payloads are logged. */
class HealthUploadClient(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun post(config: BackendSettings, day: DailyHealth): UploadResponse = withContext(Dispatchers.IO) {
        val connection = openConnection(URL("${config.baseUrl}/api/health/sync"))
        try {
            connection.configureBackend(config, "POST")
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = CacheJson.payload(day).toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                return@withContext UploadResponse(status)
            }
            // A 2xx alone is insufficient: require the endpoint's explicit acknowledgement.
            val body = connection.inputStream.use { stream ->
                val buffer = ByteArray(16_385)
                var size = 0
                while (size < buffer.size) {
                    val count = stream.read(buffer, size, buffer.size - size)
                    if (count == -1) break
                    size += count
                }
                buffer.copyOf(size)
            }
            val confirmed = if (body.size > 16_384) false else try {
                val json = JSONObject(String(body, Charsets.UTF_8))
                json.optBoolean("success") && json.optString("date") == day.date &&
                    json.has("stepsSaved") && json.optBoolean("stepsSaved") == (day.steps != null) &&
                    json.optInt("sleepSessionsSaved", -1) == day.sleepSessions.distinctBy { it.id }.size
            } catch (_: org.json.JSONException) { false }
            UploadResponse(status, confirmed)
        } finally {
            connection.disconnect()
        }
    }
}
