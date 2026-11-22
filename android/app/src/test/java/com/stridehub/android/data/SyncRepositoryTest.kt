package com.stridehub.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SyncRepositoryTest {
    private class MemoryCache(var value: HealthCache) : HealthCacheStore {
        override suspend fun snapshot() = value
        override suspend fun update(transform: (HealthCache) -> HealthCache) { value = transform(value) }
    }
    private class MemorySettings(var value: BackendSettings = BackendSettings("https://example.com", "test-only")) : BackendSettingsStore {
        override suspend fun read() = value
        override suspend fun save(settings: BackendSettings) { value = settings }
    }
    private fun day(date: String) = DailyHealth(date, "Europe/Ljubljana", 123, emptyList(), "2026-11-15T12:00:00Z")
    private fun cache() = MemoryCache(HealthCache().merge(listOf(day("2026-11-14"), day("2026-11-15")), "2026-11-15T12:00:00Z"))

    @Test fun `authentication stays blocked across runs until configuration changes`() = runBlocking {
        for (code in listOf(401, 403)) {
            val local = cache()
            val settings = MemorySettings()
            var calls = 0
            val post: suspend (BackendSettings, DailyHealth) -> UploadResponse = { _, _ -> calls++; UploadResponse(code) }
            val repo = SyncRepository(local, settings, { true }, post, {})
            assertEquals(UploadResult.NEEDS_ATTENTION, repo.upload())
            assertTrue(local.value.authBlocked)
            assertNull(local.value.lastSync)
            // Recreate the repository to model process restart; same credentials must stay blocked.
            val restarted = SyncRepository(local, settings, { true }, post, {})
            restarted.saveSettings(settings.value.baseUrl, settings.value.token)
            restarted.upload()
            assertEquals(1, calls)
            restarted.saveSettings(settings.value.baseUrl, "changed-token")
            restarted.upload()
            assertEquals(2, calls)
        }
    }

    @Test fun `invalid day does not block other days or endlessly retry unchanged data`() = runBlocking {
        val local = cache()
        val calls = mutableListOf<String>()
        val repo = SyncRepository(local, MemorySettings(), { true }, { _, day ->
            calls += day.date
            if (day.date == "2026-11-14") UploadResponse(400) else UploadResponse(200, true)
        }, {})
        assertEquals(UploadResult.NEEDS_ATTENTION, repo.upload())
        assertEquals(listOf("2026-11-14", "2026-11-15"), calls)
        assertNotNull(local.value.lastSync)
        assertTrue(local.value.days.getValue("2026-11-14").pending)
        assertFalse(local.value.days.getValue("2026-11-15").pending)
        repo.upload()
        assertEquals(2, calls.size)
        local.value = local.value.merge(listOf(day("2026-11-14").copy(steps = 456)), "2026-11-15T13:00:00Z")
        repo.upload()
        assertEquals(3, calls.size)
    }

    @Test fun `temporary failures preserve cache and other days still synchronize`() = runBlocking {
        for (status in listOf(0, 429, 503)) {
            val local = cache()
            var firstAttempt = true
            val repo = SyncRepository(local, MemorySettings(), { true }, { _, day ->
                if (firstAttempt && day.date == "2026-11-14") {
                    if (status == 0) throw IOException("offline")
                    UploadResponse(status)
                } else UploadResponse(200, true)
            }, {})
            assertEquals(UploadResult.RETRY, repo.upload())
            assertEquals(123L, local.value.days.getValue("2026-11-14").steps)
            assertTrue(local.value.days.getValue("2026-11-14").pending)
            assertFalse(local.value.days.getValue("2026-11-15").pending)
            firstAttempt = false
            assertEquals(UploadResult.COMPLETE, repo.upload())
            assertTrue(local.value.days.values.none { it.pending })
        }
    }

    @Test fun `missing permissions skip upload and preserve health data`() = runBlocking {
        val local = cache()
        val original = local.value.days
        val repo = SyncRepository(local, MemorySettings(), { false }, { _, _ -> error("Must not upload") }, {})
        assertEquals(UploadResult.SKIPPED, repo.upload())
        assertEquals(original, local.value.days)
        assertNull(local.value.lastSync)
        assertFalse(repo.syncing.value)
    }

    @Test fun `unchanged successful uploads are not resent and no unconfirmed success is recorded`() = runBlocking {
        val local = cache()
        var calls = 0
        val repo = SyncRepository(local, MemorySettings(), { true }, { _, _ -> calls++; UploadResponse(200, true) }, {})
        assertEquals(UploadResult.COMPLETE, repo.upload())
        repo.upload()
        assertEquals(2, calls)
        val unconfirmed = cache()
        SyncRepository(unconfirmed, MemorySettings(), { true }, { _, _ -> UploadResponse(200, false) }, {}).upload()
        assertNull(unconfirmed.value.lastSync)
        assertTrue(unconfirmed.value.days.values.all { it.pending })
    }
}
