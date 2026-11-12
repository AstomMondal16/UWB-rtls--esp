package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant

class CsvSessionStoreTest {
    private fun raw() = JSONObject().put("valid", true).put("format_version", 1)
        .put("distance_m_unfiltered", 1.23456776).put("tof_ticks", 1.2345678901234567)
        .put("timestamp_units", "dw1000_ticks").put("timestamp_valid_mask", 127).put("expected_timestamp_mask", 80)
        .put("local_rx_power_dbm", -87.125).put("peer_rx_power_dbm", JSONObject.NULL)
        .put("timestamps", JSONObject().apply {
            listOf("poll_tx", "poll_rx", "ack_tx", "ack_rx", "range_tx", "range_rx", "report_tx", "report_rx")
                .forEachIndexed { i, key -> put(key, if (i == 7) JSONObject.NULL else 1099511627775L - i) }
        })
        .put("rx_diagnostics", JSONObject().put("cir_power", 65535).put("rx_frame_info", 4293918820L).put("preamble_count", 4095))
        .put("frames", JSONArray().apply {
            listOf("poll", "ack", "range", "range_report").forEachIndexed { frame, kind ->
                put(JSONObject().put("kind", kind).put("direction", if (frame % 2 == 0) "rx" else "tx")
                    .put("length", 98).put("tx_sent", if (frame % 2 == 0) JSONObject.NULL else frame == 1)
                    .put("hex", (0 until 98).joinToString("") { ((frame * 98 + it) % 256).toString(16).padStart(2, '0').uppercase() }))
            }
        })

    private fun cir() = JSONObject().put("enabled", true).put("valid", true).put("sequence", 7)
        .put("timestamp_ms", 1000).put("age_ms", 5).put("rx_timestamp", 1099511627775L)
        .put("peer_address", 4660).put("frame_type", 3).put("first_path_index", 64).put("start_index", 16)
        .put("sample_count", 1016).put("preamble_count", 200).put("capture_us", 1234)
        .put("capture_interval_ms", 100).put("sample_period_ns", 1.001602564)
        .put("iq", JSONArray().apply { repeat(1016) { put(JSONArray(listOf(-32768, 32767))) } })

    @Test fun csvEscapingQuotesEveryFieldAndDoublesEmbeddedQuotes() {
        assertEquals("\"plain\"", csvEscape("plain"))
        assertEquals("\"a,b\"", csvEscape("a,b"))
        assertEquals("\"say \"\"hello\"\"\"", csvEscape("say \"hello\""))
        assertEquals("\"line1\r\nline2\"", csvEscape("line1\r\nline2"))
    }

    @Test fun exportUsesRequestedOrderAndFullIqWithoutExtraColumns() {
        val file = Files.createTempFile("columns-csv", ".jsonl").toFile()
        try {
            val row = JSONObject().put("received_at_utc", "2026-11-05T06:30:00.123Z")
                .put("source_address", "AA:BB:CC:DD:EE:FF").put("source_ip", "192.168.1.10").put("role", "anchor")
                .put("valid", true).put("distance_m", 1.23).put("speed_mps", .1234).put("uwb_raw", raw()).put("cir", cir())
                .put("tx_power", "0x67676767").put("imu_valid", true)
                .put("accel", JSONArray(listOf(.0001, -.1234, .9876))).put("gyro", JSONArray(listOf(1.2345, -2.3456, 3.4567)))
                .put("battery_valid", true).put("battery_percent", 76.5).put("battery_voltage", 3.987)
            val original = row.toString()
            val store = CsvSessionStore(file); store.append(original)
            val output = ByteArrayOutputStream(); assertEquals(1, store.exportCsv(output).records)
            val expected = "timestamp,source_address,ip_address,role,range,distance_m_unfiltered,poll_tx,poll_rx,ack_tx,ack_rx,range_tx,range_rx,report_tx,report_rx,local_rx_power_dbm,peer_rx_power_dbm,cir_power,rx_frame_info,preamble_count,tof_ticks,tx_power,iq,sequence,timestamp_ms,rx_timestamp,peer_address,frame_type,first_path_index,start_index,sample_count,cir_preamble_count,age_ms,capture_us,capture_interval_ms,sample_period_ns,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,battery,battery_voltage,speed_mps".split(",")
            assertEquals(expected, CsvSessionStore.SENSOR_FIELDS)
            val header = output.toString("UTF-8").removePrefix("\uFEFF").substringBefore("\r\n")
            assertEquals(expected.joinToString(",", transform = ::csvEscape), header)
            assertEquals(44, expected.size); assertEquals(expected.size, expected.toSet().size)
            assertEquals("1.23", sensorCsvValue(row, "range"))
            assertEquals("05-11-2026 12:00:00", sensorCsvValue(row, "timestamp"))
            assertEquals("3.987", sensorCsvValue(row, "battery_voltage"))
            assertEquals("0.1234", sensorCsvValue(row, "speed_mps"))
            assertEquals(.0001, sensorCsvValue(row, "accel_x").toDouble(), 0.0)
            assertEquals(1.23456776, sensorCsvValue(row, "distance_m_unfiltered").toDouble(), 0.0)
            assertEquals(1099511627775L, sensorCsvValue(row, "poll_tx").toLong())
            val iq = JSONArray(sensorCsvValue(row, "iq"))
            assertEquals(1016, iq.length()); assertEquals(-32768, iq.getJSONArray(1015).getInt(0))
            assertEquals(original, file.readText().trim())
            val report = File("build/reports/csv-columns").apply { mkdirs() }
            File(report, "full.csv").writeBytes(output.toByteArray())
            File(report, "expected.json").writeText(row.toString())
        } finally { file.delete() }
    }

