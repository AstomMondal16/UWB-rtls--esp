package com.example.myapplication

import org.json.JSONObject
import kotlin.math.roundToInt

internal object SamplingRate {
    const val MIN_HZ = 1
    const val MAX_HZ = 20
    const val ERROR = "Enter a whole number from 1 to 20 samples/s"

    fun intervalMs(input: String): Int? {
        val hz = input.trim().toIntOrNull() ?: return null
        if (hz !in MIN_HZ..MAX_HZ) return null
        return (1000 + hz - 1) / hz
    }

    fun controlFields(input: String): JSONObject? = intervalMs(input)?.let { period ->
        JSONObject().put("sensing_interval_ms", period).put("send_interval_ms", period)
    }

    fun textFromInterval(periodMs: Int): String = if (periodMs <= 0) "" else
        (1000.0 / periodMs).roundToInt().toString()

    fun pollIntervalMs(sample: JSONObject?): Long {
        if (sample == null) return 350L
        return maxOf(sample.optLong("sensing_interval_ms", 350L),
            sample.optLong("send_interval_ms", 350L)).coerceIn(50L, 60_000L)
    }

    fun onlineTimeoutMs(sample: JSONObject): Long = maxOf(2500L, 3 * pollIntervalMs(sample))

    fun uiPublishDelayMs(nowMs: Long, lastPublishedMs: Long): Long =
        (100L - (nowMs - lastPublishedMs).coerceAtLeast(0L)).coerceAtLeast(0L)
}
