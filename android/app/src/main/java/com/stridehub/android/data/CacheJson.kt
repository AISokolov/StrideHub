package com.stridehub.android.data

import org.json.JSONArray
import org.json.JSONObject

object CacheJson {
    fun sleepJson(s: SleepSession) = JSONObject().put("id", s.id)
        .put("startTime", s.startTime).put("endTime", s.endTime)
        .put("durationMinutes", s.durationMinutes).put("sessionDurationMinutes", s.sessionDurationMinutes)
        .put("durationKind", s.durationKind).put("source", s.source)

    fun dailyJson(day: DailyHealth) = JSONObject().put("date", day.date).put("zoneId", day.zoneId)
        .put("steps", day.steps ?: JSONObject.NULL)
        .put("sleepSessions", JSONArray(day.sleepSessions.map(::sleepJson)))
        .put("refreshedAt", day.refreshedAt).put("revision", day.revision).put("pending", day.pending)

    fun encode(cache: HealthCache): String = JSONObject().put("version", 1)
        .put("days", JSONArray(cache.days.values.map(::dailyJson)))
        .put("lastRefresh", cache.lastRefresh).put("lastSync", cache.lastSync)
        .put("syncMessage", cache.syncMessage).put("backgroundMessage", cache.backgroundMessage).toString()

    private fun JSONObject.optional(key: String) = if (isNull(key)) null else optString(key).ifEmpty { null }

    fun decode(text: String): HealthCache {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "Unsupported cache version" }
        val array = root.getJSONArray("days")
        val days = (0 until array.length()).map { index ->
            val d = array.getJSONObject(index)
            val sleeps = d.getJSONArray("sleepSessions")
            DailyHealth(d.getString("date"), d.getString("zoneId"),
                if (d.isNull("steps")) null else d.getLong("steps"),
                (0 until sleeps.length()).map { i ->
                    val s = sleeps.getJSONObject(i)
                    SleepSession(s.getString("id"), s.getString("startTime"), s.getString("endTime"),
                        s.getLong("durationMinutes"), s.getLong("sessionDurationMinutes"),
                        s.getString("durationKind"), s.getString("source"))
                }.distinctBy { it.id }, d.getString("refreshedAt"), d.getString("revision"), d.getBoolean("pending"))
        }.associateBy { it.date }
        return HealthCache(days, root.optional("lastRefresh"), root.optional("lastSync"),
            root.optional("syncMessage"), root.optional("backgroundMessage"))
    }

    fun payload(day: DailyHealth): String = dailyJson(day).apply {
        remove("pending")
        put("source", "health_connect")
    }.toString()
}
