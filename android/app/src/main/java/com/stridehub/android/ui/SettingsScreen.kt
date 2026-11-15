package com.stridehub.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.stridehub.android.AppGraph
import com.stridehub.android.data.BackendSettings
import com.stridehub.android.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf(BackendSettings()) }
    var url by remember { mutableStateOf("") }
    // Deliberately not rememberSaveable: never put a bearer token in saved instance state.
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { original = graph.settings.read(); url = original.baseUrl }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message = "Saved credentials could not be opened. Enter them again to replace them." }
        finally { busy = false }
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Connect your StrideHub server", style = MaterialTheme.typography.headlineSmall)
            Text("Optional. Steps, sleep, and the home-screen widget work without a server.")
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Backend base URL") },
                placeholder = { Text("https://stridehub.example.com") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Access token") },
                placeholder = { Text(if (original.token.isEmpty()) "Server-issued bearer token" else "Saved · leave blank to keep") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !busy, modifier = Modifier.fillMaxWidth())
            Text("Saving enables automatic upload of cached steps and sleep to this server. Credentials are encrypted with Android Keystore. Only HTTPS is accepted.", style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it) }
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        require(url.isBlank() || url.trim().trimEnd('/') == original.baseUrl || token.isNotBlank()) {
                            "Enter a token for the new server. Saved credentials are not reused across servers."
                        }
                        graph.sync.saveSettings(url, token.ifBlank { original.token })
                        original = graph.settings.read()
                        url = original.baseUrl
                        token = ""
                        message = if (url.isBlank()) "Server sync is off. Local data is kept." else "Saved. Synchronization is queued."
                        SyncScheduler.upload(context)
                    } catch (e: CancellationException) { throw e
                    } catch (e: IllegalArgumentException) { message = e.message
                    } catch (_: Exception) { message = "Settings could not be saved. Please try again." }
                    finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Save Settings") }
            Text("To disconnect, clear the URL and save. The token is removed. Pending data stays on this phone.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Text(PRIVACY_TEXT, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

const val PRIVACY_TEXT = "StrideHub reads only step totals and sleep sessions from Health Connect. It does not write health records. Data is cached in this app’s private storage and displayed in the app and widget. Anyone who can see your home screen may see widget values. When you configure a server and token, cached steps, sleep times, sleep sources, and durations are sent to that server over HTTPS. No advertising or analytics services receive your data. Background reading is optional and requires a separate permission where supported. Revoke access in Health Connect at any time; existing cached data remains visible. Clear this app’s storage in Android Settings to remove cached data and credentials. Android backup and device transfer are disabled for this app."
