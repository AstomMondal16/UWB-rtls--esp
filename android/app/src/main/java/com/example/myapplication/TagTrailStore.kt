package com.example.myapplication

data class TrailPoint(val x: Double, val y: Double, val receivedAtMs: Long, val startsSegment: Boolean)

internal const val DEFAULT_LIVE_TRAIL_SECONDS = 120
internal val LIVE_TRAIL_DURATIONS: Map<Int, String> = linkedMapOf(
    5 to "5 seconds", 10 to "10 seconds", 30 to "30 seconds", 60 to "1 minute", 120 to "2 minutes")

internal fun validTrailDuration(seconds: Int): Int =
    seconds.takeIf { it in LIVE_TRAIL_DURATIONS } ?: DEFAULT_LIVE_TRAIL_SECONDS

/** Display filtering never changes the receiver-owned history or the saved path recorder. */
internal fun trailsWithinDuration(trails: Map<String, List<TrailPoint>>, now: Long,
    seconds: Int): Map<String, List<TrailPoint>> {
    val ageLimit = validTrailDuration(seconds) * 1000L
    return trails.mapValues { (_, points) -> points.filter { now - it.receivedAtMs in 0 until ageLimit } }
        .filterValues { it.isNotEmpty() }
}

/** Receiver-owned histories; no interpolation across missing measurements. */
internal class TagTrailStore(private val maxPoints: Int = DEFAULT_LIVE_TRAIL_SECONDS * SamplingRate.MAX_HZ,
                             private val maxAgeMs: Long = DEFAULT_LIVE_TRAIL_SECONDS * 1000L,
                             private val gapMs: Long = 2500) {
    init { require(maxPoints >= 2) }
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
            if (h.points.size > maxPoints) {
                // Thin the live preview, preserving its time span and gaps; added keeps the raw new point.
                val preview = ArrayDeque<TrailPoint>()
                val firstStamp = h.points.minOf { it.receivedAtMs }
                val span = (h.points.maxOf { it.receivedAtMs } - firstStamp).coerceAtLeast(1)
                val bucketWidth = span / (maxPoints / 2) + 1
                var lastBucket = -1L
                var segmentStart = false
                h.points.forEachIndexed { index, point ->
                    segmentStart = segmentStart || point.startsSegment
                    val bucket = maxOf(lastBucket, (point.receivedAtMs - firstStamp) / bucketWidth)
                    if (index == 0 || bucket != lastBucket || index == h.points.lastIndex) {
                        preview.addLast(point.copy(startsSegment = segmentStart))
                        segmentStart = false
                        lastBucket = bucket
                    }
                }
                h.points.clear(); h.points.addAll(preview)
            }
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
