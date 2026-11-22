package com.stridehub.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate

data class GarminResponse(val status: Int, val day: GarminDay? = null)
class GarminPayloadException(cause: Exception) : IOException("Invalid Garmin response", cause)

class GarminClient(private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    suspend fun get(config: BackendSettings, date: String?): GarminResponse = withContext(Dispatchers.IO) {
        if (date != null) require(LocalDate.parse(date).toString() == date)
        val query = date?.let { "?date=$it" }.orEmpty()
        val connection = openConnection(URL("${config.baseUrl}/api/garmin/daily$query"))
        try {
            connection.configureBackend(config, "GET")
            val status = connection.responseCode
            if (status !in 200..299) {
                connection.errorStream?.close()
                return@withContext GarminResponse(status)
            }
            val text = connection.inputStream.use { String(it.readBounded(1_048_576), Charsets.UTF_8) }
            val day = try { GarminJson.parse(text, date, Instant.now().toString()) }
            catch (e: Exception) { throw GarminPayloadException(e) }
            GarminResponse(status, day)
        } finally { connection.disconnect() }
    }
}
