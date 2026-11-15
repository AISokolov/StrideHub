package com.stridehub.android.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class SleepSession(
    val id: String,
    val startTime: String,
    val endTime: String,
    val durationMinutes: Long,
    val sessionDurationMinutes: Long,
    val durationKind: String,
    val source: String,
) {
    val endInstant: Instant get() = java.time.OffsetDateTime.parse(endTime).toInstant()
}

data class DailyHealth(
    val date: String,
    val zoneId: String,
    val steps: Long?,
    val sleepSessions: List<SleepSession>,
    val refreshedAt: String,
    val revision: String = UUID.randomUUID().toString(),
    val pending: Boolean = true,
) {
    fun sameMeasurements(other: DailyHealth) = date == other.date && zoneId == other.zoneId &&
        steps == other.steps && sleepSessions == other.sleepSessions
}

data class HealthCache(
    val days: Map<String, DailyHealth> = emptyMap(),
    val lastRefresh: String? = null,
    val lastSync: String? = null,
    val syncMessage: String? = null,
    val backgroundMessage: String? = null,
) {
    fun merge(readings: List<DailyHealth>, now: String): HealthCache {
        val updated = days.toMutableMap()
        val assignedDates = readings.flatMap { d -> d.sleepSessions.map { it.id to d.date } }.toMap()
        // A corrected end time or changed phone timezone may move a session to another day.
        updated.keys.toList().forEach { date ->
            val day = updated.getValue(date)
            val remaining = day.sleepSessions.filter { assignedDates[it.id]?.let { assigned -> assigned == date } != false }
            if (remaining != day.sleepSessions) updated[date] = day.copy(sleepSessions = remaining,
                revision = UUID.randomUUID().toString(), pending = true)
        }
        readings.forEach { new ->
            val old = updated[new.date]
            updated[new.date] = if (old != null && old.sameMeasurements(new))
                new.copy(revision = old.revision, pending = old.pending) else new
        }
        return copy(days = updated, lastRefresh = now)
    }

    // An acknowledgement for an older upload must never clear a newer local edit.
    fun acknowledge(sent: DailyHealth, now: String): HealthCache {
        val current = days[sent.date] ?: return this
        val updated = if (current.revision == sent.revision) days +
            (sent.date to current.copy(pending = false)) else days
        return copy(days = updated, lastSync = now)
    }
}

fun dayBounds(date: LocalDate, zone: ZoneId): Pair<Instant, Instant> =
    date.atStartOfDay(zone).toInstant() to date.plusDays(1).atStartOfDay(zone).toInstant()

fun wakeDate(end: Instant, zone: ZoneId): LocalDate = end.atZone(zone).toLocalDate()

/** Union clipped stage intervals so malformed overlaps cannot inflate sleep time. */
fun intervalMinutes(start: Instant, end: Instant, intervals: List<Pair<Instant, Instant>>): Long {
    val clipped = intervals.map { maxOf(start, it.first) to minOf(end, it.second) }
        .filter { it.first < it.second }.sortedBy { it.first }
    var totalSeconds = 0L
    var current: Pair<Instant, Instant>? = null
    for (interval in clipped) {
        val previous = current
        if (previous == null) current = interval
        else if (interval.first <= previous.second) current = previous.first to maxOf(previous.second, interval.second)
        else {
            totalSeconds += Duration.between(previous.first, previous.second).seconds
            current = interval
        }
    }
    current?.let { totalSeconds += Duration.between(it.first, it.second).seconds }
    return totalSeconds / 60
}

fun durationLabel(minutes: Long) = "${minutes / 60}h ${minutes % 60}m"
