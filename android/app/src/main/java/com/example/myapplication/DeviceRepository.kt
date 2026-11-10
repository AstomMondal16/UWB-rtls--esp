package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val DISCOVERY_TTL_MS = 4_500L
private const val MAX_TELEMETRY_BYTES = 32 * 1024
private const val MAX_CIR_BYTES = 64 * 1024
private const val MAX_CONTROL_BYTES = 8 * 1024
class DeviceRepository(context: Context, private val changed: (DashboardSnapshot) -> Unit) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("uwb_localization", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newScheduledThreadPool(2)
    private val pollWorkers = Executors.newFixedThreadPool(8)
    private val exportWorker = Executors.newSingleThreadExecutor()
    private val polling = ConcurrentHashMap.newKeySet<String>()
    private val pollSchedule = DevicePollSchedule()
    private val publishPending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var latestSnapshot: DashboardSnapshot? = null
    @Volatile private var lastPublishedAtMs = 0L
    private val publishLatest = Runnable {
        lastPublishedAtMs = SystemClock.elapsedRealtime()
        publishPending.set(false)
        if (!closed.get()) latestSnapshot?.let(changed)
    }
    private val tlsConfigurations = mutableMapOf<String, DeviceTlsConfiguration>()
    private val clockSyncing = ConcurrentHashMap.newKeySet<String>()
    private val scanning = AtomicBoolean(false)
    private val lock = Any()
    private var devices = decodeDevices(prefs.getString("devices", "[]") ?: "[]").toMutableList()
    private val discovered = linkedMapOf<String, FoundDevice>()
    private val states = mutableMapOf<String, DeviceState>()
    private val ranges = mutableMapOf<Pair<String, String>, RangeReading>()
    private val collectionFile = File(appContext.filesDir, "uwb_session.jsonl")
    private val csvStore = CsvSessionStore(collectionFile)
    private val recorder = TelemetryRecorder(csvStore)
    private val trailStore = TagTrailStore()
    private val pathStorage = lazy { PathSessionStore(File(appContext.filesDir, "paths")) }
    private var pathRecording = false
    private var pathPointCount = 0
    private var pathError = ""
    private var pathCleanupWarning = ""
    @Volatile private var nextPathRetryAtMs = 0L
    @Volatile private var collecting = false
    private var recordingStartCount = csvStore.count()
    private var recordingStartedAtMs: Long? = null
    private var streamingPending = false
    private data class CalibrationTarget(val ip: String, val delay: Int, val expected: Double,
                                         val samples: MutableList<Double> = mutableListOf(),
                                         val seen: MutableSet<Long> = mutableSetOf())
    private var calibration = mutableMapOf<String, CalibrationTarget>()
    private var status = "Waiting for devices"

    init {
        pathCleanupWarning = PathCleanupJob.schedule(appContext).orEmpty()
        workers.scheduleWithFixedDelay({
            val cleanupWarning = PathCleanupJob.schedule(appContext).orEmpty()
            synchronized(lock) { pathCleanupWarning = cleanupWarning }
            automaticPathStore()?.let { store ->
                try {
                    store.maintenance(System.currentTimeMillis())
                    synchronized(lock) { pathRecording = store.isRecording(); pathPointCount = store.pointCount() }
                } catch (error: IOException) {
                    synchronized(lock) { pathError = "Path cleanup failed: ${error.message}" }
                }
                publish()
            }
        }, 0, 1, TimeUnit.HOURS)
        workers.scheduleWithFixedDelay({ scan() }, 0, 2, TimeUnit.SECONDS)
        workers.scheduleWithFixedDelay({ poll() }, 0, 25, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): DashboardSnapshot = synchronized(lock) { makeSnapshot() }

    private fun makeSnapshot(): DashboardSnapshot {
        val now = System.currentTimeMillis()
        val onlineStates = states.mapValues { (_, value) ->
            val last = value.sample.optLong("_received_at", 0)
            value.copy(online = value.error.isBlank() && now - last < SamplingRate.onlineTimeoutMs(value.sample))
        }
        val liveDiscovered = discovered.values
            .filter { now - it.lastSeenAtMs <= DISCOVERY_TTL_MS }
            .sortedByDescending { it.lastSeenAtMs }
        return DashboardSnapshot(devices.toList(), liveDiscovered, onlineStates,
            solvePositions(devices, ranges, now), status, collecting, csvStore.count(),
            if (calibration.isEmpty()) "" else "${calibration.values.minOf { it.samples.size }}/100 samples per anchor",
            streamingPending, recordingStartCount, recordingStartedAtMs, trailStore.snapshot(now), pathRecording, pathPointCount,
            listOf(pathError, pathCleanupWarning).filter { it.isNotBlank() }.joinToString("\n"))
    }

    private fun publish() {
        if (closed.get()) return
        synchronized(lock) { latestSnapshot = makeSnapshot() }
        if (publishPending.compareAndSet(false, true)) main.postDelayed(publishLatest,
            SamplingRate.uiPublishDelayMs(SystemClock.elapsedRealtime(), lastPublishedAtMs))
    }

    fun setStatus(message: String) {
        synchronized(lock) { status = message }
        publish()
    }

    fun manualScan() { setStatus("Scanning for UWB devices…"); workers.execute { scan() } }

    private fun scan() {
        if (!scanning.compareAndSet(false, true)) return
        try {
            val targets = mutableListOf<Pair<InetAddress?, InetAddress>>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                for (networkInterface in interfaces) {
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val local = interfaceAddress.address as? Inet4Address ?: continue
                        val broadcast = interfaceAddress.broadcast as? Inet4Address ?: continue
                        targets.add(local to broadcast)
                    }
                }
            } catch (e: Exception) {
                synchronized(lock) { status = "Could not inspect network interfaces: " + (e.message ?: "network error") }
            }
            targets.add(null to InetAddress.getByName("255.255.255.255"))

            val bytes = "UWB_DISCOVER".toByteArray(StandardCharsets.UTF_8)
            var socketError: String? = null
            for ((localAddress, broadcastAddress) in targets.distinctBy { it.first?.hostAddress to it.second.hostAddress }) {
                try {
                    DatagramSocket(null as java.net.SocketAddress?).use { socket ->
                        socket.broadcast = true
                        socket.soTimeout = 180
                        socket.bind(if (localAddress == null) InetSocketAddress(0) else InetSocketAddress(localAddress, 0))
                        val packet = DatagramPacket(bytes, bytes.size, broadcastAddress, 4210)
                        repeat(4) {
                            socket.send(packet)
                            val until = System.currentTimeMillis() + 250
                            while (System.currentTimeMillis() < until) {
                                val buffer = ByteArray(512)
                                val response = DatagramPacket(buffer, buffer.size)
                                try {
                                    socket.receive(response)
                                } catch (_: java.net.SocketTimeoutException) {
                                    break
                                }
                                val json = try {
                                    JSONObject(String(response.data, 0, response.length, StandardCharsets.UTF_8))
                                } catch (_: org.json.JSONException) {
                                    continue
                                }
                                val mac = normalizeMac(json.optString("mac"))
                                val sourceIp = (response.address as? Inet4Address)?.hostAddress.orEmpty()
                                if (mac.isNotBlank() && sourceIp.isNotBlank()) synchronized(lock) {
                                    discovered[mac] = FoundDevice(mac,
                                        sourceIp,
                                        json.optString("role", "unknown"), System.currentTimeMillis(), json.optString("api_scheme"))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    socketError = e.message ?: e.javaClass.simpleName
                }
            }
            synchronized(lock) {
                val now = System.currentTimeMillis()
                discovered.entries.removeAll { now - it.value.lastSeenAtMs > DISCOVERY_TTL_MS }
                status = if (discovered.isNotEmpty()) "Scan complete · " + discovered.size + " found"
                    else if (socketError != null) "Scan failed: " + socketError
                    else "Scan complete · 0 found after checking " + targets.size + " broadcast target(s)"
            }
        } catch (e: Exception) {
            synchronized(lock) { status = "Scan failed: " + (e.message ?: "network error") }
        } finally {
            scanning.set(false)
            publish()
        }
    }

    private fun poll() {
        val current = synchronized(lock) { devices.toList() }
        pollSchedule.retainDevices(current.map { it.key }.toSet())
        current.forEach { device ->
            val periodMs = synchronized(lock) { SamplingRate.pollIntervalMs(states[device.key]?.sample) }
            val discoveredIp = synchronized(lock) {
                discovered[device.mac]?.takeIf {
                    System.currentTimeMillis() - it.lastSeenAtMs <= DISCOVERY_TTL_MS
                }?.ip.orEmpty()
            }
            val ip = discoveredIp.ifBlank { device.ip }
            if (ip.isNotBlank() && polling.add(device.key)) {
                if (!pollSchedule.claim(device.key, SystemClock.elapsedRealtime(), periodMs)) {
                    polling.remove(device.key)
                    return@forEach
                }
                pollWorkers.execute {
                    try {
                        val sample = getJson("$ip/api/telemetry", device.mac.ifBlank { device.ip })
                        sample.put("_received_at", System.currentTimeMillis())
                        if (collecting && !sample.has("cir")) {
                            val cir = runCatching { getJson("$ip/api/cir", device.key, MAX_CIR_BYTES) }
                                .getOrElse { JSONObject().put("valid", false).put("error", it.message ?: "CIR unavailable") }
                            sample.put("cir", cir)
                        }
                        accept(device, ip, sample)
                    } catch (e: Exception) {
                        synchronized(lock) {
                            val old = states[device.key]
                            val message = "Connection failed for ${device.key}: ${e.message ?: e.javaClass.simpleName}"
                            states[device.key] = DeviceState(false, old?.sample ?: JSONObject(), message)
                            breakDeviceTrail(device.mac.ifBlank { device.key })
                        }
                    } finally {
                        polling.remove(device.key)
                        publish()
                    }
                }
            }
        }
    }

    private fun accept(device: DeviceRecord, ip: String, sample: JSONObject) {
        val pathStore = automaticPathStore() // Disk recovery never holds the receiver lock.
        synchronized(lock) {
            if (closed.get() || devices.none { it.key == device.key }) return@synchronized
            val oldKey = device.key
            val mac = normalizeMac(sample.optString("mac")).ifBlank { device.mac }
            if (device.mac.isNotBlank() && mac != device.mac) {
                status = "Device identity mismatch for ${device.mac}"
                return@synchronized
            }
            if (mac.isNotBlank() && device.mac != mac) {
                val index = devices.indexOfFirst { it.key == oldKey }
                if (devices.any { it.mac == mac && it.key != oldKey }) throw IOException("Device MAC $mac is already configured")
                if (index >= 0) {
                    val security = LastWifiCredentialStore.loadDeviceSecurity(appContext, oldKey)
                    if (security.loadError.isNotBlank() || !LastWifiCredentialStore.saveDeviceSecurity(appContext, mac, security))
                        throw IOException("Could not preserve TLS settings while learning device MAC")
                    devices[index] = devices[index].copy(mac = mac, ip = ip)
                    prefs.edit().putString("devices", encodeDevices(devices)).apply()
                }
                states.remove(oldKey)
                tlsConfigurations.remove(oldKey)
            } else {
                val index = devices.indexOfFirst { it.key == oldKey }
                if (index >= 0 && devices[index].ip != ip) {
                    devices[index] = devices[index].copy(ip = ip)
                    prefs.edit().putString("devices", encodeDevices(devices)).apply()
                }
            }
            val source = mac.ifBlank { oldKey }
            val oldSample = states[source]?.sample
            states[source] = DeviceState(true, sample)
            if (needsSystemClock(sample) && clockSyncing.add(source)) workers.execute {
                try {
                    val connection = openApiConnection("$ip/api/time", source)
                    withReusableDeviceConnection(connection) { sendSystemClock(connection) }
                } catch (error: Exception) {
                    setStatus("Clock sync failed for $source: ${error.message ?: error.javaClass.simpleName}")
                } finally { clockSyncing.remove(source) }
            }
            val sourceRole = sample.optString("role", device.role)
            val receivedAt = sample.optLong("_received_at", System.currentTimeMillis())
            if (oldSample != null && sample.optLong("timestamp_ms") < oldSample.optLong("timestamp_ms")) breakDeviceTrail(source)
            val list = sample.optJSONArray("ranges") ?: org.json.JSONArray()
            if (!sample.optBoolean("valid")) {
                breakDeviceTrail(source)
            } else if (list.length() > 0) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val peer = normalizeMac(item.optString("peer"))
                    val distance = item.optDouble("distance_m", Double.NaN)
                    if (peer.isBlank()) continue
                    val anchor = if (sourceRole == "tag") peer else source
                    val tag = if (sourceRole == "tag") source else peer
                    if (!distance.isFinite() || distance <= 0) {
                        ranges.remove(anchor to tag); trailStore.breakTag(tag); continue
                    }
                    val age = item.optLong("age_ms", 0).coerceAtLeast(0)
                    ranges[anchor to tag] = RangeReading(distance,
                        if (item.isNull("rx_power_dbm")) null else item.optDouble("rx_power_dbm"),
                        receivedAt - age, "$source:${item.optLong("timestamp_ms", sample.optLong("timestamp_ms", -1))}")
                    if (sourceRole == "anchor") {
                        val target = calibration[anchor]
                        val stamp = item.optLong("timestamp_ms", -1)
                        if (target != null && stamp >= 0 && target.seen.add(stamp)) target.samples.add(distance)
                    }
                }
            } else if (sample.optBoolean("valid")) {
                val peer = normalizeMac(sample.optString("peer"))
                val distance = sample.optDouble("distance_m", Double.NaN)
                if (peer.isNotBlank()) {
                    val anchor = if (sourceRole == "tag") peer else source
                    val tag = if (sourceRole == "tag") source else peer
                    if (distance.isFinite() && distance > 0) {
                        ranges[anchor to tag] = RangeReading(distance, null,
                            receivedAt - sample.optLong("age_ms", 0).coerceAtLeast(0), "$source:${sample.optLong("timestamp_ms", -1)}")
                    } else {
                        ranges.remove(anchor to tag)
                        trailStore.breakTag(tag)
                    }
                }
            }
            val positions = solvePositions(devices, ranges, receivedAt)
            val evidence = positions.mapValues { (tag, position) ->
                position.anchors.sorted().joinToString("|") { anchor -> "$anchor:${ranges[anchor to tag]?.measurementId}" }
            }
            val pathPoints = trailStore.update(positions, evidence, receivedAt)
            if (pathStore != null && receivedAt >= nextPathRetryAtMs) try {
                pathStore.record(devices.toList(), pathPoints, receivedAt)
                pathRecording = pathStore.isRecording()
                pathPointCount = pathStore.pointCount()
                if (pathPoints.isNotEmpty()) pathError = ""
            } catch (error: IOException) {
                pathRecording = false
                nextPathRetryAtMs = System.currentTimeMillis() + 30_000
                runCatching { pathStore.seal() }
                pathError = "Path saving paused: ${error.message ?: "storage unavailable"}. Retrying automatically."
                status = pathError
            }
            if (collecting) try {
                recorder.accept(source, ip, sample, positions)
            } catch (error: IOException) {
                collecting = false
                recorder.stop()
                status = "CSV recording stopped: ${error.message ?: "could not save session data"}"
            }
            if (calibration.isNotEmpty() && calibration.values.all { it.samples.size >= 100 }) finishCalibration()
        }
    }

    fun upsert(record: DeviceRecord, oldKey: String? = null): String? {
        return try {
            val clean = cleanDevice(record)
            synchronized(lock) {
                val index = devices.indexOfFirst { it.key == oldKey || it.key == clean.key }
                if (index >= 0) devices[index] = clean else devices.add(clean)
                finishPathForDeviceChange()
                trailStore.clear()
                prefs.edit().putString("devices", encodeDevices(devices)).apply()
                status = "${if (index >= 0) "Updated" else "Added"} ${clean.key}"
            }
            publish(); null
        } catch (e: IllegalArgumentException) { e.message ?: "Invalid device" }
    }

    fun remove(key: String) {
        synchronized(lock) {
            finishPathForDeviceChange()
            trailStore.clear()
            devices.removeAll { it.key == key }
            ranges.keys.removeAll { it.first == key || it.second == key }
            states.remove(key)
            tlsConfigurations.remove(key)
            prefs.edit().putString("devices", encodeDevices(devices)).apply()
            status = "Device removed"
        }
        publish()
    }

    fun clear() {
        synchronized(lock) {
            finishPathForDeviceChange()
            devices.clear(); ranges.clear(); states.clear(); trailStore.clear(); tlsConfigurations.clear()
            prefs.edit().putString("devices", "[]").apply()
            status = "All devices cleared"
        }
        publish()
    }

    fun setCollecting(value: Boolean): IntRange {
        val bounds = synchronized(lock) {
            if (value && devices.isEmpty()) {
                status = "Add a device before collecting"
                return@synchronized recordingStartCount until csvStore.count()
            }
            collecting = value
            if (!value) recorder.stop()
            if (value) {
                recorder.begin()
                recordingStartCount = csvStore.count()
                recordingStartedAtMs = System.currentTimeMillis()
            }
            status = if (value) "CSV recording started · ${csvStore.count()} records already stored"
                else "CSV recording stopped · ${csvStore.count()} records ready to export"
            recordingStartCount until csvStore.count()
        }
        publish()
        return bounds
    }

    fun startOrCancelCalibration(): String? = synchronized(lock) {
        if (calibration.isNotEmpty()) {
            calibration.clear(); status = "Antenna delay calibration cancelled"; publish(); return null
        }
        val tags = devices.filter { it.role == "tag" && it.mac.isNotBlank() }
        if (tags.size != 1) return "Configure exactly one tag at its measured XYZ position"
        val tag = tags.single()
        val now = System.currentTimeMillis()
        val targets = mutableMapOf<String, CalibrationTarget>()
        devices.filter { it.role == "anchor" && it.mac.isNotBlank() }.forEach { anchor ->
            val reading = ranges[anchor.mac to tag.mac]
            val sample = states.values.firstOrNull { normalizeMac(it.sample.optString("mac")) == anchor.mac }?.sample
            val ip = anchor.ip.ifBlank { sample?.optString("ip").orEmpty() }
            val delay = sample?.optInt("antenna_delay", -1) ?: -1
            val expected = kotlin.math.sqrt((anchor.x-tag.x)*(anchor.x-tag.x) + (anchor.y-tag.y)*(anchor.y-tag.y) + (anchor.z-tag.z)*(anchor.z-tag.z))
            if (reading != null && now - reading.timeMs <= 3000 && ip.isNotBlank() && delay in 0..65535 && expected in .01..1000.0) {
                targets[anchor.mac] = CalibrationTarget(ip, delay, expected)
            }
        }
        if (targets.isEmpty()) return "No fresh anchor ranges with IP, delay, and valid coordinates"
        calibration = targets
        status = "Calibration started · keep the tag still"
        publish()
        null
    }

    private fun finishCalibration() {
        val results = calibration.mapNotNull { (mac, target) ->
            val ordered = target.samples.sorted()
            val measured = (ordered[ordered.size / 2] + ordered[(ordered.size - 1) / 2]) / 2
            val correction = (measured - target.expected) / (2.0 * 299_702_547.0 * (1.0 / (499_200_000.0 * 128.0)))
            val delay = (target.delay + correction).roundToInt()
            if (delay !in 0..65535) null else Triple(mac, target, delay)
        }
        calibration.clear()
        if (results.isEmpty()) { status = "Calibration failed: calculated delay outside 0..65535"; return }
        status = "Applying calibrated antenna delays…"
        results.forEach { (mac, target, delay) ->
            postControl(target.ip, JSONObject().put("antenna_delay", delay), mac) {
                val ordered = target.samples.sorted()
                val measured = (ordered[ordered.size / 2] + ordered[(ordered.size - 1) / 2]) / 2
                setStatus("Calibrated $mac · ${"%.3f".format(measured)} → ${"%.3f".format(target.expected)} m")
            }
        }
    }

    internal fun exportCsv(output: OutputStream, callback: (Result<CsvExportSummary>) -> Unit) =
        exportCsv(output, 0, null, callback)

    internal fun exportCsv(output: OutputStream, startingAtRecord: Int, endingAtRecord: Int? = null,
                           callback: (Result<CsvExportSummary>) -> Unit) {
        val end = synchronized(lock) {
            // Stop/export is ordered after every accepted synchronous append.
            val frozenEnd = endingAtRecord ?: csvStore.count().also { collecting = false; recorder.stop() }
            status = "Exporting ${frozenEnd - startingAtRecord} CSV records..."
            frozenEnd
        }
        publish()
        exportWorker.execute {
            val result = runCatching { csvStore.exportCsv(output, startingAtRecord, end) }
            main.post {
                result.onSuccess { summary ->
                    val first = summary.firstReceivedAt ?: "unknown"
                    val last = summary.lastReceivedAt ?: "unknown"
                    setStatus("CSV exported: ${summary.records} records · $first to $last · ${summary.missedSamples} missed UWB / ${summary.missedImuSamples} missed IMU samples before receipt")
                }.onFailure { error ->
                    runCatching { output.close() }
                    setStatus("CSV export failed: ${error.message ?: "write error"}")
                }
                callback(result)
            }
        }
    }

    fun getCir(ip: String, deviceId: String, callback: (JSONObject?, String?) -> Unit) {
        pollWorkers.execute {
            val result = runCatching { getJson("$ip/api/cir", deviceId, MAX_CIR_BYTES) }
            main.post { callback(result.getOrNull(), result.exceptionOrNull()?.let { "CIR request failed: ${it.message}" }) }
        }
    }

    fun postControl(ip: String, data: JSONObject, deviceId: String? = null,
                    callback: (String) -> Unit = {}) {
        workers.execute {
            val result = runCatching {
                val id = deviceId ?: synchronized(lock) {
                    devices.firstOrNull { it.ip == ip }?.mac?.ifBlank { ip } ?: ip
                }
                val connection = openApiConnection("$ip/api/control", id)
                withReusableDeviceConnection(connection) {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(data.toString().toByteArray(StandardCharsets.UTF_8)) }
                    readDeviceResponse(connection, MAX_CONTROL_BYTES)
                }
            }.getOrElse { "Control failed: ${it.message}" }
            setStatus(result)
            if (runCatching { JSONObject(result).optBoolean("ok") }.getOrDefault(false)) refreshNow()
            main.post { callback(result) }
        }
    }

    fun setStreaming(value: Boolean) {
        val targets = synchronized(lock) {
            if (streamingPending) return
            val found = devices.mapNotNull { device ->
                val ip = device.ip.ifBlank {
                    discovered[device.mac]?.ip.orEmpty().ifBlank {
                        states[device.key]?.sample?.optString("ip").orEmpty()
                    }
                }
                ip.takeIf { it.isNotBlank() }?.let { (device.mac.ifBlank { device.ip }) to it }
            }.distinctBy { it.second }
            if (found.isNotEmpty()) {
                streamingPending = true
                status = "Sending monitoring command to ${found.size} device(s)..."
            }
            found
        }
        if (targets.isEmpty()) {
            setStatus("Add a device with an IP address before starting tracking")
            return
        }
        publish()
        workers.execute {
            var succeeded = 0
            var failure: String? = null
            targets.forEach { (deviceId, ip) ->
                runCatching {
                    val connection = openApiConnection("$ip/api/control", deviceId)
                    withReusableDeviceConnection(connection) {
                        connection.requestMethod = "POST"
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use {
                            it.write(JSONObject().put("streaming", value).toString().toByteArray(StandardCharsets.UTF_8))
                        }
                        readDeviceResponse(connection, MAX_CONTROL_BYTES)
                    }
                }.onSuccess {
                    succeeded++
                    if (!value) synchronized(lock) { breakDeviceTrail(deviceId) }
                }.onFailure { if (failure == null) failure = it.message }
            }
            synchronized(lock) {
                streamingPending = false
                status = when {
                    succeeded == targets.size -> "Monitoring ${if (value) "started" else "paused"} on $succeeded device(s)"
                    succeeded > 0 -> "Monitoring command reached $succeeded/${targets.size} devices"
                    else -> "Monitoring command failed: ${failure ?: "device did not respond"}"
                }
            }
            publish()
        }
    }

    fun refreshNow() { pollSchedule.clear(); workers.execute { poll() } }

    private fun getJson(address: String, deviceId: String,
                        maxBytes: Int = MAX_TELEMETRY_BYTES): JSONObject {
        val connection = openApiConnection(address, deviceId)
        connection.setRequestProperty("Cache-Control", "no-cache")
        return withReusableDeviceConnection(connection) { JSONObject(readDeviceResponse(connection, maxBytes)) }
    }

    private fun openApiConnection(path: String, deviceId: String): HttpURLConnection {
        val credentials = LastWifiCredentialStore.loadDeviceSecurity(appContext, deviceId)
        check(credentials.loadError.isBlank()) { credentials.loadError }
        val mac = synchronized(lock) {
            devices.firstOrNull { it.key == deviceId }?.mac.orEmpty().ifBlank {
                discovered.values.firstOrNull { it.ip == path.substringBefore('/') }?.mac ?: deviceId
            }
        }
        return openDeviceConnection(path, https = true, fingerprint = credentials.tlsFingerprint,
            deviceMac = mac, trustedPublicKeyPin = credentials.trustedPublicKeyPin,
            acceptFirstUse = { candidate -> LastWifiCredentialStore.acceptDeviceIdentity(appContext, deviceId, candidate) },
            tlsConfiguration = synchronized(lock) { tlsConfigurations.getOrPut(deviceId) { DeviceTlsConfiguration() } },
            trustRevision = credentials.transportRevision)
    }

    private fun breakDeviceTrail(source: String) {
        trailStore.breakTag(source)
        ranges.keys.filter { it.first == source || it.second == source }.forEach { pair ->
            trailStore.breakTag(pair.second)
            ranges.remove(pair)
        }
    }

    private fun <T> pathTask(action: () -> T, callback: (T?, String?) -> Unit) {
        exportWorker.execute {
            val result = runCatching(action)
            if (result.isFailure) publish()
            main.post {
                if (!closed.get()) callback(result.getOrNull(), result.exceptionOrNull()?.let {
                    it.message ?: "Could not access saved paths"
                })
            }
        }
    }

    internal fun listPaths(callback: (List<SavedPathSummary>?, String?) -> Unit) =
        pathTask({ pathStorage.value.list() }, callback)

    internal fun loadPath(id: String, callback: (SavedPath?, String?) -> Unit) =
        pathTask({ pathStorage.value.load(id) }, callback)

    private fun automaticPathStore(): PathSessionStore? {
        if (closed.get() || System.currentTimeMillis() < nextPathRetryAtMs) return null
        return try { pathStorage.value } catch (error: Exception) {
            nextPathRetryAtMs = System.currentTimeMillis() + 30_000
            synchronized(lock) {
                pathError = "Automatic path saving is unavailable: ${error.message ?: "storage error"}. Retrying automatically."
                status = pathError
            }
            null
        }
    }

    private fun finishPathForDeviceChange() {
        if (pathRecording) {
            runCatching { pathStorage.value.seal() }
            pathRecording = false
        }
    }

    fun close() {
        closed.set(true)
        main.removeCallbacks(publishLatest)
        workers.shutdownNow(); pollWorkers.shutdownNow(); exportWorker.shutdown()
        synchronized(lock) {
            pathRecording = false
            if (pathStorage.isInitialized()) runCatching { pathStorage.value.seal() }
            tlsConfigurations.clear()
        }
    }

}
