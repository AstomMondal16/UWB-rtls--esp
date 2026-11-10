package com.example.myapplication

data class TrailPoint(val x: Double, val y: Double, val receivedAtMs: Long, val startsSegment: Boolean)

/** Receiver-owned histories; no interpolation across missing measurements. */
internal class TagTrailStore(private val maxPoints: Int = 128, private val maxAgeMs: Long = 120_000,
                             private val gapMs: Long = 2500) {
    private data class History(val points: ArrayDeque<TrailPoint> = ArrayDeque(),
                               var evidence: String = "", var broken: Boolean = true)
    private val histories = linkedMapOf<String, History>()

    fun clear() = histories.clear()
    fun breakTag(tag: String) { histories[tag]?.broken = true }
    fun update(positions: Map<String, Position>, evidence: Map<String, String>, now: Long): Map<String, TrailPoint> {
        val added = linkedMapOf<String, TrailPoint>()
        histories.filterKeys { it !in positions }.values.forEach { it.broken = true }
        positions.forEach { (tag, p) ->
            if (!listOf(p.x, p.y, p.z, p.error).all(Double::isFinite)) { breakTag(tag); return@forEach }
            val token = evidence[tag] ?: return@forEach
            val h = histories.getOrPut(tag) { History() }
            if (h.evidence == token) return@forEach
            val last = h.points.lastOrNull()
            val point = TrailPoint(p.x, p.y, now, h.broken || last == null || now - last.receivedAtMs > gapMs)
            h.points.addLast(point)
            added[tag] = point
            h.evidence = token; h.broken = false
            while (h.points.size > maxPoints) h.points.removeFirst()
        }
        prune(now)
        while (histories.size > 64) histories.remove(histories.keys.first())
        return added
    }

    fun snapshot(now: Long): Map<String, List<TrailPoint>> {
        prune(now)
        return histories.mapValues { it.value.points.toList() }
    }

    private fun prune(now: Long) {
        histories.values.forEach { h ->
            while (h.points.isNotEmpty() && now - h.points.first().receivedAtMs > maxAgeMs) h.points.removeFirst()
            if (h.points.lastOrNull()?.let { now - it.receivedAtMs > gapMs } != false) h.broken = true
        }
        histories.entries.removeAll { it.value.points.isEmpty() }
    }
}

internal fun mapFitPoints(devices: List<DeviceRecord>, positions: Map<String, Position>,
    trails: Map<String, List<TrailPoint>>, includeTrails: Boolean): List<Pair<Double, Double>> =
    devices.filter { it.role == "anchor" }.map { it.x to it.y } + positions.values.map { it.x to it.y } +
        if (includeTrails) trails.values.flatMap { history -> history.map { it.x to it.y } } else emptyList()

/** Both marker and trail coordinates use this exact projection, at every viewport size. */
internal class MapProjection(private val minX: Double, private val minY: Double, private val scale: Double,
                             private val height: Float, private val margin: Float,
                             private val panX: Float, private val panY: Float) {
    fun x(x: Double): Float = margin + ((x - minX) * scale).toFloat() + panX
    fun y(y: Double): Float = height - margin - ((y - minY) * scale).toFloat() + panY
}
