package com.stridehub.android.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.stridehub.android.MainActivity
import com.stridehub.android.appGraph
import com.stridehub.android.data.GarminActivity
import com.stridehub.android.data.GarminCache
import com.stridehub.android.data.durationLabel
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

private val white = ColorProvider(Color(0xFFF3F4F6))
private val muted = ColorProvider(Color(0xFFB0B7CA))
private val accent = ColorProvider(Color(0xFF60A5FA))

class StrideHubWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(
        DpSize(110.dp, 110.dp), DpSize(240.dp, 110.dp), DpSize(180.dp, 180.dp),
        DpSize(240.dp, 220.dp), DpSize(240.dp, 320.dp), DpSize(320.dp, 440.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cache = try { context.appGraph.local.snapshot() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val zone = ZoneId.systemDefault()
        val today = cache?.days?.get(LocalDate.now(zone).toString())?.takeIf { it.zoneId == zone.id }
        val sleep = cache?.days?.values?.flatMap { it.sleepSessions }?.filter { it.endInstant <= Instant.now() }?.maxByOrNull { it.endInstant }
        val garmin = cache?.garmin ?: GarminCache()
        // Never substitute a historical day, including while offline or before first fetch.
        val workouts = garmin.displayDay(garmin.today().toString())?.activities.orEmpty()
        val steps = today?.steps?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"
        val sleepLabel = sleep?.let { durationLabel(it.durationMinutes) } ?: "—"
        val updated = today?.refreshedAt?.let { Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—"
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        provideContent {
            val size = LocalSize.current
            val detailed = size.width >= 180.dp && size.height >= 180.dp
            val limit = if (size.height >= 440.dp) 5 else if (size.height >= 320.dp) 3 else 1
            // Reserve health/footer space and two metric lines per workout, including large text.
            val capacity = ((size.height.value - 32f - 98f * fontScale) / (44f * fontScale)).toInt().coerceIn(0, limit)
            val wideShort = size.width >= 240.dp && !detailed && workouts.isNotEmpty()
            val modifier = GlanceModifier.fillMaxSize().background(Color(0xFF172033)).cornerRadius(16.dp)
                .padding(if (detailed) 12.dp else 8.dp).clickable(actionStartActivity<MainActivity>())
            if (wideShort) {
                Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                    Column(GlanceModifier.width(94.dp)) {
                        HealthMetrics(steps, sleepLabel, false)
                        Updated(updated)
                    }
                    Column(GlanceModifier.defaultWeight().padding(start = 8.dp)) {
                        Workouts(workouts, 1, compact = true)
                    }
                }
            } else {
                // Centre the compact block when the launcher retains a large empty widget.
                Column(modifier, verticalAlignment = Alignment.CenterVertically) {
                    HealthMetrics(steps, sleepLabel, detailed)
                    if (workouts.isNotEmpty()) {
                        if (detailed && capacity > 0) {
                            Spacer(GlanceModifier.height(6.dp))
                            Workouts(workouts, capacity)
                            Spacer(GlanceModifier.height(4.dp))
                        } else {
                            Text("${workouts.size} workout(s) · tap", style = TextStyle(color = accent, fontSize = 10.sp), maxLines = 1)
                        }
                    }
                    Updated(updated)
                }
            }
        }
    }
}

@Composable
private fun HealthMetrics(steps: String, sleep: String, detailed: Boolean) {
    Text("StrideHub", style = TextStyle(color = accent, fontSize = if (detailed) 20.sp else 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    // Android's system emoji font supplies both icons; no downloaded assets.
    Text("\uD83D\uDC3E $steps", style = TextStyle(color = white, fontSize = if (detailed) 19.sp else 15.sp), maxLines = 1)
    Text("\uD83D\uDCA4 $sleep", style = TextStyle(color = white, fontSize = if (detailed) 19.sp else 15.sp), maxLines = 1)
}

@Composable
private fun Workouts(workouts: List<GarminActivity>, capacity: Int, compact: Boolean = false) {
    workouts.take(capacity).forEach { activity ->
        Text(activity.title(), style = TextStyle(color = white, fontSize = if (compact) 11.sp else 13.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Text(activity.detail(), style = TextStyle(color = muted, fontSize = if (compact) 10.sp else 11.sp), maxLines = 2)
    }
    if (workouts.size > capacity) Text("+${workouts.size - capacity} more · tap", style = TextStyle(color = accent, fontSize = 10.sp), maxLines = 1)
}

@Composable
private fun Updated(time: String) {
    Text("Updated: $time", style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 1)
}
