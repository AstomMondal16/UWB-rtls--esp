package com.example.myapplication

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class DeviceRecord(
    val mac: String = "",
    val ip: String = "",
    val role: String = "anchor",
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yawOffset: Double = 0.0
) {
    val key: String get() = mac.ifBlank { ip }
}

data class FoundDevice(val mac: String, val ip: String, val role: String,
                       val lastSeenAtMs: Long = System.currentTimeMillis(), val apiScheme: String = "")
data class Position(val x: Double, val y: Double, val z: Double, val error: Double, val anchors: List<String>)
data class RangeReading(val distance: Double, val rxPower: Double?, val timeMs: Long, val measurementId: String = "")
data class DeviceState(val online: Boolean, val sample: JSONObject, val error: String = "")

data class DashboardSnapshot(
    val devices: List<DeviceRecord> = emptyList(),
    val discovered: List<FoundDevice> = emptyList(),
    val states: Map<String, DeviceState> = emptyMap(),
    val positions: Map<String, Position> = emptyMap(),
    val status: String = "READY",
    val collecting: Boolean = false,
    val collected: Int = 0,
    val calibrationProgress: String = "",
    val streamingPending: Boolean = false,
    val recordingStartCount: Int = 0,
    val recordingStartedAtMs: Long? = null,
    val trails: Map<String, List<TrailPoint>> = emptyMap()
)

fun normalizeMac(value: String): String = value.trim().replace("-", ":").uppercase()
    .takeIf { Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(it) }.orEmpty()

fun validIp(value: String): Boolean = try {
    val parts = value.trim().split(".")
    parts.size == 4 && parts.all { it.toInt() in 0..255 }
} catch (_: NumberFormatException) { false }

fun cleanDevice(device: DeviceRecord): DeviceRecord {
    val mac = normalizeMac(device.mac)
    val ip = device.ip.trim()
    require(mac.isNotBlank() || validIp(ip)) { "Enter a valid MAC address or IPv4 address" }
    require(device.role == "anchor" || device.role == "tag") { "Role must be anchor or tag" }
    require(listOf(device.x, device.y, device.z, device.yawOffset).all { it.isFinite() }) {
        "Coordinates must be finite numbers"
    }
    return device.copy(mac = mac, ip = ip)
}

fun encodeDevices(devices: List<DeviceRecord>): String = JSONArray().apply {
    devices.forEach { d -> put(JSONObject().apply {
        put("mac", d.mac); put("ip", d.ip); put("role", d.role)
        put("x", d.x); put("y", d.y); put("z", d.z); put("yaw_offset", d.yawOffset)
    }) }
}.toString()

fun decodeDevices(raw: String): List<DeviceRecord> = try {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        runCatching { cleanDevice(DeviceRecord(o.optString("mac"), o.optString("ip"),
            o.optString("role", "anchor"), o.optDouble("x"), o.optDouble("y"),
            o.optDouble("z"), o.optDouble("yaw_offset"))) }.getOrNull()
    }
} catch (_: Exception) { emptyList() }

fun trilaterate2d(measurements: List<Triple<Double, Double, Double>>): Triple<Double, Double, Double>? {
    if (measurements.size < 3) return null
    val (x0, y0, d0) = measurements.first()
    val rows = measurements.drop(1).map { (x, y, d) ->
        Triple(2 * (x - x0), 2 * (y - y0), d0 * d0 - d * d + x * x + y * y - x0 * x0 - y0 * y0)
    }
    val aa = rows.sumOf { it.first * it.first }
    val ab = rows.sumOf { it.first * it.second }
    val bb = rows.sumOf { it.second * it.second }
    val ac = rows.sumOf { it.first * it.third }
    val bc = rows.sumOf { it.second * it.third }
    val determinant = aa * bb - ab * ab
    if (abs(determinant) < 1e-9) return null
    val px = (ac * bb - bc * ab) / determinant
    val py = (aa * bc - ab * ac) / determinant
    val error = sqrt(measurements.sumOf { (x, y, d) -> (hypot(px - x, py - y) - d) * (hypot(px - x, py - y) - d) } / measurements.size)
    return Triple(px, py, error)
}

fun solvePositions(devices: List<DeviceRecord>, ranges: Map<Pair<String, String>, RangeReading>, nowMs: Long): Map<String, Position> {
    val anchors = devices.filter { it.role == "anchor" && it.mac.isNotBlank() }.associateBy { it.mac }
    val result = mutableMapOf<String, Position>()
    ranges.keys.map { it.second }.distinct().forEach { tag ->
        val tagZ = devices.firstOrNull { it.mac == tag && it.role == "tag" }?.z ?: 0.0
        val used = mutableListOf<String>()
        val measurements = ranges.mapNotNull { (pair, reading) ->
            val anchor = anchors[pair.first] ?: return@mapNotNull null
            if (pair.second != tag || nowMs - reading.timeMs > 3000 || !reading.distance.isFinite() || reading.distance <= 0) return@mapNotNull null
            val dz = anchor.z - tagZ
            val horizontalSq = reading.distance * reading.distance - dz * dz
            if (horizontalSq <= 0) return@mapNotNull null
            used += pair.first
            Triple(anchor.x, anchor.y, sqrt(horizontalSq))
        }
        val solved = trilaterate2d(measurements)
        if (solved != null && listOf(solved.first, solved.second, solved.third).all(Double::isFinite)) result[tag] = Position(solved.first, solved.second, tagZ, solved.third, used)
    }
    return result
}
