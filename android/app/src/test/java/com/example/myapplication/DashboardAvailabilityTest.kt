package com.example.myapplication

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DashboardAvailabilityTest {
    private val anchor = DeviceRecord(mac = "AA:BB:CC:DD:EE:01")
    private val tag = DeviceRecord(mac = "AA:BB:CC:DD:EE:02", role = "tag")
    private fun state(online: Boolean) = DeviceState(online, JSONObject())

    @Test fun noSavedDevicesOrMissingTelemetryUsesCompactLayout() {
        assertTrue(DashboardSnapshot().allDevicesOffline)
        assertTrue(DashboardSnapshot(devices = listOf(anchor, tag)).allDevicesOffline)
    }

    @Test fun allOfflineUsesCompactLayoutDespiteCachedMeasurements() {
        val cached = JSONObject().put("valid", true).put("battery_percent", 90)
        val snapshot = DashboardSnapshot(devices = listOf(anchor, tag), states = mapOf(
            anchor.key to DeviceState(false, cached), tag.key to state(false)))
        assertTrue(snapshot.allDevicesOffline)
    }

    @Test fun aReconnectRestoresLiveLayoutAndTheLastDisconnectCompactsIt() {
        val offline = DashboardSnapshot(devices = listOf(anchor, tag), states = mapOf(
            anchor.key to state(false), tag.key to state(false)))
        val connected = offline.copy(states = offline.states + (tag.key to state(true)))
        assertTrue(offline.allDevicesOffline)
        assertFalse(connected.allDevicesOffline)
        assertTrue(connected.copy(states = connected.states + (tag.key to state(false))).allDevicesOffline)
        // Pausing sensors leaves Wi-Fi/telemetry online and must not collapse the UI.
        val paused = connected.copy(states = mapOf(anchor.key to state(false), tag.key to
            DeviceState(true, JSONObject().put("streaming", false))))
        assertFalse(paused.allDevicesOffline)
    }

    @Test fun discoveryAndStatesFromRemovedDevicesDoNotKeepPanelsExpanded() {
        val snapshot = DashboardSnapshot(devices = listOf(anchor),
            discovered = listOf(FoundDevice(tag.mac, "192.168.1.20", "tag")),
            states = mapOf(anchor.key to state(false), tag.key to state(true)))
        assertTrue(snapshot.allDevicesOffline)
        assertTrue(snapshot.copy(devices = emptyList()).allDevicesOffline)
    }
}
