package com.stridehub.android.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HealthUploadClientTest {
    private val day = DailyHealth("2026-11-15", "Europe/Ljubljana", 6508, emptyList(), "2026-11-15T12:00:00Z")
    private val config = BackendSettings("http://192.168.0.79:3001", "test-token-only")

    @Test fun `real HTTP round trip confirms upload without touching the user backend`() = runBlocking {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)); soTimeout = 5000 }
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<Pair<Map<String, String>, JSONObject>> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                assertEquals("POST /api/health/sync HTTP/1.1", reader.readLine())
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine()
                    if (line.isEmpty()) break
                    headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                }
                val body = CharArray(headers.getValue("content-length").toInt())
                var offset = 0
                while (offset < body.size) {
                    val count = reader.read(body, offset, body.size - offset)
                    check(count > 0)
                    offset += count
                }
                val response = """{"success":true,"date":"2026-11-15","stepsSaved":true,"sleepSessionsSaved":0}"""
                socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n" + response).toByteArray())
                headers to JSONObject(String(body))
            }
        }
        try {
            val result = HealthUploadClient().post(config.copy(baseUrl = "http://127.0.0.1:${server.localPort}"), day)
            assertTrue(result.confirmed)
            val (headers, payload) = received.get(5, TimeUnit.SECONDS)
            assertEquals("Bearer test-token-only", headers["authorization"])
            assertEquals(6508L, payload.getLong("steps"))
        } finally { server.close(); executor.shutdownNow() }
    }

    private class Connection(private val status: Int, body: String) : HttpURLConnection(URL("http://localhost")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var inputClosed = false
        var outputClosed = false
        var failure: IOException? = null
        val response = object : ByteArrayInputStream(body.toByteArray()) {
            override fun close() { inputClosed = true; super.close() }
        }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getOutputStream() = object : java.io.OutputStream() {
            override fun write(value: Int) { failure?.let { throw it }; sent.write(value) }
            override fun close() { outputClosed = true }
        }
        override fun getResponseCode() = status
        override fun getInputStream() = response
        override fun getErrorStream() = response
    }

    @Test fun `POST includes bearer authentication JSON and timeouts and closes streams`() = runBlocking {
        val connection = Connection(200, """{"success":true,"date":"2026-11-15","stepsSaved":true,"sleepSessionsSaved":0}""")
        var target: URL? = null
        val caller = Thread.currentThread()
        val client = HealthUploadClient {
            assertNotEquals(caller, Thread.currentThread())
            target = it
            connection
        }
        assertTrue(client.post(config, day).confirmed)
        assertEquals("http://192.168.0.79:3001/api/health/sync", target.toString())
        assertEquals("POST", connection.requestMethod)
        assertEquals("Bearer test-token-only", connection.getRequestProperty("Authorization"))
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(6508L, JSONObject(connection.sent.toString("UTF-8")).getLong("steps"))
        assertTrue(connection.inputClosed && connection.outputClosed && connection.disconnected)
    }

    @Test fun `HTTP failures are not acknowledged or redirected`() = runBlocking {
        for (status in listOf(301, 400, 401, 403, 429, 500, 503)) {
            val connection = Connection(status, "sensitive response must not be surfaced")
            val result = HealthUploadClient { connection }.post(config, day)
            assertEquals(status, result.status)
            assertFalse(result.confirmed)
            assertTrue(connection.inputClosed && connection.disconnected)
        }
    }

    @Test fun `2xx requires confirmation of the date steps and session count`() = runBlocking {
        for (body in listOf("", "not json", """{"success":false}""",
            """{"success":true,"date":"2026-11-14","stepsSaved":true,"sleepSessionsSaved":0}""",
            """{"success":true,"date":"2026-11-15","stepsSaved":false,"sleepSessionsSaved":0}""",
            """{"success":true,"date":"2026-11-15","stepsSaved":true,"sleepSessionsSaved":1}""")) {
            assertFalse(HealthUploadClient { Connection(200, body) }.post(config, day).confirmed)
        }
    }

    @Test fun `offline and timeout errors close the connection and remain retryable`() = runBlocking {
        for (error in listOf(IOException("offline"), SocketTimeoutException("timeout"))) {
            val connection = Connection(200, "").apply { failure = error }
            try { HealthUploadClient { connection }.post(config, day); fail("Expected IO failure") }
            catch (caught: IOException) { assertEquals(error.javaClass, caught.javaClass) }
            assertTrue(connection.outputClosed && connection.disconnected)
        }
    }
}
