package com.example.myapplication

import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Keep TLS configuration stable so HttpsURLConnection can reuse verified keep-alive sockets. */
internal class DeviceTlsConfiguration {
    private var profile: List<String>? = null
    private var factory: javax.net.ssl.SSLSocketFactory? = null
    private var verifier: javax.net.ssl.HostnameVerifier? = null

    @Synchronized
    fun configure(connection: HttpsURLConnection, key: List<String>, initialize: () -> Unit) {
        if (profile != key) {
            initialize()
            factory = connection.sslSocketFactory
            verifier = connection.hostnameVerifier
            profile = key
        } else {
            connection.sslSocketFactory = factory!!
            connection.hostnameVerifier = verifier!!
        }
    }
}

internal inline fun <T> withReusableDeviceConnection(connection: HttpURLConnection, action: () -> T): T =
    try { action() } catch (error: Throwable) {
        connection.disconnect()
        throw error
    }

internal fun readDeviceResponse(connection: HttpURLConnection, maxBytes: Int): String {
    require(maxBytes > 0)
    requireSuccessfulResponse(connection)
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
    return output.toString(Charsets.UTF_8.name())
}

/** A configured fingerprint is a trust anchor, never a replacement for hostname verification. */
internal fun requireFirstUseKey(saved: String, candidate: String, persist: () -> Boolean) {
    val valid = Regex("[0-9A-Fa-f]{64}")
    if (!candidate.matches(valid) || saved.isNotEmpty() && !saved.matches(valid))
        throw CertificateException("Saved device identity is invalid; pair again explicitly")
    if (saved.isNotEmpty() && !MessageDigest.isEqual(saved.uppercase(java.util.Locale.ROOT).toByteArray(),
            candidate.uppercase(java.util.Locale.ROOT).toByteArray()))
        throw CertificateException("Device identity changed; pair again explicitly on trusted Wi-Fi")
    if (saved.isEmpty() && !persist()) throw CertificateException("Could not securely save the device identity")
}

internal fun mergeDeviceTrust(saved: String, requested: String, reset: Boolean): String {
    if (reset) return ""
    if (saved.isNotEmpty() && requested.isNotEmpty()) requireFirstUseKey(saved, requested) { false }
    return saved.ifBlank { requested }
}

internal fun deviceTlsName(mac: String): String {
    val compact = mac.replace(":", "").replace("-", "").lowercase(java.util.Locale.ROOT)
    require(compact.matches(Regex("[0-9a-f]{12}"))) { "Scan network to identify the device MAC before connecting securely" }
    return "uwb-$compact.local"
}

internal fun openDeviceConnection(path: String, https: Boolean, fingerprint: String = "",
                                  deviceMac: String = "", trustedPublicKeyPin: String = "",
                                  acceptFirstUse: ((String) -> Unit)? = null,
                                  tlsConfiguration: DeviceTlsConfiguration? = null,
                                  trustRevision: String = ""): HttpURLConnection {
    val pin = fingerprint.filterNot { it == ':' || it.isWhitespace() }
    require(pin.isEmpty() || (pin.length == 64 && pin.all { it.digitToIntOrNull(16) != null })) {
        "TLS certificate fingerprint must be 64 hexadecimal characters"
    }
    val secure = https || pin.isNotEmpty() || deviceMac.isNotBlank() || trustedPublicKeyPin.isNotBlank()
    val connection = URL("${if (secure) "https" else "http"}://$path").openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.connectTimeout = if (secure) 5000 else 1000
    connection.readTimeout = if (secure) 5000 else 1500
    fun initializeTls() {
        if (pin.isNotEmpty()) {
            val expected = pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("Client certificates are not used")
                }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty() || !MessageDigest.isEqual(expected,
                            MessageDigest.getInstance("SHA-256").digest(chain[0].encoded))) {
                        throw CertificateException("Device certificate fingerprint mismatch")
                    }
                    chain[0].checkValidity()
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            (connection as HttpsURLConnection).sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trust), null)
            }.socketFactory
            // Keep HttpsURLConnection's default hostname verifier, including IP subjectAltName checks.
        } else if (deviceMac.isNotBlank() || trustedPublicKeyPin.isNotBlank() || acceptFirstUse != null) {
            val expectedName = deviceTlsName(deviceMac)
            fun matchesName(leaf: X509Certificate): Boolean =
                leaf.subjectAlternativeNames?.any { it[0] == 2 && expectedName.equals(it[1] as? String, true) } == true
            val identityTrust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw CertificateException("Client certificates are not used")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.isEmpty()) throw CertificateException("Device certificate is missing")
                    val leaf = chain[0]
                    leaf.checkValidity()
                    if (leaf.issuerX500Principal != leaf.subjectX500Principal) throw CertificateException("Expected a self-signed device identity")
                    leaf.verify(leaf.publicKey)
                    if (!matchesName(leaf)) throw CertificateException("Certificate does not identify this device")
                    val candidate = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                        .joinToString("") { "%02X".format(it.toInt() and 255) }
                    if (trustedPublicKeyPin.isNotEmpty()) requireFirstUseKey(trustedPublicKeyPin, candidate) { false }
                    else if (acceptFirstUse == null) throw CertificateException("Device has not been paired")
                    // The store atomically compares/saves again, protecting concurrent first connections.
                    acceptFirstUse?.invoke(candidate)
                }
            }
            (connection as HttpsURLConnection).sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(identityTrust), null)
            }.socketFactory
            // The MAC-derived DNS identity is stable even when the transport IP changes.
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                runCatching { matchesName(session.peerCertificates[0] as X509Certificate) }.getOrDefault(false)
            }
        }
    }
    if (connection is HttpsURLConnection && tlsConfiguration != null) {
        tlsConfiguration.configure(connection, listOf(pin, deviceMac, trustedPublicKeyPin, trustRevision), ::initializeTls)
    } else initializeTls()
    return connection
}

internal fun requireSuccessfulResponse(connection: HttpURLConnection) {
    val code = connection.responseCode
    if (code !in 200..299) throw IOException("${connection.url.protocol.uppercase()} ${connection.url.host}: HTTP $code ${connection.responseMessage}; redirects are not followed")
}


internal fun needsSystemClock(sample: JSONObject): Boolean =
    sample.optString("clock_source") == "phone" && sample.optLong("device_time_unix_ms", 0) <= 0

internal fun sendSystemClock(connection: HttpURLConnection) {
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json")
    connection.connect()
    val body = JSONObject().put("unix_ms", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
    connection.outputStream.use { it.write(body) }
    readDeviceResponse(connection, 8 * 1024)
}
