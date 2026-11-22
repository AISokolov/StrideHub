package com.stridehub.android.data

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection

/** Shared transport policy: never follow redirects with the device credential. */
fun HttpURLConnection.configureBackend(config: BackendSettings, method: String) {
    validateBackendUrl(config.baseUrl)
    require(config.token.none { it.isISOControl() }) { "Invalid access token" }
    requestMethod = method
    instanceFollowRedirects = false
    connectTimeout = 10_000
    readTimeout = 15_000
    setRequestProperty("Authorization", "Bearer ${config.token}")
    setRequestProperty("Accept", "application/json")
}

fun InputStream.readBounded(limit: Int): ByteArray {
    val buffer = ByteArray(limit + 1)
    var size = 0
    while (size < buffer.size) {
        val count = read(buffer, size, buffer.size - size)
        if (count == -1) break
        size += count
    }
    if (size > limit) throw IOException("Backend response too large")
    return buffer.copyOf(size)
}
