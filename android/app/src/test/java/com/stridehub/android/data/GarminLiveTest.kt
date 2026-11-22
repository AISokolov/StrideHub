package com.stridehub.android.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Opt in with STRIDEHUB_LIVE_URL/TOKEN; reads Garmin only, never uploads health. */
class GarminLiveTest {
    @get:Rule val folder = TemporaryFolder()

    private class DiskCache(private val file: File) : HealthCacheStore {
        override suspend fun snapshot() = if (file.length() == 0L) HealthCache() else CacheJson.decode(file.readText())
        override suspend fun update(transform: (HealthCache) -> HealthCache) {
            file.writeText(CacheJson.encode(transform(snapshot())))
        }
    }

    @Test fun `live November 18 activity downloads and survives disk cache reload for app display`() = runBlocking {
        val base = System.getenv("STRIDEHUB_LIVE_URL").orEmpty()
        val token = System.getenv("STRIDEHUB_LIVE_TOKEN").orEmpty()
        assumeTrue("Live backend credentials were not supplied", base.isNotBlank() && token.isNotBlank())
        val settings = object : BackendSettingsStore {
            override suspend fun read() = BackendSettings(base, token)
            override suspend fun save(settings: BackendSettings) = Unit
        }
        val file = folder.newFile("cache.json")
        val result = GarminRepository(DiskCache(file), settings).refresh("2026-11-18")
        assertEquals(UploadResult.COMPLETE, result)
        val restored = DiskCache(file).snapshot().garmin
        val workout = restored.displayDay("2026-11-18")!!.activities.first { it.name.startsWith("Koper") }
        assertEquals("running", workout.type)
        assertEquals(8300.0, workout.distanceMeters!!, 10.0)
        assertEquals(2352.0, workout.durationSeconds!!, 1.0)
        assertEquals(283.0, workout.averagePaceSecondsPerKm!!, 1.0)
        assertTrue(workout.title().contains("Koper"))
        assertTrue(workout.detail().contains("39:12"))
        assertTrue(workout.detail().contains("4:43 /km"))
        assertNull(restored.displayDay("2026-11-19"))
    }
}
