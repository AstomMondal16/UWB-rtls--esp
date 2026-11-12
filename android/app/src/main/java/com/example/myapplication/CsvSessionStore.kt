package com.example.myapplication

import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal data class CsvExportSummary(
    val records: Int,
    val firstReceivedAt: String?,
    val lastReceivedAt: String?,
    val missedSamples: Long,
    val missedImuSamples: Long = 0
)

/** App-private append-only session storage; CSV exports stream directly to the destination. */
internal class CsvSessionStore(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        val UWB_FIELDS = linkedMapOf(
            "distance_m_unfiltered" to "distance_m_unfiltered",
            "poll_tx" to "timestamps.poll_tx",
            "poll_rx" to "timestamps.poll_rx",
            "ack_tx" to "timestamps.ack_tx",
            "ack_rx" to "timestamps.ack_rx",
            "range_tx" to "timestamps.range_tx",
            "range_rx" to "timestamps.range_rx",
            "report_tx" to "timestamps.report_tx",
            "report_rx" to "timestamps.report_rx",
            "local_rx_power_dbm" to "local_rx_power_dbm",
            "peer_rx_power_dbm" to "peer_rx_power_dbm",
            "cir_power" to "rx_diagnostics.cir_power",
            "rx_frame_info" to "rx_diagnostics.rx_frame_info",
            "preamble_count" to "rx_diagnostics.preamble_count",
            "tof_ticks" to "tof_ticks"
        )
        val CIR_FIELDS = linkedMapOf(
            "iq" to "iq",
            "sequence" to "sequence",
            "timestamp_ms" to "timestamp_ms",
            "rx_timestamp" to "rx_timestamp",
            "peer_address" to "peer_address",
            "frame_type" to "frame_type",
            "first_path_index" to "first_path_index",
            "start_index" to "start_index",
            "sample_count" to "sample_count",
            "cir_preamble_count" to "preamble_count",
            "age_ms" to "age_ms",
            "capture_us" to "capture_us",
            "capture_interval_ms" to "capture_interval_ms",
            "sample_period_ns" to "sample_period_ns"
        )
        val REQUIRED_FIELDS = listOf("timestamp", "source_address", "ip_address", "role")
        private val IMU_FIELDS = listOf("accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z")
        val OPTIONAL_FIELD_GROUPS = linkedMapOf(
            "IMU" to IMU_FIELDS,
            "Battery" to listOf("battery", "battery_voltage"),
            "UWB" to (listOf("range") + UWB_FIELDS.keys + "tx_power" + CIR_FIELDS.keys + "speed_mps")
        )
        // Keep the existing CSV column order regardless of the settings display order.
        val SENSOR_FIELDS = REQUIRED_FIELDS + listOf("range") + UWB_FIELDS.keys + "tx_power" +
            CIR_FIELDS.keys + IMU_FIELDS + listOf("battery", "battery_voltage", "speed_mps")

        fun orderedExportFields(fields: Collection<String>): List<String> {
            if (fields.any { it !in SENSOR_FIELDS }) throw IOException("CSV selection contains an unknown field")
            return SENSOR_FIELDS.filter { it in REQUIRED_FIELDS || it in fields }
        }

        fun restoredExportFields(saved: Collection<String>?): List<String> {
            if (saved == null) return SENSOR_FIELDS
            val selected = OPTIONAL_FIELD_GROUPS.values.filter { group -> group.any { it in saved } }.flatten()
            return orderedExportFields(selected)
        }

    }

    // Preferences can lag a completed append after process death; the file is authoritative.
    private var recordCount = if (file.isFile) file.useLines(StandardCharsets.UTF_8) { lines ->
        lines.count { it.isNotBlank() }
    } else 0

    @Synchronized
    fun count(): Int = recordCount

    @Synchronized
    @Throws(IOException::class)
    fun append(jsonLine: String): Int {
        val bytes = jsonLine.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() + 1 > maxBytes - file.length()) {
            throw IOException("CSV session reached the ${maxBytes / (1024 * 1024)} MiB storage limit")
        }
        FileOutputStream(file, true).use { output ->
            output.write(bytes)
            output.write('\n'.code)
        }
        recordCount++
        return recordCount
    }

    @Synchronized
    @Throws(IOException::class)
    fun exportCsv(output: OutputStream, startingAtRecord: Int = 0, endingAtRecord: Int = recordCount,
        fields: Collection<String> = SENSOR_FIELDS): CsvExportSummary {
        val orderedFields = orderedExportFields(fields)
        if (!file.isFile || recordCount == 0) throw IOException("No CSV records are available")
        if (startingAtRecord !in 0..endingAtRecord || endingAtRecord > recordCount) throw IOException("Invalid CSV recording start position")

        var rowsFound = 0
        var selectedRows = 0
        var index = 0
        var firstReceivedAt: String? = null
        var lastReceivedAt: String? = null
        var missedSamples = 0L
        var missedImuSamples = 0L
        readRows { row ->
            rowsFound++
            val rowIndex = index++
            if (rowIndex < startingAtRecord || rowIndex >= endingAtRecord) return@readRows
            selectedRows++
            val receivedAt = row.optString("received_at_utc").takeIf(String::isNotBlank)
            if (firstReceivedAt == null) firstReceivedAt = receivedAt
            lastReceivedAt = receivedAt
            missedSamples += row.optLong("missed_sequence_count", 0).coerceAtLeast(0)
            missedImuSamples += row.optLong("missed_imu_sequence_count", 0).coerceAtLeast(0)
        }
        if (rowsFound != recordCount) {
            throw IOException("CSV session integrity check failed: expected $recordCount rows, found $rowsFound")
        }
        if (selectedRows == 0) throw IOException("No CSV records are available for this recording")

        OutputStreamWriter(output, StandardCharsets.UTF_8).buffered().use { writer ->
            writer.write('\uFEFF'.code)
            writer.write(orderedFields.joinToString(",", transform = ::csvEscape))
            writer.write("\r\n")
            index = 0
            readRows { row ->
                val rowIndex = index++
                if (rowIndex < startingAtRecord || rowIndex >= endingAtRecord) return@readRows
                writer.write(orderedFields.joinToString(",") { key ->
                    csvEscape(sensorCsvValue(row, key))
                })
                writer.write("\r\n")
            }
        }
        return CsvExportSummary(selectedRows, firstReceivedAt, lastReceivedAt, missedSamples, missedImuSamples)
    }

    private inline fun readRows(action: (JSONObject) -> Unit) {
        BufferedReader(InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8)).use { reader ->
            var lineNumber = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineNumber++
                if (line.isBlank()) continue
                val row = try {
                    JSONObject(line)
                } catch (error: Exception) {
                    throw IOException("CSV session contains invalid data at row $lineNumber", error)
                }
                action(row)
            }
        }
    }
}

