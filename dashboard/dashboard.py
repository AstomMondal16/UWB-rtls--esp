#!/usr/bin/env python3
"""UWB + MPU6050 desktop dashboard. Python standard library only."""

import argparse
import csv
import hashlib
import hmac
import json
import math
import os
import queue
import socket
import ssl
import statistics
import threading
import time
import tkinter as tk
from collections import deque
from functools import lru_cache
from pathlib import Path
from tempfile import TemporaryDirectory, NamedTemporaryFile
from tkinter import filedialog
from urllib.error import URLError
from urllib.parse import urlsplit
from http.client import HTTPSConnection, HTTPConnection

BG = "#f5f7fa"
PANEL = "#ffffff"
PANEL_2 = "#edf0f5"
GRID = "#e1e6ed"
GRID_MAJOR = "#c3ccd8"
TEXT = "#111827"
MUTED = "#4b5565"
CYAN = "#2857a8"
GREEN = "#286941"
AMBER = "#955600"
RED = "#ba1a1a"
ENTRY_BG = "#ffffff"
SELECT_BG = "#dce5ff"
BORDER = "#d8dee7"
POPUP_BG = PANEL
DEVICE_FACES = ("#dbe4f1", "#c4d3e8", "#d1ddeb",
                "#b7c9e1", "#c2d1e6", "#e3eaf3")
DISCOVERY_PORT = 4210
DW1000_TIME_UNIT_S = 1 / (499_200_000 * 128)
DW1000_AIR_SPEED_MPS = 299_702_547
CALIBRATION_SAMPLES = 100
DEVICE_HISTORY_FILE = Path.home() / ".spatial_node_devices.json"
LOGO_FILE = Path(__file__).resolve().parent / "assets" / "sense-logo.png"
MAX_TELEMETRY_BYTES = 32 * 1024
MAX_CONTROL_BYTES = 8 * 1024
TRUSTED_CERTIFICATES_PATH = Path.home() / ".uwb_trusted_certificates.json"
_device_trust_lock = threading.Lock()


def read_json_response(response, max_bytes):
    body = response.read(max_bytes + 1)
    if len(body) > max_bytes:
        raise ValueError(f"HTTP response exceeds {max_bytes} bytes")
    return json.loads(body.decode("utf-8"))


def sync_system_clock(host, device_id, data):
    if data.get("clock_source") == "phone" and data.get("device_time_unix_ms", 0) <= 0:
        api_json(host + "/api/time", device_id, method="POST",
                 body={"unix_ms": time.time_ns() // 1_000_000})


class DeviceHTTPSConnection(HTTPSConnection):
    """Connect to a discovered IP while verifying the board's stable DNS identity."""
    def __init__(self, ip, port, context, tls_name, fingerprint, verify_device_name=True):
        super().__init__(ip, port, timeout=5.0, context=context)
        self.device_context = context
        self.tls_name = tls_name
        self.fingerprint = fingerprint
        self.verify_device_name = verify_device_name

    def connect(self):
        HTTPConnection.connect(self)
        try:
            self.sock = self.device_context.wrap_socket(self.sock, server_hostname=self.tls_name)
            names = self.sock.getpeercert().get("subjectAltName", ())
            if self.verify_device_name and not any(kind == "DNS" and name.lower() == self.tls_name.lower() for kind, name in names):
                raise ssl.CertificateError("Certificate does not identify this device")
            actual = hashlib.sha256(self.sock.getpeercert(binary_form=True)).hexdigest()
            if not hmac.compare_digest(actual, self.fingerprint):
                raise ssl.SSLError("Device TLS certificate changed during handshake")
        except Exception:
            self.close()
            raise


def _read_device_trust():
    if not TRUSTED_CERTIFICATES_PATH.exists():
        return {}
    value = json.loads(TRUSTED_CERTIFICATES_PATH.read_text(encoding="utf-8"))
    if not isinstance(value, dict) or any(not isinstance(pin, str) or len(pin) != 64 or
            any(c not in "0123456789abcdefABCDEF" for c in pin) for pin in value.values()):
        raise ValueError("Saved device trust is invalid; repair it explicitly before pairing")
    return value


def _remember_device_certificate(identity, candidate):
    with _device_trust_lock:
        trusted = _read_device_trust()
        saved = trusted.get(identity)
        if saved and not hmac.compare_digest(saved.lower(), candidate.lower()):
            raise ssl.SSLError("Device identity changed; pair again explicitly on trusted Wi-Fi")
        if saved:
            return
        trusted[identity] = candidate
        temporary = None
        try:
            with NamedTemporaryFile(mode="w", encoding="utf-8", dir=TRUSTED_CERTIFICATES_PATH.parent,
                                    prefix="uwb-trust-", suffix=".tmp", delete=False) as output:
                temporary = Path(output.name)
                json.dump(trusted, output)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, TRUSTED_CERTIFICATES_PATH)
        finally:
            if temporary and temporary.exists():
                temporary.unlink()


class _ApiSession:
    def __init__(self):
        self.lock = threading.Lock()
        self.connection = None
        self.fingerprint = ""
        self.manual = False

    def close(self):
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def __del__(self):
        self.close()


@lru_cache(maxsize=32)
def _cached_api_session(identity, host, port, trust_file):
    # Active callers retain their session when its idle cache entry is evicted.
    return _ApiSession()


def close_device_api_connections():
    _cached_api_session.cache_clear()


def api_json(address, device_id, method="GET", body=None, max_bytes=MAX_TELEMETRY_BYTES):
    parsed = urlsplit(address)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("The device API requires HTTPS")
    identity = normalize_mac(device_id)
    credentials = json.loads(os.environ.get("UWB_DEVICE_CREDENTIALS", "{}"))
    if not isinstance(credentials, dict):
        raise ValueError("Device credentials must be a JSON object")
    secret = credentials.get(identity or str(device_id).strip().lower(), {})
    if not isinstance(secret, dict) or not isinstance(secret.get("tls_fingerprint", ""), str):
        raise ValueError("Device TLS settings must contain a string fingerprint")
    fingerprint = secret.get("tls_fingerprint", "").replace(":", "").replace(" ", "")
    if fingerprint and (len(fingerprint) != 64 or any(c not in "0123456789abcdefABCDEF" for c in fingerprint)):
        raise ValueError("Device TLS fingerprint must be 64 hexadecimal characters")
    if not fingerprint and not identity:
        raise ValueError("Scan network to identify the device MAC before connecting securely")
    session = _cached_api_session(identity or str(device_id).strip().lower(), parsed.hostname,
                                  parsed.port or 443, str(TRUSTED_CERTIFICATES_PATH))
    with session.lock:
        if fingerprint:
            saved = fingerprint.lower()
        else:
            with _device_trust_lock:
                saved = _read_device_trust().get(identity, "").lower()
        if session.connection is not None and (session.manual != bool(fingerprint) or session.fingerprint != saved):
            session.close()
        try:
            if session.connection is None:
                # Certificate-only probe is needed once, not before every telemetry request.
                pem = ssl.get_server_certificate((parsed.hostname, parsed.port or 443), timeout=5.0)
                candidate = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
                if saved and not hmac.compare_digest(saved, candidate):
                    raise ssl.SSLError("Device identity changed; pair again explicitly on trusted Wi-Fi")
                context = ssl.create_default_context(cadata=pem)
                if hasattr(ssl, "VERIFY_X509_PARTIAL_CHAIN"):
                    context.verify_flags |= ssl.VERIFY_X509_PARTIAL_CHAIN
                session.connection = DeviceHTTPSConnection(parsed.hostname, parsed.port or 443, context,
                    parsed.hostname if fingerprint else "uwb-" + identity.replace(":", "").lower() + ".local",
                    candidate, verify_device_name=not bool(fingerprint))
                session.fingerprint = candidate
                session.manual = bool(fingerprint)
            connection = session.connection
            if connection.sock is None:
                connection.connect()
            if not fingerprint:
                _remember_device_certificate(identity, session.fingerprint)
            headers = {"Cache-Control": "no-cache"}
            if body is not None:
                headers["Content-Type"] = "application/json"
                if isinstance(body, dict):
                    body = json.dumps(body).encode("utf-8")
            connection.request(method, parsed.path or "/", body=body, headers=headers)
            response = connection.getresponse()
            try:
                payload = response.read(max_bytes + 1)
                if len(payload) > max_bytes:
                    raise ValueError(f"HTTP response exceeds {max_bytes} bytes")
                if response.status != 200:
                    raise OSError(f"Device HTTP status {response.status}: {response.reason}")
                return json.loads(payload.decode("utf-8"))
            finally:
                response.close()
        except Exception:
            session.close()
            raise


def normalize_mac(value):
    if not isinstance(value, str):
        return None
    digits = "".join(ch for ch in value.upper() if ch in "0123456789ABCDEF")
    if len(digits) != 12:
        return None
    return ":".join(digits[i:i + 2] for i in range(0, 12, 2))


def discover_by_mac(mac, timeout=2.0):
    wanted = normalize_mac(mac)
    if not wanted:
        raise ValueError("Enter a 12-digit MAC such as AA:BB:CC:DD:EE:FF")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(0.2)
    sock.bind(("", 0))
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            sock.sendto(b"UWB_DISCOVER", ("255.255.255.255", DISCOVERY_PORT))
            listen_until = min(deadline, time.monotonic() + 0.35)
            while time.monotonic() < listen_until:
                try:
                    payload, source = sock.recvfrom(512)
                    device = json.loads(payload.decode("utf-8"))
                    if not isinstance(device, dict):
                        continue
                    if normalize_mac(device.get("mac", "")) == wanted:
                        return source[0]
                except socket.timeout:
                    break
                except (UnicodeError, ValueError, KeyError):
                    continue
    finally:
        sock.close()
    raise TimeoutError(f"No device with MAC {wanted} answered on this network")


def load_device_history(path=DEVICE_HISTORY_FILE):
    try:
        devices = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return []
    if not isinstance(devices, list):
        return []
    return [device for device in devices[:8]
            if isinstance(device, dict) and (device.get("mac") or device.get("ip"))]


def save_device_history(devices, path=DEVICE_HISTORY_FILE):
    try:
        path.write_text(json.dumps(devices[:8], indent=2), encoding="utf-8")
    except OSError:
        pass


def forget_device(devices, forgotten):
    return [device for device in devices if device != forgotten]


def calibrated_antenna_delay(current, measured_m, actual_m):
    """Tune one device's equal TX/RX delay from a static pair measurement."""
    if not 0 <= current <= 65535 or not 0.01 <= actual_m <= 1000:
        raise ValueError("invalid antenna delay or known distance")
    correction = (measured_m - actual_m) / (
        2 * DW1000_AIR_SPEED_MPS * DW1000_TIME_UNIT_S)
    result = round(current + correction)
    if not 0 <= result <= 65535:
        raise ValueError("calculated antenna delay is outside 0..65535")
    return result


def adjusted_zoom(current, direction):
    factor = 1.12 if direction > 0 else 1 / 1.12
    return max(0.45, min(2.5, current * factor))


def endpoint_key(value):
    endpoint = str(value).strip().lower().rstrip("/")
    if endpoint.startswith("http://"):
        endpoint = endpoint[7:]
    elif endpoint.startswith("https://"):
        endpoint = endpoint[8:]
    return endpoint.split("/", 1)[0]


def event_is_current(source, current):
    return source is None or (source is current and not source.is_set())


def rotate_point(point, roll, pitch, yaw):
    x, y, z = point
    cr, sr = math.cos(roll), math.sin(roll)
    cp, sp = math.cos(pitch), math.sin(pitch)
    cy, sy = math.cos(yaw), math.sin(yaw)
    y, z = y * cr - z * sr, y * sr + z * cr
    x, z = x * cp + z * sp, -x * sp + z * cp
    return x * cy - y * sy, x * sy + y * cy, z


def quaternion_multiply(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw)


def quaternion_euler(q):
    w, x, y, z = q
    roll = math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y))
    pitch = math.asin(max(-1.0, min(1.0, 2 * (w * y - z * x))))
    yaw = math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))
    return tuple(math.degrees(value) for value in (roll, pitch, yaw))


