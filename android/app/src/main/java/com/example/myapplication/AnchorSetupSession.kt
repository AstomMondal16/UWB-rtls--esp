package com.example.myapplication

import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface AnchorSetupTransport {
    fun read(device: DeviceRecord): JSONObject
    fun post(device: DeviceRecord, body: JSONObject)
}

/** Coordinates stay in the editor; this session only leases radio roles and reads ranges. */
internal class AnchorSetupSession(
    private val transport: AnchorSetupTransport,
    private val now: () -> Long,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val cancelled = AtomicBoolean()
    private val leased = ConcurrentHashMap<String, DeviceRecord>()
    private val heartbeatFailure = AtomicReference<Throwable?>()

    fun cancel() { cancelled.set(true) }

    private fun check(deadline: Long) {
        if (cancelled.get()) throw CancellationException("Coordinate assignment cancelled")
        heartbeatFailure.get()?.let { throw IOException("Setup lease could not be renewed; retry", it) }
        if (now() >= deadline) throw IOException("Coordinate ranging timed out; retry or use manual entry")
    }

    private fun command(action: String) = JSONObject().put("action", action).put("id", id)

    private fun begin(device: DeviceRecord, role: String, peers: List<DeviceRecord> = emptyList()) {
        // Include an unacknowledged start in cleanup: the board may have received it.
        leased[device.mac] = device
        transport.post(device, command("begin").put("role", role).put("peers", peers.joinToString(",") { it.mac }))
    }

    private fun cleanup(): String? {
        var failure: String? = null
        leased.values.toList().forEach { device ->
            leased.remove(device.mac)
            runCatching { transport.post(device, command("end")) }.onFailure {
                failure = "Could not confirm cleanup for ${device.mac}; its temporary role expires within 15 seconds"
            }
        }
        return failure
    }

    fun run(candidate: DeviceRecord, anchors: List<DeviceRecord>, progress: (String) -> Unit): Pair<Double, Double> {
        validateDeviceCoordinateReferences(candidate.role, anchors)
        if (anchors.isEmpty()) return assignDeviceCoordinates(candidate.role, anchors, emptyMap(), candidate.z)
        require(anchors.all { it.mac.isNotBlank() }) { "Scan to resolve the saved reference MAC addresses first" }
        require(normalizeMac(candidate.mac).isNotBlank() && validIp(candidate.ip)) { "Enter or scan a valid new device MAC and IP address first" }
        // Validate the frame before changing any device state.
        val deadline = now() + 60_000
        val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "anchor-setup-lease").apply { isDaemon = true }
        }
        timer.scheduleWithFixedDelay({
            leased.values.toList().forEach { device ->
                runCatching { transport.post(device, command("renew")) }.onFailure {
                    if (leased.containsKey(device.mac)) heartbeatFailure.compareAndSet(null, it)
                }
            }
        }, 5, 5, TimeUnit.SECONDS)
        var operationError: Throwable? = null
        try {
            fun read(device: DeviceRecord): JSONObject {
                check(deadline)
                val sample = transport.read(device)
                require(normalizeMac(sample.optString("mac")) == device.mac) { "Device identity mismatch for ${device.mac}" }
                require(sample.has("setup_state")) { "Update firmware on ${device.mac} to support safe coordinate setup, or use manual entry" }
                return sample
            }
            read(candidate)
            val distances = mutableMapOf<String, Double>()
            val batches = anchors.chunked(8)
            batches.forEachIndexed { index, batch ->
                check(deadline)
                progress("Keep the device still. Ranging to reference anchors (${index + 1}/${batches.size})…")
                val available = batch.filter { reference ->
                    if (!validIp(reference.ip)) false else try { read(reference); true }
                    catch (error: Exception) { check(deadline); false }
                }
                if (anchors.size <= 2) require(available.size == anchors.size) { "Required reference anchor is unavailable; retry or use manual entry" }
                if (available.isEmpty()) return@forEachIndexed
                available.forEach { check(deadline); begin(it, "anchor", listOf(candidate)) }
                begin(candidate, "tag", available)
                val samples = available.associate { it.mac to linkedMapOf<Long, Double>() }
                val batchDeadline = minOf(deadline - 1000, now() + (deadline - now()) / (batches.size - index))
                var generation = -1L
                while (now() < batchDeadline && samples.values.any { it.size < 5 }) {
                    check(deadline)
                    val sample = read(candidate)
                    require(sample.optString("setup_id") == id) { "The temporary ranging session ended; retry" }
                    if (sample.optBoolean("setup_ready") && sample.optString("role") == "tag") {
                        val current = sample.optLong("setup_generation", -1)
                        if (generation < 0) generation = current
                        require(current > 0 && generation == current) { "Ranging session changed unexpectedly; retry" }
                        val ranges = sample.optJSONArray("ranges")
                        if (sample.optBoolean("valid") && ranges != null) for (i in 0 until ranges.length()) {
                            val range = ranges.optJSONObject(i) ?: continue
                            val values = samples[normalizeMac(range.optString("peer"))] ?: continue
                            val stamp = range.optLong("timestamp_ms", -1)
                            val age = range.optLong("age_ms", Long.MAX_VALUE)
                            val distance = range.optDouble("distance_m", Double.NaN)
                            if (stamp >= 0 && age in 0..3000 && distance.isFinite() && distance > 0 && distance <= 1000 && values.size < 5)
                                values.putIfAbsent(stamp, distance)
                        }
                    }
                    pause(150)
                }
                samples.forEach { (mac, values) ->
                    if (values.size >= 5) distances[mac] = values.values.sorted()[2]
                }
                cleanup()?.let { throw IOException(it) }
            }
            check(deadline)
            return assignDeviceCoordinates(candidate.role, anchors, distances, candidate.z)
        } catch (error: Throwable) {
            operationError = error
            throw error
        } finally {
            timer.shutdownNow()
            val cleanupError = cleanup()
            if (cleanupError != null) {
                if (operationError == null) throw IOException(cleanupError)
                operationError.addSuppressed(IOException(cleanupError))
            }
        }
    }
}
