package com.stridehub.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stridehub.android.ui.PRIVACY_TEXT
import com.stridehub.android.ui.theme.StrideHubTheme

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrideHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("StrideHub · Health privacy", style = MaterialTheme.typography.headlineMedium)
                        Text(PRIVACY_TEXT)
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}
