package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class AutomaticPathHistoryTest {
    private fun time(s: String) = Instant.parse(s).toEpochMilli()
    private val tagA = "AA:BB:CC:DD:EE:01"
    private val tagB = "AA:BB:CC:DD:EE:02"
    private val devices = listOf(DeviceRecord("AA:BB:CC:DD:EE:11"),
        DeviceRecord("AA:BB:CC:DD:EE:12", x = 4.0),
        DeviceRecord("AA:BB:CC:DD:EE:13", y = 4.0), DeviceRecord(tagA, role = "tag"))
    private fun point(x: Double, at: Long, split: Boolean = false) = TrailPoint(x, 1.25, at, split)
    private fun directory(check: (File) -> Unit) {
        val dir = Files.createTempDirectory("automatic-path-test").toFile()
        try { check(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun fullRateRecordingAndRotationRequireNoStartOrSaveAndLoseNoSamples() = directory { dir ->
        var now = time("2026-11-07T06:00:00Z")
        val store = PathSessionStore(dir, maxPoints = 3, clock = { now }, minFreeBytes = 0)
        val live = TagTrailStore()
        repeat(101) { i ->
            now += 10 // 100 updates/s proves that no one-second sampling filter was added.
            val positions = mapOf(tagA to Position(i / 10.0, 1.25, 0.0, 0.0, emptyList()),
                tagB to Position(-i / 10.0, 1.25, 0.0, 0.0, emptyList()))
            val evidence = positions.mapValues { "$i" }
            store.record(devices, live.update(positions, evidence, now), now)
            store.record(devices, live.update(positions, evidence, now), now) // unchanged evidence
        }
        val current = store.list()
        assertEquals(202, current.sumOf { it.pointCount })
        assertTrue(current.size > 50) // The former 50-session cap must not stop automatic history.
        assertEquals(1, current.count { it.active })
        val loaded = current.flatMap { store.load(it.id).trails.entries.flatMap { (tag, points) -> points.map { tag to it } } }
        assertEquals(202, loaded.size)
        for (tag in listOf(tagA, tagB)) {
            val points = loaded.filter { it.first == tag }.map { it.second }.sortedBy { it.receivedAtMs }
            assertEquals(101, points.size)
            points.forEachIndexed { i, p ->
                assertEquals((if (tag == tagA) i else -i) / 10.0, p.x, 0.0)
                assertEquals(1.25, p.y, 0.0)
                assertEquals(time("2026-11-07T06:00:00Z") + (i + 1) * 10, p.receivedAtMs)
            }
        }
        store.seal() // App shutdown, not a user save action.
        val reopened = PathSessionStore(dir, clock = { now }, minFreeBytes = 0)
        assertEquals(202, reopened.list().sumOf { it.pointCount })
        assertEquals(202, reopened.list().sumOf { reopened.load(it.id).trails.values.sumOf { points -> points.size } })
    }

    @Test fun activePathsCanBeViewedWithoutStoppingOrChangingEarlierSnapshots() = directory { dir ->
        val now = time("2026-11-07T06:00:00Z")
        val store = PathSessionStore(dir, clock = { now }, minFreeBytes = 0)
        store.record(devices, mapOf(tagA to point(1.0, now)), now)
        val summary = store.list().single()
        assertTrue(summary.active)
        val before = store.load(summary.id)
        store.record(devices, mapOf(tagA to point(2.0, now + 50)), now + 50)
        assertEquals(1, before.summary.pointCount)
        assertEquals(1.0, before.positions.getValue(tagA).x, 0.0)
        val after = store.load(summary.id)
        assertEquals(2, after.summary.pointCount)
        assertEquals(2.0, after.positions.getValue(tagA).x, 0.0)
        assertTrue(store.isRecording())
        store.seal()
    }

    @Test fun expiryUsesOneCalendarMonthIncludingShortMonthsAndLeapYears() {
        assertEquals(time("2026-02-28T12:00:00Z"), PathSessionStore.expiresAt(time("2026-01-31T12:00:00Z")))
        assertEquals(time("2028-02-29T12:00:00Z"), PathSessionStore.expiresAt(time("2028-01-31T12:00:00Z")))
        assertEquals(time("2026-04-30T12:00:00Z"), PathSessionStore.expiresAt(time("2026-03-31T12:00:00Z")))
        assertEquals(time("2027-01-31T12:00:00Z"), PathSessionStore.expiresAt(time("2026-12-31T12:00:00Z")))
    }

    @Test fun startupAndPeriodicCleanupRemoveOnlyExpiredPathsAtTheBoundary() = directory { dir ->
        var now = time("2026-01-10T12:00:00Z")
        val store = PathSessionStore(dir, clock = { now }, minFreeBytes = 0)
        store.record(devices, mapOf(tagA to point(1.0, now)), now); val old = store.seal()!!
        now = time("2026-01-11T12:00:00Z")
        store.record(devices, mapOf(tagA to point(2.0, now)), now); val young = store.seal()!!
        now = time("2026-02-10T11:59:59.999Z")
        store.maintenance(now)
        assertEquals(2, store.list().size)
        now++
        store.maintenance(now)
        assertEquals(listOf(young.id), store.list().map { it.id })
        assertFalse(File(dir, "${old.id}.jsonl").exists())
        now = time("2026-02-11T12:00:00Z")
        assertTrue(PathSessionStore(dir, clock = { now }, minFreeBytes = 0).list().isEmpty())
    }

    @Test fun backgroundCleanupWorksWithoutStartingARecorderAndKeepsOtherFiles() = directory { root ->
        val dir = File(root, "paths")
        val old = time("2026-01-10T12:00:00Z")
        val store = PathSessionStore(dir, clock = { old }, minFreeBytes = 0)
        store.record(devices, mapOf(tagA to point(1.0, old)), old); store.seal()
        val csv = File(root, "uwb_session.jsonl").apply { writeText("existing CSV data") }
        val notes = File(dir, "notes.txt").apply { writeText("keep") }
        val orphan = File(dir, "orphan.recording").apply { writeText("old incomplete write"); setLastModified(old) }
        PathSessionStore.deleteExpiredFiles(dir, time("2026-02-10T12:00:00Z"))
        assertEquals(listOf(notes), dir.listFiles()!!.toList())
        assertFalse(orphan.exists())
        assertEquals("existing CSV data", csv.readText())
    }

    @Test fun backgroundCleanupNeverDeletesAnOpenWriterThenMaintenanceRotatesIt() = directory { dir ->
        var now = time("2026-01-10T12:00:00Z")
        val store = PathSessionStore(dir, clock = { now }, minFreeBytes = 0)
        store.record(devices, mapOf(tagA to point(1.0, now)), now)
        val active = dir.listFiles()!!.single().apply { setLastModified(now) }
        now = time("2026-02-11T12:00:00Z")
        PathSessionStore.deleteExpiredFiles(dir, now)
        assertTrue(active.isFile)
        store.record(devices, mapOf(tagA to point(2.0, now)), now)
        val current = store.list().single()
        assertEquals(1, current.pointCount)
        assertEquals(2.0, store.load(current.id).positions.getValue(tagA).x, 0.0)
        store.seal()
    }

    @Test fun dayRolloverAndConfigurationChangesSaveAndResumeAutomatically() = directory { dir ->
        var now = time("2026-11-07T23:59:59Z")
        val store = PathSessionStore(dir, clock = { now }, minFreeBytes = 0)
        store.record(devices, mapOf(tagA to point(1.0, now)), now)
        now += 2000
        store.record(devices, mapOf(tagA to point(2.0, now, true)), now)
        assertEquals(2, store.list().size)
        store.seal() // Device configuration is being changed.
        val moved = devices.map { if (it.role == "anchor") it.copy(x = it.x + 10) else it }
        now += 1000
        store.record(moved, mapOf(tagA to point(12.0, now, true)), now)
        val paths = store.list().map { store.load(it.id) }
        assertEquals(3, paths.size)
        assertEquals(moved, paths.first().devices)
        assertEquals(devices, paths.last().devices)
        assertTrue(paths.all { it.trails.getValue(tagA).first().startsSegment })
        store.seal()
    }

    @Test fun storageFailureKeepsNewerPathsAndRecordingCanResumeAfterAutomaticExpiry() = directory { dir ->
        var now = time("2026-01-10T12:00:00Z")
        val store = PathSessionStore(dir, maxPoints = 3, maxBytes = 6000, clock = { now }, minFreeBytes = 0)
        var accepted = 0
        try {
            repeat(100) { i ->
                store.record(devices, mapOf(tagA to point(i.toDouble(), now)), now)
                accepted++
            }
            fail("Expected the test storage quota")
        } catch (_: java.io.IOException) { }
        assertTrue(accepted > 0)
        assertEquals(accepted, store.list().sumOf { it.pointCount })
        now = time("2026-03-10T12:00:00Z")
        store.maintenance(now)
        assertTrue(store.list().isEmpty())
        store.record(devices, mapOf(tagA to point(1000.0, now)), now)
        assertEquals(1000.0, store.load(store.list().single().id).positions.getValue(tagA).x, 0.0)
        store.seal()
    }
}