    @Test fun rawFieldsAndCirNamesDoNotCollideWithTopLevelTelemetry() {
        val row = JSONObject().put("uwb_raw", raw()).put("cir", cir()).put("valid", true)
            .put("distance_m", 1.23).put("sequence", 999).put("timestamp_ms", 9999).put("age_ms", 9999)
        val expected = mapOf("tof_ticks" to "1.2345678901234567", "local_rx_power_dbm" to "-87.125",
            "peer_rx_power_dbm" to "0", "cir_power" to "65535", "rx_frame_info" to "4293918820",
            "preamble_count" to "4095", "report_rx" to "0", "sequence" to "7", "timestamp_ms" to "1000",
            "age_ms" to "5", "rx_timestamp" to "1099511627775", "peer_address" to "4660", "frame_type" to "3",
            "first_path_index" to "64", "start_index" to "16", "sample_count" to "1016", "cir_preamble_count" to "200",
            "capture_us" to "1234", "capture_interval_ms" to "100", "sample_period_ns" to "1.001602564")
        expected.forEach { (key, value) -> assertEquals(key, value, sensorCsvValue(row, key)) }
        row.remove("uwb_raw")
        assertEquals("0", sensorCsvValue(row, "distance_m_unfiltered"))
    }

    @Test fun unavailableFieldsUseZeroWithoutMutatingOriginalStatusFlags() {
        val row = JSONObject().put("valid", false).put("distance_m", 99).put("speed_mps", 99).put("battery_voltage", 99)
            .put("imu_valid", false).put("battery_valid", false)
            .put("uwb_raw", JSONObject().put("valid", false).put("format_version", 1))
            .put("cir", JSONObject().put("enabled", true).put("valid", false))
            .put("sequence", 999).put("timestamp_ms", 9999).put("streaming", false).put("source_ip", " ")
        val original = row.toString()
        for (key in CsvSessionStore.SENSOR_FIELDS - "timestamp")
            assertEquals(key, "0", sensorCsvValue(row, key))
        assertEquals("0", sensorCsvValue(row, "timestamp"))
        assertEquals(original, row.toString())
    }

    @Test fun timestampSourceFollowsFirmwareModeAndKeepsPhoneReceiptMetadata() {
        val row = JSONObject().put("received_at_utc", "2026-11-05T11:18:29.720Z")
            .put("device_time_unix_ms", 1793879430391L).put("clock_source", "phone")
        assertEquals("05-11-2026 16:48:29", sensorCsvValue(row, "timestamp"))
        row.put("clock_source", "api")
        assertEquals("05-11-2026 17:20:30", sensorCsvValue(row, "timestamp"))
        assertEquals("2026-11-05T11:18:29.720Z", row.getString("received_at_utc"))
        for (invalid in listOf(0L, -1L, 4102444800000L)) {
            row.put("device_time_unix_ms", invalid)
            assertEquals("05-11-2026 16:48:29", sensorCsvValue(row, "timestamp"))
        }
    }

