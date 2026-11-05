package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Called under the repository ingestion lock; independent of UI selection and rendering. */
internal class TelemetryRecorder(private val store: CsvSessionStore) {
    private data class Cursor(val sequence: Long, val timestamp: Long)
    private val cursors = mutableMapOf<Pair<String, String>, Cursor>()
    private val batteries = mutableMapOf<String, String>()

    private var active = false
    fun begin() { cursors.clear(); batteries.clear(); active = true }
    fun stop() { active = false }

    fun accept(source: String, ip: String, sample: JSONObject, positions: Map<String, Position>): Boolean {
        if (!active) return false
        val updates = mutableListOf<String>()
        val next = mutableMapOf<Pair<String, String>, Cursor>()
        fun stream(name: String, valid: Boolean, seqKey: String, timeKey: String, payload: JSONObject = sample): Long {
            val cursor = Cursor(payload.optLong(seqKey, -1), payload.optLong(timeKey, -1))
            if (!valid || cursor.sequence < 0 || cursor.timestamp < 0) return 0
            val key = source to name
            val previous = cursors[key]
            if (cursor == previous) return 0
            updates += name
            next[key] = cursor
            return if (previous != null && cursor.timestamp > previous.timestamp && cursor.sequence > previous.sequence)
                (cursor.sequence - previous.sequence - 1).coerceAtLeast(0) else 0
        }
        val hasRange = sample.optDouble("distance_m", Double.NaN).let { it.isFinite() && it > 0 } ||
            sample.optJSONArray("ranges")?.let { ranges -> (0 until ranges.length()).any { i ->
                ranges.optJSONObject(i)?.optDouble("distance_m", Double.NaN)?.let { it.isFinite() && it > 0 } == true
            } } == true
        fun validVector(name: String): Boolean = sample.optJSONArray(name)?.let { values ->
            values.length() == 3 && (0..2).all { values.optDouble(it, Double.NaN).isFinite() }
        } == true
        val missedUwb = stream("uwb", sample.optBoolean("valid") && hasRange, "sequence", "timestamp_ms")
        val missedImu = stream("imu", sample.optBoolean("imu_valid") && validVector("accel") && validVector("gyro"), "imu_sequence", "imu_timestamp_ms")
        val cir = sample.optJSONObject("cir")
        if (cir != null) {
            val iq = cir.optJSONArray("iq")
            val valid = cir.optBoolean("valid") && iq != null && iq.length() > 0 && iq.length() == cir.optInt("sample_count") &&
                (0 until iq.length()).all { i -> iq.optJSONArray(i)?.let { pair ->
                    pair.length() == 2 && (0..1).all { axis -> pair.optDouble(axis, Double.NaN).let {
                        it.isFinite() && it >= -32768 && it <= 32767 && it == kotlin.math.floor(it)
                    } }
                } == true }
            stream("cir", valid, "sequence", "timestamp_ms", cir)
        }
        val battery = if (sample.optBoolean("battery_valid") && sample.optDouble("battery_voltage", Double.NaN).isFinite() && sample.optDouble("battery_percent", Double.NaN).isFinite())
            "${sample.opt("battery_voltage")}/${sample.opt("battery_percent")}" else null
        // Firmware has no battery sample identifier: only observable value changes can be distinguished.
        if (battery != null && battery != batteries[source]) updates += "battery"
        if (updates.isEmpty()) return false
        val row = JSONObject(sample.toString()).apply {
            put("source_address", source); put("source_ip", ip)
            put("received_at_utc", Instant.ofEpochMilli(sample.optLong("_received_at", System.currentTimeMillis())).toString())
            put("missed_sequence_count", missedUwb); put("missed_imu_sequence_count", missedImu)
            put("updated_streams", JSONArray(updates))
            put("positions", JSONArray().apply {
                positions.forEach { (tag, p) -> put(JSONObject().apply {
                    put("tag_id", tag); put("x_m", p.x); put("y_m", p.y); put("z_m", p.z)
                    put("rms_error_m", p.error); put("anchors", JSONArray(p.anchors))
                }) }
            })
        }
        store.append(row.toString())
        cursors.putAll(next)
        if (battery != null) batteries[source] = battery
        return true
    }
}
