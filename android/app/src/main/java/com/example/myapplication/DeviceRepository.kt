package com.example.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val DISCOVERY_TTL_MS = 4_500L
private const val MAX_TELEMETRY_BYTES = 32 * 1024
private const val MAX_CIR_BYTES = 64 * 1024
private const val MAX_CONTROL_BYTES = 8 * 1024
private const val MAX_COLLECTION_BYTES = 4 * 1024 * 1024

class DeviceRepository(context: Context, private val changed: (DashboardSnapshot) -> Unit) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("uwb_localization", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newScheduledThreadPool(2)
    private val pollWorkers = Executors.newFixedThreadPool(8)
    private val polling = ConcurrentHashMap.newKeySet<String>()
    private val scanning = AtomicBoolean(false)
    private val lock = Any()
    private var devices = decodeDevices(prefs.getString("devices", "[]") ?: "[]").toMutableList()
    private val discovered = linkedMapOf<String, FoundDevice>()
    private val states = mutableMapOf<String, DeviceState>()
    private val ranges = mutableMapOf<Pair<String, String>, RangeReading>()
    private val collected = ArrayDeque<String>()
    private var collectedBytes = 0
    private var collecting = false
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
            solvePositions(devices, ranges, now), status, collecting, collected.size,
            if (calibration.isEmpty()) "" else "${calibration.values.minOf { it.samples.size }}/100 samples per anchor")
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
                    val sample = getJson("https://$ip/api/telemetry", device.mac.ifBlank { device.ip })
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
        if (collecting && sample.optLong("sequence", -1) >= 0) {
            val row = JSONObject(sample.toString()).apply {
                put("source_address", source); put("source_ip", ip)
                put("dashboard_devices", encodeDevices(devices))
            }.toString()
            val rowBytes = row.toByteArray(StandardCharsets.UTF_8).size
            if (rowBytes <= MAX_COLLECTION_BYTES) {
                while (collected.isNotEmpty() && collectedBytes + rowBytes > MAX_COLLECTION_BYTES) {
                    collectedBytes -= collected.removeFirst().toByteArray(StandardCharsets.UTF_8).size
                }
                collected.addLast(row)
                collectedBytes += rowBytes
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
            if (value && devices.isEmpty()) { status = "Add a device before collecting"; publish(); return }
            if (value) { collected.clear(); collectedBytes = 0 }
            collecting = value
            status = if (value) "Data collection running" else "Data collection stopped · ${collected.size} samples"
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

    fun csv(): String = synchronized(lock) {
        if (collected.isEmpty()) return@synchronized ""
        val first = runCatching { JSONObject(collected.first()) }.getOrNull()
            ?: return@synchronized ""
        val fields = linkedSetOf<String>().apply {
            add("source_address"); add("source_ip")
            addAll((0 until first.length()).map { first.names()?.optString(it) ?: "" })
        }.filter { it.isNotBlank() }
        buildString {
            append(fields.joinToString(",") { csvEscape(it) }).append('\n')
            collected.forEach { encoded ->
                val row = runCatching { JSONObject(encoded) }.getOrNull() ?: return@forEach
                append(fields.joinToString(",") { key -> csvEscape(row.opt(key)?.toString().orEmpty()) }).append('\n')
            }
        }
    }

    fun getCir(ip: String, deviceId: String, callback: (JSONObject?, String?) -> Unit) {
        pollWorkers.execute {
            val result = runCatching { getJson("https://$ip/api/cir", deviceId, MAX_CIR_BYTES) }
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
                val connection = openApiConnection("https://$ip/api/control", id)
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
            devices.mapNotNull { device ->
                val ip = device.ip.ifBlank {
                    discovered[device.mac]?.ip.orEmpty().ifBlank {
                        states[device.key]?.sample?.optString("ip").orEmpty()
                    }
                }
                ip.takeIf { it.isNotBlank() }?.let { (device.mac.ifBlank { device.ip }) to it }
            }.distinctBy { it.second }
        }
        if (targets.isEmpty()) {
            setStatus("Add a device with an IP address before starting tracking")
            return
        }
        workers.execute {
            var succeeded = 0
            targets.forEach { (deviceId, ip) ->
                runCatching {
                    val connection = openApiConnection("https://$ip/api/control", deviceId)
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
                }.onSuccess { succeeded++ }
            }
            setStatus("Tracking ${if (value) "started" else "paused"} on $succeeded/${targets.size} devices")
        }
    }

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

    private fun openApiConnection(address: String, deviceId: String): HttpsURLConnection {
        val credentials = LastWifiCredentialStore.loadDeviceSecurity(appContext, deviceId)
        val apiKey = credentials.apiKey.trim()
        val pin = credentials.tlsFingerprint.filterNot { it == ':' || it.isWhitespace() }
            .uppercase(java.util.Locale.ROOT)
        require(apiKey.length == 64 && apiKey.all { it.digitToIntOrNull(16) != null }) {
            "Enter the 64-character API key for $deviceId"
        }
        require(pin.length == 64 && pin.all { it.digitToIntOrNull(16) != null }) {
            "Enter the TLS certificate fingerprint for $deviceId"
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
        val connection = URL(address).openConnection() as HttpsURLConnection
        connection.sslSocketFactory = tls.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, session ->
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.let(::matches) == true
        }
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        return connection
    }

    fun close() { workers.shutdownNow(); pollWorkers.shutdownNow() }
}

private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
