package com.example.myapplication.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Use the platform sans for familiar Android controls and tabular figures for live data.
private fun instrumentType(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
    lineHeight = line.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum"
)
val Typography = Typography(
    displaySmall = instrumentType(36, 44, FontWeight.Medium),
    headlineLarge = instrumentType(32, 40, FontWeight.Medium),
    headlineMedium = instrumentType(28, 36, FontWeight.Medium),
    headlineSmall = instrumentType(24, 32, FontWeight.Medium),
    titleLarge = instrumentType(22, 28, FontWeight.Medium),
    titleMedium = instrumentType(18, 24, FontWeight.Medium),
    titleSmall = instrumentType(16, 24, FontWeight.Medium),
    bodyLarge = instrumentType(16, 24), bodyMedium = instrumentType(14, 20),
    bodySmall = instrumentType(12, 16),
    labelLarge = instrumentType(14, 20, FontWeight.Medium),
    labelMedium = instrumentType(14, 20, FontWeight.Medium),
    labelSmall = instrumentType(12, 16, FontWeight.Medium)
)
