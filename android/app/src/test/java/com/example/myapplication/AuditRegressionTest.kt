package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.math.hypot

class AuditRegressionTest {
    private fun folder() = Files.createTempDirectory("uwb-audit-").toFile()
    private val row = """{"source_address":"tag","received_at_utc":"2026-11-14T10:00:00Z"}"""

    @Test fun interruptedTailIsPreservedAndEarlierRowsRemainExportable() {
        val directory = folder()
        try {
            val file = File(directory, "capture.jsonl")
            CsvSessionStore(file).append(row)
            file.appendText("{\"source_address\":")
            val recovered = CsvSessionStore(file)
            assertEquals(1, recovered.count())
            recovered.append(row)
            assertEquals(2, recovered.exportCsv(ByteArrayOutputStream()).records)
            assertEquals("{\"source_address\":\n", File(directory, "capture.jsonl.interrupted").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun completeLastRecordWithoutNewlineIsRetained() {
        val directory = folder()
        try {
            val file = File(directory, "capture.jsonl").apply { writeText(row) }
            val recovered = CsvSessionStore(file)
            assertEquals(1, recovered.count())
            recovered.append(row)
            assertEquals(2, recovered.exportCsv(ByteArrayOutputStream()).records)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedAppendRollsBackOnlyTheNewBytes() {
        val directory = folder()
        try {
            val file = File(directory, "capture.jsonl")
            CsvSessionStore(file).append(row)
            val original = file.readBytes()
            val broken = CsvSessionStore(file, appendOutput = { target ->
                object : OutputStream() {
                    private val output = FileOutputStream(target, true)
                    private var written = 0
                    override fun write(value: Int) {
                        if (++written == 5) throw IOException("Injected disk failure")
                        output.write(value)
                    }
                    override fun close() = output.close()
                }
            })
            try { broken.append(row); fail("Expected disk failure") } catch (_: IOException) { }
            assertArrayEquals(original, file.readBytes())
            assertEquals(1, broken.count())
            assertEquals(1, CsvSessionStore(file).exportCsv(ByteArrayOutputStream()).records)
        } finally { directory.deleteRecursively() }
    }

    @Test fun completeCorruptRowsAreReportedInsteadOfSilentlyDiscarded() {
        val directory = folder()
        try {
            val file = File(directory, "capture.jsonl").apply { writeText("$row\nBROKEN\n") }
            val store = CsvSessionStore(file)
            try { store.exportCsv(ByteArrayOutputStream()); fail("Expected integrity failure") }
            catch (_: IOException) { }
            assertTrue(file.readText().contains("BROKEN"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun clearingHistoryRestoresCapacityWithoutDeletingOtherFiles() {
        val directory = folder()
        try {
            val file = File(directory, "capture.jsonl")
            val other = File(directory, "paths.jsonl").apply { writeText("keep") }
            val store = CsvSessionStore(file, row.toByteArray().size.toLong() + 1)
            store.append(row)
            try { store.append(row); fail("Expected limit") } catch (_: IOException) { }
            assertEquals(1, store.exportCsv(ByteArrayOutputStream()).records)
            store.clear()
            assertEquals(0, store.count())
            store.append(row)
            assertEquals(1, store.exportCsv(ByteArrayOutputStream()).records)
            assertEquals("keep", other.readText())
        } finally { directory.deleteRecursively() }
    }

    private val anchors = listOf(
        DeviceRecord("02:00:00:00:00:01", role = "anchor", x = 0.0, y = 0.0),
        DeviceRecord("02:00:00:00:00:02", role = "anchor", x = 4.0, y = 0.0),
        DeviceRecord("02:00:00:00:00:03", role = "anchor", x = 0.0, y = 4.0))
    private val tag = "02:00:00:00:00:04"
    private fun readings(distances: List<Double>, time: Long = 1000) = anchors.mapIndexed { index, anchor ->
        (anchor.mac to tag) to RangeReading(distances[index], null, time)
    }.toMap()

    @Test fun inconsistentLiveRangesCannotEnterPositionsOrTrails() {
        val result = solvePositions(anchors, readings(listOf(2.0, 2.0, 20.0)), 1000)
        assertTrue(result.isEmpty())
        assertTrue(TagTrailStore().update(result, mapOf(tag to "evidence"), 1000).isEmpty())
    }

    @Test fun validNegativeCoordinatesInAnExistingManualFrameRemainSupported() {
        val distances = anchors.map { hypot(it.x + 1, it.y - 2) }
        val position = solvePositions(anchors, readings(distances), 1000).getValue(tag)
        assertEquals(-1.0, position.x, 1e-8); assertEquals(2.0, position.y, 1e-8)
        assertTrue(solvePositions(anchors, readings(distances, 1001), 1000).isEmpty())
    }

    @Test fun nearlyCollinearLiveGeometryIsRejected() {
        val bad = listOf(anchors[0], anchors[1], anchors[2].copy(x = 8.0, y = 1e-7))
        val ranges = bad.associate { (it.mac to tag) to RangeReading(hypot(it.x - 2, it.y - 3), null, 1000) }
        assertTrue(solvePositions(bad, ranges, 1000).isEmpty())
    }

    @Test fun liveFitUsesSavedHeightsAndPhysicalRanges() {
        val raised = anchors.map { it.copy(z = 5.0) }
        val records = raised + DeviceRecord(tag, role = "tag", z = 1.5)
        val ranges = raised.associate { (it.mac to tag) to RangeReading(
            hypot(hypot(it.x - 2, it.y - 3), it.z - 1.5), null, 1000) }
        val position = solvePositions(records, ranges, 1000).getValue(tag)
        assertEquals(2.0, position.x, 1e-8); assertEquals(3.0, position.y, 1e-8)
    }

    @Test fun alternatingTagsBothRetainTheirSavedPathShape() {
        val directory = folder()
        try {
            val store = PathSessionStore(directory, clock = { 100_000L }, minFreeBytes = 0)
            store.start(anchors, 100_000L)
            for (i in 1..10_000) store.append(mapOf(
                (if (i % 2 == 1) "B" else "A") to TrailPoint(i.toDouble(), kotlin.math.sin(i * .01), 100_000L + i, false)))
            val summary = requireNotNull(store.finish(110_000))
            val saved = store.load(summary.id, 5000)
            assertTrue(saved.trails.getValue("A").size >= 2500)
            assertTrue(saved.trails.getValue("B").size >= 2500)
            assertEquals(9999.0, saved.trails.getValue("B").last().x, 0.0)
            assertEquals(10_000.0, saved.trails.getValue("A").last().x, 0.0)
        } finally { directory.deleteRecursively() }
    }
}
