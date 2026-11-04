package com.example.myapplication

import androidx.compose.ui.graphics.Color
import com.example.myapplication.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSemanticsTest {
    @Test fun wallpaperAccentCannotRecolourSensorZOrPositiveStatus() {
        for (scheme in listOf(LightDashboardColorScheme, DarkDashboardColorScheme)) {
            val personalized = scheme.copy(primary = Color(0xFF339944), secondary = Color(0xFFFF00FF))
            assertEquals(scheme.axisZ, personalized.axisZ)
            assertEquals(scheme.positive, personalized.positive)
            assertEquals(scheme.positiveContainer, personalized.positiveContainer)
        }
    }
}
