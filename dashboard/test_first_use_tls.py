"""First-use HTTPS trust tests with local mock boards; never contacts hardware."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

import dashboard

MAC = "02:00:00:00:00:01"
NAME = "uwb-020000000001.local"


@contextmanager
def device(name=NAME, expired=False, keep_alive=False):
    with tempfile.TemporaryDirectory(prefix="uwb-first-use-test-") as folder:
        folder = Path(folder)
        key, cert = folder / "key.pem", folder / "cert.pem"
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-nodes", "-days", "30", "-subj", f"/CN={name}", "-addext", f"subjectAltName=DNS:{name}",
            "-addext", "basicConstraints=critical,CA:FALSE", "-keyout", str(key), "-out", str(cert)],
            check=True, capture_output=True)
        if expired:
            output = folder / "expired.pem"
            subprocess.run(["openssl", "x509", "-in", str(cert), "-signkey", str(key), "-not_before", "20200101000000Z", "-not_after", "20200102000000Z", "-out", str(output)],
                           check=True, capture_output=True)
            cert = output
        class RequestLog(list):
            def __init__(self):
                super().__init__()
                self.tls_ports = []
                self.request_ports = []
        received = RequestLog()
        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1" if keep_alive else "HTTP/1.0"
            def do_GET(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                received.append((self.path, json.loads(body) if body else None))
                received.request_ports.append(self.client_address[1])
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", f"http://127.0.0.1:{server.server_port}/target")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                payload = json.dumps({"ok": True, "sequence": len(received)}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            do_POST = do_GET
            def log_message(self, *args):
                pass
        class Server(ThreadingHTTPServer):
            def get_request(self):
                connection, peer = super().get_request()
                received.tls_ports.append(peer[1])
                return connection, peer
        server = Server(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.maximum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(cert), str(key))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server.server_port, received
        finally:
            dashboard.close_device_api_connections()
            server.shutdown(); server.server_close(); thread.join(timeout=5)


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required for test-only TLS fixtures")
class FirstUseTlsTest(unittest.TestCase):
    def test_all_routes_first_use_persistence_address_changes_and_replacement_key(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"UWB_DEVICE_CREDENTIALS": "{}"}), \
             patch.object(dashboard, "TRUSTED_CERTIFICATES_PATH", Path(folder) / "trust.json"):
            with device() as (port, received):
                base = f"https://127.0.0.1:{port}"
                self.assertTrue(dashboard.api_json(base + "/api/telemetry", MAC)["ok"])
                original = dashboard.TRUSTED_CERTIFICATES_PATH.read_bytes()
                self.assertTrue(dashboard.api_json(f"https://localhost:{port}/api/cir", MAC)["ok"])
                dashboard.api_json(base + "/api/control", MAC, method="POST", body={"streaming": True})
                dashboard.sync_system_clock(base, MAC, {"clock_source": "phone", "device_time_unix_ms": 0})
                self.assertEqual(["/api/telemetry", "/api/cir", "/api/control", "/api/time"], [p for p, body in received])
                self.assertEqual({"streaming": True}, received[2][1])
                self.assertGreater(received[3][1]["unix_ms"], 1700000000000)
                self.assertEqual(original, dashboard.TRUSTED_CERTIFICATES_PATH.read_bytes())
                with self.assertRaises(OSError):
                    dashboard.api_json(base + "/redirect", MAC)
                self.assertEqual(5, len(received))
            with device() as (port, received):
                with self.assertRaises(ssl.SSLError):
                    dashboard.api_json(f"https://127.0.0.1:{port}/api/control", MAC, method="POST", body={"secret": True})
                self.assertEqual([], received)
                self.assertEqual(original, dashboard.TRUSTED_CERTIFICATES_PATH.read_bytes())

    def test_wrong_name_wildcard_and_expired_certificate_are_not_remembered(self):
        for name, expired in [("uwb-020000000002.local", False), ("*.local", False), (NAME, True)]:
            with self.subTest(name=name, expired=expired), tempfile.TemporaryDirectory() as folder, \
                 patch.dict(os.environ, {"UWB_DEVICE_CREDENTIALS": "{}"}), \
                 patch.object(dashboard, "TRUSTED_CERTIFICATES_PATH", Path(folder) / "trust.json"), \
                 device(name, expired) as (port, received):
                with self.assertRaises(ssl.SSLError):
                    dashboard.api_json(f"https://127.0.0.1:{port}/api/control", MAC, method="POST", body={"secret": True})
                self.assertEqual([], received)
                self.assertFalse(dashboard.TRUSTED_CERTIFICATES_PATH.exists())

    def test_storage_failure_aborts_before_the_api_request(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"UWB_DEVICE_CREDENTIALS": "{}"}), \
             patch.object(dashboard, "TRUSTED_CERTIFICATES_PATH", Path(folder) / "trust.json"), \
             device() as (port, received), patch.object(dashboard.os, "replace", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                dashboard.api_json(f"https://127.0.0.1:{port}/api/control", MAC, method="POST", body={"secret": True})
            self.assertEqual([], received)
            self.assertFalse(dashboard.TRUSTED_CERTIFICATES_PATH.exists())

    def test_keep_alive_removes_repeated_handshakes_without_losing_samples(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"UWB_DEVICE_CREDENTIALS": "{}"}), \
             patch.object(dashboard, "TRUSTED_CERTIFICATES_PATH", Path(folder) / "trust.json"), \
             device(keep_alive=True) as (port, received):
            url = f"https://127.0.0.1:{port}/api/telemetry"
            dashboard.close_device_api_connections()
            start = time.perf_counter()
            for index in range(20):
                self.assertEqual(index + 1, dashboard.api_json(url, MAC)["sequence"])
                dashboard.close_device_api_connections()  # Simulate the previous per-request lifecycle.
            before_ms = round((time.perf_counter() - start) * 1000)
            before_tls = len(received.tls_ports)
            before_ports = len(set(received.request_ports))
            start = time.perf_counter()
            for index in range(20):
                self.assertEqual(index + 21, dashboard.api_json(url, MAC)["sequence"])
            after_ms = round((time.perf_counter() - start) * 1000)
            after_tls = len(received.tls_ports) - before_tls
            after_ports = len(set(received.request_ports[20:]))
            self.assertEqual(40, before_tls)
            self.assertEqual(20, before_ports)
            self.assertEqual(2, after_tls)  # One certificate-only probe and one persistent API socket.
            self.assertEqual(1, after_ports)
            self.assertEqual(40, len(received))
            print(f"PYTHON_TLS_REUSE before_tls={before_tls} after_tls={after_tls} samples=20/20 before_ms={before_ms} after_ms={after_ms}")

    def test_cached_socket_cannot_bypass_changed_saved_trust(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"UWB_DEVICE_CREDENTIALS": "{}"}), \
             patch.object(dashboard, "TRUSTED_CERTIFICATES_PATH", Path(folder) / "trust.json"), \
             device(keep_alive=True) as (port, received):
            url = f"https://127.0.0.1:{port}/api/control"
            dashboard.api_json(url, MAC)
            dashboard.TRUSTED_CERTIFICATES_PATH.write_text(json.dumps({MAC: "00" * 32}), encoding="utf-8")
            with self.assertRaises(ssl.SSLError):
                dashboard.api_json(url, MAC, method="POST", body={"secret": True})
            self.assertEqual(1, len(received))

    def test_concurrent_pairing_and_corrupt_storage_do_not_replace_trust(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(dashboard, "TRUSTED_CERTIFICATES_PATH", Path(folder) / "trust.json"):
            failures = []
            gate = threading.Barrier(2)
            def remember(pin):
                gate.wait()
                try:
                    dashboard._remember_device_certificate(MAC, pin)
                except ssl.SSLError:
                    failures.append(pin)
            threads = [threading.Thread(target=remember, args=(p * 64,)) for p in ["a", "b"]]
            for thread in threads: thread.start()
            for thread in threads: thread.join(timeout=5)
            self.assertEqual(1, len(failures))
            saved = dashboard._read_device_trust()[MAC]
            self.assertNotEqual(saved, failures[0])
            dashboard.TRUSTED_CERTIFICATES_PATH.write_text('{"invalid":"corrupt"}', encoding="utf-8")
            with self.assertRaises(ValueError):
                dashboard._remember_device_certificate(MAC, "c" * 64)