internal fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""

internal fun sensorCsvValue(row: JSONObject, key: String): String {
    val value: Any? = when {
        key in CsvSessionStore.UWB_FIELDS -> rawCsvValue(row.optJSONObject("uwb_raw"), CsvSessionStore.UWB_FIELDS.getValue(key))
        key in CsvSessionStore.CIR_FIELDS -> rawCsvValue(row.optJSONObject("cir"), CsvSessionStore.CIR_FIELDS.getValue(key))
        key == "timestamp" -> csvTimestamp(row)
        key == "range" -> if (row.optBoolean("valid")) row.opt("distance_m") else null
        key == "ip_address" -> row.opt("source_ip") ?: row.opt("ip")
        key in listOf("accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y", "gyro_z") -> {
            val vector = if (key.startsWith("accel")) "accel" else "gyro"
            val axis = when (key.last()) { 'x' -> 0; 'y' -> 1; else -> 2 }
            if (row.optBoolean("imu_valid")) row.optJSONArray(vector)?.opt(axis) else null
        }
        key in listOf("battery", "battery_percent", "battery_voltage") -> if (row.optBoolean("battery_valid"))
            row.opt(if (key == "battery") "battery_percent" else key) else null
        key in listOf("accel", "gyro", "temperature_c") -> if (row.optBoolean("imu_valid")) row.opt(key) else null
        key in listOf("distance_m", "speed_mps", "rx_power_dbm", "peer", "ranges", "timestamp_ms", "age_ms") ->
            if (row.optBoolean("valid")) row.opt(key) else null
        else -> row.opt(key)
    }
    return csvAvailableValue(value).toString()
}

private val csvTimestampFormat = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ROOT)
    .withZone(ZoneOffset.ofHoursMinutes(5, 30))

private fun csvTimestamp(row: JSONObject): String? {
    val deviceTime = row.optLong("device_time_unix_ms", 0)
    val instant = if (row.optString("clock_source") == "api" && deviceTime in 1577836800000L until 4102444800000L)
        Instant.ofEpochMilli(deviceTime)
    else runCatching { Instant.parse(row.optString("received_at_utc")) }.getOrNull()
    return instant?.let(csvTimestampFormat::format)
}

private fun rawCsvValue(raw: JSONObject?, path: String): Any? {
    if (raw == null) return null
    if (!raw.optBoolean("valid")) return null
    var value: Any? = raw
    for (key in path.split('.')) value = (value as? JSONObject)?.opt(key)
    return value
}

/** Presentation only: keep the original ingestion payload and genuine zero/false values intact. */
private fun csvAvailableValue(value: Any?): Any = when {
    value == null || value === JSONObject.NULL -> 0
    value is String && value.isBlank() -> 0
    value is Double && !value.isFinite() -> 0
    value is Float && !value.isFinite() -> 0
    value is JSONObject -> if (value.length() == 0) 0 else JSONObject().apply {
        value.keys().forEach { key -> put(key, csvAvailableValue(value.opt(key))) }
    }
    value is JSONArray -> if (value.length() == 0) 0 else JSONArray().apply {
        for (i in 0 until value.length()) put(csvAvailableValue(value.opt(i)))
    }
    else -> value
}
