package com.example.myapplication

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

class FirstUseHttpsTest {
    private fun withDeviceCertificate(name: String, expired: Boolean = false, invalidSignature: Boolean = false, test: (String, MutableList<String>) -> Unit) {
        val folder = Files.createTempDirectory("uwb-product-tls-").toFile()
        val openssl = System.getenv("UWB_OPENSSL") ?: File("C:/Espressif/tools/git/usr/bin/openssl.exe")
            .takeIf { it.exists() }?.path ?: "openssl"
        fun run(vararg arguments: String) {
            val process = ProcessBuilder(listOf(openssl) + arguments).directory(folder).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            assertEquals(log, 0, process.waitFor())
        }
        try {
            run("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                "-days", "30", "-keyout", "device.key", "-out", "device.pem", "-subj", "/CN=$name",
                "-addext", "subjectAltName=DNS:$name", "-addext", "basicConstraints=critical,CA:FALSE",
                "-addext", "keyUsage=critical,digitalSignature", "-addext", "extendedKeyUsage=serverAuth")
            if (expired) {
                run("x509", "-in", "device.pem", "-signkey", "device.key", "-not_before", "20200101000000Z",
                    "-not_after", "20200102000000Z", "-out", "expired.pem")
                File(folder, "device.pem").writeBytes(File(folder, "expired.pem").readBytes())
            }
            if (invalidSignature) {
                val pem = File(folder, "device.pem").readText()
                val encoded = pem.lineSequence().filterNot { it.startsWith("---") }.joinToString("")
                val der = java.util.Base64.getDecoder().decode(encoded)
                der[der.lastIndex] = (der.last().toInt() xor 1).toByte()
                File(folder, "device.pem").writeText("-----BEGIN CERTIFICATE-----\n" +
                    java.util.Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(der) + "\n-----END CERTIFICATE-----\n")
            }
            run("pkcs12", "-export", "-inkey", "device.key", "-in", "device.pem",
                "-out", "server.p12", "-passout", "pass:test-only")
            val store = KeyStore.getInstance("PKCS12").apply {
                File(folder, "server.p12").inputStream().use { load(it, "test-only".toCharArray()) }
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "test-only".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
            val requests = mutableListOf<String>()
            val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.httpsConfigurator = HttpsConfigurator(context)
            server.createContext("/") { exchange ->
                synchronized(requests) { requests += exchange.requestURI.path }
                exchange.requestBody.use { it.readBytes() }
                if (exchange.requestURI.path == "/redirect") {
                    exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/target")
                    exchange.sendResponseHeaders(302, -1)
                } else {
                    val bytes = "{\"ok\":true,\"connection_port\":${exchange.remoteAddress.port},\"sequence\":${requests.size}}".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            server.start()
            try { test("127.0.0.1:${server.address.port}", requests) }
            finally { server.stop(0) }
        } finally { folder.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun firstUseSavesIdentityAndLaterConnectionsVerifyItAcrossTransportAddresses() =
        withDeviceCertificate("uwb-a4cb8fe0d3c4.local") { host, requests ->
            val mac = "A4:CB:8F:E0:D3:C4"
            var saved = ""
            var writes = 0
            val accept: (String) -> Unit = { candidate -> requireFirstUseKey(saved, candidate) { saved = candidate; writes++; true } }
            for (route in listOf("telemetry", "cir", "control", "time")) {
                val address = if (route == "cir") host.replace("127.0.0.1", "localhost") else host
                val connection = openDeviceConnection("$address/api/$route", true, deviceMac = mac,
                    trustedPublicKeyPin = saved, acceptFirstUse = accept)
                try {
                    if (route == "time") sendSystemClock(connection)
                    else {
                        if (route == "control") {
                            connection.requestMethod = "POST"; connection.doOutput = true
                            connection.outputStream.use { it.write("{}".toByteArray()) }
                        }
                        requireSuccessfulResponse(connection)
                    }
                } finally { connection.disconnect() }
            }
            assertEquals(4, requests.size)
            assertEquals(64, saved.length)
            assertEquals(1, writes)
            val original = saved
            withDeviceCertificate("uwb-a4cb8fe0d3c4.local") { changedHost, changedRequests ->
                val changed = openDeviceConnection("$changedHost/api/control", true, deviceMac = mac,
                    trustedPublicKeyPin = saved, acceptFirstUse = accept)
                try {
                    changed.requestMethod = "POST"; changed.doOutput = true
                    assertThrows(java.io.IOException::class.java) { changed.outputStream.use { it.write("secret".toByteArray()) } }
                } finally { changed.disconnect() }
                assertTrue(changedRequests.isEmpty())
                assertEquals(original, saved)
            }
            val wrongBoard = openDeviceConnection("$host/api/control", true, deviceMac = "02:00:00:00:00:01", acceptFirstUse = accept)
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(wrongBoard) } }
            finally { wrongBoard.disconnect() }
            val cannotSave = openDeviceConnection("$host/api/control", true, deviceMac = mac,
                acceptFirstUse = { throw java.security.cert.CertificateException("Storage unavailable") })
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(cannotSave) } }
            finally { cannotSave.disconnect() }
            assertEquals(4, requests.size)
            val redirect = openDeviceConnection("$host/redirect", true, deviceMac = mac, trustedPublicKeyPin = saved)
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(redirect) } }
            finally { redirect.disconnect() }
            assertEquals(5, requests.size)
            assertFalse(requests.contains("/target"))
        }

    @Test fun wildcardCertificateCannotImpersonateADevice() =
        withDeviceCertificate("*.local") { host, requests ->
            var saved = false
            val connection = openDeviceConnection("$host/api/control", true, deviceMac = "A4:CB:8F:E0:D3:C4", acceptFirstUse = { saved = true })
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(connection) } }
            finally { connection.disconnect() }
            assertTrue(requests.isEmpty())
            assertFalse(saved)
        }

    @Test fun anIpSanCannotBypassTheExpectedDeviceIdentity() =
        withDeviceCertificate("uwb-020000000001.local,IP:127.0.0.1") { host, requests ->
            var saved = false
            val connection = openDeviceConnection("$host/api/control", true, deviceMac = "A4:CB:8F:E0:D3:C4", acceptFirstUse = { saved = true })
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(connection) } }
            finally { connection.disconnect() }
            assertTrue(requests.isEmpty())
            assertFalse(saved)
        }

    @Test fun expiredFirstUseCertificateIsNotRemembered() =
        withDeviceCertificate("uwb-a4cb8fe0d3c4.local", expired = true) { host, requests ->
            var saved = false
            val connection = openDeviceConnection("$host/api/control", true, deviceMac = "A4:CB:8F:E0:D3:C4", acceptFirstUse = { saved = true })
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(connection) } }
            finally { connection.disconnect() }
            assertFalse(saved)
            assertTrue(requests.isEmpty())
        }

    @Test fun invalidSelfSignatureIsNotRemembered() =
        withDeviceCertificate("uwb-a4cb8fe0d3c4.local", invalidSignature = true) { host, requests ->
            var saved = false
            val connection = openDeviceConnection("$host/api/control", true, deviceMac = "A4:CB:8F:E0:D3:C4", acceptFirstUse = { saved = true })
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(connection) } }
            finally { connection.disconnect() }
            assertFalse(saved)
            assertTrue(requests.isEmpty())
        }

    @Test fun repeatedPollingReusesOneTlsConnectionAndPreservesEverySample() =
        withDeviceCertificate("uwb-a4cb8fe0d3c4.local") { host, requests ->
            val mac = "A4:CB:8F:E0:D3:C4"
            var saved = ""
            val accept: (String) -> Unit = { candidate -> requireFirstUseKey(saved, candidate) { saved = candidate; true } }
            val beforePorts = mutableSetOf<Int>()
            val beforeStart = System.nanoTime()
            repeat(20) { index ->
                val connection = openDeviceConnection("$host/api/telemetry", true, deviceMac = mac,
                    trustedPublicKeyPin = saved, acceptFirstUse = accept)
                try {
                    val value = org.json.JSONObject(readDeviceResponse(connection, 4096))
                    assertEquals(index + 1, value.getInt("sequence"))
                    beforePorts += value.getInt("connection_port")
                } finally { connection.disconnect() }
            }
            val beforeMs = (System.nanoTime() - beforeStart) / 1_000_000
            val configuration = DeviceTlsConfiguration()
            val afterPorts = mutableSetOf<Int>()
            val afterStart = System.nanoTime()
            repeat(20) { index ->
                val connection = openDeviceConnection("$host/api/telemetry", true, deviceMac = mac,
                    trustedPublicKeyPin = saved, acceptFirstUse = accept, tlsConfiguration = configuration)
                val value = withReusableDeviceConnection(connection) { org.json.JSONObject(readDeviceResponse(connection, 4096)) }
                assertEquals(index + 21, value.getInt("sequence"))
                afterPorts += value.getInt("connection_port")
            }
            val afterMs = (System.nanoTime() - afterStart) / 1_000_000
            assertEquals(20, beforePorts.size)
            assertEquals("20 fully read requests should share one verified TLS socket", 1, afterPorts.size)
            assertEquals(40, requests.size)
            println("TLS_REUSE_CHECK before_connections=${beforePorts.size} after_connections=${afterPorts.size} samples=20/20 before_ms=$beforeMs after_ms=$afterMs")
        }

    @Test fun trustRevisionChangesReverifyEvenWhenPinIsUnchangedOrEmpty() =
        withDeviceCertificate("uwb-a4cb8fe0d3c4.local") { host, requests ->
            val configuration = DeviceTlsConfiguration()
            var saved = ""
            var verifications = 0
            val accept: (String) -> Unit = { candidate -> verifications++; requireFirstUseKey(saved, candidate) { saved = candidate; true } }
            fun read(revision: String): Int {
                val connection = openDeviceConnection("$host/api/telemetry", true, deviceMac = "A4:CB:8F:E0:D3:C4",
                    trustedPublicKeyPin = "", acceptFirstUse = accept, tlsConfiguration = configuration, trustRevision = revision)
                return withReusableDeviceConnection(connection) { org.json.JSONObject(readDeviceResponse(connection, 4096)).getInt("connection_port") }
            }
            val first = read("initial")
            assertEquals(first, read("initial"))
            assertEquals(1, verifications)
            saved = "" // Simulate explicit re-pairing before a pinned profile was ever opened.
            assertNotEquals(first, read("explicit-reset"))
            assertEquals(2, verifications)
            assertEquals(64, saved.length)
            val invalidPin = openDeviceConnection("$host/api/control", true, deviceMac = "A4:CB:8F:E0:D3:C4",
                trustedPublicKeyPin = "00".repeat(32), tlsConfiguration = configuration, trustRevision = "changed-pin")
            try { assertThrows(java.io.IOException::class.java) { requireSuccessfulResponse(invalidPin) } }
            finally { invalidPin.disconnect() }
            assertEquals(3, requests.size)
        }

    @Test fun successfulBodiesAreReusableButOversizedResponsesCloseTheConnection() =
        withDeviceCertificate("uwb-a4cb8fe0d3c4.local") { host, requests ->
            val configuration = DeviceTlsConfiguration()
            var saved = ""
            fun open() = openDeviceConnection("$host/api/telemetry", true, deviceMac = "A4:CB:8F:E0:D3:C4",
                trustedPublicKeyPin = "", acceptFirstUse = { candidate -> requireFirstUseKey(saved, candidate) { saved = candidate; true } },
                tlsConfiguration = configuration)
            val initial = open()
            val first = withReusableDeviceConnection(initial) { org.json.JSONObject(readDeviceResponse(initial, 4096)).getInt("connection_port") }
            val oversized = open()
            assertThrows(java.io.IOException::class.java) { withReusableDeviceConnection(oversized) { readDeviceResponse(oversized, 1) } }
            val recovered = open()
            val next = withReusableDeviceConnection(recovered) { org.json.JSONObject(readDeviceResponse(recovered, 4096)).getInt("connection_port") }
            assertNotEquals(first, next)
            assertEquals(3, requests.size)
        }

    @Test fun trustPolicyDoesNotSilentlyReplacePinsOrIgnoreStorageFailures() {
        val a = "AA".repeat(32)
        val b = "BB".repeat(32)
        var saved = ""
        var writes = 0
        requireFirstUseKey(saved, a) { saved = a; writes++; true }
        requireFirstUseKey(saved.lowercase(), a) { fail("Existing trust must not be rewritten"); false }
        assertThrows(java.security.cert.CertificateException::class.java) { requireFirstUseKey(saved, b) { saved = b; true } }
        assertThrows(java.security.cert.CertificateException::class.java) { requireFirstUseKey("", a) { false } }
        assertThrows(java.security.cert.CertificateException::class.java) { requireFirstUseKey("corrupt", a) { true } }
        assertEquals(a, saved)
        assertEquals(1, writes)
        assertEquals(a, mergeDeviceTrust(a, "", false))
        assertEquals(a, mergeDeviceTrust(a, a, false))
        assertThrows(java.security.cert.CertificateException::class.java) { mergeDeviceTrust(a, b, false) }
        assertEquals("", mergeDeviceTrust(a, b, true))
    }

    @Test fun concurrentFirstConnectionsKeepOneIdentity() {
        var saved = ""
        var writes = 0
        val lock = Any()
        val start = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val threads = listOf("AA", "BB").map { prefix ->
            Thread {
                start.await()
                try {
                    synchronized(lock) { requireFirstUseKey(saved, prefix.repeat(32)) { saved = prefix.repeat(32); writes++; true } }
                    accepted.incrementAndGet()
                } catch (_: java.security.cert.CertificateException) { }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, accepted.get())
        assertEquals(1, writes)
    }
}
