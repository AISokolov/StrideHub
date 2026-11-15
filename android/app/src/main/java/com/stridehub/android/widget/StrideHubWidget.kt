package com.stridehub.android.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.stridehub.android.MainActivity
import com.stridehub.android.appGraph
import com.stridehub.android.data.durationLabel
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

class StrideHubWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 110.dp), DpSize(240.dp, 110.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val cache = try { context.appGraph.local.snapshot() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
        val zone = ZoneId.systemDefault()
        val today = cache?.days?.get(LocalDate.now().toString())?.takeIf { it.zoneId == zone.id }
        val sleep = cache?.days?.values?.flatMap { it.sleepSessions }?.filter { it.endInstant <= Instant.now() }?.maxByOrNull { it.endInstant }
        val white = ColorProvider(Color(0xFFF3F4F6))
        val muted = ColorProvider(Color(0xFFB0B7CA))
        provideContent {
            val wide = LocalSize.current.width >= 240.dp
            Column(GlanceModifier.fillMaxSize().background(Color(0xFF172033)).padding(if (wide) 16.dp else 10.dp)
                .clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                Text("StrideHub", style = TextStyle(color = ColorProvider(Color(0xFF60A5FA)), fontSize = 16.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(6.dp))
                Text("Steps: ${today?.steps?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"}",
                    style = TextStyle(color = white, fontSize = if (wide) 22.sp else 16.sp))
                Text("${if (sleep?.durationKind == "session") "Session" else "Sleep"}: ${sleep?.let { durationLabel(it.durationMinutes) } ?: "—"}",
                    style = TextStyle(color = white, fontSize = 16.sp))
                if (wide && sleep != null) Text("Woke ${sleep.endInstant.atZone(zone).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))}",
                    style = TextStyle(color = muted, fontSize = 12.sp))
                Text("Updated: ${today?.refreshedAt?.let { Instant.parse(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "—"}",
                    style = TextStyle(color = muted, fontSize = 12.sp))
                if (wide) Text("Tap to open & refresh", style = TextStyle(color = muted, fontSize = 12.sp))
            }
        }
    }
}
