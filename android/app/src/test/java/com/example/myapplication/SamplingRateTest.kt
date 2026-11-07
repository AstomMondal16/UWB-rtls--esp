package com.example.myapplication

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SamplingRateTest {
    @Test fun liveViewDoesNotAddAnotherSamplePeriodAtTenSamplesPerSecond() {
        assertEquals(0L, SamplingRate.uiPublishDelayMs(1000, 0))
        assertEquals(0L, SamplingRate.uiPublishDelayMs(1100, 1000))
        assertEquals(50L, SamplingRate.uiPublishDelayMs(1050, 1000))
        assertEquals(100L, SamplingRate.uiPublishDelayMs(1000, 1000))
        assertEquals(0L, SamplingRate.uiPublishDelayMs(2000, 1000))
    }

    @Test fun boundsAreInclusiveAndOutsideValuesAreRejected() {
        assertEquals(1000, SamplingRate.intervalMs("1"))
        assertEquals(50, SamplingRate.intervalMs("20"))
        for (input in listOf("0", "-1", "0.999", "20.001", "1000", ""))
            assertNull(input, SamplingRate.controlFields(input))
    }

    @Test fun decimalMalformedAndNonFiniteInputNeverCreatesARequest() {
        for (input in listOf("12.5", "12,5", "5.0", "1e1", "abc", "1.2.3", "NaN", "Infinity", "-Infinity", "1e999"))
            assertNull(input, SamplingRate.controlFields(input))
    }

    @Test fun wholeRatesSetTheSameAcquisitionAndReportingPeriod() {
        for ((input, period) in listOf("5" to 200, "13" to 77, " 2 " to 500, "12" to 84)) {
            val fields = requireNotNull(SamplingRate.controlFields(input))
            assertEquals(2, fields.length())
            assertEquals(period, fields.getInt("sensing_interval_ms"))
            assertEquals(period, fields.getInt("send_interval_ms"))
        }
    }

    @Test fun everyAllowedRateProducesAValidConservativeInterval() {
        for (hz in 1..20) {
            val period = requireNotNull(SamplingRate.intervalMs(hz.toString()))
            assertTrue(period in 50..1000)
            assertTrue(1000.0 / period <= hz + 1e-9)
            assertEquals(hz.toString(), SamplingRate.textFromInterval(period))
        }
    }

    @Test fun currentDeviceIntervalsAreReadableAndLocaleIndependent() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("13", SamplingRate.textFromInterval(80))
            assertEquals("5", SamplingRate.textFromInterval(200))
            assertEquals("20", SamplingRate.textFromInterval(50))
            assertEquals("", SamplingRate.textFromInterval(0))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun pollingUsesBothIntervalsAndKeepsLegacyBounds() {
        assertEquals(350L, SamplingRate.pollIntervalMs(null))
        fun sample(sense: Long, send: Long) = JSONObject()
            .put("sensing_interval_ms", sense).put("send_interval_ms", send)
        assertEquals(80L, SamplingRate.pollIntervalMs(sample(80, 50)))
        assertEquals(200L, SamplingRate.pollIntervalMs(sample(100, 200)))
        assertEquals(50L, SamplingRate.pollIntervalMs(sample(0, -1)))
        assertEquals(60_000L, SamplingRate.pollIntervalMs(sample(70_000, 50)))
    }

    @Test fun slowConfiguredRatesAreNotMarkedOfflineBetweenExpectedPolls() {
        assertEquals(2500L, SamplingRate.onlineTimeoutMs(JSONObject().put("sensing_interval_ms", 50).put("send_interval_ms", 50)))
        assertEquals(3000L, SamplingRate.onlineTimeoutMs(JSONObject().put("sensing_interval_ms", 1000).put("send_interval_ms", 1000)))
        assertEquals(180_000L, SamplingRate.onlineTimeoutMs(JSONObject().put("sensing_interval_ms", 60_000).put("send_interval_ms", 60_000)))
    }
}
