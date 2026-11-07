package com.example.myapplication.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.ColorScheme

// Material colour roles keep interaction separate from sensor/status meaning.
val BrandPrimary = Color(0xFF2857A8)
val BrandOnContainer = Color(0xFF102D61)
val BrandContainer = Color(0xFFDCE5FF)
val AppBackground = Color(0xFFF5F7FA)
val AppSurface = Color(0xFFFFFFFF)
val AppText = Color(0xFF111827)
val AppMuted = Color(0xFF4B5565)
val PositiveLight = Color(0xFF286941)
val PositiveDark = Color(0xFF9EDAAF)
val PositiveContainerLight = Color(0xFFD5EEDB)
val PositiveContainerDark = Color(0xFF243E2D)
val OnPositiveContainerLight = Color(0xFF123D23)
val OnPositiveContainerDark = Color(0xFFB9F1C9)
val OnPositiveLight = Color(0xFFFFFFFF)
val OnPositiveDark = Color(0xFF10391F)
val AxisZLight = Color(0xFF2857A8)
val AxisZDark = Color(0xFFB2C5FF)

val ColorScheme.positive: Color get() = if (surface.luminance() < .5f) PositiveDark else PositiveLight
val ColorScheme.positiveContainer: Color get() = if (surface.luminance() < .5f) PositiveContainerDark else PositiveContainerLight
val ColorScheme.onPositiveContainer: Color get() = if (surface.luminance() < .5f) OnPositiveContainerDark else OnPositiveContainerLight
val ColorScheme.onPositive: Color get() = if (surface.luminance() < .5f) OnPositiveDark else OnPositiveLight
val ColorScheme.axisZ: Color get() = if (surface.luminance() < .5f) AxisZDark else AxisZLight
