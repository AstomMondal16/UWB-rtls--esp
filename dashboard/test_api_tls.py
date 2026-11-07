"""Loopback-only checks: no UWB hardware or phone is contacted."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

from dashboard import api_json, sync_system_clock


@unittest.skipUnless(shutil.which("openssl"), "openssl is needed to issue test-only certificates")
class ApiTlsTest(unittest.TestCase):
    def check_server(self, hostname="localhost", wrong_pin=False, clock=False):
        with tempfile.TemporaryDirectory(prefix="uwb-loopback-test-") as directory:
            cert = Path(directory) / "cert.pem"
            key = Path(directory) / "key.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-days", "1", "-subj", "/CN=UWB loopback test",
                            "-addext", f"subjectAltName=DNS:{hostname}",
                            "-keyout", str(key), "-out", str(cert)],
                           capture_output=True, check=True)
            requests = []
            clock_payloads = []

            class Handler(BaseHTTPRequestHandler):
                def do_GET(self):
                    requests.append(self.path)
                    payload = b'{"valid":true}'
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    self.wfile.write(payload)

                def do_POST(self):
                    clock_payloads.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                    self.do_GET()

                def log_message(self, *args):
                    pass

            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(str(cert), str(key))
            server.socket = context.wrap_socket(server.socket, server_side=True)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
            credentials = {"test": {"tls_fingerprint": "00" * 32 if wrong_pin else pin}}
            try:
                with patch.dict(os.environ, {"UWB_DEVICE_CREDENTIALS": json.dumps(credentials)}):
                    address = f"https://localhost:{server.server_port}/api/telemetry"
                    if wrong_pin or hostname != "localhost":
                        with self.assertRaises(ssl.SSLError):
                            api_json(address, "test")
                        self.assertEqual([], requests)
                    elif clock:
                        sync_system_clock(address.removesuffix("/api/telemetry"), "test",
                                          {"clock_source": "phone", "device_time_unix_ms": 0})
                        self.assertEqual(["/api/time"], requests)
                        self.assertEqual(["unix_ms"], list(clock_payloads[0]))
                        self.assertGreater(clock_payloads[0]["unix_ms"], 1700000000000)
                    else:
                        self.assertEqual({"valid": True}, api_json(address, "test"))
                        self.assertEqual(["/api/telemetry"], requests)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=5)

    def test_matching_pin_and_hostname(self):
        self.check_server()

    def test_phone_clock_json_is_sent_over_verified_https(self):
        self.check_server(clock=True)

    def test_wrong_pin_never_sends_api_request(self):
        self.check_server(wrong_pin=True)

    def test_matching_pin_does_not_bypass_hostname(self):
        self.check_server(hostname="other.invalid")


if __name__ == "__main__":
    unittest.main()
