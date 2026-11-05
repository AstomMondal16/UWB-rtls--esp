package com.example.myapplication

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

class DeviceHttpClientTest {
    private fun withTls(san: String = "dns:localhost", expired: Boolean = false,
                        test: (String, String, AtomicInteger) -> Unit) {
        val folder = Files.createTempDirectory("uwb-test-tls").toFile()
        val file = java.io.File(folder, "test.p12")
        val keytool = java.io.File(System.getProperty("java.home"), "bin/keytool.exe")
            .takeIf { it.exists() } ?: java.io.File(System.getProperty("java.home"), "bin/keytool")
        val args = mutableListOf(keytool.path, "-genkeypair", "-alias", "test", "-keyalg", "EC",
            "-groupname", "secp256r1", "-dname", "CN=UWB loopback test", "-ext", "SAN=$san",
            "-validity", if (expired) "1" else "30", "-storetype", "PKCS12", "-keystore", file.path,
            "-storepass", "test-only-password", "-noprompt")
        if (expired) args.addAll(listOf("-startdate", "2020/01/01 00:00:00"))
        try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(log, 0, process.waitFor())
            val store = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, "test-only-password".toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "test-only-password".toCharArray())
            }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.httpsConfigurator = HttpsConfigurator(context)
            val requests = AtomicInteger()
            server.createContext("/api/") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.use { it.readBytes() }
                val bytes = "{\"valid\":true}".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val pin = MessageDigest.getInstance("SHA-256").digest(store.getCertificate("test").encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            try { test("localhost:${server.address.port}/api/telemetry", pin, requests) }
            finally { server.stop(0) }
        } finally { file.delete(); folder.delete() }
    }

    @Test fun phoneClockIsSentOnlyWhenRequestedAndUsesCurrentEpochMilliseconds() {
        assertFalse(needsSystemClock(org.json.JSONObject()))
        assertFalse(needsSystemClock(org.json.JSONObject().put("clock_source", "api")))
        assertFalse(needsSystemClock(org.json.JSONObject().put("clock_source", "phone").put("device_time_unix_ms", 1793879430391L)))
        assertTrue(needsSystemClock(org.json.JSONObject().put("clock_source", "phone").put("device_time_unix_ms", 0)))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val received = java.util.concurrent.atomic.AtomicLong()
        server.createContext("/api/time") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("application/json", exchange.requestHeaders.getFirst("Content-Type"))
            received.set(org.json.JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }).getLong("unix_ms"))
            val body = "{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val before = System.currentTimeMillis()
        val connection = openDeviceConnection("127.0.0.1:${server.address.port}/api/time", false)
        try { sendSystemClock(connection) } finally { connection.disconnect(); server.stop(0) }
        assertTrue(received.get() in before..System.currentTimeMillis())
    }

    @Test fun correctPinAndHostnameWorkButWrongPinAndSystemUntrustedFail() = withTls { path, pin, requests ->
        val good = openDeviceConnection(path, false, pin)
        assertEquals("https", good.url.protocol)
        try { requireSuccessfulResponse(good); assertTrue(good.inputStream.bufferedReader().use { it.readText() }.contains("valid")) }
        finally { good.disconnect() }
        for (route in listOf("cir", "control")) {
            val connection = openDeviceConnection(path.replace("telemetry", route), true, pin)
            try {
                if (route == "control") {
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.outputStream.use { it.write("{\"streaming\":true}".toByteArray()) }
                }
                requireSuccessfulResponse(connection)
            } finally { connection.disconnect() }
        }
        assertEquals(3, requests.get())
        for (wrongPin in listOf("00".repeat(32), "")) {
            val bad = openDeviceConnection(path, true, wrongPin)
            try { assertThrows(SSLException::class.java) { requireSuccessfulResponse(bad) } }
            finally { bad.disconnect() }
        }
        assertEquals("Rejected TLS must not reach the API", 3, requests.get())
    }

    @Test fun matchingPinDoesNotBypassHostnameVerification() = withTls("dns:other.invalid") { path, pin, requests ->
        val connection = openDeviceConnection(path, true, pin)
        connection.requestMethod = "POST"; connection.doOutput = true
        try { assertThrows(java.io.IOException::class.java) {
            connection.outputStream.use { it.write("sensitive control payload".toByteArray()) }
        } } finally { connection.disconnect() }
        assertEquals(0, requests.get())
    }

    @Test fun expiredPinnedCertificateFails() = withTls(expired = true) { path, pin, requests ->
        val connection = openDeviceConnection(path, true, pin)
        try { assertThrows(SSLException::class.java) { requireSuccessfulResponse(connection) } }
        finally { connection.disconnect() }
        assertEquals(0, requests.get())
    }

    @Test fun legacyLocalHttpWorksAndRedirectsAreRejected() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val targetRequests = AtomicInteger()
        server.createContext("/api/telemetry") { e -> e.sendResponseHeaders(200, 2); e.responseBody.use { it.write("{}".toByteArray()) } }
        server.createContext("/redirect") { e -> e.responseHeaders.add("Location", "/target"); e.sendResponseHeaders(302, -1); e.close() }
        server.createContext("/target") { e -> targetRequests.incrementAndGet(); e.sendResponseHeaders(200, -1); e.close() }
        server.start()
        try {
            val good = openDeviceConnection("127.0.0.1:${server.address.port}/api/telemetry", false)
            try { requireSuccessfulResponse(good) } finally { good.disconnect() }
            val redirect = openDeviceConnection("127.0.0.1:${server.address.port}/redirect", false)
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(redirect) } }
            finally { redirect.disconnect() }
            assertEquals(0, targetRequests.get())
        } finally { server.stop(0) }
    }
}
