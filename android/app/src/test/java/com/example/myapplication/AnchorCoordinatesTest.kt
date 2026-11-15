package com.example.myapplication

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class AnchorCoordinatesTest {
    private fun anchor(index: Int, x: Double, y: Double, z: Double = 0.0) =
        DeviceRecord("02:00:00:00:00:%02X".format(index), "192.168.1.${index + 10}", "anchor", x, y, z)
    private val a = anchor(1, 0.0, 0.0)
    private val b = anchor(2, 4.0, 0.0)
    private val c = anchor(3, 3.0, 2.0)
    private fun ranges(anchors: List<DeviceRecord>, x: Double, y: Double, z: Double = 0.0) =
        anchors.associate { it.mac to hypot(hypot(x - it.x, y - it.y), z - it.z) }
    private fun fails(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun firstFourAdditionsUseTheEstablishedFrameWithoutChangingReferences() {
        assertEquals(0.0 to 0.0, assignAnchorCoordinates(emptyList(), emptyMap(), 0.0))
        assertEquals(4.0 to 0.0, assignAnchorCoordinates(listOf(a), ranges(listOf(a), 4.0, 0.0), 0.0))
        val third = assignAnchorCoordinates(listOf(a, b), ranges(listOf(a, b), 3.0, 2.0), 0.0)
        assertEquals(3.0, third.first, 1e-8); assertEquals(2.0, third.second, 1e-8)
        val original = listOf(a, b, c).toList()
        val fourth = assignAnchorCoordinates(original, ranges(original, 2.0, 3.0), 0.0)
        assertEquals(2.0, fourth.first, 1e-8); assertEquals(3.0, fourth.second, 1e-8)
        assertEquals(listOf(a, b, c), original)
    }

    @Test fun blankHeightDefaultsToZeroButInvalidNonemptyHeightIsRejected() {
        assertEquals(0.0, coordinateZ("")); assertEquals(0.0, coordinateZ("  "))
        assertEquals(2.5, coordinateZ("2.5"))
        listOf("abc", "NaN", "Infinity", "1e999").forEach { assertNull(coordinateZ(it)) }
    }

    @Test fun heightsAreCompensatedForBothBaselineAndTrilateration() {
        assertEquals(4.0 to 0.0, assignAnchorCoordinates(listOf(a), mapOf(a.mac to 5.0), 3.0))
        val anchors = listOf(a.copy(z = 1.0), b.copy(z = 2.0), c.copy(z = 3.0))
        val position = assignAnchorCoordinates(anchors, ranges(anchors, 2.0, 3.0, 4.0), 4.0)
        assertEquals(2.0, position.first, 1e-8); assertEquals(3.0, position.second, 1e-8)
        fails { assignAnchorCoordinates(listOf(a), mapOf(a.mac to 1.0), 2.0) }
    }

    @Test fun invalidTrianglesAndNonPositiveThirdCoordinatesAreRejected() {
        fails { assignAnchorCoordinates(listOf(a), mapOf(a.mac to 0.0), 0.0) }
        fails { assignAnchorCoordinates(listOf(a, b), mapOf(a.mac to 1.0, b.mac to 1.0), 0.0) }
        fails { assignAnchorCoordinates(listOf(a, b), ranges(listOf(a, b), -1.0, 2.0), 0.0) }
        fails { assignAnchorCoordinates(listOf(a, b), ranges(listOf(a, b), 2.0, 0.0), 0.0) }
    }

    @Test fun missingInconsistentNegativeAndOutOfBoundsPositionsAreRejected() {
        val anchors = listOf(a, b, c)
        fails { assignAnchorCoordinates(anchors, ranges(listOf(a, b), 2.0, 3.0), 0.0) }
        fails { assignAnchorCoordinates(anchors, ranges(anchors, 2.0, 3.0) + (c.mac to 8.0), 0.0) }
        fails { assignAnchorCoordinates(anchors, ranges(anchors, -1.0, 3.0), 0.0) }
        fails { assignAnchorCoordinates(listOf(a), mapOf(a.mac to 1000.1), 0.0) }
    }

    @Test fun collinearAndNearlyCollinearReferencesAreRejected() {
        listOf(anchor(3, 3.0, 0.0), anchor(3, 3.0, 0.000001)).forEach { bad ->
            assertFalse(hasAnchorGeometry(listOf(a, b, bad)))
            fails { assignAnchorCoordinates(listOf(a, b, bad), ranges(listOf(a, b, bad), 2.0, 3.0), 0.0) }
        }
    }

    @Test fun additionalRangesAndExistingManualFramesAreUsed() {
        val anchors = listOf(a, b, c, anchor(4, 8.0, 7.0)).map { it.copy(x = it.x + 10, y = it.y + 10) }
        val solved = assignAnchorCoordinates(anchors, ranges(anchors, 12.0, 13.0), 0.0)
        assertEquals(12.0, solved.first, 1e-8); assertEquals(13.0, solved.second, 1e-8)
        fails { assignAnchorCoordinates(anchors.take(2), ranges(anchors.take(2), 12.0, 13.0), 0.0) }
        fails { assignAnchorCoordinates(anchors, ranges(anchors, 12.0, 13.0) + (anchors.last().mac to 30.0), 0.0) }
    }

    @Test fun manualValuesAndOriginSurviveStorageAndMissingZLoadsAsZero() {
        val manual = listOf(a, b.copy(x = -4.25, z = 1.5))
        assertEquals(manual, decodeDevices(encodeDevices(manual)))
        val raw = """[{"mac":"${a.mac}","role":"anchor","x":0,"y":0}]"""
        assertEquals(0.0, decodeDevices(raw).single().z, 0.0)
    }

    @Test fun referenceExclusionUsesIdentityInsteadOfAnotherDevicesStaleIp() {
        val candidate = c.copy(ip = a.ip)
        assertEquals(listOf(a, b), anchorReferences(listOf(a, b, c), candidate))
    }

    @Test fun savedCoordinatesAreSentOnlyWhenMissingOrDifferentAndNeverDuringRanging() {
        val sample = JSONObject().put("setup_state", "coordinates").put("configured", false)
        assertTrue(needsDeviceConfiguration(a, sample))
        sample.put("configured", true).put("configured_role", "anchor").put("coordinates_valid", true)
            .put("x", 0).put("y", 0).put("z", 0)
        assertFalse(needsDeviceConfiguration(a, sample))
        assertTrue(needsDeviceConfiguration(b, sample))
        sample.put("setup_state", "ranging")
        assertFalse(needsDeviceConfiguration(b, sample))
        assertFalse(needsDeviceConfiguration(a, JSONObject()))
    }

    @Test fun tagsUseThreeSavedAnchorsAndNeverCreateAnOriginOrAnAxis() {
        for (count in 0..2) fails {
            assignDeviceCoordinates("tag", listOf(a, b).take(count), emptyMap(), 0.0)
        }
        val anchors = listOf(a, b, c).map { it.copy(x = it.x + 10, y = it.y + 5) }
        val position = assignDeviceCoordinates("tag", anchors, ranges(anchors, 12.0, 8.0), 0.0)
        assertEquals(12.0, position.first, 1e-8); assertEquals(8.0, position.second, 1e-8)
    }

    @Test fun tagHeightAndCoordinatesAreSynchronizedAndPersistedLikeAnchorCoordinates() {
        val tag = c.copy(role = "tag", z = 1.5)
        val sample = JSONObject().put("setup_state", "ready").put("configured", true)
            .put("configured_role", "tag").put("coordinates_valid", false)
            .put("x", 0).put("y", 0).put("z", 0)
        assertTrue(needsDeviceConfiguration(tag, sample))
        sample.put("x", tag.x).put("y", tag.y).put("z", tag.z)
        // Older firmware can store Tag coordinates without setting this flag.
        assertFalse(needsDeviceConfiguration(tag, sample))
        sample.put("coordinates_valid", true)
        assertFalse(needsDeviceConfiguration(tag, sample))
        assertTrue(needsDeviceConfiguration(tag.copy(z = 2.0), sample))
        assertTrue(needsDeviceConfiguration(tag.copy(x = 1.0), sample))
        assertEquals(tag, decodeDevices(encodeDevices(listOf(tag))).single())
    }

    @Test fun tagReferencesExcludeAllOtherTagsAndCompensateForEnteredHeight() {
        val tag = c.copy(mac = "02:00:00:00:00:07", role = "tag", z = 4.0)
        val anchors = listOf(a.copy(z = 1.0), b.copy(z = 2.0), c.copy(z = 3.0))
        val references = anchorReferences(anchors + tag + tag.copy(mac = "02:00:00:00:00:08"), tag)
        assertEquals(anchors, references)
        val position = assignDeviceCoordinates("tag", references, ranges(references, 2.0, 3.0, tag.z), tag.z)
        assertEquals(2.0, position.first, 1e-8); assertEquals(3.0, position.second, 1e-8)
    }
}