    @Test fun timestampIsIstWithRolloverAndNeverTreatsUptimeAsCalendarTime() {
        val row = JSONObject().put("received_at_utc", "2026-11-05T16:33:59.999Z")
        assertEquals("05-11-2026 22:03:59", sensorCsvValue(row, "timestamp"))
        row.put("received_at_utc", "2026-12-31T20:00:00Z")
        assertEquals("01-01-2027 01:30:00", sensorCsvValue(row, "timestamp"))
        row.put("received_at_utc", "invalid").put("timestamp_ms", 122553)
        assertEquals("0", sensorCsvValue(row, "timestamp"))
        row.remove("received_at_utc")
        assertEquals("0", sensorCsvValue(row, "timestamp"))
    }

    @Test fun genuineZeroReadingsStayZero() {
        val raw = JSONObject().put("valid", true).put("distance_m_unfiltered", 0)
            .put("timestamps", JSONObject().put("poll_tx", 0).put("poll_rx", JSONObject.NULL))
        val row = JSONObject().put("valid", true).put("distance_m", 0).put("speed_mps", 0).put("battery_voltage", 0).put("imu_valid", true).put("battery_valid", true).put("uwb_raw", raw)
            .put("accel", JSONArray(listOf(0, 0, 0))).put("gyro", JSONArray(listOf(0, 0, 0))).put("battery_percent", 0)
            .put("cir", JSONObject().put("valid", true).put("iq", JSONArray().put(JSONArray(listOf(0, 0)))))
        for (key in listOf("range", "accel_x", "gyro_z", "battery", "distance_m_unfiltered", "poll_tx", "poll_rx", "battery_voltage", "speed_mps"))
            assertEquals(key, 0.0, sensorCsvValue(row, key).toDouble(), 0.0)
        assertTrue(raw.getJSONObject("timestamps").isNull("poll_rx"))
        assertEquals("[[0,0]]", sensorCsvValue(row, "iq"))
    }

