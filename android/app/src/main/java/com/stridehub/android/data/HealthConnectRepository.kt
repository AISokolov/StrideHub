package com.stridehub.android.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthAccess(val availability: Int, val granted: Boolean = false,
    val backgroundSupported: Boolean = false, val backgroundGranted: Boolean = false) {
    val available get() = availability == HealthConnectClient.SDK_AVAILABLE
    val backgroundMessage get() = when {
        !available -> "Health Connect is unavailable. Cached data remains available."
        !granted -> "Grant steps and sleep permissions to refresh health data."
        !backgroundSupported -> "Background reads are unavailable here. Open StrideHub to refresh."
        !backgroundGranted -> "Background access is off. Data refreshes when you open StrideHub."
        else -> "Background refresh enabled; Android decides the execution time."
    }
}

class HealthConnectRepository(private val context: Context) {
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    val readPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class))
    val backgroundPermission = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    suspend fun access(): HealthAccess {
        val status = HealthConnectClient.getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) return HealthAccess(status)
        val granted = client.permissionController.getGrantedPermissions()
        val supported = client.features.getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return HealthAccess(status, granted.containsAll(readPermissions), supported,
            supported && backgroundPermission in granted)
    }

    suspend fun readDates(dates: Set<LocalDate>, background: Boolean): List<DailyHealth> {
        val access = access()
        check(access.available) { "Health Connect is not available." }
        if (!access.granted) throw SecurityException("Steps and sleep permissions are required.")
        if (background && !access.backgroundGranted) throw SecurityException("Open the app to refresh health data.")
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        // No artificial 24h/48h sleep cutoff. Read all accessible sessions before the
        // last selected day, then assign by END time. HC enforces its history window.
        val cutoff = dayBounds(dates.max(), zone).second
        val sessions = mutableMapOf<String, SleepSession>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.before(cutoff),
                pageSize = 500, pageToken = token))
            response.records.filter { it.endTime <= now && it.endTime < cutoff }.forEach { record ->
                val asleep = record.stages.filter { it.stage in sleepingStages }
                val hasKnownStages = record.stages.any { it.stage in knownStages }
                val interval = Duration.between(record.startTime, record.endTime).toMinutes()
                val duration = if (hasKnownStages) intervalMinutes(record.startTime, record.endTime,
                    asleep.map { it.startTime to it.endTime }) else interval
                val source = record.metadata.dataOrigin.packageName
                val id = record.metadata.id.ifEmpty { "$source:${record.startTime}:${record.endTime}" }
                sessions[id] = SleepSession(id,
                    record.startTime.atOffset(record.startZoneOffset ?: zone.rules.getOffset(record.startTime)).toString(),
                    record.endTime.atOffset(record.endZoneOffset ?: zone.rules.getOffset(record.endTime)).toString(),
                    duration, interval, if (hasKnownStages) "recorded_sleep" else "session", source)
            }
            token = response.pageToken
        } while (token != null)
        // Include the most recent available sleep even if OHealth has not written
        // anything in the three-day automatic reconciliation window.
        val latestWakeDate = sessions.values.maxByOrNull { it.endInstant }?.let { wakeDate(it.endInstant, zone) }
        val readDates = dates + listOfNotNull(latestWakeDate)
        return readDates.sorted().map { date ->
            val (start, end) = dayBounds(date, zone)
            val aggregate = client.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)))
            DailyHealth(date.toString(), zone.id, aggregate[StepsRecord.COUNT_TOTAL],
                sessions.values.filter { wakeDate(it.endInstant, zone) == date }.sortedBy { it.id }, now.toString())
        }
    }

    private val sleepingStages = setOf(SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM)
    private val knownStages = sleepingStages + setOf(SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
}
