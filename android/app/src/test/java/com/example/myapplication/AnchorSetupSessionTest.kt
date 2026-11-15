package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.math.hypot

class AnchorSetupSessionTest {
    private val anchors = listOf(
        DeviceRecord("02:00:00:00:00:01", "192.168.1.1", "anchor", 0.0, 0.0),
        DeviceRecord("02:00:00:00:00:02", "192.168.1.2", "anchor", 4.0, 0.0),
        DeviceRecord("02:00:00:00:00:03", "192.168.1.3", "anchor", 3.0, 2.0))
    private val candidate = DeviceRecord("02:00:00:00:00:04", "192.168.1.4", "anchor", 99.0, 88.0)

    private inner class Boards(private val references: List<DeviceRecord> = anchors) : AnchorSetupTransport {
        var time = 0L
        var stamp = 0L
        var stale = false
        var duplicates = false
        var failStart = ""
        var failRead = false
        var oldFirmware = false
        var wrongIdentity = false
        var offlineReference = ""
        var onRange: (() -> Unit)? = null
        var generation = 0
        val leases = mutableMapOf<String, JSONObject>()
        val commands = mutableListOf<Pair<String, String>>()
        val assignedRoles = mutableMapOf<String, String>()
        override fun post(device: DeviceRecord, body: JSONObject) {
            val action = body.getString("action")
            commands += device.mac to action
            when (action) {
                "begin" -> {
                    assignedRoles[device.mac] = body.getString("role")
                    leases[device.mac] = body
                    if (device.mac == candidate.mac) generation++
                    if (device.mac == failStart) throw IOException("Start acknowledgement lost")
                }
                "end" -> leases.remove(device.mac)
                "renew" -> check(leases.containsKey(device.mac))
                else -> fail("Preview must never commit coordinates")
            }
        }
        override fun read(device: DeviceRecord): JSONObject {
            if (device.mac == offlineReference) throw IOException("Reference offline")
            val json = JSONObject().put("mac", if (wrongIdentity) "02:00:00:FF:FF:FF" else device.mac)
            if (!oldFirmware) json.put("setup_state", "coordinates")
            val lease = leases[device.mac] ?: return json
            if (device.mac != candidate.mac) return json
            if (failRead) throw IOException("Connection lost")
            onRange?.invoke()
            val peers = lease.getString("peers").split(",").toSet()
            val array = JSONArray()
            if (!duplicates) stamp++
            references.filter { it.mac in peers }.forEach {
                array.put(JSONObject().put("peer", it.mac).put("timestamp_ms", stamp)
                    .put("age_ms", if (stale) 4000 else 0)
                    .put("distance_m", hypot(it.x - 2.0, it.y - 3.0)))
            }
            return json.put("setup_id", lease.getString("id")).put("setup_ready", true)
                .put("setup_generation", generation).put("role", "tag").put("valid", true).put("ranges", array)
        }
        fun session() = AnchorSetupSession(this, { time }, { time += it })
    }

    @Test fun firstAnchorRequiresNoNetworkAndDoesNotChangeTheDraft() {
        val boards = Boards()
        assertEquals(0.0 to 0.0, boards.session().run(candidate, emptyList()) {})
        assertTrue(boards.commands.isEmpty())
        assertEquals(99.0, candidate.x, 0.0)
    }

    @Test fun fourthAnchorUsesAllReferencesAndRestoresEveryTemporaryRole() {
        val boards = Boards()
        val result = boards.session().run(candidate, anchors) {}
        assertEquals(2.0, result.first, 1e-8); assertEquals(3.0, result.second, 1e-8)
        assertTrue(boards.leases.isEmpty())
        (anchors + candidate).forEach { assertTrue(boards.commands.contains(it.mac to "end")) }
    }

    @Test fun tagPreviewUsesTheSameRangingSessionAndReturnsToTheSelectedTagRole() {
        val boards = Boards()
        val tag = candidate.copy(role = "tag")
        val result = boards.session().run(tag, anchors) {}
        assertEquals(2.0, result.first, 1e-8); assertEquals(3.0, result.second, 1e-8)
        assertEquals("tag", boards.assignedRoles[tag.mac])
        anchors.forEach { assertEquals("anchor", boards.assignedRoles[it.mac]) }
        assertTrue(boards.leases.isEmpty())
        assertEquals(99.0, tag.x, 0.0); assertEquals(88.0, tag.y, 0.0)
    }

