package com.stridehub.android.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class GarminTest {
    private val date = "2026-11-18"
    private val now = "2026-11-18T12:00:00Z"
    private val config = BackendSettings("https://example.com", "test-device-token")
    private val activity = """{"id":1234567890123,"name":"Koper Ber","type":"running","distanceMeters":8303.76953125,"durationSeconds":2352.367919921875,"averagePaceSecondsPerKm":283.28612127617026,"averageHeartRate":182}"""
    private fun json(activities: String = activity) = """{"date":"$date","timeZone":"Europe/Ljubljana","activities":[$activities]}"""
    private fun day() = GarminJson.parse(json(), date, now)
    private class MemoryCache(var value: HealthCache = HealthCache()) : HealthCacheStore {
        override suspend fun snapshot() = value
        override suspend fun update(transform: (HealthCache) -> HealthCache) { value = transform(value) }
    }
    private class MemorySettings(var value: BackendSettings) : BackendSettingsStore {
        override suspend fun read() = value
        override suspend fun save(settings: BackendSettings) { value = settings }
    }
    private fun cached() = MemoryCache(HealthCache(garmin = GarminCache(mapOf(date to day()), configId = config.fingerprint())))

    @Test fun `parses real metrics IDs and multiple workouts without inventing cycling pace`() {
        val cycling = JSONObject(activity).put("id", "second").put("type", "cycling").toString()
        val parsed = GarminJson.parse(json("$activity,$cycling"), date, now)
        assertEquals(2, parsed.activities.size)
        assertEquals("1234567890123", parsed.activities[0].id)
        assertEquals(8303.76953125, parsed.activities[0].distanceMeters!!, 0.01)
        assertTrue(parsed.activities[0].detail().contains("4:43 /km"))
        assertFalse(parsed.activities[1].detail().contains("/km"))
    }
    @Test fun `cache round trip preserves all metrics and last successful refresh`() {
        val cache = cached().value
        assertEquals(cache, CacheJson.decode(CacheJson.encode(cache)))
        val legacy = JSONObject(CacheJson.encode(cache)).apply { remove("garmin") }
        assertEquals(GarminCache(), CacheJson.decode(legacy.toString()).garmin)
    }
    @Test fun `known empty is distinct from not loaded or failed`() {
        val empty = GarminJson.parse(json(""), date, now)
        assertEquals("No Garmin workouts · $date", GarminCache(mapOf(date to empty)).status(date))
        assertTrue(GarminCache().status(date).contains("not loaded"))
        assertTrue(GarminCache(failed = true).status(date).contains("unavailable"))
        assertTrue(GarminCache(mapOf(date to empty), failed = true).status(date).contains("cached"))
    }
    @Test fun `previous day cache never claims workouts are from today`() {
        val cache = cached().value.garmin
        assertTrue(cache.status("2026-11-19").contains("not loaded"))
        assertFalse(cache.status("2026-11-19").contains("today"))
        assertNull(cache.displayDay("2026-11-19"))
    }
    @Test fun `server and offline failures preserve cached workouts and health`() = runBlocking {
        for (status in listOf(0, 429, 502, 503)) {
            val local = cached()
            val health = DailyHealth(date, "Europe/Ljubljana", 500, emptyList(), now)
            local.value = local.value.copy(days = mapOf(date to health))
            val repo = GarminRepository(local, MemorySettings(config)) { _, _ ->
                if (status == 0) throw IOException("offline") else GarminResponse(status)
            }
            assertEquals(UploadResult.RETRY, repo.refresh(date))
            assertEquals(day(), local.value.garmin.days[date])
            assertEquals(health, local.value.days[date])
            assertTrue(local.value.garmin.failed)
            assertTrue(local.value.garmin.status(date).contains("cached"))
        }
    }
    @Test fun `successful empty download replaces stale workouts and advances timestamp`() = runBlocking {
        val local = cached()
        val empty = GarminJson.parse(json(""), date, "2026-11-18T14:00:00Z")
        val repo = GarminRepository(local, MemorySettings(config)) { _, _ -> GarminResponse(200, empty) }
        assertEquals(UploadResult.COMPLETE, repo.refresh(date))
        assertEquals(empty, local.value.garmin.days[date])
        assertFalse(local.value.garmin.failed)
    }
    @Test fun `authentication rejection persists until settings change`() = runBlocking {
        val local = cached()
        val settings = MemorySettings(config)
        var calls = 0
        val get: suspend (BackendSettings, String?) -> GarminResponse = { _, _ -> calls++; GarminResponse(401) }
        GarminRepository(local, settings, get = get).refresh(date)
        GarminRepository(local, settings, get = get).refresh(date)
        assertEquals(1, calls)
        settings.value = config.copy(token = "changed-test-token")
        GarminRepository(local, settings, get = get).refresh(date)
        assertEquals(2, calls)
    }
    @Test fun `changing backend during request discards response`() = runBlocking {
        val local = cached()
        val settings = MemorySettings(config)
        val repo = GarminRepository(local, settings) { _, _ ->
            settings.value = config.copy(baseUrl = "https://other.example")
            GarminResponse(200, GarminJson.parse(json(""), date, now))
        }
        assertEquals(UploadResult.SKIPPED, repo.refresh(date))
        assertEquals(1, local.value.garmin.days.getValue(date).activities.size)
    }
    @Test fun `malformed and wrong-date payloads are not accepted as empty data`() {
        for (body in listOf("{}", json().replace(date, "2026-11-15"), json().replace("8303.76953125", "-1"))) {
            assertThrows(Exception::class.java) { GarminJson.parse(body, date, now) }
        }
    }
    private class Connection(private val status: Int, body: String) : HttpURLConnection(URL("https://example.com")) {
        var disconnected = false
        private val response = ByteArrayInputStream(body.toByteArray())
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getInputStream() = response
        override fun getErrorStream() = response
    }
    @Test fun `authenticated GET uses backend timeouts and disables redirects`() = runBlocking {
        val connection = Connection(200, json())
        var target = ""
        val result = GarminClient { target = it.toString(); connection }.get(config, date)
        assertEquals("https://example.com/api/garmin/daily?date=$date", target)
        assertEquals("Bearer test-device-token", connection.getRequestProperty("Authorization"))
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertTrue(connection.disconnected)
        assertEquals(1, result.day!!.activities.size)
    }
    @Test fun `HTTP errors redirects and malformed responses cannot clear cache`() = runBlocking {
        for (status in listOf(301, 401, 403, 429, 500, 502)) {
            val connection = Connection(status, "upstream error")
            val result = GarminClient { connection }.get(config, date)
            assertEquals(status, result.status)
            assertNull(result.day)
            assertTrue(connection.disconnected)
        }
        try { GarminClient { Connection(200, "{}") }.get(config, date); fail("Expected parse failure") }
        catch (_: IOException) { }
    }
    @Test fun `November 18 downloads parses persists and supplies the selected app date only`() = runBlocking {
        val local = MemoryCache()
        val client = GarminClient { Connection(200, json()) }
        val repo = GarminRepository(local, MemorySettings(config), get = client::get)
        assertEquals(UploadResult.COMPLETE, repo.refresh(date))
        // Same serialized representation written by LocalRepository's DataStore.
        val restored = CacheJson.decode(CacheJson.encode(local.snapshot())).garmin
        val activity = restored.displayDay(date)!!.activities.single()
        assertEquals("Running — Koper Ber", activity.title())
        assertTrue(activity.detail().contains("39:12"))
        assertTrue(activity.detail().contains("4:43 /km"))
        assertNull(restored.displayDay("2026-11-19"))
    }
    @Test fun `fresh refresh replaces cached selected date without losing other dates`() = runBlocking {
        val local = cached()
        var calls = 0
        val repo = GarminRepository(local, MemorySettings(config)) { _, selected ->
            assertEquals(date, selected)
            calls++
            GarminResponse(200, day().copy(activities = if (calls == 1) emptyList() else day().activities))
        }
        repo.refresh(date)
        assertTrue(local.snapshot().garmin.displayDay(date)!!.activities.isEmpty())
        repo.refresh(date)
        assertEquals(2, calls)
        assertEquals(1, local.snapshot().garmin.displayDay(date)!!.activities.size)
    }
    @Test fun `today is selected by backend and uses its timezone around midnight and DST`() = runBlocking {
        var target = ""
        val client = GarminClient { target = it.toString(); Connection(200, json()) }
        val local = MemoryCache()
        GarminRepository(local, MemorySettings(config), get = client::get).refresh()
        assertEquals("https://example.com/api/garmin/daily", target)
        val cache = local.snapshot().garmin
        for ((instant, expected) in listOf(
            "2026-11-18T23:30:00Z" to "2026-11-19",
            "2026-03-29T22:30:00Z" to "2026-03-30",
            "2026-10-25T22:30:00Z" to "2026-10-25",
            "2026-10-25T23:30:00Z" to "2026-10-26")) {
            assertEquals(expected, cache.today(Instant.parse(instant)).toString())
        }
    }
    @Test fun `failure categories survive persistence and never become successful empty data`() = runBlocking {
        for ((status, expected) in listOf(200 to GarminFailure.INVALID_RESPONSE, 401 to GarminFailure.AUTHENTICATION,
            502 to GarminFailure.BACKEND, 0 to GarminFailure.NETWORK)) {
            val local = cached()
            val client = GarminClient { if (status == 0) throw IOException() else Connection(status, "{}") }
            GarminRepository(local, MemorySettings(config), get = client::get).refresh(date)
            val result = CacheJson.decode(CacheJson.encode(local.snapshot())).garmin
            assertEquals(expected, result.failure)
            assertEquals(day(), result.displayDay(date))
            assertTrue(result.failedFor(date))
            assertFalse(result.failedFor("2026-11-19"))
        }
    }
    @Test fun `diagnostics contain only date response status count and timezone`() = runBlocking {
        val messages = mutableListOf<String>()
        GarminRepository(MemoryCache(), MemorySettings(config), diagnostic = { messages += it }) { _, _ -> GarminResponse(200, day()) }.refresh(date)
        assertEquals(listOf("date=$date http=200 count=1 zone=Europe/Ljubljana"), messages)
        assertFalse(messages.joinToString().contains(config.token))
    }
    @Test fun `Garmin failure still acknowledges health and refreshes widget`() = runBlocking {
        val calls = mutableListOf<String>()
        val result = SyncCoordinator(
            { calls += "health"; UploadResult.COMPLETE },
            { calls += "garmin"; UploadResult.RETRY },
            { calls += "widget" }, { calls += "health-failed" }).run()
        assertEquals(UploadResult.RETRY, result)
        assertEquals(listOf("health", "garmin", "widget"), calls)
    }
    @Test fun `health exceptions and missing permission do not block Garmin download`() = runBlocking {
        for (throws in listOf(false, true)) {
            var downloaded = false
            var refreshed = false
            SyncCoordinator({ if (throws) throw IOException() else UploadResult.SKIPPED },
                { downloaded = true; UploadResult.COMPLETE }, { refreshed = true }, {}).run()
            assertTrue(downloaded)
            assertTrue(refreshed)
        }
    }
}

