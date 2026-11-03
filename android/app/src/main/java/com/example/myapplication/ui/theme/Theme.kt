package com.example.myapplication.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DashboardColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = AppSurface,
    primaryContainer = TealSoft,
    onPrimaryContainer = AppText,
    secondary = TealDark,
    onSecondary = AppSurface,
    tertiary = TealDark,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = TealSoft,
    onSurfaceVariant = AppMuted
)

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DashboardColorScheme,
        typography = Typography,
        content = content
    )
}
