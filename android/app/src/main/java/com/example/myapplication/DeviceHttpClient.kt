package com.example.myapplication

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** A configured fingerprint is a trust anchor, never a replacement for hostname verification. */
internal fun openDeviceConnection(path: String, https: Boolean, fingerprint: String = ""): HttpURLConnection {
    val pin = fingerprint.filterNot { it == ':' || it.isWhitespace() }
    require(pin.isEmpty() || (pin.length == 64 && pin.all { it.digitToIntOrNull(16) != null })) {
        "TLS certificate fingerprint must be 64 hexadecimal characters"
    }
    val secure = https || pin.isNotEmpty()
    val connection = URL("${if (secure) "https" else "http"}://$path").openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = false
    connection.useCaches = false
    connection.connectTimeout = if (secure) 5000 else 1000
    connection.readTimeout = if (secure) 5000 else 1500
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
    }
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
    requireSuccessfulResponse(connection)
    connection.inputStream.close()
}