def rotate_quaternion(point, q):
    w, x, y, z = q
    px, py, pz = point
    tx, ty, tz = (2 * (y * pz - z * py), 2 * (z * px - x * pz),
                  2 * (x * py - y * px))
    return (px + w * tx + y * tz - z * ty,
            py + w * ty + z * tx - x * tz,
            pz + w * tz + x * ty - y * tx)


class Orientation:
    """Mahony 6-axis quaternion fusion with stationary gyro-bias tracking."""

    def __init__(self):
        self.q = (1.0, 0.0, 0.0, 0.0)
        self.zero_q = self.q
        self.bias = [0.0, 0.0, 0.0]
        self.integral = [0.0, 0.0, 0.0]
        self.gravity = None
        self.stationary_samples = 0
        self.recent_still_gyro = deque(maxlen=15)
        self.last_ms = None

    def update(self, sample):
        if not sample.get("imu_valid"):
            return
        ax, ay, az = sample["accel"]
        raw_gyro = sample["gyro"]
        stamp = sample["timestamp_ms"]
        accel_norm = math.sqrt(ax * ax + ay * ay + az * az)
        if accel_norm < 0.2:
            return
        measured_gravity = [ax / accel_norm, ay / accel_norm, az / accel_norm]
        if self.last_ms is None:
            roll = math.atan2(ay, az or 1e-9)
            pitch = math.atan2(-ax, math.hypot(ay, az))
            cr, sr = math.cos(roll / 2), math.sin(roll / 2)
            cp, sp = math.cos(pitch / 2), math.sin(pitch / 2)
            self.q = (cr * cp, sr * cp, cr * sp, -sr * sp)
            self.gravity = measured_gravity
            self.last_ms = stamp
            return
        if stamp <= self.last_ms:
            return

        dt = min((stamp - self.last_ms) / 1000.0, 0.25)
        self.last_ms = stamp
        gravity_change = math.sqrt(sum(
            (measured_gravity[i] - self.gravity[i]) ** 2 for i in range(3)))
        raw_gyro_norm = math.sqrt(sum(value * value for value in raw_gyro))
        if (0.88 < accel_norm < 1.12 and raw_gyro_norm < 5.0 and
                gravity_change < .04):
            self.recent_still_gyro.append(tuple(raw_gyro))
        else:
            self.recent_still_gyro.clear()

        gyro = [raw_gyro[i] - self.bias[i] for i in range(3)]
        gyro_norm = math.sqrt(sum(value * value for value in gyro))
        stationary = (0.92 < accel_norm < 1.08 and gyro_norm < 1.2 and
                      gravity_change < .035)
        self.stationary_samples = self.stationary_samples + 1 if stationary else 0
        gravity_alpha = .08 if stationary else .25
        self.gravity = [self.gravity[i] * (1 - gravity_alpha) +
                        measured_gravity[i] * gravity_alpha for i in range(3)]
        gravity_norm = math.sqrt(sum(value * value for value in self.gravity))
        ax, ay, az = (value / gravity_norm for value in self.gravity)

        if stationary:
            for axis in range(3):
                self.bias[axis] = .95 * self.bias[axis] + .05 * raw_gyro[axis]
        gyro = [raw_gyro[i] - self.bias[i] for i in range(3)]
        gyro = [0.0 if abs(value) < .08 else value for value in gyro]
        if self.stationary_samples >= 5:
            gyro = [0.0, 0.0, 0.0]

        w, x, y, z = self.q
        vx, vy = 2 * (x * z - w * y), 2 * (w * x + y * z)
        vz = w * w - x * x - y * y + z * z
        error = (ay * vz - az * vy, az * vx - ax * vz,
                 ax * vy - ay * vx)
        error_norm = math.sqrt(sum(value * value for value in error))
        if self.stationary_samples >= 5:
            self.integral = [0.0, 0.0, 0.0]
            if error_norm < math.sin(math.radians(.75)):
                return
        for axis in range(3):
            self.integral[axis] += .02 * error[axis] * dt
        gx, gy, gz = (math.radians(gyro[i]) + 2.0 * error[i] +
                      self.integral[i] for i in range(3))
        qdot = (-x * gx - y * gy - z * gz,
                w * gx + y * gz - z * gy,
                w * gy - x * gz + z * gx,
                w * gz + x * gy - y * gx)
        q = tuple(self.q[i] + .5 * qdot[i] * dt for i in range(4))
        norm = math.sqrt(sum(value * value for value in q))
        self.q = tuple(value / norm for value in q)

    def zero(self, calibrate=False):
        calibrated = calibrate and len(self.recent_still_gyro) >= 5
        if calibrated:
            self.bias = [statistics.median(sample[axis]
                                           for sample in self.recent_still_gyro)
                         for axis in range(3)]
            self.integral = [0.0, 0.0, 0.0]
            self.stationary_samples = 5
        w, x, y, z = self.q
        self.zero_q = (w, -x, -y, -z)
        return calibrated

    @property
    def display_quaternion(self):
        return quaternion_multiply(self.zero_q, self.q)

    @property
    def display(self):
        return quaternion_euler(self.display_quaternion)

    @property
    def yaw(self):
        return quaternion_euler(self.q)[2]


