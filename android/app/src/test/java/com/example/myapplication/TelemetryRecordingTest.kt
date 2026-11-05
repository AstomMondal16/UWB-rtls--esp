package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class TelemetryRecordingTest {
    private fun sample(n: Int) = JSONObject().apply {
        put("valid", true); put("sequence", n / 10 + 1); put("timestamp_ms", (n / 10 + 1) * 100)
        put("distance_m", 1.2345); put("speed_mps", .1234); put("rx_power_dbm", -81.25)
        put("peer", "AA:BB:CC:DD:EE:FF")
        put("ranges", JSONArray().put(JSONObject().put("peer", "AA:BB:CC:DD:EE:FF")
            .put("distance_m", 1.2345).put("speed_mps", .1234).put("rx_power_dbm", -81.25)
            .put("timestamp_ms", (n / 10 + 1) * 100).put("age_ms", n % 10)))
        put("imu_valid", true); put("imu_enabled", true)
        put("imu_sequence", n + 1); put("imu_timestamp_ms", (n + 1) * 10)
        put("accel", JSONArray(listOf(n / 10000.0, -.1234, .9876)))
        put("gyro", JSONArray(listOf(1.2345, -2.3456, 3.4567))); put("temperature_c", 23.45)
        put("battery_valid", true); put("battery_voltage", 3.987); put("battery_percent", 76.5)
        put("uwb_raw", JSONObject().put("valid", true).put("format_version", 1)
            .put("distance_m_unfiltered", 1.23456776).put("tof_ticks", 1.2345678901234567)
            .put("timestamp_units", "dw1000_ticks").put("timestamp_valid_mask", 1).put("expected_timestamp_mask", 0)
            .put("local_rx_power_dbm", -87.125).put("peer_rx_power_dbm", JSONObject.NULL)
            .put("rx_diagnostics", JSONObject().put("cir_power", 65535).put("rx_frame_info", 4293918820L).put("preamble_count", 4095))
            .put("timestamps", JSONObject().put("poll_tx", 1099511627775L).put("poll_rx", JSONObject.NULL))
            .put("frames", JSONArray().apply {
                repeat(4) { frame -> put(JSONObject().put("kind", listOf("poll", "ack", "range", "range_report")[frame])
                    .put("direction", if (frame % 2 == 0) "tx" else "rx").put("tx_sent", if (frame % 2 == 0) true else JSONObject.NULL)
                    .put("length", 98).put("hex",
                    (0 until 98).joinToString("") { ((frame * 98 + it) % 256).toString(16).padStart(2, '0').uppercase() })) }
            }))
        put("tx_power", "0x67676767")
        put("cir", JSONObject().put("enabled", true).put("valid", true).put("sequence", n + 1)
            .put("timestamp_ms", (n + 1) * 10).put("sample_count", 2).put("age_ms", 5)
            .put("rx_timestamp", 1099511627775L).put("peer_address", 4660).put("frame_type", 3)
            .put("first_path_index", 64).put("start_index", 16).put("preamble_count", 200)
            .put("capture_us", 1234).put("capture_interval_ms", 100).put("sample_period_ns", 1.001602564)
            .put("iq", JSONArray().put(JSONArray(listOf(-32768, 32767))).put(JSONArray(listOf(123, -456)))))
        put("_received_at", 1700000000000L + n)
        put("note", "comma, quote\" and\r\nnew line")
    }

    private fun assertJsonEqual(expected: Any, actual: Any) {
        when (expected) {
            is JSONArray -> {
                assertTrue(actual is JSONArray)
                val values = actual as JSONArray
                assertEquals(expected.length(), values.length())
                for (i in 0 until expected.length()) assertJsonEqual(expected.get(i), values.get(i))
            }
            is JSONObject -> {
                assertTrue(actual is JSONObject)
                val value = actual as JSONObject
                assertEquals(expected.length(), value.length())
                expected.keys().forEach { assertJsonEqual(expected.get(it), value.get(it)) }
            }
            is Number -> assertEquals(expected.toDouble(), (actual as Number).toDouble(), 0.0)
            else -> assertEquals(expected, actual)
        }
    }

    @Test fun burstKeepsEveryReceivedStreamAndFinalSamplesBeforeStop() {
        val file = Files.createTempFile("telemetry", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file)
            val recorder = TelemetryRecorder(store)
            val position = mapOf("tag-A" to Position(1.234567890123, -2.345678901234, .25, .0123, listOf("anchor-A")))
            assertFalse(recorder.accept("A", "192.168.1.1", sample(0), position))
            recorder.begin()
            val expected = mutableListOf<JSONObject>()
            for (n in 0..201) for (source in listOf("A", "B")) {
                val input = sample(n).put("mac", source).put("role", "tag")
                assertTrue("IMU-only updates must survive a constant UWB sequence", recorder.accept(source, "192.168.1.1", input, position))
                expected += input
                assertFalse("Repeated latest snapshot is not a new sample", recorder.accept(source, "192.168.1.1", input, position))
            }
            recorder.stop()
            val end = store.count()
            assertEquals(404, end)
            assertFalse(recorder.accept("A", "192.168.1.1", sample(202), position))
            // A new recording starts while the user is choosing where to save the previous one.
            recorder.begin()
            assertTrue(recorder.accept("A", "192.168.1.1", sample(203), position))
            val output = ByteArrayOutputStream()
            val summary = store.exportCsv(output, 0, end)
            assertEquals(404, summary.records)
            assertEquals(405, store.count())
            assertEquals(0, summary.missedSamples)
            assertEquals(0, summary.missedImuSamples)
            val rows = file.readLines().map(::JSONObject).take(end)
            expected.zip(rows).forEach { (input, row) ->
                input.keys().forEach { field ->
                    val original = input.get(field)
                    assertJsonEqual(original, row.get(field))
                }
                assertEquals(input.getString("mac"), row.getString("source_address"))
                assertEquals(1.234567890123, row.getJSONArray("positions").getJSONObject(0).getDouble("x_m"), 0.0)
                assertTrue(row.getString("received_at_utc").endsWith("Z"))
            }
            // Also parsed by Python's standard CSV reader in the repository verification run.
            val report = File("build/reports/recording-completeness").apply { mkdirs() }
            File(report, "burst.csv").writeBytes(output.toByteArray())
            File(report, "expected.json").writeText(JSONArray(expected).toString())
            assertEquals(404, CsvSessionStore(file).exportCsv(ByteArrayOutputStream(), 0, end).records)
        } finally { file.delete() }
    }

    @Test fun independentStreamGapsRebootValidityAndDevicePause() {
        val file = Files.createTempFile("streams", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file); val recorder = TelemetryRecorder(store); recorder.begin()
            assertTrue(recorder.accept("A", "ip", sample(0), emptyMap()))
            val jump = sample(30).apply { remove("cir") } // UWB: 1 -> 4; IMU: 1 -> 31
            assertTrue(recorder.accept("A", "ip", jump, emptyMap()))
            val paused = JSONObject(jump.toString()).put("streaming", false)
                .put("imu_sequence", 32).put("imu_timestamp_ms", 320)
            // Firmware pause stops UWB; IMU still arrives and remains recordable.
            assertTrue(recorder.accept("A", "ip", paused, emptyMap()))
            val invalid = JSONObject(paused.toString()).put("imu_sequence", 33).put("imu_valid", false)
            assertFalse(recorder.accept("A", "ip", invalid, emptyMap()))
            assertTrue(recorder.accept("A", "ip", sample(0), emptyMap())) // reboot
            val result = store.exportCsv(ByteArrayOutputStream())
            assertEquals(4, result.records)
            assertEquals(2, result.missedSamples)
            assertEquals(29, result.missedImuSamples)
        } finally { file.delete() }
    }

    @Test fun imuAndBatteryDoNotRequireAnyUwbSequence() {
        val file = Files.createTempFile("no-uwb", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file); val recorder = TelemetryRecorder(store); recorder.begin()
            val imu = sample(0).put("valid", false).apply { remove("sequence"); remove("timestamp_ms"); remove("cir") }
            assertTrue(recorder.accept("A", "ip", imu, emptyMap()))
            assertTrue(recorder.accept("A", "ip", JSONObject(imu.toString()).put("imu_sequence", 2).put("imu_timestamp_ms", 20), emptyMap()))
            val battery = JSONObject().put("battery_valid", true).put("battery_voltage", 3.812).put("battery_percent", 50.5)
            assertTrue(recorder.accept("B", "ip", battery, emptyMap()))
            assertFalse(recorder.accept("B", "ip", battery, emptyMap()))
            assertEquals(3, store.count())
        } finally { file.delete() }
    }

    @Test fun writeFailureDoesNotAdvanceRecorderCursorAndCorruptExportFailsClearly() {
        val file = Files.createTempFile("limit", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file, maxBytes = 10); val recorder = TelemetryRecorder(store); recorder.begin()
            assertThrows(java.io.IOException::class.java) { recorder.accept("A", "ip", sample(1), emptyMap()) }
            assertEquals(0, store.count())
            file.writeText("not JSON\n")
            assertThrows(java.io.IOException::class.java) { CsvSessionStore(file).exportCsv(ByteArrayOutputStream()) }
        } finally { file.delete() }
    }

    @Test fun cirOnlyUpdatesAreRecordedWithoutVisiblePanelsAndDuplicatesAreSkipped() {
        val file = Files.createTempFile("cir-stream", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file); val recorder = TelemetryRecorder(store); recorder.begin()
            val cir = JSONObject().put("enabled", true).put("valid", true).put("sequence", 1)
                .put("timestamp_ms", 100).put("sample_count", 1)
                .put("iq", JSONArray().put(JSONArray(listOf(-32768, 32767))))
            val input = JSONObject().put("role", "tag").put("cir", cir)
            assertTrue(recorder.accept("A", "ip", input, emptyMap()))
            assertFalse(recorder.accept("A", "ip", input, emptyMap()))
            cir.put("sequence", 2).put("timestamp_ms", 200)
            assertTrue(recorder.accept("A", "ip", input, emptyMap()))
            cir.put("sequence", 3).put("sample_count", 2) // incomplete capture
            assertFalse(recorder.accept("A", "ip", input, emptyMap()))
            recorder.stop()
            cir.put("sample_count", 1)
            assertFalse(recorder.accept("A", "ip", input, emptyMap()))
            assertEquals(2, store.count())
            val rows = file.readLines().map(::JSONObject)
            assertEquals("cir", rows.last().getJSONArray("updated_streams").getString(0))
            assertEquals(-32768, rows.last().getJSONObject("cir").getJSONArray("iq").getJSONArray(0).getInt(0))
        } finally { file.delete() }
    }
}
