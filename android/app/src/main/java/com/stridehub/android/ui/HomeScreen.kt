package com.stridehub.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stridehub.android.data.durationLabel
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter

fun timestampLabel(value: String?): String = value?.let {
    Instant.parse(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
} ?: "Never"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeState, onRefresh: () -> Unit, onSync: () -> Unit,
    onSettings: () -> Unit, onSelectDate: (LocalDate) -> Unit, onPermissions: () -> Unit,
    onBackground: () -> Unit, onHealthSettings: () -> Unit) {
    var datePickerOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val day = state.cache.days[state.selected.toString()]?.takeIf { it.zoneId == zone.id }
    val sleeps = if (state.selected == today) state.cache.days.values.flatMap { it.sleepSessions }
        .filter { it.endInstant <= Instant.now() }.distinctBy { it.id }
        else day?.sleepSessions.orEmpty()
    val sleep = sleeps.maxByOrNull { it.endInstant }
    Scaffold(topBar = { TopAppBar(title = { Text("StrideHub", fontWeight = FontWeight.Bold) },
        actions = { TextButton(onClick = onSettings) { Text("Settings") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("YOUR DAILY RHYTHM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { datePickerOpen = true }, enabled = !state.busy) {
                Text(if (state.selected == today) "Today · ${today.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}"
                    else state.selected.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")))
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when {
                        state.access == null -> "Checking Health Connect…"
                        !state.access.available -> "Health Connect needs attention"
                        !state.access.granted -> "Health Connect permissions needed"
                        else -> "Health Connect connected"
                    }, fontWeight = FontWeight.SemiBold)
                    Text(state.access?.backgroundMessage ?: "Your health data stays available offline.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.access?.available == true && !state.access.granted)
                        Button(onClick = onPermissions, enabled = !state.busy) { Text("Grant Permissions") }
                    if (state.access?.let { it.granted && it.backgroundSupported && !it.backgroundGranted } == true)
                        OutlinedButton(onClick = onBackground, enabled = !state.busy) { Text("Allow background reads") }
                    TextButton(onClick = onHealthSettings) { Text(if (state.access?.available == true) "Manage Health Connect" else "Install / update Health Connect") }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            MetricCard(if (state.selected == today) "TODAY’S STEPS" else "DAILY STEPS",
                day?.steps?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—",
                if (day == null) "No cached data for this date. Refresh to read Health Connect."
                else if (day.steps == null) "No step records for this date. Check OHealth sharing."
                else "Midnight to midnight · ${zone.id}")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.selected == today) "LAST SLEEP" else "SLEEP · WAKE-UP DATE", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary)
                    Text(sleep?.let { durationLabel(it.durationMinutes) } ?: "—", style = MaterialTheme.typography.headlineLarge)
                    if (sleep == null) Text("No sleep sessions found. Sync your watch with OHealth and refresh.", style = MaterialTheme.typography.bodySmall)
                    else {
                        val formatter = DateTimeFormatter.ofPattern("d MMM, HH:mm xxx")
                        Text("${OffsetDateTime.parse(sleep.startTime).atZoneSameInstant(zone).format(formatter)}\n${OffsetDateTime.parse(sleep.endTime).atZoneSameInstant(zone).format(formatter)}")
                        Text(if (sleep.durationKind == "session") "Session duration · sleep stages unavailable"
                            else "Recorded sleep stages · session ${durationLabel(sleep.sessionDurationMinutes)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Source: ${sleep.source.ifBlank { "Not supplied" }}", style = MaterialTheme.typography.bodySmall)
                        if (sleeps.size > 1 && state.selected != today) Text("${sleeps.size} sessions on this date; latest shown. All are cached and synchronized.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = onRefresh, enabled = !state.busy && state.access?.granted == true, modifier = Modifier.fillMaxWidth()) { Text("Refresh Data") }
            OutlinedButton(onClick = onSync, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sync to Server") }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Local refresh: ${timestampLabel(state.cache.lastRefresh)}", style = MaterialTheme.typography.bodySmall)
                Text("Selected date refreshed: ${timestampLabel(day?.refreshedAt)}", style = MaterialTheme.typography.bodySmall)
                Text("Server sync: ${timestampLabel(state.cache.lastSync)}", style = MaterialTheme.typography.bodySmall)
                Text("${state.cache.days.values.count { it.pending }} days pending upload", style = MaterialTheme.typography.bodySmall)
                state.cache.syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                state.cache.backgroundMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Steps and sleep only. Cached values may be older than your latest watch sync.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (datePickerOpen) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = state.selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return date in today.minusDays(29)..today
                }
            })
        DatePickerDialog(onDismissRequest = { datePickerOpen = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onSelectDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; datePickerOpen = false }) { Text("Show date") }
        }, dismissButton = { TextButton(onClick = { datePickerOpen = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
}

@Composable
private fun MetricCard(title: String, value: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
