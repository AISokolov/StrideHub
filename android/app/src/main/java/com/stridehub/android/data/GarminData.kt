package com.stridehub.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

data class GarminActivity(
    val id: String, val name: String, val type: String,
    val distanceMeters: Double?, val durationSeconds: Double?,
    val averagePaceSecondsPerKm: Double?, val averageHeartRate: Double?,
) {
    val running get() = type == "running" || type.endsWith("_running")
    fun title(): String {
        val label = type.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
        return if (name.isBlank() || name.equals(type, ignoreCase = true)) label else "$label — $name"
    }
    fun detail(): String {
        val distance = distanceMeters?.let { String.format(Locale.getDefault(), "%.2f km", it / 1000) }
        val duration = durationSeconds?.let { seconds ->
            val total = seconds.roundToLong()
            if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
            else "%d:%02d".format(total / 60, total % 60)
        }
        val pace = averagePaceSecondsPerKm?.takeIf { running && it > 0 }?.roundToLong()
            ?.let { "%d:%02d /km".format(it / 60, it % 60) }
        return listOfNotNull(distance, duration, pace).joinToString(" · ").ifEmpty { "Metrics unavailable" }
    }
}

data class GarminDay(val date: String, val activities: List<GarminActivity>, val refreshedAt: String, val timeZone: String? = null)
enum class GarminFailure { NETWORK, AUTHENTICATION, BACKEND, INVALID_RESPONSE, CONFIGURATION, TLS }
data class GarminCache(
    val days: Map<String, GarminDay> = emptyMap(), val message: String? = null,
    val failed: Boolean = false, val configId: String? = null, val authBlocked: Boolean = false,
    val failure: GarminFailure? = null, val attemptedDate: String? = null,
) {
    val calendarZone: ZoneId get() = days.values.maxByOrNull { it.refreshedAt }?.timeZone?.let(ZoneId::of) ?: ZoneId.systemDefault()
    fun today(instant: Instant = Instant.now()): LocalDate = instant.atZone(calendarZone).toLocalDate()
    fun displayDay(date: String): GarminDay? = days[date]?.takeIf { it.timeZone == null || it.timeZone == calendarZone.id }
    fun failedFor(date: String) = failed && (attemptedDate == null || attemptedDate == date)
    fun status(date: String): String {
        val day = displayDay(date) ?: return if (failedFor(date)) "Garmin unavailable · no cached workouts" else "Garmin workouts not loaded yet"
        if (failedFor(date)) return "Garmin unavailable · cached result for $date"
        return if (day.activities.isEmpty()) "No Garmin workouts · $date" else "Garmin workouts · $date"
    }
}

object GarminJson {
    private fun JSONObject.metric(key: String): Double? {
        if (isNull(key)) return null
        val value = getDouble(key)
        require(value.isFinite() && value >= 0) { "Invalid Garmin metric" }
        return value
    }
    fun parse(text: String, expectedDate: String?, refreshedAt: String): GarminDay {
        val root = JSONObject(text)
        val date = root.getString("date")
        require((expectedDate == null || date == expectedDate) && LocalDate.parse(date).toString() == date) { "Wrong Garmin date" }
        val list = root.getJSONArray("activities")
        val activities = (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            val id = item.get("id").toString()
            require(id.isNotBlank() && id != "null") { "Missing Garmin ID" }
            GarminActivity(id, item.getString("name"), item.getString("type"), item.metric("distanceMeters"),
                item.metric("durationSeconds"), item.metric("averagePaceSecondsPerKm"), item.metric("averageHeartRate"))
        }.distinctBy { it.id }
        val timeZone = root.optString("timeZone").takeIf { it.isNotBlank() && it != "null" }
        timeZone?.let(ZoneId::of)
        return GarminDay(date, activities, refreshedAt, timeZone)
    }
    private fun dayJson(day: GarminDay) = JSONObject().put("date", day.date).put("refreshedAt", day.refreshedAt)
        .put("timeZone", day.timeZone).put("activities", JSONArray(day.activities.map { activity ->
            JSONObject().put("id", activity.id).put("name", activity.name).put("type", activity.type)
                .put("distanceMeters", activity.distanceMeters ?: JSONObject.NULL)
                .put("durationSeconds", activity.durationSeconds ?: JSONObject.NULL)
                .put("averagePaceSecondsPerKm", activity.averagePaceSecondsPerKm ?: JSONObject.NULL)
                .put("averageHeartRate", activity.averageHeartRate ?: JSONObject.NULL)
        }))
    fun encode(cache: GarminCache) = JSONObject().put("days", JSONArray(cache.days.values.map(::dayJson)))
        .put("message", cache.message).put("failed", cache.failed).put("configId", cache.configId).put("authBlocked", cache.authBlocked)
        .put("failure", cache.failure?.name).put("attemptedDate", cache.attemptedDate)
    fun decode(root: JSONObject?): GarminCache {
        if (root == null) return GarminCache()
        val list = root.optJSONArray("days") ?: JSONArray()
        val days = (0 until list.length()).map { i ->
            val day = list.getJSONObject(i)
            val timestamp = day.getString("refreshedAt")
            Instant.parse(timestamp)
            parse(day.toString(), day.getString("date"), timestamp)
        }.associateBy { it.date }
        return GarminCache(days, root.optString("message").takeIf { it.isNotBlank() }, root.optBoolean("failed"),
            root.optString("configId").takeIf { it.isNotBlank() }, root.optBoolean("authBlocked"),
            root.optString("failure").takeIf { it.isNotBlank() }?.let(GarminFailure::valueOf),
            root.optString("attemptedDate").takeIf { it.isNotBlank() })
    }
}
