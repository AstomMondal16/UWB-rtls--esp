package com.example.myapplication.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.os.Build

internal val LightDashboardColorScheme = lightColorScheme(
    primary = BrandPrimary, onPrimary = Color.White,
    primaryContainer = BrandContainer, onPrimaryContainer = BrandOnContainer,
    secondary = Color(0xFF535F78), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE2F5), onSecondaryContainer = Color(0xFF18233A),
    tertiary = Color(0xFF8B4C14), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC3), onTertiaryContainer = Color(0xFF542A00),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF7D1010),
    background = AppBackground, onBackground = AppText,
    surface = AppSurface, onSurface = AppText,
    surfaceVariant = Color(0xFFE9EDF3), onSurfaceVariant = AppMuted,
    outline = Color(0xFF727987), outlineVariant = Color(0xFFC3C9D6),
    surfaceContainer = Color(0xFFEDF0F5), surfaceContainerHigh = Color(0xFFE6EAF0),
    surfaceContainerHighest = Color(0xFFDEE4EC), surfaceContainerLow = Color(0xFFF1F4F8)
)
internal val DarkDashboardColorScheme = darkColorScheme(
    primary = Color(0xFFB2C5FF), onPrimary = Color(0xFF122D61),
    primaryContainer = Color(0xFF284575), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFBCC6DF), onSecondary = Color(0xFF263148),
    secondaryContainer = Color(0xFF3C475F), onSecondaryContainer = Color(0xFFDCE2F5),
    tertiary = Color(0xFFFFB77D), onTertiary = Color(0xFF4C2700),
    tertiaryContainer = Color(0xFF6D3B0B), onTertiaryContainer = Color(0xFFFFDCC3),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF11141C), onBackground = Color(0xFFE3E6F0),
    surface = Color(0xFF171B24), onSurface = Color(0xFFE3E6F0),
    surfaceVariant = Color(0xFF303644), onSurfaceVariant = Color(0xFFC4CAD8),
    outline = Color(0xFF8D95A5), outlineVariant = Color(0xFF434B5B),
    surfaceContainer = Color(0xFF20252F), surfaceContainerHigh = Color(0xFF2A2F3B),
    surfaceContainerHighest = Color(0xFF353B47), surfaceContainerLow = Color(0xFF1A1F28)
)

@Composable
fun MyApplicationTheme(darkTheme: Boolean = false, deviceColors: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (deviceColors && Build.VERSION.SDK_INT >= 31) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkDashboardColorScheme else LightDashboardColorScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
        content = content
    )
}
