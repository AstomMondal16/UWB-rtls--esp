package com.example.myapplication

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class TagTrailStoreTest {
    private fun p(x: Double, y: Double = 1.0) = Position(x, y, 0.0, .01, listOf("a", "b", "c"))

    @Test fun separateBoundedHistoriesSkipDuplicateEvidenceAndSplitOnReconnect() {
        val store = TagTrailStore(maxPoints = 3)
        for (i in 0..4) store.update(mapOf("A" to p(i.toDouble()), "B" to p(-i.toDouble())),
            mapOf("A" to "$i", "B" to "$i"), i * 100L)
        var trails = store.snapshot(400)
        assertEquals(listOf(2.0, 3.0, 4.0), trails.getValue("A").map { it.x })
        assertEquals(listOf(-2.0, -3.0, -4.0), trails.getValue("B").map { it.x })
        store.update(mapOf("A" to p(99.0)), mapOf("A" to "4"), 500) // same range evidence
        assertEquals(4.0, store.snapshot(500).getValue("A").last().x, 0.0)
        store.breakTag("A")
        store.update(mapOf("A" to p(6.0)), mapOf("A" to "6"), 600)
        assertTrue(store.snapshot(600).getValue("A").last().startsSegment)
        store.update(mapOf("A" to p(7.0)), mapOf("A" to "7"), 700)
        assertFalse(store.snapshot(700).getValue("A").last().startsSegment)
        store.update(mapOf("B" to p(8.0)), mapOf("B" to "8"), 800)
        assertTrue(store.snapshot(800).getValue("B").last().startsSegment)
        store.update(mapOf("B" to p(9.0)), mapOf("B" to "9"), 4000)
        assertTrue(store.snapshot(4000).getValue("B").last().startsSegment)
        assertTrue(store.snapshot(125000).isEmpty())
    }

    @Test fun invalidAndMissingPositionsBreakLinesAndNeverReachHistory() {
        val store = TagTrailStore()
        store.update(mapOf("A" to p(0.0)), mapOf("A" to "1"), 0)
        store.update(mapOf("A" to p(Double.NaN)), mapOf("A" to "2"), 100)
        store.update(mapOf("A" to p(1.0)), mapOf("A" to "3"), 200)
        assertEquals(2, store.snapshot(200).getValue("A").size)
        assertTrue(store.snapshot(200).getValue("A").last().startsSegment)
        store.update(emptyMap(), emptyMap(), 300)
        store.update(mapOf("A" to p(2.0)), mapOf("A" to "4"), 400)
        assertTrue(store.snapshot(400).getValue("A").last().startsSegment)
    }

    @Test fun longSessionsKeepOnly128PointsPerTag() {
        val store = TagTrailStore()
        for (i in 0 until 10000) {
            val positions = (0..3).associate { "tag-$it" to p(i / 100.0, it.toDouble()) }
            store.update(positions, positions.mapValues { "$i" }, i * 10L)
        }
        val trails = store.snapshot(99990)
        assertEquals(4, trails.size)
        assertTrue(trails.values.all { it.size == 128 })
        assertTrue(trails.values.all { it.last().x == 99.99 })
    }

    @Test fun solvedPositionsAndTrailsUseIdenticalProjectionInDashboardAndFullscreen() {
        val anchors = listOf(DeviceRecord("a", role = "anchor", x = 0.0, y = 0.0),
            DeviceRecord("b", role = "anchor", x = 4.0, y = 0.0),
            DeviceRecord("c", role = "anchor", x = 0.0, y = 4.0))
        val store = TagTrailStore()
        val ranges = anchors.associate { (it.mac to "tag") to RangeReading(hypot(1.0 - it.x, 2.0 - it.y), null, 100, "100") }
        val positions = solvePositions(anchors, ranges, 100)
        val marker = positions.getValue("tag")
        assertEquals(1.0, marker.x, 1e-9); assertEquals(2.0, marker.y, 1e-9)
        store.update(positions, mapOf("tag" to "100"), 100)
        val trail = store.snapshot(100).getValue("tag").last()
        for ((width, height) in listOf(360f to 280f, 1080f to 2392f)) for (zoom in listOf(.45, 1.0, 2.5)) {
            val projection = MapProjection(-1.0, -1.0, (width - 64) / 6 * zoom, height, 32f, 17f, -23f)
            assertEquals(projection.x(marker.x), projection.x(trail.x), 0f)
            assertEquals(projection.y(marker.y), projection.y(trail.y), 0f)
            assertTrue(projection.y(3.0) < projection.y(2.0))
        }
        assertTrue(solvePositions(anchors, ranges, 3200).isEmpty())
    }
}
