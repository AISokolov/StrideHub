package com.stridehub.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stridehub.android.ui.*
import com.stridehub.android.ui.theme.StrideHubTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrideHubTheme {
                val model: HomeViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    PermissionController.createRequestPermissionResultContract()) { model.refresh() }
                LifecycleResumeEffect(Unit) { model.onOpen(); onPauseOrDispose { } }
                if (settingsOpen) SettingsScreen(appGraph, onBack = { settingsOpen = false })
                else HomeScreen(state, onRefresh = model::refresh, onSync = model::sync,
                    onSettings = { settingsOpen = true }, onSelectDate = model::select,
                    onPermissions = { permissionLauncher.launch(appGraph.health.readPermissions) },
                    onBackground = { permissionLauncher.launch(setOf(appGraph.health.backgroundPermission)) },
                    onHealthSettings = {
                        val intent = if (state.access?.available == true)
                            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                        else Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
                        try { startActivity(intent) } catch (_: Exception) {
                            Toast.makeText(this, "Open Health Connect from Android Settings.", Toast.LENGTH_LONG).show()
                        }
                    })
            }
        }
    }
}
