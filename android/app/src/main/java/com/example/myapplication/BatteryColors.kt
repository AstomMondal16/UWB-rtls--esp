package com.example.myapplication

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal fun batteryLevelColor(percent: Double, dark: Boolean): Color {
    val level = if (percent.isFinite()) percent.coerceIn(0.0, 100.0).toFloat() else 0f
    val red = if (dark) Color(0xFFFFABA2) else Color(0xFFB62E2E)
    val orange = if (dark) Color(0xFFF5BA78) else Color(0xFFA65308)
    val green = if (dark) Color(0xFF9BDDAD) else Color(0xFF28733F)
    return if (level <= 50f) lerp(red, orange, level/50f)
        else lerp(orange, green, (level-50f)/50f)
}