    @Test fun tagsWithFewerThanThreeReferencesFailBeforeAnyDeviceIsChanged() {
        for (count in 0..2) {
            val boards = Boards()
            try { boards.session().run(candidate.copy(role = "tag"), anchors.take(count)) {}; fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            assertTrue(boards.commands.isEmpty())
        }
    }

    @Test fun tagCancellationRestoresLeasesAndKeepsManualCoordinatesAvailable() {
        val boards = Boards()
        val session = boards.session()
        boards.onRange = { session.cancel() }
        val tag = candidate.copy(role = "tag")
        try { session.run(tag, anchors) {}; fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(boards.leases.isEmpty())
        assertEquals("tag", tag.role)
        assertEquals(99.0, tag.x, 0.0)
    }

    @Test fun lostStartAcknowledgementStillEndsThePossiblyStartedSession() {
        val boards = Boards().apply { failStart = candidate.mac }
        try { boards.session().run(candidate, anchors) {}; fail("Expected failure") } catch (_: IOException) { }
        assertTrue(boards.leases.isEmpty())
        assertTrue(boards.commands.contains(candidate.mac to "end"))
    }

    @Test fun connectionFailureCleansUpAndAllowsANewAttempt() {
        val boards = Boards().apply { failRead = true }
        try { boards.session().run(candidate, anchors) {}; fail("Expected failure") } catch (_: IOException) { }
        assertTrue(boards.leases.isEmpty())
        boards.failRead = false
        assertEquals(2.0, boards.session().run(candidate, anchors) {}.first, 1e-8)
    }

    @Test fun cancellationRestoresAllRolesAndLeavesManualDraftValuesIntact() {
        val boards = Boards()
        val session = boards.session()
        boards.onRange = { session.cancel() }
        try { session.run(candidate, anchors) {}; fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(boards.leases.isEmpty())
        assertEquals(99.0, candidate.x, 0.0); assertEquals(88.0, candidate.y, 0.0)
    }

    @Test fun timeoutRestoresEveryStartedDevice() {
        val boards = Boards()
        boards.onRange = { boards.time = 60_001 }
        try { boards.session().run(candidate, anchors) {}; fail("Expected timeout") } catch (_: IOException) { }
        assertTrue(boards.leases.isEmpty())
    }

    @Test fun unsupportedFirmwareAndWrongIdentityFailBeforeAnyRoleSwitch() {
        listOf(true, false).forEach { old ->
            val boards = Boards().apply { oldFirmware = old; wrongIdentity = !old }
            try { boards.session().run(candidate, anchors) {}; fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            assertTrue(boards.commands.isEmpty())
        }
    }

    @Test fun unavailableAdditionalReferenceDoesNotDiscardThreeValidReferences() {
        val extra = candidate.copy(mac = "02:00:00:00:00:05", ip = "192.168.1.5", x = 8.0, y = 8.0)
        val references = anchors + extra
        val boards = Boards(references).apply { offlineReference = extra.mac }
        assertEquals(2.0, boards.session().run(candidate, references) {}.first, 1e-8)
        assertTrue(boards.leases.isEmpty())
    }

    @Test fun staleOrDuplicateMeasurementsCannotCompleteAnAssignment() {
        listOf(true, false).forEach { stale ->
            val boards = Boards().apply { this.stale = stale; duplicates = !stale }
            try { boards.session().run(candidate, anchors) {}; fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
            assertTrue(boards.leases.isEmpty())
        }
    }

    @Test fun moreThanEightReferencesAreRangedInTargetedBatches() {
        val references = anchors + (5..10).map { index ->
            DeviceRecord("02:00:00:00:00:%02X".format(index), "192.168.1.$index", "anchor", index.toDouble(), index * 0.5)
        }
        val boards = Boards(references)
        val result = boards.session().run(candidate, references) {}
        assertEquals(2.0, result.first, 1e-8); assertEquals(3.0, result.second, 1e-8)
        assertEquals(2, boards.commands.count { it == candidate.mac to "begin" })
        assertTrue(boards.leases.isEmpty())
    }
}
