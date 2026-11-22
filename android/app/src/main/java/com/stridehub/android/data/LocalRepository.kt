package com.stridehub.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.healthStore by preferencesDataStore(name = "health_cache")

interface HealthCacheStore {
    suspend fun snapshot(): HealthCache
    suspend fun update(transform: (HealthCache) -> HealthCache)
}

class LocalRepository(private val context: Context) : HealthCacheStore {
    private val key = stringPreferencesKey("cache_v1")
    val cache = context.healthStore.data.map { prefs -> prefs[key]?.let(CacheJson::decode) ?: HealthCache() }
    override suspend fun snapshot() = cache.first()
    override suspend fun update(transform: (HealthCache) -> HealthCache) {
        context.healthStore.edit { prefs ->
            val old = prefs[key]?.let(CacheJson::decode) ?: HealthCache()
            prefs[key] = CacheJson.encode(transform(old))
        }
    }
}
