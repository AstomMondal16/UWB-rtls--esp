package com.example.myapplication

import org.json.JSONObject
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
    val missedSamples: Long
)

/** App-private append-only session storage; CSV exports stream directly to the destination. */
internal class CsvSessionStore(
    private val file: File,
    initialRecordCount: Int,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    companion object {
        const val DEFAULT_MAX_BYTES = 512L * 1024L * 1024L
        private val PRIORITY_FIELDS = listOf(
            "received_at_utc", "source_address", "source_ip", "timestamp_ms", "sequence",
            "missed_sequence_count", "mac", "role", "valid", "peer", "distance_m",
            "speed_mps", "rx_power_dbm", "accel", "gyro", "temperature_c",
            "imu_valid", "imu_enabled", "battery_voltage", "battery_percent",
            "battery_valid", "dropped", "ranges"
        )
    }

    private var recordCount = initialRecordCount.coerceAtLeast(0)

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
    fun exportCsv(output: OutputStream): CsvExportSummary {
        if (!file.isFile || recordCount == 0) throw IOException("No CSV records are available")

        val fields = linkedSetOf<String>()
        var rowsFound = 0
        var firstReceivedAt: String? = null
        var lastReceivedAt: String? = null
        var missedSamples = 0L
        readRows { row ->
            rowsFound++
            val keys = row.keys()
            while (keys.hasNext()) fields.add(keys.next())
            val receivedAt = row.optString("received_at_utc").takeIf(String::isNotBlank)
            if (firstReceivedAt == null) firstReceivedAt = receivedAt
            lastReceivedAt = receivedAt
            missedSamples += row.optLong("missed_sequence_count", 0).coerceAtLeast(0)
        }
        if (rowsFound != recordCount) {
            throw IOException("CSV session integrity check failed: expected $recordCount rows, found $rowsFound")
        }

        val orderedFields = PRIORITY_FIELDS.filter(fields::contains) +
            fields.filterNot(PRIORITY_FIELDS.toSet()::contains).sorted()
        OutputStreamWriter(output, StandardCharsets.UTF_8).buffered().use { writer ->
            writer.write('\uFEFF'.code)
            writer.write(orderedFields.joinToString(",", transform = ::csvEscape))
            writer.write("\r\n")
            readRows { row ->
                writer.write(orderedFields.joinToString(",") { key ->
                    val value = row.opt(key)
                    csvEscape(if (value == null || value === JSONObject.NULL) "" else value.toString())
                })
                writer.write("\r\n")
            }
        }
        return CsvExportSummary(rowsFound, firstReceivedAt, lastReceivedAt, missedSamples)
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
