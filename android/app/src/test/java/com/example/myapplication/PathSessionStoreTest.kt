package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class PathSessionStoreTest {
    private val tagA = "AA:BB:CC:DD:EE:01"
    private val tagB = "AA:BB:CC:DD:EE:02"
    private val devices = listOf(
        DeviceRecord("AA:BB:CC:DD:EE:11", x = 0.0, y = 0.0),
        DeviceRecord("AA:BB:CC:DD:EE:12", x = 4.0, y = 0.0),
        DeviceRecord("AA:BB:CC:DD:EE:13", x = 0.0, y = 4.0),
        DeviceRecord(tagA, role = "tag"), DeviceRecord(tagB, role = "tag"))
    private fun store(dir: File, maxPoints: Int = 100_000, maxBytes: Long = Long.MAX_VALUE,
        maxSessions: Int = Int.MAX_VALUE) = PathSessionStore(dir, maxPoints, maxBytes, maxSessions,
            clock = { 0L }, minFreeBytes = 0)
    private fun p(x: Double) = Position(x, 1.23456789012345, 0.0, 0.01, emptyList())
    private fun point(x: Double, time: Long, split: Boolean = false) = TrailPoint(x, 2.5, time, split)
    private fun directory(check: (File) -> Unit) {
        val dir = Files.createTempDirectory("saved-path-test").toFile()
        try { check(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun ingestionBurstSavesEveryNewPositionIncludingImmediatelyBeforeStop() = directory { dir ->
        val store = store(dir)
        val live = TagTrailStore()
        store.start(devices, 1000)
        for (i in 0 until 100) {
            val positions = mapOf(tagA to p(i / 10.0), tagB to p(-i / 10.0))
            val evidence = mapOf(tagA to "$i", tagB to "$i")
            store.append(live.update(positions, evidence, 1000L + i * 50))
            store.append(live.update(positions, evidence, 1001L + i * 50)) // UI/duplicate poll adds nothing.
        }
        store.append(live.update(mapOf(tagA to p(Double.NaN)), mapOf(tagA to "invalid"), 6050))
        live.breakTag(tagB)
        store.append(live.update(mapOf(tagA to p(10.1234567890123), tagB to p(-10.25)),
            mapOf(tagA to "last", tagB to "last"), 6100))
        val summary = store.finish(6101)!!
        assertEquals(202, summary.pointCount)
        val reopened = store(dir).load(summary.id)
        assertEquals(202, reopened.trails.values.sumOf { it.size })
        assertEquals(101, reopened.trails.getValue(tagA).size)
        assertEquals(101, reopened.trails.getValue(tagB).size)
        for (i in 0 until 100) {
            val a = reopened.trails.getValue(tagA)[i]
            val b = reopened.trails.getValue(tagB)[i]
            assertEquals(i / 10.0, a.x, 0.0)
            assertEquals(-i / 10.0, b.x, 0.0)
            assertEquals(1.23456789012345, a.y, 0.0)
            assertEquals(1000L + i * 50, a.receivedAtMs)
            assertEquals(a.receivedAtMs, b.receivedAtMs)
        }
        assertEquals(10.1234567890123, reopened.positions.getValue(tagA).x, 0.0)
        assertTrue(reopened.trails.getValue(tagA).last().startsSegment)
        assertTrue(reopened.trails.getValue(tagB).last().startsSegment)
        assertEquals(6100L, reopened.trails.getValue(tagA).last().receivedAtMs)
        assertEquals(devices, reopened.devices)
    }

    @Test fun sessionsReopenSeparatelyAndKeepOriginalAnchorCoordinates() = directory { dir ->
        val store = store(dir)
        store.start(devices, 100)
        store.append(mapOf(tagA to point(1.0, 110)))
        val first = store.finish(120)!!
        val moved = devices.map { if (it.role == "anchor") it.copy(x = it.x + 10) else it }
        store.start(moved, 200)
        store.append(mapOf(tagB to point(12.0, 210)))
        val second = store.finish(220)!!
        val reopened = store(dir)
        assertEquals(listOf(second.id, first.id), reopened.list().map { it.id })
        assertEquals(devices, reopened.load(first.id).devices)
        assertEquals(moved, reopened.load(second.id).devices)
        assertEquals(setOf(tagA), reopened.load(first.id).trails.keys)
        assertEquals(setOf(tagB), reopened.load(second.id).trails.keys)
        assertTrue(reopened.load(first.id).trails.getValue(tagA).first().startsSegment)
    }

    @Test fun longPreviewIsBoundedKeepsEndpointsAndNeverConnectsAcrossSkippedGaps() = directory { dir ->
        val store = store(dir)
        store.start(devices, 0)
        repeat(20_000) { i -> store.append(mapOf(tagA to point(i.toDouble(), i * 50L, i == 701))) }
        val summary = store.finish(1_000_000)!!
        val preview = store.load(summary.id, previewPoints = 100)
        val trail = preview.trails.getValue(tagA)
        assertTrue(trail.size <= 102)
        assertEquals(0.0, trail.first().x, 0.0)
        assertEquals(19999.0, trail.last().x, 0.0)
        val crossing = trail.zipWithNext().first { (a, b) -> a.x < 701 && b.x >= 701 }
        assertTrue(crossing.second.startsSegment)
        assertEquals(20_000, preview.summary.pointCount)
    }

    @Test fun processDeathRecoversCompletePointsAndIgnoresPartialLastWrite() = directory { dir ->
        val source = File(dir, "source"); val recovered = File(dir, "recovered"); recovered.mkdirs()
        val store = store(source)
        store.start(devices, 100)
        store.append(mapOf(tagA to point(1.0, 110)))
        store.append(mapOf(tagA to point(2.0, 120)))
        val live = source.listFiles()!!.single()
        val copy = File(recovered, live.name)
        copy.writeBytes(live.readBytes() + "[\"incomplete".toByteArray())
        store.finish(130)
        val reopened = store(recovered)
        val summary = reopened.list().single()
        assertTrue(summary.interrupted)
        assertEquals(2, summary.pointCount)
        assertEquals(listOf(1.0, 2.0), reopened.load(summary.id).trails.getValue(tagA).map { it.x })
    }

    @Test fun emptyRecordingCreatesNoHistoryEntry() = directory { dir ->
        val store = store(dir)
        store.start(devices, 100)
        assertNull(store.finish(200))
        assertTrue(store(dir).list().isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun pointLimitSavesExistingSamplesWithoutOverwritingAnotherSession() = directory { dir ->
        val store = store(dir, maxPoints = 2)
        store.start(devices, 100)
        store.append(mapOf(tagA to point(1.0, 110)))
        store.append(mapOf(tagA to point(2.0, 120)))
        try {
            store.append(mapOf(tagA to point(3.0, 130)))
            fail("Expected the explicit recording limit")
        } catch (e: IOException) { assertTrue(e.message!!.contains("saved")) }
        assertNull(store.finish(140))
        val saved = store.list().single()
        assertEquals(2, saved.pointCount)
        assertEquals(listOf(1.0, 2.0), store.load(saved.id).trails.getValue(tagA).map { it.x })
    }

    @Test fun storageAndSessionLimitsRequireExplicitDeletion() = directory { dir ->
        val store = store(dir, maxSessions = 1)
        store.start(devices, 100); store.append(mapOf(tagA to point(1.0, 110)))
        val saved = store.finish(120)!!
        try { store.start(devices, 200); fail("Must not overwrite a saved route") }
        catch (e: IOException) { assertTrue(e.message!!.contains("Delete")) }
        assertEquals(listOf(saved), store.list())
        try { store.delete("../outside"); fail("Must reject arbitrary paths") }
        catch (_: IllegalArgumentException) { }
        store.delete(saved.id)
        assertTrue(store.list().isEmpty())
        store.start(devices, 300); store.finish(310)
        val tiny = store(File(dir, "tiny"), maxBytes = 1024)
        try { tiny.start(devices, 400); fail("Must respect storage quota") }
        catch (_: IOException) { }
        assertTrue(tiny.list().isEmpty())
    }

    @Test fun savedMapFitsWholeRouteAndUsesSameProjectionAsLiveMarkers() = directory { dir ->
        val store = store(dir)
        store.start(devices, 100)
        listOf(1.0, 80.0, -20.0, 2.0).forEachIndexed { i, x -> store.append(mapOf(tagA to point(x, 110L + i))) }
        val saved = store.load(store.finish(120)!!.id)
        val fit = mapFitPoints(saved.devices, saved.positions, saved.trails, true)
        assertEquals(-20.0, fit.minOf { it.first }, 0.0)
        assertEquals(80.0, fit.maxOf { it.first }, 0.0)
        assertEquals(4.0, mapFitPoints(saved.devices, saved.positions, saved.trails, false).maxOf { it.first }, 0.0)
        for (height in listOf(320f, 1400f)) {
            val projection = MapProjection(-30.0, -10.0, 3.0, height, 32f, 0f, 0f)
            val end = saved.trails.getValue(tagA).last()
            val marker = saved.positions.getValue(tagA)
            assertEquals(projection.x(end.x), projection.x(marker.x), 0f)
            assertEquals(projection.y(end.y), projection.y(marker.y), 0f)
        }
    }

    @Test fun damagedInterruptedHeaderDoesNotBlockOtherSavedPaths() = directory { dir ->
        val bad = File(dir, "broken.recording").apply { writeText("{partial") }
        val store = store(dir)
        assertFalse(bad.exists())
        assertEquals("{partial", File(dir, "broken.damaged").readText())
        store.start(devices, 100); store.append(mapOf(tagA to point(1.0, 110)))
        assertEquals(1, store.load(store.finish(120)!!.id).summary.pointCount)
    }

    @Test fun byteQuotaAutomaticallySavesBeforeStorageIsFull() = directory { dir ->
        val store = store(dir, maxBytes = 6000)
        store.start(devices, 100)
        var accepted = 0
        try {
            repeat(1000) { i ->
                store.append(mapOf(tagA to point(i.toDouble(), 110L + i)))
                accepted++
            }
            fail("Expected byte quota to stop recording")
        } catch (_: IOException) { }
        assertTrue(accepted > 0)
        val saved = store.list().single()
        assertEquals(accepted, saved.pointCount)
        assertEquals(accepted, store.load(saved.id).trails.getValue(tagA).size)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 6000)
    }

    @Test fun corruptSavedPointIsReportedInsteadOfShowingAnIncompleteRoute() = directory { dir ->
        val store = store(dir)
        store.start(devices, 100); store.append(mapOf(tagA to point(1.0, 110)))
        val saved = store.finish(120)!!
        val file = dir.listFiles()!!.single()
        file.writeText(file.readLines().mapIndexed { i, line -> if (i == 1) "corrupt point" else line }.joinToString("\n") + "\n")
        try { store.load(saved.id); fail("Must report damaged data") }
        catch (e: IOException) { assertTrue(e.message!!.contains("incomplete or damaged")) }
    }
}
