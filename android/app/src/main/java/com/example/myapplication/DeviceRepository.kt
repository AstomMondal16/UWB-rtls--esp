package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
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
    private val scanning = AtomicBoolean(false)
    private val lock = Any()
    private var devices = decodeDevices(prefs.getString("devices", "[]") ?: "[]").toMutableList()
    private val discovered = linkedMapOf<String, FoundDevice>()
    private val states = mutableMapOf<String, DeviceState>()
    private val ranges = mutableMapOf<Pair<String, String>, RangeReading>()
    private val collectionFile = File(appContext.filesDir, "uwb_session.jsonl")
    private val csvStore = CsvSessionStore(collectionFile,
        if (collectionFile.isFile) prefs.getInt("csv_record_count", 0) else 0)
    private val lastCollectedSamples = loadCollectedCursors()
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
        workers.scheduleWithFixedDelay({ scan() }, 0, 2, TimeUnit.SECONDS)
        workers.scheduleWithFixedDelay({ poll() }, 0, 350, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): DashboardSnapshot = synchronized(lock) { makeSnapshot() }

    private fun makeSnapshot(): DashboardSnapshot {
        val now = System.currentTimeMillis()
        val onlineStates = states.mapValues { (_, value) ->
            val last = value.sample.optLong("_received_at", 0)
            value.copy(online = now - last < 2500)
        }
        val liveDiscovered = discovered.values
            .filter { now - it.lastSeenAtMs <= DISCOVERY_TTL_MS }
            .sortedByDescending { it.lastSeenAtMs }
        return DashboardSnapshot(devices.toList(), liveDiscovered, onlineStates,
            solvePositions(devices, ranges, now), status, collecting, csvStore.count(),
            if (calibration.isEmpty()) "" else "${calibration.values.minOf { it.samples.size }}/100 samples per anchor",
            streamingPending, recordingStartCount, recordingStartedAtMs)
    }

    private fun publish() {
        val copy = synchronized(lock) { makeSnapshot() }
        main.post { changed(copy) }
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
                                        json.optString("role", "unknown"), System.currentTimeMillis())
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
        current.forEach { device ->
            val discoveredIp = synchronized(lock) {
                discovered[device.mac]?.takeIf {
                    System.currentTimeMillis() - it.lastSeenAtMs <= DISCOVERY_TTL_MS
                }?.ip.orEmpty()
            }
            val ip = discoveredIp.ifBlank { device.ip }
            if (ip.isNotBlank() && polling.add(device.key)) pollWorkers.execute {
                try {
                    val sample = getJson("$ip/api/telemetry", device.mac.ifBlank { device.ip })
                    sample.put("_received_at", System.currentTimeMillis())
                    accept(device, ip, sample)
                } catch (e: Exception) {
                    synchronized(lock) {
                        val old = states[device.key]
                        states[device.key] = DeviceState(old?.online == true, old?.sample ?: JSONObject(), e.message ?: "Offline")
                    }
                } finally {
                    polling.remove(device.key)
                    publish()
                }
            }
        }
    }

    private fun accept(device: DeviceRecord, ip: String, sample: JSONObject) = synchronized(lock) {
        val oldKey = device.key
        val mac = normalizeMac(sample.optString("mac")).ifBlank { device.mac }
        if (device.mac.isNotBlank() && mac != device.mac) {
            status = "Device identity mismatch for ${device.mac}"
            return@synchronized
        }
        if (mac.isNotBlank() && device.mac != mac) {
            val index = devices.indexOfFirst { it.key == oldKey }
            if (index >= 0) {
                devices[index] = devices[index].copy(mac = mac, ip = ip)
                prefs.edit().putString("devices", encodeDevices(devices)).apply()
            }
            states.remove(oldKey)
        } else {
            val index = devices.indexOfFirst { it.key == oldKey }
            if (index >= 0 && devices[index].ip != ip) {
                devices[index] = devices[index].copy(ip = ip)
                prefs.edit().putString("devices", encodeDevices(devices)).apply()
            }
        }
        val source = mac.ifBlank { oldKey }
        states[source] = DeviceState(true, sample)
        val sourceRole = sample.optString("role", device.role)
        val list = sample.optJSONArray("ranges") ?: org.json.JSONArray()
        if (list.length() > 0) {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val peer = normalizeMac(item.optString("peer"))
                val distance = item.optDouble("distance_m", Double.NaN)
                if (peer.isBlank() || !distance.isFinite() || distance <= 0) continue
                val anchor = if (sourceRole == "tag") peer else source
                val tag = if (sourceRole == "tag") source else peer
                val age = item.optLong("age_ms", 0).coerceAtLeast(0)
                ranges[anchor to tag] = RangeReading(distance,
                    if (item.isNull("rx_power_dbm")) null else item.optDouble("rx_power_dbm"),
                    System.currentTimeMillis() - age)
                if (sourceRole == "anchor") {
                    val target = calibration[anchor]
                    val stamp = item.optLong("timestamp_ms", -1)
                    if (target != null && stamp >= 0 && target.seen.add(stamp)) target.samples.add(distance)
                }
            }
        } else if (sample.optBoolean("valid")) {
            val peer = normalizeMac(sample.optString("peer"))
            val distance = sample.optDouble("distance_m", Double.NaN)
            if (peer.isNotBlank() && distance.isFinite() && distance > 0) {
                val anchor = if (sourceRole == "tag") peer else source
                val tag = if (sourceRole == "tag") source else peer
                ranges[anchor to tag] = RangeReading(distance, null, System.currentTimeMillis())
            }
        }
        val sequence = sample.optLong("sequence", -1)
        if (collecting && sequence >= 0) {
            val timestamp = sample.optLong("timestamp_ms", -1)
            val previous = lastCollectedSamples[source]
            val duplicate = previous?.let {
                it.sequence == sequence && (timestamp < 0 || it.timestampMs == timestamp)
            } == true
            if (!duplicate) {
                val missed = previous?.takeIf { timestamp > it.timestampMs && sequence > it.sequence }
                    ?.let { (sequence - it.sequence - 1).coerceAtLeast(0) } ?: 0
                val receivedAt = sample.optLong("_received_at", System.currentTimeMillis())
                val row = JSONObject(sample.toString()).apply {
                    put("source_address", source)
                    put("source_ip", ip)
                    put("received_at_utc", Instant.ofEpochMilli(receivedAt).toString())
                    put("missed_sequence_count", missed)
                }.toString()
                try {
                    val count = csvStore.append(row)
                    lastCollectedSamples[source] = CollectedCursor(sequence, timestamp)
                    prefs.edit().putInt("csv_record_count", count)
                        .putString("csv_last_sequences", encodeCollectedCursors()).apply()
                } catch (error: IOException) {
                    collecting = false
                    status = "CSV recording stopped: ${error.message ?: "could not save session data"}"
                }
            }
        }
        if (calibration.isNotEmpty() && calibration.values.all { it.samples.size >= 100 }) finishCalibration()
    }

    fun upsert(record: DeviceRecord, oldKey: String? = null): String? {
        return try {
            val clean = cleanDevice(record)
            synchronized(lock) {
                val index = devices.indexOfFirst { it.key == oldKey || it.key == clean.key }
                if (index >= 0) devices[index] = clean else devices.add(clean)
                prefs.edit().putString("devices", encodeDevices(devices)).apply()
                status = "${if (index >= 0) "Updated" else "Added"} ${clean.key}"
            }
            publish(); null
        } catch (e: IllegalArgumentException) { e.message ?: "Invalid device" }
    }

    fun remove(key: String) {
        synchronized(lock) {
            devices.removeAll { it.key == key }
            ranges.keys.removeAll { it.first == key || it.second == key }
            states.remove(key)
            prefs.edit().putString("devices", encodeDevices(devices)).apply()
            status = "Device removed"
        }
        publish()
    }

    fun clear() {
        synchronized(lock) {
            devices.clear(); ranges.clear(); states.clear()
            prefs.edit().putString("devices", "[]").apply()
            status = "All devices cleared"
        }
        publish()
    }

    fun setCollecting(value: Boolean) {
        synchronized(lock) {
            if (value && devices.isEmpty()) {
                status = "Add a device before collecting"
                return@synchronized
            }
            collecting = value
            if (value) {
                recordingStartCount = csvStore.count()
                recordingStartedAtMs = System.currentTimeMillis()
            }
            status = if (value) "CSV recording started · ${csvStore.count()} records already stored"
                else "CSV recording stopped · ${csvStore.count()} records ready to export"
        }
        publish()
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
        exportCsv(output, 0, callback)

    internal fun exportCsv(output: OutputStream, startingAtRecord: Int,
                           callback: (Result<CsvExportSummary>) -> Unit) {
        synchronized(lock) {
            collecting = false
            status = "Exporting ${csvStore.count() - startingAtRecord.coerceAtMost(csvStore.count())} CSV records..."
        }
        publish()
        exportWorker.execute {
            val result = runCatching { csvStore.exportCsv(output, startingAtRecord) }
            main.post {
                result.onSuccess { summary ->
                    val first = summary.firstReceivedAt ?: "unknown"
                    val last = summary.lastReceivedAt ?: "unknown"
                    setStatus("CSV exported: ${summary.records} records · $first to $last · ${summary.missedSamples} missed telemetry samples")
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
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 1000; connection.readTimeout = 1500
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(data.toString().toByteArray(StandardCharsets.UTF_8)) }
                    readLimited(connection, MAX_CONTROL_BYTES)
                } finally { connection.disconnect() }
            }.getOrElse { "Control failed: ${it.message}" }
            setStatus(result)
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
                    try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 1000; connection.readTimeout = 1500
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use {
                            it.write(JSONObject().put("streaming", value).toString().toByteArray(StandardCharsets.UTF_8))
                        }
                        readLimited(connection, MAX_CONTROL_BYTES)
                    } finally { connection.disconnect() }
                }.onSuccess { succeeded++ }.onFailure { if (failure == null) failure = it.message }
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

    fun refreshNow() { workers.execute { poll() } }

    private fun getJson(address: String, deviceId: String,
                        maxBytes: Int = MAX_TELEMETRY_BYTES): JSONObject {
        val connection = openApiConnection(address, deviceId)
        connection.connectTimeout = 700; connection.readTimeout = 900
        connection.setRequestProperty("Cache-Control", "no-cache")
        return try { JSONObject(readLimited(connection, maxBytes)) }
        finally { connection.disconnect() }
    }

    private fun readLimited(connection: HttpURLConnection, maxBytes: Int): String {
        if (connection.contentLengthLong > maxBytes) throw IOException("HTTP response exceeds $maxBytes bytes")
        val output = ByteArrayOutputStream(minOf(maxBytes, 4096))
        connection.inputStream.use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > maxBytes) throw IOException("HTTP response exceeds $maxBytes bytes")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun openApiConnection(path: String, deviceId: String): HttpURLConnection {
        val credentials = LastWifiCredentialStore.loadDeviceSecurity(appContext, deviceId)
        val pin = credentials.tlsFingerprint.filterNot { it == ':' || it.isWhitespace() }
            .uppercase(java.util.Locale.ROOT)
        if (pin.isBlank()) {
            return URL("http://$path").openConnection() as HttpURLConnection
        }
        require(pin.length == 64 && pin.all { it.digitToIntOrNull(16) != null }) {
            "TLS certificate fingerprint for $deviceId must be 64 hexadecimal characters"
        }
        val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        fun matches(certificate: X509Certificate): Boolean =
            MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(certificate.encoded), expected)
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("Client certificates are not used")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !matches(chain[0]))
                    throw CertificateException("Device certificate fingerprint mismatch")
                chain[0].checkValidity()
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf(trustManager), SecureRandom())
        val connection = URL("https://$path").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::matches) == true
        }
        return connection
    }

    fun close() { workers.shutdownNow(); pollWorkers.shutdownNow(); exportWorker.shutdown() }

    private fun loadCollectedCursors(): MutableMap<String, CollectedCursor> = synchronized(lock) {
        val raw = prefs.getString("csv_last_sequences", "{}") ?: "{}"
        runCatching {
            val json = JSONObject(raw)
            buildMap {
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val cursor = json.optJSONObject(key) ?: continue
                    put(key, CollectedCursor(cursor.optLong("sequence", -1),
                        cursor.optLong("timestamp_ms", -1)))
                }
            }.toMutableMap()
        }.getOrElse { mutableMapOf() }
    }

    private fun encodeCollectedCursors(): String = JSONObject().apply {
        lastCollectedSamples.forEach { (key, cursor) ->
            put(key, JSONObject().put("sequence", cursor.sequence).put("timestamp_ms", cursor.timestampMs))
        }
    }.toString()
}

private data class CollectedCursor(val sequence: Long, val timestampMs: Long)
