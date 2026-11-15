package com.example.myapplication

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.json.JSONObject

internal fun coordinateZ(text: String): Double? =
    (if (text.isBlank()) 0.0 else text.trim().toDoubleOrNull())?.takeIf(Double::isFinite)

internal fun needsDeviceConfiguration(device: DeviceRecord, sample: JSONObject): Boolean =
    sample.has("setup_state") && sample.optString("setup_state") != "ranging" &&
        (!sample.optBoolean("configured") || sample.optString("configured_role") != device.role ||
            (device.role == "anchor" && !sample.optBoolean("coordinates_valid")) ||
            listOf("x" to device.x, "y" to device.y, "z" to device.z).any { (key, value) ->
                !sample.optDouble(key, Double.NaN).isFinite() || abs(sample.optDouble(key) - value) > 1e-8
            })

internal fun validateDeviceCoordinateReferences(role: String, anchors: List<DeviceRecord>) {
    require(role == "anchor" || role == "tag") { "Select an Anchor or Tag role" }
    if (role == "tag") require(anchors.size >= 3) {
        "Tag auto-assignment needs at least three saved anchors that are not in a straight line. Add anchors first or use manual entry."
    }
    validateAnchorFrame(anchors)
}

internal fun assignDeviceCoordinates(role: String, anchors: List<DeviceRecord>, ranges: Map<String, Double>, z: Double): Pair<Double, Double> {
    validateDeviceCoordinateReferences(role, anchors)
    return assignAnchorCoordinates(anchors, ranges, z)
}

internal fun anchorReferences(devices: List<DeviceRecord>, candidate: DeviceRecord): List<DeviceRecord> =
    devices.filter { it.role == "anchor" && it.key != candidate.key &&
        (candidate.mac.isBlank() || it.mac != candidate.mac) &&
        (candidate.mac.isNotBlank() || candidate.ip.isBlank() || it.ip != candidate.ip) }

internal fun hasAnchorGeometry(anchors: List<DeviceRecord>): Boolean {
    if (anchors.size < 3) return false
    val origin = anchors.first()
    val aa = anchors.drop(1).sumOf { (it.x - origin.x) * (it.x - origin.x) }
    val ab = anchors.drop(1).sumOf { (it.x - origin.x) * (it.y - origin.y) }
    val bb = anchors.drop(1).sumOf { (it.y - origin.y) * (it.y - origin.y) }
    return aa > 0 && bb > 0 && aa * bb - ab * ab > 1e-6 * (aa + bb) * (aa + bb)
}

internal fun validateAnchorFrame(anchors: List<DeviceRecord>) {
    require(anchors.all { listOf(it.x, it.y, it.z).all(Double::isFinite) }) { "Reference coordinates are invalid" }
    if (anchors.isNotEmpty() && anchors.size <= 2) {
        require(abs(anchors[0].x) < 1e-9 && abs(anchors[0].y) < 1e-9) {
            "Automatic setup needs an origin at (0, 0), or three non-collinear references. Use manual entry."
        }
        if (anchors.size == 2) require(anchors[1].x > 0 && abs(anchors[1].y) < 1e-9) {
            "The second reference must define a positive X-axis baseline. Use manual entry."
        }
    }
    if (anchors.size >= 3) require(hasAnchorGeometry(anchors)) { "Saved reference anchors are collinear or too nearly collinear" }
}

internal fun assignAnchorCoordinates(anchors: List<DeviceRecord>, ranges: Map<String, Double>, z: Double): Pair<Double, Double> {
    require(z.isFinite()) { "Enter a valid Z coordinate, or leave it blank for zero" }
    validateAnchorFrame(anchors)
    if (anchors.isEmpty()) return 0.0 to 0.0
    val measured = anchors.mapNotNull { a -> ranges[a.mac]?.let { a to it } }
    require(measured.size >= minOf(anchors.size, 3)) { "Insufficient fresh reference ranges; retry or enter coordinates manually" }
    val horizontal = measured.map { (a, distance) ->
        require(distance.isFinite() && distance > 0 && distance <= 1000) { "Invalid reference range" }
        val squared = distance * distance - (a.z - z) * (a.z - z)
        require(squared > 0) { "Range is incompatible with the entered heights" }
        Triple(a.x, a.y, sqrt(squared))
    }
    val result = when (anchors.size) {
        1 -> horizontal.single().third to 0.0
        2 -> {
            val d0 = horizontal[0].third
            val d1 = horizontal[1].third
            val baseline = anchors[1].x
            val x = (d0 * d0 - d1 * d1 + baseline * baseline) / (2 * baseline)
            val ySquared = d0 * d0 - x * x
            require(x > 0 && ySquared > 0) { "The ranges cannot produce positive X and Y; retry or use manual entry" }
            x to sqrt(ySquared)
        }
        else -> {
            require(hasAnchorGeometry(measured.map { it.first })) { "Reference anchors are collinear or too nearly collinear" }
            val solved = requireNotNull(trilaterate2d(horizontal)) { "Could not solve the reference geometry" }
            solved.first to solved.second
        }
    }
    val (x, y) = result
    require(x.isFinite() && y.isFinite() && x in 0.0..1000.0 && y in 0.0..1000.0 &&
        (anchors.size != 1 || x > 0)) { "Calculated coordinates are outside the non-negative 0–1,000 m region" }
    require(measured.all { (a, distance) ->
        val expected = hypot(hypot(x - a.x, y - a.y), z - a.z)
        rangeFits(expected, distance)
    }) { "Reference ranges are inconsistent with the position; retry or enter coordinates manually" }
    return result
}
