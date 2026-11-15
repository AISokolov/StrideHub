package com.stridehub.android.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthDataTest {
    private val zone = ZoneId.of("Europe/Ljubljana")
    private val now = "2026-11-15T12:00:00Z"
    private fun day(steps: Long? = 100L, date: String = "2026-11-15", sessions: List<SleepSession> = emptyList()) =
        DailyHealth(date, zone.id, steps, sessions, now)
    private fun sleep(id: String = "hc-1") = SleepSession(id, "2026-11-14T23:00:00+01:00",
        "2026-11-15T07:00:00+01:00", 450, 480, "recorded_sleep", "com.ohealth")

    @Test fun `spring DST day contains 23 hours`() {
        val (start, end) = dayBounds(LocalDate.parse("2026-03-29"), zone)
        assertEquals(23L, Duration.between(start, end).toHours())
        assertEquals(Instant.parse("2026-03-28T23:00:00Z"), start)
    }

    @Test fun `autumn DST day contains 25 hours`() {
        val (start, end) = dayBounds(LocalDate.parse("2026-10-25"), zone)
        assertEquals(25L, Duration.between(start, end).toHours())
    }

    @Test fun `wake date uses local timezone including exact midnight`() {
        assertEquals(LocalDate.parse("2026-11-15"), wakeDate(Instant.parse("2026-11-14T23:00:00Z"), zone))
        assertEquals(LocalDate.parse("2026-11-14"), wakeDate(Instant.parse("2026-11-14T22:59:59Z"), zone))
        assertEquals(LocalDate.parse("2026-11-14"), wakeDate(Instant.parse("2026-11-15T01:00:00Z"), ZoneId.of("America/Los_Angeles")))
    }

    @Test fun `stages are clipped merged and measured without double counting`() {
        val start = Instant.parse("2026-11-14T22:00:00Z")
        val end = start.plusSeconds(3600)
        assertEquals(60L, intervalMinutes(start, end, listOf(
            start.minusSeconds(600) to start.plusSeconds(1800),
            start.plusSeconds(1200) to end.plusSeconds(600))))
        assertEquals(0L, intervalMinutes(start, end, emptyList()))
    }

    @Test fun `unchanged refresh retains acknowledgement and revision`() {
        val first = day()
        val cache = HealthCache().merge(listOf(first), now).acknowledge(first, now)
        val refreshed = cache.merge(listOf(day().copy(refreshedAt = "2026-11-15T13:00:00Z")), now)
        assertEquals(1, refreshed.days.size)
        assertFalse(refreshed.days.getValue(first.date).pending)
        assertEquals(first.revision, refreshed.days.getValue(first.date).revision)
    }

    @Test fun `stale server acknowledgement cannot clear changed data`() {
        val original = day()
        val changed = day(200)
        val cache = HealthCache().merge(listOf(original), now).merge(listOf(changed), now).acknowledge(original, now)
        assertTrue(cache.days.getValue(changed.date).pending)
        assertEquals(200L, cache.days.getValue(changed.date).steps)
    }

    @Test fun `replacement removes deleted sleep sessions and marks day pending`() {
        val original = day(sessions = listOf(sleep()))
        val cache = HealthCache().merge(listOf(original), now).acknowledge(original, now)
            .merge(listOf(day(sessions = emptyList())), now)
        assertTrue(cache.days.getValue(original.date).sleepSessions.isEmpty())
        assertTrue(cache.days.getValue(original.date).pending)
    }

    @Test fun `corrected session is not duplicated across dates`() {
        val first = day(date = "2026-11-14", sessions = listOf(sleep()))
        val cache = HealthCache().merge(listOf(first), now).acknowledge(first, now)
            .merge(listOf(day(sessions = listOf(sleep()))), now)
        assertEquals(1, cache.days.values.sumOf { it.sleepSessions.size })
        assertTrue(cache.days.getValue("2026-11-14").pending)
    }

    @Test fun `empty values survive disk and upload serialization without invented zeros`() {
        val original = day(null)
        val cache = HealthCache().merge(listOf(original), now)
        assertEquals(cache, CacheJson.decode(CacheJson.encode(cache)))
        val payload = org.json.JSONObject(CacheJson.payload(original))
        assertTrue(payload.isNull("steps"))
        assertEquals(0, payload.getJSONArray("sleepSessions").length())
        assertEquals("health_connect", payload.getString("source"))
        assertFalse(payload.has("pending"))
    }

    @Test fun `sleep offsets sources and duration semantics survive cache`() {
        val cache = HealthCache().merge(listOf(day(sessions = listOf(sleep()))), now)
        assertEquals(cache, CacheJson.decode(CacheJson.encode(cache)))
    }

    @Test fun `temporary HTTP errors are retried but auth and missing endpoint are not`() {
        listOf(408, 429, 500, 502, 503).forEach { assertTrue(retryableStatus(it)) }
        listOf(200, 301, 400, 401, 403, 404).forEach { assertFalse(retryableStatus(it)) }
    }

    @Test fun `only HTTPS origins without embedded credentials are accepted`() {
        assertEquals("https://stridehub.example.com", validateBackendUrl(" https://stridehub.example.com/ "))
        assertEquals("https://192.168.1.10:8443", validateBackendUrl("https://192.168.1.10:8443"))
        assertEquals("", validateBackendUrl(""))
        listOf("http://192.168.1.10:3000", "https://user:secret@example.com", "https://example.com?token=secret",
            "https://example.com/api", "https://example.com/#fragment", "file:///tmp", "https://example.com:99999").forEach {
            assertThrows(IllegalArgumentException::class.java) { validateBackendUrl(it) }
        }
    }
}