    @Test fun selectedColumnsKeepEveryBurstRowAndFreezeTheEndBeforeLaterSamples() {
        val file = Files.createTempFile("selected-csv", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file)
            val base = Instant.parse("2026-11-10T18:00:00Z")
            repeat(202) { i ->
                store.append(JSONObject().put("received_at_utc", base.plusMillis(i * 10L).toString())
                    .put("source_address", if (i % 2 == 0) "AA:BB:CC:DD:EE:01" else "AA:BB:CC:DD:EE:02")
                    .put("source_ip", "192.168.1.100").put("role", "tag")
                    .put("valid", true).put("distance_m", i + 1.23456789012345)
                    .put("imu_valid", true).put("accel", JSONArray(listOf(i / 1000.0, -0.125, 0.987654321)))
                    .put("gyro", JSONArray(listOf(1.0, 2.0, -i / 100.0)))
                    .put("battery_valid", true).put("battery_percent", 80).put("battery_voltage", 3.7 + i / 10000.0)
                    .put("cir", cir()).toString())
            }
            val original = file.readBytes()
            // 200 is the Stop boundary; rows 200 and 201 arrived while export setup was pending.
            val fields = listOf("battery_voltage", "gyro_z", "accel_x", "iq", "range", "source_address", "timestamp")
            val output = ByteArrayOutputStream()
            val summary = store.exportCsv(output, 0, 200, fields)
            assertEquals(200, summary.records)
            assertEquals(202, store.count())
            assertEquals(base.toString(), summary.firstReceivedAt)
            assertEquals(base.plusMillis(1990).toString(), summary.lastReceivedAt)
            assertArrayEquals(original, file.readBytes())
            val header = listOf("timestamp", "source_address", "ip_address", "role", "range", "iq", "accel_x", "gyro_z", "battery_voltage")
            assertEquals(header.joinToString(",", transform = ::csvEscape), output.toString("UTF-8").removePrefix("\uFEFF").substringBefore("\r\n"))
            val report = File("build/reports/csv-custom-fields").apply { mkdirs() }
            File(report, "selected.csv").writeBytes(output.toByteArray())
            File(report, "recorded.jsonl").writeBytes(original)
            File(report, "expected.json").writeText(JSONObject().put("rows", 200)
                .put("raw_rows", 202).put("fields", JSONArray(header)).put("iq_pairs", 1016).toString())
        } finally { file.delete() }
    }

    @Test fun choosingAllFieldsKeepsTheOriginalCsvBytesAndRemovesDuplicateColumns() {
        val file = Files.createTempFile("all-fields-csv", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file)
            store.append(JSONObject().put("received_at_utc", "2026-11-10T18:00:00Z")
                .put("valid", true).put("distance_m", 1.23456789012345).put("cir", cir()).toString())
            val default = ByteArrayOutputStream(); store.exportCsv(default)
            val selected = ByteArrayOutputStream()
            store.exportCsv(selected, fields = CsvSessionStore.SENSOR_FIELDS.reversed() + "timestamp")
            assertArrayEquals(default.toByteArray(), selected.toByteArray())
            assertEquals(44, CsvSessionStore.SENSOR_FIELDS.size)
            assertEquals(44, CsvSessionStore.SENSOR_FIELDS.distinct().size)
        } finally { file.delete() }
    }

    @Test fun unknownFieldSelectionsFailBeforeWritingAndPreserveTheRecording() {
        val file = Files.createTempFile("invalid-fields-csv", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file)
            store.append(JSONObject().put("received_at_utc", "2026-11-10T18:00:00Z").toString())
            val original = file.readBytes()
            for (fields in listOf(listOf("unknown_field"), listOf("timestamp", "unknown_field"))) {
                val output = ByteArrayOutputStream()
                try { store.exportCsv(output, fields = fields); fail("Expected invalid selection to fail") }
                catch (_: java.io.IOException) { }
                assertEquals(0, output.size())
                assertArrayEquals(original, file.readBytes())
                assertEquals(1, store.count())
            }
        } finally { file.delete() }
    }

    @Test fun rememberedFieldsKeepRequiredDetailsAndExpandLegacyChoicesIntoGroups() {
        val required = CsvSessionStore.REQUIRED_FIELDS
        assertEquals(CsvSessionStore.SENSOR_FIELDS, CsvSessionStore.restoredExportFields(null))
        assertEquals(required, CsvSessionStore.restoredExportFields(emptyList()))
        assertEquals(required, CsvSessionStore.restoredExportFields(listOf("removed_field")))
        val groups = CsvSessionStore.OPTIONAL_FIELD_GROUPS
        assertEquals(listOf("IMU", "Battery", "UWB"), groups.keys.toList())
        assertEquals(CsvSessionStore.orderedExportFields(groups.getValue("IMU") + groups.getValue("UWB")),
            CsvSessionStore.restoredExportFields(listOf("gyro_z", "removed_field", "timestamp", "iq", "iq")))
        assertEquals(required + "gyro_z", CsvSessionStore.orderedExportFields(listOf("gyro_z", "timestamp", "timestamp")))
        assertEquals(required, CsvSessionStore.orderedExportFields(emptyList()))
    }

    @Test fun allEightSensorGroupCombinationsAlwaysExportDeviceDetailsAndEveryRow() {
        val file = Files.createTempFile("group-fields-csv", ".jsonl").toFile()
        try {
            val store = CsvSessionStore(file)
            repeat(3) { store.append(JSONObject().put("source_address", "device-$it").toString()) }
            val original = file.readBytes()
            val groups = CsvSessionStore.OPTIONAL_FIELD_GROUPS.values.toList()
            assertEquals(CsvSessionStore.SENSOR_FIELDS.toSet(),
                (CsvSessionStore.REQUIRED_FIELDS + groups.flatten()).toSet())
            assertEquals(40, groups.flatten().distinct().size)
            assertEquals(40, groups.flatten().size)
            for (mask in 0..7) {
                val chosen = groups.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.flatten()
                val fields = CsvSessionStore.restoredExportFields(chosen)
                val output = ByteArrayOutputStream()
                assertEquals(3, store.exportCsv(output, fields = fields).records)
                val expected = CsvSessionStore.SENSOR_FIELDS.filter { it in CsvSessionStore.REQUIRED_FIELDS || it in chosen }
                val lines = output.toString("UTF-8").removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
                assertEquals(expected.joinToString(",", transform = ::csvEscape), lines.first())
                assertEquals(4, lines.size)
                assertEquals(expected, fields)
                assertEquals(fields, CsvSessionStore.restoredExportFields(fields))
                assertTrue(CsvSessionStore.REQUIRED_FIELDS.all { it in fields })
            }
            assertArrayEquals(original, file.readBytes())
        } finally { file.delete() }
    }
}
