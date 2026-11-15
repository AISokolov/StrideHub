package com.stridehub.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val colors = darkColorScheme(
    primary = Color(0xFF60A5FA), onPrimary = Color(0xFF082348),
    secondary = Color(0xFF86EFAC), background = Color(0xFF0B1120),
    surface = Color(0xFF172033), surfaceVariant = Color(0xFF202D43),
    onBackground = Color(0xFFF8FAFC), onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8), outline = Color(0xFF293449),
)

@Composable
fun StrideHubTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)