class MetricCard(tk.Frame):
    def __init__(self, parent, title, unit, color=CYAN):
        super().__init__(parent, bg=PANEL, highlightbackground=BORDER,
                         highlightthickness=1, padx=16, pady=12)
        tk.Label(self, text=title.upper(), bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 10, "bold")).pack(anchor="w")
        row = tk.Frame(self, bg=PANEL)
        row.pack(fill="x", pady=(7, 0))
        self.value = tk.Label(row, text="—", bg=PANEL, fg=color,
                              font=("Consolas", 24, "bold"))
        self.value.pack(side="left")
        tk.Label(row, text=unit, bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 11)).pack(side="left", padx=7, pady=(11, 0))

    def set(self, value):
        self.value.configure(text=value)


class Trend(tk.Canvas):
    def __init__(self, parent, title, color, history):
        super().__init__(parent, height=150, bg=PANEL, highlightbackground=BORDER,
                         highlightthickness=1)
        self.title, self.color, self.history = title, color, history
        self.bind("<Configure>", lambda _event: self.draw())

    def draw(self):
        self.delete("all")
        width, height = max(self.winfo_width(), 20), max(self.winfo_height(), 20)
        self.create_text(14, 12, text=self.title.upper(), anchor="nw",
                         fill=MUTED, font=("Segoe UI", 9, "bold"))
        left, top, right, bottom = 14, 38, width - 12, height - 18
        for i in range(4):
            y = top + (bottom - top) * i / 3
            self.create_line(left, y, right, y, fill=GRID)
        values = list(self.history)
        if len(values) < 2:
            return
        low, high = min(values), max(values)
        padding = max((high - low) * 0.15, 0.02)
        low, high = low - padding, high + padding
        points = []
        for index, value in enumerate(values):
            x = left + (right - left) * index / (len(values) - 1)
            y = bottom - (value - low) * (bottom - top) / (high - low)
            points.extend((x, y))
        self.create_line(*points, fill=self.color, width=2, smooth=True)
        self.create_text(right, top, text=f"{high:.2f}", anchor="ne",
                         fill=TEXT, font=("Consolas", 9, "bold"))
        self.create_text(right, bottom, text=f"{low:.2f}", anchor="se",
                         fill=TEXT, font=("Consolas", 9, "bold"))


