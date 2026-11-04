package com.example.myapplication

import com.example.myapplication.ui.theme.LightDashboardColorScheme
import com.example.myapplication.ui.theme.DarkDashboardColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class BatteryColorsTest {
    @Test fun anchorsMatchEmptyHalfAndFullInBothThemes() {
        assertEquals(Color(0xFFB62E2E), batteryLevelColor(0.0, false))
        assertEquals(Color(0xFFA65308), batteryLevelColor(50.0, false))
        assertEquals(Color(0xFF28733F), batteryLevelColor(100.0, false))
        assertEquals(Color(0xFFFFABA2), batteryLevelColor(0.0, true))
        assertEquals(Color(0xFFF5BA78), batteryLevelColor(50.0, true))
        assertEquals(Color(0xFF9BDDAD), batteryLevelColor(100.0, true))
    }

    @Test fun clampsOutOfRangeAndRejectsNonFiniteLevels() {
        for (dark in listOf(false, true)) {
            assertEquals(batteryLevelColor(0.0, dark), batteryLevelColor(-20.0, dark))
            assertEquals(batteryLevelColor(100.0, dark), batteryLevelColor(120.0, dark))
            assertEquals(batteryLevelColor(0.0, dark), batteryLevelColor(Double.NaN, dark))
        }
    }

    @Test fun blendsContinuouslyThroughTheHalfFullBoundary() {
        for (dark in listOf(false, true)) {
            val midpoint = batteryLevelColor(50.0, dark)
            for (level in listOf(49.99, 50.01)) {
                val color = batteryLevelColor(level, dark)
                assertEquals(midpoint.red, color.red, .002f)
                assertEquals(midpoint.green, color.green, .002f)
                assertEquals(midpoint.blue, color.blue, .002f)
            }
            assertNotEquals(batteryLevelColor(0.0, dark), batteryLevelColor(25.0, dark))
            assertNotEquals(batteryLevelColor(50.0, dark), batteryLevelColor(75.0, dark))
        }
    }

    @Test fun everyBatteryHueRemainsVisibleAgainstBothThemeSurfaces() {
        var minimum = Float.POSITIVE_INFINITY
        for (dark in listOf(false, true)) {
            val scheme = if (dark) DarkDashboardColorScheme else LightDashboardColorScheme
            val backgrounds = listOf(scheme.background, scheme.surface)
            for (step in 0..1000) for (background in backgrounds) {
                val a = batteryLevelColor(step/10.0, dark).luminance()
                val b = background.luminance()
                val contrast = (maxOf(a, b)+.05f)/(minOf(a, b)+.05f)
                minimum = minOf(minimum, contrast)
                assertTrue("Battery icon contrast at $step, dark=$dark: $contrast", contrast >= 3f)
            }
        }
        println("Battery hue minimum contrast: $minimum")
    }
}
