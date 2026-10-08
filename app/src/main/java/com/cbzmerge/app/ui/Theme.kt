package com.cbzmerge.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3348D6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7FF), onPrimaryContainer = Color(0xFF14206B),
    background = Color(0xFFF5F6FA), onBackground = Color(0xFF15171C),
    surface = Color(0xFFF5F6FA), onSurface = Color(0xFF15171C),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White,
    surfaceVariant = Color(0xFFECEEF4), onSurfaceVariant = Color(0xFF5E6472),
    outlineVariant = Color(0xFFE0E3EB), error = Color(0xFFB3261E)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8EA2FF), onPrimary = Color(0xFF0B1033),
    primaryContainer = Color(0xFF26304F), onPrimaryContainer = Color(0xFFDDE2FF),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF0F1115), onSurface = Color(0xFFE6E8EE),
    surfaceContainer = Color(0xFF181B22), surfaceContainerHigh = Color(0xFF1F232C),
    surfaceVariant = Color(0xFF1F232C), onSurfaceVariant = Color(0xFF9BA1AF),
    outlineVariant = Color(0xFF2B303B), error = Color(0xFFFF8A80)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

val CardShape = RoundedCornerShape(16.dp)
val ButtonShape = RoundedCornerShape(12.dp)

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes > 0 -> "${maxOf(1, bytes / 1000)} KB"
    else -> ""
}

fun formatCount(n: Int): String = "%,d".format(n)