class Dashboard:
    def __init__(self, root, target="", demo=False):
        self.root, self.demo = root, demo
        self.events = queue.Queue(maxsize=4)
        self.stop_event = threading.Event()
        self.worker_thread = None
        self.host = ""
        self.active_target = ""
        self.device_identity = ""
        self.connected = False
        self.grid_enabled = True
        self.device_zoom = 1.0
        self.orientation = Orientation()
        self.orientation_reference_pending = True
        self.last_sequence = -1
        self.last_imu_sample = None
        self.latest = {}
        self.distance_history = deque(maxlen=120)
        self.speed_history = deque(maxlen=120)
        # ponytail: bounded RAM history; stream to disk if sessions exceed 100k samples.
        self.records = deque(maxlen=100000)
        self.target = tk.StringVar(value=target)
        self.recent_devices = load_device_history()
        self.history_show_all = False
        self.remembered_device = None
        self.sensing_interval = tk.StringVar(value="80")
        self.send_interval = tk.StringVar(value="50")
        self.role = tk.StringVar(value="TAG")
        self.known_distance = tk.StringVar(value="1.000")
        self.antenna_delay = tk.StringVar(value="—")
        self.calibration_target = None
        self.calibration_samples = []
        self.settings_loaded = False
        self.status = tk.StringVar(value="DEMO" if demo else "DISCONNECTED")
        self.identity = tk.StringVar(value="MAC —  •  IP —")
        self.streaming = True
        self.root.dashboard = self
        self.build_ui()
        self.target.trace_add("write", self.target_changed)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(50, self.process_events)
        if demo:
            self.connect()

    def build_ui(self):
        self.root.title("Spatial Node — UWB + IMU")
        self.root.geometry("1440x900")
        self.root.minsize(1100, 720)
        self.root.configure(bg=BG)
        top = tk.Frame(self.root, bg=PANEL, padx=16, pady=12,
                       highlightbackground=BORDER, highlightthickness=1)
        top.pack(fill="x", padx=16, pady=(16, 8))
        brand = tk.Frame(top, bg=PANEL)
        brand.pack(side="left")
        try:
            self.brand_logo_source = tk.PhotoImage(file=LOGO_FILE)
            for y in range(self.brand_logo_source.height()):
                for x in range(self.brand_logo_source.width()):
                    color = self.brand_logo_source.get(x, y)
                    if isinstance(color, tuple) and min(color) >= 240:
                        self.brand_logo_source.transparency_set(x, y, True)
            self.brand_logo = self.brand_logo_source
            tk.Label(brand, image=self.brand_logo, bg=PANEL, borderwidth=0).pack()
        except tk.TclError:
            tk.Label(brand, text="SE 〰 SE", bg=PANEL, fg=AMBER,
                     font=("Segoe UI", 19, "bold")).pack()
        tk.Label(brand, text="© Made by astommondal013@gmail.com", bg=PANEL,
                 fg=MUTED, font=("Segoe UI", 8)).pack(anchor="center", pady=(2, 0))

        controls = tk.Frame(self.root, bg=PANEL, padx=16, pady=12,
                            highlightbackground=BORDER, highlightthickness=1)
        controls.pack(fill="x", padx=16, pady=(0, 8))
        tk.Label(controls, text="MAC / IP", bg=PANEL, fg=TEXT,
                 font=("Segoe UI", 9, "bold")).pack(side="left", padx=(0, 7))
        self.search_box = tk.Frame(controls, bg=ENTRY_BG,
                                   highlightbackground=BORDER,
                                   highlightcolor=CYAN, highlightthickness=1)
        self.search_box.pack(side="left", padx=(0, 8))
        entry = tk.Entry(self.search_box, textvariable=self.target, width=28,
                         bg=ENTRY_BG, fg=TEXT, insertbackground=TEXT,
                         selectbackground=SELECT_BG, relief="flat",
                         highlightthickness=0, font=("Consolas", 11))
        self.target_entry = entry
        entry.pack(side="left", ipady=8, padx=(8, 0))
        tk.Button(self.search_box, text="×", command=self.clear_search,
                  bg=ENTRY_BG, fg=MUTED, activebackground=SELECT_BG,
                  activeforeground=RED, relief="flat", borderwidth=0,
                  cursor="hand2", takefocus=False, padx=9,
                  font=("Segoe UI", 14)).pack(side="left", fill="y")
        entry.bind("<Return>", lambda _event: self.toggle_connection())
        entry.bind("<FocusIn>", lambda event: self.show_device_history(event, True))
        entry.bind("<KeyRelease>", self.show_device_history)
        entry.bind("<Down>", self.focus_device_history)
        entry.bind("<Escape>", self.hide_device_history)
        entry.bind("<FocusOut>", self.schedule_history_hide)
        self.history_list = tk.Listbox(
            self.root, bg=POPUP_BG, fg=TEXT, selectbackground=SELECT_BG,
            selectforeground=CYAN, relief="flat", highlightbackground=BORDER,
            highlightthickness=1, activestyle="none", font=("Consolas", 10))
        self.history_list.bind("<ButtonRelease-1>", self.history_click)
        self.history_list.bind("<Return>", self.choose_device_history)
        self.history_list.bind("<Escape>", self.hide_device_history)
        self.history_list.bind("<FocusOut>", self.schedule_history_hide)
        self.connect_button = self.button(controls, "Connect", self.toggle_connection, CYAN)
        self.connect_button.configure(width=11)
        self.connect_button.pack(side="left", padx=6)
        self.pause_button = self.button(
            controls, "Pause sensors", self.toggle_stream, AMBER)
        self.pause_button.configure(width=14)
        self.pause_button.pack(side="left", padx=6)
        self.zero_button = self.button(
            controls, "Zero", self.zero_orientation, GREEN)
        self.zero_button.configure(width=11)
        self.zero_button.pack(side="left", padx=6)
        save_button = self.button(controls, "Save", self.save_data, CYAN)
        save_button.configure(width=8)
        save_button.pack(side="left", padx=6)
        refresh_button = self.button(controls, "Refresh", self.restart, CYAN)
        refresh_button.configure(width=9)
        refresh_button.pack(side="left")

        status_tab = tk.Frame(top, bg=PANEL_2, padx=12, pady=6,
                              highlightbackground=BORDER, highlightthickness=1)
        status_tab.pack(side="left", fill="x", expand=True, padx=(24, 14))
        status_line = tk.Frame(status_tab, bg=PANEL_2)
        status_line.pack(fill="x")
        self.status_dot = tk.Label(status_line, text="●", bg=PANEL_2, fg=RED,
                                   font=("Segoe UI", 11))
        self.status_dot.pack(side="left")
        tk.Label(status_line, textvariable=self.status, anchor="w",
                 bg=PANEL_2, fg=TEXT, font=("Segoe UI", 10, "bold")).pack(
                     side="left", fill="x", expand=True, padx=7)
        tk.Label(status_tab, textvariable=self.identity, anchor="w",
                 bg=PANEL_2, fg=MUTED, font=("Consolas", 9)).pack(fill="x")

        settings = tk.Frame(self.root, bg=PANEL, padx=16, pady=10,
                            highlightbackground=BORDER, highlightthickness=1)
        settings.pack(fill="x", padx=18, pady=(5, 0))
        timing = tk.Frame(settings, bg=PANEL)
        timing.pack(side="left")
        tk.Label(timing, text="Sensing (ms)", bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 9, "bold")).pack(side="left")
        tk.Entry(timing, textvariable=self.sensing_interval, width=7,
                 bg=PANEL, fg=TEXT, insertbackground=TEXT, relief="flat",
                 highlightbackground=BORDER, highlightcolor=CYAN,
                 highlightthickness=1, justify="center",
                 font=("Consolas", 10)).pack(side="left", padx=(7, 18), ipady=6)
        tk.Label(timing, text="Network (ms)", bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 9, "bold")).pack(side="left")
        tk.Entry(timing, textvariable=self.send_interval, width=7,
                 bg=PANEL, fg=TEXT, insertbackground=TEXT, relief="flat",
                 highlightbackground=BORDER, highlightcolor=CYAN,
                 highlightthickness=1, justify="center",
                 font=("Consolas", 10)).pack(side="left", padx=(7, 18), ipady=6)
        tk.Label(timing, text="Role", bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 9, "bold")).pack(side="left")
        role_menu = tk.OptionMenu(timing, self.role, "TAG", "ANCHOR")
        role_menu.configure(bg=PANEL_2, fg=TEXT, activebackground=SELECT_BG,
                            activeforeground=TEXT, relief="flat", borderwidth=0,
                            highlightbackground=BORDER, highlightthickness=1,
                            width=8, padx=4, font=("Consolas", 10, "bold"))
        role_menu["menu"].configure(bg=POPUP_BG, fg=TEXT,
                                    activebackground=SELECT_BG,
                                    activeforeground=TEXT,
                                    font=("Consolas", 10))
        role_menu.pack(side="left", padx=(7, 9), ipady=2)
        self.button(timing, "Apply", self.apply_intervals, GREEN).pack(side="left")

        calibration = tk.Frame(settings, bg=PANEL)
        calibration.pack(side="right")
        tk.Label(calibration, text="Known distance (m)", bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 9, "bold")).pack(side="left")
        tk.Entry(calibration, textvariable=self.known_distance, width=8,
                 bg=PANEL, fg=TEXT, insertbackground=TEXT, relief="flat",
                 highlightbackground=BORDER, highlightcolor=CYAN,
                 highlightthickness=1, justify="center",
                 font=("Consolas", 10)).pack(
                     side="left", padx=(7, 9), ipady=6)
        self.calibrate_button = self.button(
            calibration, "Calibrate UWB", self.calibrate_uwb, AMBER)
        self.calibrate_button.pack(side="left")
        tk.Label(calibration, text="Antenna delay DTU", bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 9)).pack(side="left", padx=(18, 5))
        tk.Label(calibration, textvariable=self.antenna_delay, bg=PANEL, fg=TEXT,
                 font=("Consolas", 10, "bold")).pack(side="left")
        tk.Label(calibration, text="clear LOS • keep still",
                 bg=PANEL, fg=MUTED, font=("Segoe UI", 9)).pack(
                     side="left", padx=(14, 0))

        body = tk.Frame(self.root, bg=BG, padx=18, pady=10)
        body.pack(fill="both", expand=True)
        body.grid_columnconfigure(0, weight=3)
        body.grid_columnconfigure(1, weight=2)
        body.grid_rowconfigure(0, weight=1)
        left = tk.Frame(body, bg=BG)
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 7))
        right = tk.Frame(body, bg=BG)
        right.grid(row=0, column=1, sticky="nsew", padx=(7, 0))

        self.device = tk.Canvas(left, bg=PANEL, highlightbackground=BORDER,
                                highlightthickness=1)
        self.device.pack(fill="both", expand=True)
        self.device.bind("<Configure>", lambda _event: self.draw_device())
        self.device.bind("<MouseWheel>", self.zoom_device)
        self.device.bind("<Button-4>", self.zoom_device)
        self.device.bind("<Button-5>", self.zoom_device)
        self.grid_button = self.button(self.device, "Hide grid", self.toggle_grid, GREEN)
        self.grid_button.place(relx=1, x=-14, y=14, anchor="ne")

        cards = tk.Frame(right, bg=BG)
        cards.pack(fill="x")
        for column in range(2):
            cards.grid_columnconfigure(column, weight=1)
        self.distance = MetricCard(cards, "Distance", "m", CYAN)
        self.speed = MetricCard(cards, "Radial speed", "m/s", GREEN)
        self.battery = MetricCard(cards, "Battery", "%", AMBER)
        self.rx = MetricCard(cards, "Signal", "dBm", CYAN)
        for index, card in enumerate((self.distance, self.speed, self.battery, self.rx)):
            card.grid(row=index // 2, column=index % 2, sticky="nsew", padx=4, pady=5)

        self.imu_text = tk.Label(right, text="ACCEL  —\nGYRO   —\nTEMP   —",
                                 justify="left", anchor="nw", bg=PANEL,
                                 fg=TEXT, font=("Consolas", 12), padx=16, pady=14,
                                 highlightbackground=BORDER, highlightthickness=1)
        self.imu_text.pack(fill="x", padx=4, pady=5)
        graphs = tk.Frame(right, bg=BG)
        graphs.pack(fill="both", expand=True, padx=4)
        self.distance_trend = Trend(graphs, "Distance history (m)", CYAN,
                                    self.distance_history)
        self.distance_trend.pack(fill="both", expand=True, pady=(0, 5))
        self.speed_trend = Trend(graphs, "Radial speed history (m/s)", GREEN,
                                 self.speed_history)
        self.speed_trend.pack(fill="both", expand=True, pady=(5, 0))

    @staticmethod
    def button(parent, text, command, color):
        return tk.Button(parent, text=text, command=command, bg=PANEL_2, fg=color,
                         activebackground=SELECT_BG, activeforeground=TEXT,
                         highlightbackground=BORDER, highlightthickness=1,
                         borderwidth=0, relief="flat", cursor="hand2",
                         padx=16, pady=8, font=("Segoe UI", 10))

    def show_device_history(self, event=None, show_all=False):
        if event and event.keysym in ("Down", "Up", "Return", "Escape"):
            return
        self.history_show_all = show_all
        query = "" if show_all else self.target.get().strip().lower()
        self.history_matches = [device for device in self.recent_devices
                                if query in f"{device.get('mac', '')} {device.get('ip', '')}".lower()]
        self.history_list.delete(0, "end")
        if not self.history_matches:
            self.hide_device_history()
            return
        for device in self.history_matches:
            mac = device.get("mac") or "—"
            ip = device.get("ip") or "—"
            self.history_list.insert(
                "end", f"  {mac:<17}    {ip:<15}  ×")
        self.root.update_idletasks()
        x = self.search_box.winfo_rootx() - self.root.winfo_rootx()
        y = (self.search_box.winfo_rooty() - self.root.winfo_rooty() +
             self.search_box.winfo_height())
        self.history_list.place(x=x, y=y, width=self.search_box.winfo_width(),
                                height=min(8, len(self.history_matches)) * 28 + 2)
        self.history_list.lift()

    def clear_search(self):
        self.target.set("")
        self.hide_device_history()
        self.target_entry.focus_set()

    def history_click(self, event):
        index = self.history_list.nearest(event.y)
        bounds = self.history_list.bbox(index)
        if not bounds or not bounds[1] <= event.y <= bounds[1] + bounds[3]:
            return "break"
        if event.x >= bounds[0] + bounds[2] - 24:
            device = self.history_matches[index]
            self.recent_devices = forget_device(self.recent_devices, device)
            save_device_history(self.recent_devices)
            self.show_device_history(show_all=self.history_show_all)
            return "break"
        self.history_list.selection_clear(0, "end")
        self.history_list.selection_set(index)
        return self.choose_device_history()

    def focus_device_history(self, _event=None):
        if not self.history_list.winfo_ismapped():
            self.show_device_history()
        if self.history_list.size():
            self.history_list.selection_clear(0, "end")
            self.history_list.selection_set(0)
            self.history_list.focus_set()
        return "break"

    def choose_device_history(self, _event=None):
        selection = self.history_list.curselection()
        if selection:
            device = self.history_matches[selection[0]]
            self.target.set(device.get("mac") or device.get("ip"))
        self.hide_device_history()
        self.target_entry.focus_set()
        self.target_entry.icursor("end")
        return "break"

    def schedule_history_hide(self, _event=None):
        self.root.after(120, lambda: self.hide_device_history()
                        if self.root.focus_get() not in
                        (self.target_entry, self.history_list) else None)

    def hide_device_history(self, _event=None):
        self.history_list.place_forget()
        return "break"

    def remember_device(self, sample):
        mac = normalize_mac(str(sample.get("mac", "")))
        ip = str(sample.get("ip", "")).strip()
        key = (mac, ip)
        if key == self.remembered_device or not any(key):
            return
        self.remembered_device = key
        self.recent_devices = [device for device in self.recent_devices
                               if device.get("mac") != mac and device.get("ip") != ip]
        self.recent_devices.insert(0, {"mac": mac, "ip": ip})
        self.recent_devices = self.recent_devices[:8]
        save_device_history(self.recent_devices)
        if self.history_list.winfo_ismapped():
            self.show_device_history()

    def toggle_connection(self):
        target = self.target.get().strip()
        running = (self.worker_thread and self.worker_thread.is_alive() and
                   not self.stop_event.is_set())
        if (self.connected or running) and (
                not target or self.target_is_active(target)):
            self.disconnect()
        else:
            self.connect()

    def target_changed(self, *_args):
        target = self.target.get().strip()
        running = (self.worker_thread and self.worker_thread.is_alive() and
                   not self.stop_event.is_set())
        if self.connected:
            action = ("Disconnect" if not target or
                      self.target_is_active(target) else "SWITCH")
            self.connect_button.configure(text=action)
        elif not running:
            self.connect_button.configure(text="Connect")

    def target_is_active(self, target):
        wanted_mac = normalize_mac(target)
        active_mac = normalize_mac(self.active_target)
        device_mac = normalize_mac(str(self.latest.get("mac", "")))
        if wanted_mac:
            return wanted_mac in (active_mac, device_mac)
        wanted = endpoint_key(target)
        return wanted in {endpoint_key(self.active_target),
                          endpoint_key(self.host),
                          endpoint_key(self.latest.get("ip", ""))}

    def connect(self):
        if self.worker_thread and self.worker_thread.is_alive():
            self.stop_event.set()
        self.stop_event = threading.Event()
        stop_event = self.stop_event
        self.active_target = self.target.get().strip()
        self.device_identity = normalize_mac(self.active_target) or endpoint_key(self.active_target)
        self.host = ""
        self.connected = False
        self.last_sequence = -1
        self.last_imu_sample = None
        self.settings_loaded = False
        self.orientation = Orientation()
        self.orientation_reference_pending = True
        self.status.set("DISCOVERING" if not self.demo else "DEMO STREAM")
        self.status_dot.configure(fg=AMBER)
        self.connect_button.configure(text="Cancel")
        self.pause_button.configure(state="normal")
        self.zero_button.configure(text="ZERO", state="normal")
        self.worker_thread = threading.Thread(target=self.worker,
                                              args=(stop_event,), daemon=True)
        self.worker_thread.start()

    def disconnect(self):
        self.stop_event.set()
        close_device_api_connections()
        self.host = ""
        self.active_target = ""
        self.connected = False
        self.calibration_target = None
        self.calibration_samples.clear()
        self.calibrate_button.configure(text="Calibrate UWB")
        self.connect_button.configure(text="Connect")
        self.pause_button.configure(state="normal")
        self.zero_button.configure(text="ZERO", state="normal")
        self.status.set("DISCONNECTED")
        self.status_dot.configure(fg=RED)
        self.identity.set("MAC —  •  IP —")

    def worker(self, stop_event):
        if self.demo:
            self.demo_worker(stop_event)
            return
        target = self.active_target
        device_id = self.device_identity or normalize_mac(target) or target
        try:
            if normalize_mac(target):
                target = discover_by_mac(target)
                if stop_event.is_set():
                    return
                self.post("target", target, stop_event)
            if not target:
                raise ValueError("Enter the device MAC address or IP")
            host = (target if target.startswith(("http://", "https://"))
                    else "https://" + target)
            if host.startswith("http://"):
                host = "https://" + host[len("http://"):]
            if stop_event.is_set():
                return
            host = host.rstrip("/")
            self.post("host", host, stop_event)
            while not stop_event.is_set():
                data = api_json(host + "/api/telemetry", device_id,
                                max_bytes=MAX_TELEMETRY_BYTES)
                if stop_event.is_set():
                    return
                sync_system_clock(host, device_id, data)
                self.post("data", data, stop_event)
                stop_event.wait(max(.05, data.get("send_interval_ms", 50) / 1000))
        except (OSError, URLError, ValueError, TimeoutError, json.JSONDecodeError) as error:
            if not stop_event.is_set():
                self.post("connection_error", str(error), stop_event)

    def demo_worker(self, stop_event):
        start = time.monotonic()
        sequence = 0
        while not stop_event.is_set():
            t = time.monotonic() - start
            sequence += 1
            roll, pitch = 25 * math.sin(t * .7), 18 * math.sin(t * .43)
            data = {
                "valid": True, "streaming": True, "sequence": sequence,
                "timestamp_ms": int(t * 1000), "age_ms": 0,
                "mac": "A4:CF:12:34:56:78", "ip": "192.168.1.42",
                "role": "tag", "peer": "A4:CF:87:65:43:21",
                "distance_m": 2.4 + .35 * math.sin(t * .5),
                "speed_mps": .175 * math.cos(t * .5),
                "rx_power_dbm": -67 + 3 * math.sin(t * .3),
                "imu_valid": True,
                "accel": [-math.sin(math.radians(pitch)),
                          math.sin(math.radians(roll)), .93],
                "gyro": [17.5 * math.cos(t * .7), 7.7 * math.cos(t * .43), 12],
                "temperature_c": 29.4, "battery_valid": True,
                "battery_voltage": 3.91 - t / 20000,
                "battery_percent": 76 - t / 200,
            }
            if self.streaming:
                self.post("data", data, stop_event)
            stop_event.wait(0.08)

    def post(self, kind, payload, source=None):
        try:
            self.events.put_nowait((kind, payload, source))
        except queue.Full:
            try:
                self.events.get_nowait()
            except queue.Empty:
                pass
            try:
                self.events.put_nowait((kind, payload, source))
            except queue.Full:
                pass

    def process_events(self):
        try:
            while True:
                kind, payload, source = self.events.get_nowait()
                if not event_is_current(source, self.stop_event):
                    continue
                if kind == "data":
                    self.apply_sample(payload)
                elif kind == "target":
                    self.target.set(payload)
                    self.active_target = payload
                elif kind == "host":
                    self.host = payload
                elif kind == "control":
                    self.streaming = payload["streaming"]
                    self.sensing_interval.set(payload["sensing_interval_ms"])
                    self.send_interval.set(payload["send_interval_ms"])
                    self.antenna_delay.set(payload["antenna_delay"])
                    role = payload.get("role", "").upper()
                    if role in ("TAG", "ANCHOR"):
                        self.role.set(role)
                    self.pause_button.configure(
                        text=("Pause sensors" if self.streaming else
                              "Resume sensors"), state="normal")
                elif kind == "connection_error":
                    self.connected = False
                    self.host = ""
                    self.active_target = ""
                    self.device_identity = ""
                    self.connect_button.configure(text="Connect")
                    self.status.set("OFFLINE  •  " + payload)
                    self.status_dot.configure(fg=RED)
                elif kind == "error":
                    self.status.set("OFFLINE  •  " + payload)
                    self.status_dot.configure(fg=RED)
                    self.pause_button.configure(state="normal")
        except queue.Empty:
            pass
        self.root.after(50, self.process_events)

    def apply_sample(self, sample):
        self.latest = sample
        self.remember_device(sample)
        self.connected = True
        self.connect_button.configure(text="Disconnect")
        if not sample.get("streaming", True):
            self.status.set(
                f"SENSORS PAUSED  •  WIFI ONLINE  •  age {sample.get('age_ms', 0)} ms")
        else:
            state = "LIVE" if sample.get("valid") else "WAITING"
            self.status.set(state +
                            f"  •  {sample.get('role', '').upper()}  •  age {sample.get('age_ms', 0)} ms"
                            f"  •  drops {sample.get('dropped', 0)}")
        self.status_dot.configure(fg=GREEN if sample.get("valid") else AMBER)
        self.identity.set(f"MAC {sample.get('mac', '—')}  •  IP {sample.get('ip', '—')}  •  PEER {sample.get('peer', '—')}")
        self.streaming = sample.get("streaming", True)
        self.pause_button.configure(
            text="Pause sensors" if self.streaming else "Resume sensors")
        if not self.settings_loaded:
            self.sensing_interval.set(sample.get("sensing_interval_ms", 80))
            self.send_interval.set(sample.get("send_interval_ms", 50))
            role = sample.get("role", "").upper()
            if role in ("TAG", "ANCHOR"):
                self.role.set(role)
            self.settings_loaded = True
        self.antenna_delay.set(sample.get("antenna_delay", "—"))
        imu_stamp = sample.get("imu_timestamp_ms", sample.get("timestamp_ms", 0))
        imu_key = (sample.get("imu_sequence"), imu_stamp)
        if sample.get("imu_valid") and imu_key != self.last_imu_sample:
            self.last_imu_sample = imu_key
            imu_sample = dict(sample, timestamp_ms=imu_stamp)
            self.orientation.update(imu_sample)
            if (self.orientation_reference_pending and
                    self.orientation.last_ms is not None):
                self.orientation.zero()
                self.orientation_reference_pending = False
            accel = sample.get("accel", [0, 0, 0])
            gyro = sample.get("gyro", [0, 0, 0])
            self.imu_text.configure(text=
                f"ACCEL  X {accel[0]:+7.3f}  Y {accel[1]:+7.3f}  Z {accel[2]:+7.3f}  g\n"
                f"GYRO   X {gyro[0]:+7.2f}  Y {gyro[1]:+7.2f}  Z {gyro[2]:+7.2f}  °/s\n"
                f"TEMP   {sample.get('temperature_c', 0):5.1f} °C")
            self.draw_device()
        if not sample.get("valid") or sample.get("sequence") == self.last_sequence:
            return
        self.last_sequence = sample.get("sequence")
        self.records.append(dict(sample))
        distance, speed = sample.get("distance_m", 0), sample.get("speed_mps", 0)
        if self.calibration_target is not None:
            self.calibration_samples.append(distance)
            count = len(self.calibration_samples)
            self.status.set(f"CALIBRATING UWB  •  {count}/{CALIBRATION_SAMPLES}  •  KEEP STILL")
            if count >= CALIBRATION_SAMPLES:
                measured = statistics.median(self.calibration_samples)
                try:
                    delay = calibrated_antenna_delay(
                        int(sample.get("antenna_delay", 0)), measured,
                        self.calibration_target)
                    self.send_control({"antenna_delay": delay})
                    self.status.set(
                        f"CALIBRATED  •  median {measured:.3f} m  •  delay {delay} DTU")
                except ValueError as error:
                    self.post("error", str(error))
                self.calibration_target = None
                self.calibration_samples.clear()
                self.calibrate_button.configure(text="Calibrate UWB")
        self.distance_history.append(distance)
        self.speed_history.append(speed)
        self.distance.set(f"{distance:.3f}")
        self.speed.set(f"{speed:+.3f}")
        rx_power = sample.get("rx_power_dbm")
        self.rx.set(f"{rx_power:.1f}" if isinstance(rx_power, (int, float))
                    and math.isfinite(rx_power) else "N/A")
        if sample.get("battery_valid"):
            self.battery.set(f"{sample.get('battery_percent', 0):.0f}")
        else:
            self.battery.set("—")
        self.draw_device()
        self.distance_trend.draw()
        self.speed_trend.draw()

    def draw_device(self):
        canvas = self.device
        canvas.delete("all")
        width, height = max(canvas.winfo_width(), 200), max(canvas.winfo_height(), 200)
        origin_x, origin_y = width * .5, height * .48
        vertices = [(-1.4, -.75, -.28), (1.4, -.75, -.28),
                    (1.4, .75, -.28), (-1.4, .75, -.28),
                    (-1.4, -.75, .28), (1.4, -.75, .28),
                    (1.4, .75, .28), (-1.4, .75, .28)]
        rotated = [rotate_quaternion(point, self.orientation.display_quaternion)
                   for point in vertices]
        scale = min(width, height) * .17 * self.device_zoom
        projected = [(origin_x + x * scale, origin_y - y * scale - z * scale * .35)
                     for x, y, z in rotated]
        device_half_width = max(abs(x - origin_x) for x, _ in projected)
        device_half_height = max(abs(y - origin_y) for _, y in projected)
        if self.grid_enabled:
            cell = max(32.0, min(60.0,
                       max(device_half_width, device_half_height) * .30))
            row_count = math.ceil(max(origin_y, height - origin_y) / cell)
            for step in range(-row_count, row_count + 1):
                y = origin_y + step * cell
                if 0 <= y <= height:
                    major = step % 5 == 0
                    canvas.create_line(0, y, width, y,
                                       fill=GRID_MAJOR if major else GRID,
                                       width=2 if major else 1,
                                       tags="orientation_grid")
            skew = .55
            diagonal_count = math.ceil((width + skew * height) / cell) + 2
            for step in range(-diagonal_count, diagonal_count + 1):
                x_at_origin = origin_x + step * cell
                major = step % 5 == 0
                canvas.create_line(x_at_origin + skew * origin_y, 0,
                                   x_at_origin - skew * (height - origin_y), height,
                                   fill=GRID_MAJOR if major else GRID,
                                   width=2 if major else 1,
                                   tags="orientation_grid")
            margin = 10
            canvas.create_line(margin, origin_y, width - margin, origin_y, fill=CYAN,
                               width=2, arrow="both", tags="orientation_grid")
            canvas.create_line(origin_x, height - margin,
                               origin_x, margin, fill=AMBER,
                               width=2, arrow="last", tags="orientation_grid")
            z_top_x = origin_x + skew * (origin_y - margin)
            z_bottom_x = origin_x - skew * (height - margin - origin_y)
            canvas.create_line(z_top_x, margin, z_bottom_x, height - margin,
                               fill=GREEN,
                               width=2, arrow="both", tags="orientation_grid")
            canvas.create_text(width - 7, origin_y - 6,
                               text="X", fill=CYAN, anchor="se",
                               font=("Consolas", 9, "bold"),
                               tags="orientation_grid")
            canvas.create_text(z_bottom_x - 5, height - 12,
                               text="Z", fill=GREEN,
                               anchor="se", font=("Consolas", 9, "bold"),
                               tags="orientation_grid")
            canvas.create_text(origin_x + 7, 13,
                               text="Y", fill=AMBER,
                               anchor="nw", font=("Consolas", 9, "bold"),
                               tags="orientation_grid")
        faces = [(0, 1, 2, 3, DEVICE_FACES[0]),
                 (4, 5, 6, 7, DEVICE_FACES[1]),
                 (0, 1, 5, 4, DEVICE_FACES[2]),
                 (1, 2, 6, 5, DEVICE_FACES[3]),
                 (2, 3, 7, 6, DEVICE_FACES[4]),
                 (3, 0, 4, 7, DEVICE_FACES[5])]
        faces.sort(key=lambda face: sum(rotated[i][2] for i in face[:4]) / 4)
        for *indices, color in faces:
            points = [coordinate for i in indices for coordinate in projected[i]]
            canvas.create_polygon(*points, fill=color, outline=CYAN, width=2,
                                  tags="device_body")
        canvas.create_text(origin_x, origin_y, text="UWB\nIMU",
                           justify="center", fill=TEXT,
                           font=("Segoe UI", 14, "bold"))
        display = self.orientation.display
        canvas.create_text(18, 18, anchor="nw", fill=MUTED,
                           font=("Segoe UI", 10, "bold"),
                           text=("DEVICE ORIENTATION  •  BOOT REFERENCE (0,0,0)"
                                 f"  •  ZOOM {self.device_zoom:.0%}"))
        canvas.create_text(18, 43, anchor="nw", fill=TEXT,
                           font=("Consolas", 12, "bold"),
                           text=f"ROLL  {display[0]:+7.1f}°\nPITCH {display[1]:+7.1f}°\nYAW   {display[2]:+7.1f}°")
        canvas.create_text(width / 2, height - 25, anchor="s", fill=MUTED,
                           font=("Segoe UI", 10),
                           text=("Mouse wheel zooms • Move the physical device • "
                                 "ZERO sets the current view as reference"))

    def toggle_stream(self):
        if self.demo:
            self.streaming = not self.streaming
            self.pause_button.configure(
                text="Pause sensors" if self.streaming else "Resume sensors")
            self.status.set("DEMO STREAM" if self.streaming else
                            "SENSORS PAUSED  •  DASHBOARD ONLINE")
            return
        self.send_control({"streaming": not self.streaming})

    def send_control(self, payload):
        host = self.host
        source = self.stop_event
        if not host:
            self.post("error", "Connect to a device before changing controls")
            return
        self.pause_button.configure(state="disabled")
        def send():
            try:
                body = json.dumps(payload).encode()
                result = api_json(host + "/api/control", self.device_identity,
                                  method="POST", body=body, max_bytes=MAX_CONTROL_BYTES)
                self.post("control", result, source)
            except (OSError, URLError, ValueError) as error:
                self.post("error", str(error), source)
        threading.Thread(target=send, daemon=True).start()

    def apply_intervals(self):
        try:
            sensing = int(self.sensing_interval.get())
            sending = int(self.send_interval.get())
            if not 20 <= sensing <= 60000 or not 50 <= sending <= 60000:
                raise ValueError("sensing 20..60000 ms; network 50..60000 ms")
        except ValueError as error:
            self.post("error", str(error))
            return
        if self.demo:
            return
        self.send_control({"sensing_interval_ms": sensing,
                           "send_interval_ms": sending,
                           "role": self.role.get().lower()})

    def calibrate_uwb(self):
        if self.calibration_target is not None:
            self.calibration_target = None
            self.calibration_samples.clear()
            self.calibrate_button.configure(text="Calibrate UWB")
            self.status.set("UWB CALIBRATION CANCELLED")
            return
        if not self.connected or self.demo:
            self.post("error", "Connect to a real device before UWB calibration")
            return
        try:
            target = float(self.known_distance.get())
            if not 0.01 <= target <= 1000:
                raise ValueError("known distance must be 0.01..1000 m")
        except ValueError as error:
            self.post("error", str(error))
            return
        self.calibration_target = target
        self.calibration_samples.clear()
        self.calibrate_button.configure(text="Cancel calibration")
        self.status.set(f"CALIBRATING UWB  •  0/{CALIBRATION_SAMPLES}  •  KEEP STILL")

    def toggle_grid(self):
        self.grid_enabled = not self.grid_enabled
        self.grid_button.configure(text="Hide grid" if self.grid_enabled else "Show grid")
        self.draw_device()

    def zoom_device(self, event):
        direction = (1 if getattr(event, "delta", 0) > 0 or
                     getattr(event, "num", 0) == 4 else -1)
        self.device_zoom = adjusted_zoom(self.device_zoom, direction)
        self.draw_device()
        return "break"

    def save_data(self):
        if not self.records:
            self.status.set("NO DATA TO SAVE")
            return
        path = filedialog.asksaveasfilename(
            defaultextension=".csv",
            initialfile=time.strftime("uwb_%Y%m%d_%H%M%S.csv"),
            filetypes=(("CSV file", "*.csv"), ("JSON file", "*.json")))
        if not path:
            return
        records = list(self.records)
        with open(path, "w", newline="", encoding="utf-8") as output:
            if path.lower().endswith(".json"):
                json.dump(records, output, indent=2)
            else:
                fields = sorted({field for record in records for field in record})
                writer = csv.DictWriter(output, fieldnames=fields)
                writer.writeheader()
                for record in records:
                    writer.writerow({key: json.dumps(value) if isinstance(value, (list, dict))
                                     else value for key, value in record.items()})
        self.status.set(f"SAVED {len(records)} SAMPLES  •  {path}")

    def zero_orientation(self):
        if not self.demo and not self.latest.get("imu_valid"):
            self.status.set("IMU UNAVAILABLE  •  CANNOT ZERO")
            return
        calibrated = self.orientation.zero(calibrate=not self.demo)
        self.status.set("IMU ZEROED  •  GYRO BIAS UPDATED" if calibrated else
                        "IMU ZEROED  •  HOLD STILL BRIEFLY BEFORE ZERO FOR BEST RESULTS")
        self.draw_device()

    def restart(self):
        target = self.target.get().strip()
        reconnect = self.demo or self.connected or (
            self.worker_thread and self.worker_thread.is_alive())
        self.stop_event.set()
        for job in self.root.tk.splitlist(self.root.tk.call("after", "info")):
            self.root.after_cancel(job)

        loading = tk.Canvas(self.root, bg=BG, highlightthickness=0)
        loading.place(x=0, y=0, relwidth=1, relheight=1)
        if hasattr(self, "brand_logo"):
            self.loading_logo = self.brand_logo.subsample(2)
        self.animate_loading(loading)
        self.root.after(1200, lambda: self.finish_restart(target, reconnect))

    def animate_loading(self, canvas, phase=0):
        if not canvas.winfo_exists():
            return
        canvas.delete("all")
        width = max(canvas.winfo_width(), 1100)
        height = max(canvas.winfo_height(), 720)
        center_x, center_y = width / 2, height / 2
        card_w, card_h = min(580, width - 80), 440
        x1, y1 = center_x - card_w / 2, center_y - card_h / 2
        x2, y2 = center_x + card_w / 2, center_y + card_h / 2

        for radius, color in ((330, "#d4e0e5"), (250, "#d9e4e8")):
            canvas.create_oval(center_x - radius, center_y - radius,
                               center_x + radius, center_y + radius,
                               outline=color, width=1)
        canvas.create_rectangle(x1 + 7, y1 + 9, x2 + 7, y2 + 9,
                                fill="#cad6dc", outline="")
        canvas.create_rectangle(x1, y1, x2, y2, fill=PANEL,
                                outline=BORDER, width=1)

        if hasattr(self, "loading_logo"):
            canvas.create_image(center_x, y1 + 62, image=self.loading_logo)
        else:
            canvas.create_text(center_x, y1 + 62, text="Spatial Node",
                               fill=TEXT, font=("Segoe UI", 18, "bold"))
        canvas.create_text(center_x, y1 + 112, text="Refreshing workspace",
                           fill=TEXT, font=("Segoe UI", 17, "bold"))
        canvas.create_text(center_x, y1 + 139,
                           text="Your live session will continue automatically",
                           fill=MUTED, font=("Segoe UI", 10))

        spinner_y, radius = y1 + 224, 38
        canvas.create_oval(center_x - radius, spinner_y - radius,
                           center_x + radius, spinner_y + radius,
                           outline=GRID, width=5)
        canvas.create_arc(center_x - radius, spinner_y - radius,
                          center_x + radius, spinner_y + radius,
                          start=90 - phase * 11, extent=105, style="arc",
                          outline=CYAN, width=5)
        canvas.create_arc(center_x - radius + 9, spinner_y - radius + 9,
                          center_x + radius - 9, spinner_y + radius - 9,
                          start=270 + phase * 8, extent=75, style="arc",
                          outline=GREEN, width=3)
        pulse = 7 + 2 * math.sin(phase * .35)
        canvas.create_oval(center_x - pulse, spinner_y - pulse,
                           center_x + pulse, spinner_y + pulse,
                           fill=CYAN, outline="")

        messages = ("Resetting the live view", "Restoring dashboard controls",
                    "Reconnecting telemetry")
        stage = min(2, phase // 17)
        canvas.create_text(center_x, y1 + 291, text=messages[stage],
                           fill=TEXT, font=("Segoe UI", 11, "bold"))
        for index in range(3):
            dot_x = center_x - 18 + index * 18
            canvas.create_oval(dot_x - 3, y1 + 314, dot_x + 3, y1 + 320,
                               fill=CYAN if index <= stage else GRID,
                               outline="")

        progress = min(1.0, phase / 49)
        track_left, track_right, track_y = x1 + 70, x2 - 70, y1 + 365
        canvas.create_line(track_left, track_y, track_right, track_y,
                           fill=PANEL_2, width=8, capstyle="round")
        canvas.create_line(track_left, track_y,
                           track_left + (track_right - track_left) * progress,
                           track_y, fill=CYAN, width=8, capstyle="round")
        canvas.create_text(center_x, y1 + 398,
                           text=f"{int(progress * 100):02d}%",
                           fill=MUTED, font=("Consolas", 10, "bold"))
        self.loading_animation = self.root.after(
            24, lambda: self.animate_loading(canvas, phase + 1))

    def finish_restart(self, target, reconnect):
        if getattr(self, "loading_animation", None):
            self.root.after_cancel(self.loading_animation)
        for child in self.root.winfo_children():
            child.destroy()
        dashboard = Dashboard(self.root, target, self.demo)
        if reconnect and not self.demo and target:
            dashboard.connect()

    def close(self):
        self.stop_event.set()
        close_device_api_connections()
        self.root.destroy()


def self_test():
    point = rotate_point((1, 2, 3), .4, -.3, .2)
    assert abs(sum(value * value for value in point) - 14) < 1e-9
    point = rotate_quaternion((1, 2, 3), (1, 0, 0, 0))
    assert point == (1, 2, 3)
    assert calibrated_antenna_delay(16384, .975, .110) == 16476
    assert normalize_mac("aa-bb-cc-dd-ee-ff") == "AA:BB:CC:DD:EE:FF"
    assert normalize_mac(None) is None and normalize_mac([]) is None
    assert endpoint_key("HTTP://192.168.1.42/") == "192.168.1.42"
    token = threading.Event()
    assert event_is_current(None, token) and event_is_current(token, token)
    token.set()
    assert not event_is_current(token, token)
    dashboard = Dashboard.__new__(Dashboard)
    dashboard.active_target = "192.168.1.42"
    dashboard.host = "http://192.168.1.42"
    dashboard.latest = {"mac": "AA:BB:CC:DD:EE:FF", "ip": "192.168.1.42"}
    assert dashboard.target_is_active("AA-BB-CC-DD-EE-FF")
    assert dashboard.target_is_active("http://192.168.1.42/")
    assert not dashboard.target_is_active("192.168.1.99")
    assert 1.11 < adjusted_zoom(1.0, 1) < 1.13
    assert adjusted_zoom(2.5, 1) == 2.5
    assert adjusted_zoom(.45, -1) == .45
    orientation = Orientation()
    orientation.update({"imu_valid": True, "accel": [0, 0, 1],
                        "gyro": [0, 0, 0], "timestamp_ms": 1000})
    orientation.update({"imu_valid": True, "accel": [0, 0, 1],
                        "gyro": [0, 0, 90], "timestamp_ms": 1100})
    assert 8.9 < orientation.yaw < 9.1
    tilted = Orientation()
    for index in range(100):
        tilted.update({"imu_valid": True, "accel": [-1, 0, 0],
                       "gyro": [.05, -.04, .03],
                       "timestamp_ms": 1000 + index * 100})
    assert abs(abs(quaternion_euler(tilted.q)[1]) - 90) < .1
    assert abs(sum(value * value for value in tilted.q) - 1) < 1e-9
    stable = Orientation()
    settled = None
    for index in range(600):
        stable.update({"imu_valid": True,
                       "accel": [-1, .002 * math.sin(index),
                                 .002 * math.cos(index)],
                       "gyro": [2.4, -1.8, 1.1],
                       "timestamp_ms": 1000 + index * 100})
        if index == 20:
            assert stable.zero(calibrate=True)
        if index == 100:
            settled = stable.q
    dot = abs(sum(settled[i] * stable.q[i] for i in range(4)))
    drift = math.degrees(2 * math.acos(min(1.0, dot)))
    assert drift < .05
    with TemporaryDirectory() as folder:
        path = Path(folder) / "devices.json"
        devices = [{"mac": "AA:BB:CC:DD:EE:FF", "ip": "192.168.1.42"}]
        save_device_history(devices, path)
        assert load_device_history(path) == devices
        assert forget_device(devices, devices[0]) == []
    print("dashboard self-test passed")


def main():
    parser = argparse.ArgumentParser(description="UWB + IMU device dashboard")
    parser.add_argument("target", nargs="?", default="",
                        help="device MAC address, IP address, or URL")
    parser.add_argument("--demo", action="store_true",
                        help="show simulated data without hardware")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    root = tk.Tk()
    Dashboard(root, args.target, args.demo)
    root.mainloop()


if __name__ == "__main__":
    main()
