#!/usr/bin/env python3
"""Multi-anchor UWB localization dashboard. Python standard library only."""

import argparse
import base64
import csv
import json
import math
import queue
import socket
import statistics
import threading
import time
import tkinter as tk
from collections import deque
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from tempfile import TemporaryDirectory
from tkinter import filedialog, messagebox, ttk
from urllib.error import URLError
from urllib.request import Request, urlopen

from dashboard import (CALIBRATION_SAMPLES, Orientation, adjusted_zoom,
                       calibrated_antenna_delay, normalize_mac)
from cir_viewer import CIRWindow

BG = "#e1e8ec"
PANEL = "#f4f6f7"
PANEL_2 = "#d7e1e6"
TEXT = "#102a3a"
MUTED = "#4c6c80"
CYAN = "#007f9f"
GREEN = "#087a4a"
AMBER = "#a55b00"
RED = "#c3344f"
BORDER = "#829eae"
GRID = "#c4d2d9"
DISCOVERY_PORT = 4210
FAST_SCAN_SECONDS = 2
CONNECTED_SCAN_SECONDS = 10
RANGE_MAX_AGE_SECONDS = 3.0
CONFIG_FILE = Path.home() / ".uwb_localization_devices.json"
LOGO_FILE = Path(__file__).resolve().parent / "assets" / "sense-logo.png"


def endpoint(value):
    value = str(value).strip().rstrip("/")
    if value.lower().startswith(("http://", "https://")):
        value = value.split("://", 1)[1].split("/", 1)[0]
    return value


def valid_ip(value):
    try:
        socket.inet_aton(endpoint(value))
        return endpoint(value).count(".") == 3
    except OSError:
        return False


def record_key(record):
    return normalize_mac(record.get("mac", "")) or endpoint(
        record.get("ip") or record.get("address", ""))


def clean_record(record):
    if not isinstance(record, dict):
        raise ValueError("device record must be an object")
    address = endpoint(record.get("address", ""))
    mac = normalize_mac(record.get("mac", "") or address) or ""
    ip = endpoint(record.get("ip", "") or (address if valid_ip(address) else ""))
    if not mac and not valid_ip(ip):
        raise ValueError("enter a valid MAC or IPv4 address")
    role = str(record.get("role", "anchor")).lower()
    if role not in ("anchor", "tag"):
        raise ValueError("role must be anchor or tag")
    coordinates = [float(record.get(name, 0))
                   for name in ("x", "y", "z", "yaw_offset")]
    if not all(math.isfinite(value) for value in coordinates):
        raise ValueError("coordinates and yaw offset must be finite numbers")
    return {
        "mac": mac,
        "ip": ip,
        "address": mac or ip,
        "role": role,
        "x": coordinates[0],
        "y": coordinates[1],
        "z": coordinates[2],
        "yaw_offset": coordinates[3],
    }


def load_devices(path=CONFIG_FILE):
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        return [clean_record(item) for item in raw if isinstance(item, dict)]
    except (OSError, ValueError, TypeError):
        return []


def save_devices(devices, path=CONFIG_FILE):
    try:
        path.write_text(json.dumps(devices, indent=2), encoding="utf-8")
    except OSError:
        pass


def trilaterate_2d(measurements):
    """Linear least-squares 2D position from [(x, y, range), ...]."""
    if len(measurements) < 3:
        raise ValueError("at least three fresh anchor ranges are required")
    x0, y0, d0 = measurements[0]
    rows = []
    for x, y, distance in measurements[1:]:
        rows.append((2 * (x - x0), 2 * (y - y0),
                     d0 * d0 - distance * distance + x * x + y * y -
                     x0 * x0 - y0 * y0))
    aa = sum(a * a for a, _, _ in rows)
    ab = sum(a * b for a, b, _ in rows)
    bb = sum(b * b for _, b, _ in rows)
    ac = sum(a * c for a, _, c in rows)
    bc = sum(b * c for _, b, c in rows)
    determinant = aa * bb - ab * ab
    if abs(determinant) < 1e-9:
        raise ValueError("anchor coordinates are collinear")
    px = (ac * bb - bc * ab) / determinant
    py = (aa * bc - ab * ac) / determinant
    residual = math.sqrt(sum(
        (math.hypot(px - x, py - y) - distance) ** 2
        for x, y, distance in measurements) / len(measurements))
    return px, py, residual


def coordinate_distance(first, second):
    return math.sqrt(sum((first[axis] - second[axis]) ** 2
                         for axis in ("x", "y", "z")))


def fitted_map_bounds(points, width, height, margin=55, top_margin=None):
    """Return padded world bounds matching the canvas aspect ratio."""
    top_margin = margin if top_margin is None else top_margin
    min_x, max_x = min(x for x, _ in points), max(x for x, _ in points)
    min_y, max_y = min(y for _, y in points), max(y for _, y in points)
    pad = max(0.05, max(max_x - min_x, max_y - min_y) * .15)
    min_x, max_x = min_x - pad, max_x + pad
    min_y, max_y = min_y - pad, max_y + pad
    span_x, span_y = max_x - min_x, max_y - min_y
    aspect = max(width - 2 * margin, 1) / max(height - margin - top_margin, 1)
    if span_x / span_y < aspect:
        extra = (span_y * aspect - span_x) / 2
        min_x, max_x = min_x - extra, max_x + extra
    else:
        extra = (span_x / aspect - span_y) / 2
        min_y, max_y = min_y - extra, max_y + extra
    return min_x, max_x, min_y, max_y


def visual_device_names(devices, observed_tags=()):
    """Create dashboard-only A1/A2 and T1/T2 labels in list order."""
    names, counts = {}, {"anchor": 0, "tag": 0}
    for device in devices:
        role = device.get("role")
        if role not in counts:
            continue
        counts[role] += 1
        name = f"{'A' if role == 'anchor' else 'T'}{counts[role]}"
        for identity in (record_key(device), device.get("mac"), device.get("ip")):
            if identity:
                names[identity] = name
    for tag in sorted(observed_tags):
        if tag not in names:
            counts["tag"] += 1
            names[tag] = f"T{counts['tag']}"
    return names


def records_for_sources(records, sources):
    return [record for record in records
            if record.get("source_address") in sources]


def system_timestamp(unix_seconds):
    stamp = datetime.fromtimestamp(unix_seconds)
    return stamp.strftime("%d/%m/%Y %H:%M:%S:") + f"{stamp.microsecond // 1000:03d}"


def csv_fields(records):
    fields = {field for record in records for field in record}
    first = [field for field in ("timestamp", "id", "ip") if field in fields]
    return first + sorted(fields.difference(first))


def discover_devices(timeout=0.8):
    found = {}
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.settimeout(0.12)
    sock.bind(("", 0))
    deadline = time.monotonic() + timeout
    try:
        while time.monotonic() < deadline:
            sock.sendto(b"UWB_DISCOVER", ("255.255.255.255", DISCOVERY_PORT))
            listen_until = min(deadline, time.monotonic() + 0.25)
            while time.monotonic() < listen_until:
                try:
                    payload, source = sock.recvfrom(512)
                    device = json.loads(payload.decode("utf-8"))
                    if not isinstance(device, dict):
                        continue
                    mac = normalize_mac(device.get("mac", ""))
                    if not mac:
                        continue
                    found[mac] = {"mac": mac,
                                  "ip": source[0],
                                  "role": (device.get("role") if device.get("role")
                                           in ("tag", "anchor") else "unknown")}
                except socket.timeout:
                    break
                except (UnicodeError, ValueError, TypeError):
                    continue
    finally:
        sock.close()
    return list(found.values())


def fetch_telemetry(ip):
    request = Request(f"http://{endpoint(ip)}/api/telemetry",
                      headers={"Cache-Control": "no-cache"})
    with urlopen(request, timeout=0.7) as response:
        return json.load(response)


class LocalizationDashboard:
    def __init__(self, root, demo=False):
        self.root, self.demo = root, demo
        self.devices = self.demo_devices() if demo else load_devices()
        self.device_lock = threading.Lock()
        self.discovery_lock = threading.Lock()
        self.discovery_cache = {}
        self.states = {}
        self.ranges = {}
        self.positions = {}
        self.orientations = {}
        self.imu_histories = {}
        self.imu_name_to_key = {}
        self.imu_selected_key = None
        self.map_zoom = 1.0
        self.collecting = False
        self.collection_sources = set()
        self.collection_ids = {}
        # ponytail: bounded RAM capture; stream to disk if sessions exceed 100k samples.
        self.collection_records = deque(maxlen=100000)
        self.calibration_pairs = {}
        self.calibration_seen = set()
        self.events = queue.Queue(maxsize=128)
        self.stop_event = threading.Event()
        self.scan_now = threading.Event()
        self.online_event = threading.Event()
        self.selected_key = None
        self.device_dialog = None
        self.cir_window = None
        self.scan_status = tk.StringVar(value="Scanner starting")
        self.summary = tk.StringVar(value="Waiting for anchor ranges")
        self.address = tk.StringVar()
        self.role = tk.StringVar(value="anchor")
        self.x = tk.StringVar(value="0")
        self.y = tk.StringVar(value="0")
        self.z = tk.StringVar(value="0")
        self.yaw = tk.StringVar(value="0")
        self.imu_device_name = tk.StringVar()
        self.imu_device_details = tk.StringVar()
        self.status = tk.StringVar(value="DEMO MODE" if demo else "READY")
        self.build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(50, self.process_events)
        self.root.after(1000, self.refresh_online_state)
        if demo:
            self.root.after(100, self.demo_tick)
        else:
            threading.Thread(target=self.scanner_loop, name="device-discovery", daemon=True).start()
            threading.Thread(target=self.poll_loop, name="telemetry-poller", daemon=True).start()

    @staticmethod
    def demo_devices():
        return [
            clean_record({"mac": f"02:00:00:00:00:0{i}", "role": "anchor",
                          "x": x, "y": y, "z": 2.2})
            for i, (x, y) in enumerate(((0, 0), (8, 0), (8, 6), (0, 6)), 1)
        ] + [clean_record({"mac": "02:00:00:00:01:01", "role": "tag",
                           "x": 0, "y": 0, "z": 0.8})]

    def build_ui(self):
        self.root.title("SE UWB Localization")
        self.root.geometry("1500x920")
        self.root.minsize(1120, 720)
        self.root.configure(bg=BG)
        style = ttk.Style()
        style.theme_use("clam")
        style.configure("Treeview", background=PANEL, fieldbackground=PANEL,
                        foreground=TEXT, rowheight=27, bordercolor=BORDER)
        style.configure("Treeview.Heading", background=PANEL_2, foreground=TEXT,
                        font=("Segoe UI", 9, "bold"))
        style.map("Treeview", background=[("selected", "#cfeef5")],
                  foreground=[("selected", CYAN)])

        top = tk.Frame(self.root, bg=PANEL, padx=18, pady=10,
                       highlightbackground=BORDER, highlightthickness=1)
        top.pack(fill="x", padx=16, pady=(14, 7))
        brand = tk.Frame(top, bg=PANEL)
        brand.pack(side="left")
        try:
            self.brand_logo_source = tk.PhotoImage(
                data=base64.b64encode(LOGO_FILE.read_bytes()))
            self.brand_logo = self.brand_logo_source
            tk.Label(brand, image=self.brand_logo, bg=PANEL, borderwidth=0).pack()
        except tk.TclError:
            tk.Label(brand, text="SE 〰 SE", bg=PANEL, fg=AMBER,
                     font=("Segoe UI", 19, "bold")).pack()
        tk.Label(brand, text="© Made by astommondal013@gmail.com", bg=PANEL,
                 fg=MUTED, font=("Segoe UI", 8)).pack(anchor="center", pady=(2, 0))

        self.brand_separator = tk.Frame(top, bg=CYAN, width=3, height=105)
        self.brand_separator.pack(side="left", fill="y", padx=(20, 18), pady=5)
        heading = tk.Frame(top, bg=PANEL)
        heading.pack(side="left", padx=(0, 28), fill="x", expand=True)
        tk.Label(heading, text="UWB LOCALIZATION", bg=PANEL,
                 fg=TEXT, font=("Segoe UI", 36, "bold")).pack(anchor="w")
        tk.Label(heading, textvariable=self.summary, bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 10)).pack(anchor="w", pady=(4, 0))
        actions = tk.Frame(top, bg=PANEL)
        actions.pack(side="right")
        tk.Label(actions, textvariable=self.scan_status, bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 9)).pack(anchor="e", pady=(0, 6))
        self.pose_button = self.button(actions, "START TRACKING", self.toggle_device_streaming, GREEN)
        self.pose_button.pack(side="left", padx=4)
        self.button(actions, "↻  REFRESH SCAN", self.manual_scan, CYAN).pack(
            side="left", padx=4)
        self.collection_button = self.button(
            actions, "SAVE CSV", self.toggle_collection, GREEN)
        self.collection_button.configure(width=11)
        self.collection_button.pack(side="left", padx=4)
        self.button(actions, "CLEAR ALL DEVICES", self.clear_all, RED).pack(
            side="left", padx=4)

        split = tk.PanedWindow(self.root, orient="horizontal", bg=BG,
                               sashwidth=7, sashrelief="flat")
        split.pack(fill="both", expand=True, padx=16, pady=(0, 14))
        map_panel = tk.Frame(split, bg=PANEL, highlightbackground=BORDER,
                             highlightthickness=1)
        side = tk.Frame(split, bg=BG, width=540)
        split.add(map_panel, stretch="always", minsize=580)
        split.add(side, stretch="never", minsize=470)

        map_head = tk.Frame(map_panel, bg=PANEL_2, padx=14, pady=9)
        map_head.pack(fill="x")
        tk.Label(map_head, text="LIVE 2D LOCALIZATION MAP", bg=PANEL_2, fg=TEXT,
                 font=("Segoe UI", 11, "bold")).pack(side="left")
        # tk.Label(map_head,
        #          text="Anchor coordinates define the map • Tag Z is its tracking-plane height • Fresh ranges expire after 3 s",
        #          bg=PANEL_2, fg=MUTED, font=("Segoe UI", 9)).pack(
        #              side="left", fill="x", expand=True)
        tk.Label(map_head, textvariable=self.status, bg=PANEL_2, fg=GREEN,
                 font=("Consolas", 10, "bold")).pack(side="right")
        self.map = tk.Canvas(map_panel, bg="#f9fbfc", highlightthickness=0)
        self.map.pack(fill="both", expand=True)
        self.map.bind("<Configure>", lambda _event: self.draw_map())
        self.map.bind("<MouseWheel>", self.zoom_map)
        self.map.bind("<Button-4>", self.zoom_map)
        self.map.bind("<Button-5>", self.zoom_map)
        self.map.bind("<Double-1>", self.fit_map)

        imu_head = tk.Frame(map_panel, bg=PANEL_2, padx=14, pady=6)
        imu_head.pack(fill="x")
        tk.Label(imu_head, text="LIVE IMU", bg=PANEL_2, fg=TEXT,
                 font=("Segoe UI", 10, "bold")).pack(side="left")
        tk.Label(imu_head, text="DEVICE", bg=PANEL_2, fg=MUTED,
                 font=("Segoe UI", 8, "bold")).pack(side="left", padx=(18, 6))
        self.imu_selector = ttk.Combobox(
            imu_head, textvariable=self.imu_device_name, state="readonly", width=7)
        self.imu_selector.pack(side="left")
        self.imu_selector.bind("<<ComboboxSelected>>", self.select_imu_device)
        tk.Label(imu_head, textvariable=self.imu_device_details, bg=PANEL_2,
                 fg=TEXT, font=("Consolas", 9, "bold")).pack(
                     side="left", padx=8)
        self.imu_graph = tk.Canvas(map_panel, height=175, bg=PANEL,
                                   highlightbackground=BORDER,
                                   highlightthickness=1)
        self.imu_graph.pack(fill="x")
        self.imu_graph.bind("<Configure>", lambda _event: self.draw_imu_graph())

        self.section(side, "CONFIGURED DEVICES")
        self.configured = ttk.Treeview(side, columns=("name", "role", "address", "xyz", "state"),
                                       show="headings", height=7)
        for column, title, width in (("name", "NAME", 52), ("role", "ROLE", 58),
                                     ("address", "MAC / IP", 150),
                                     ("xyz", "X, Y, Z (m)", 120),
                                     ("state", "STATE", 72)):
            self.configured.heading(column, text=title)
            self.configured.column(column, width=width, anchor="center")
        self.configured.pack(fill="x")
        self.configured.bind("<<TreeviewSelect>>", self.select_configured)
        self.configured.bind("<Double-1>", lambda _event: self.edit_selected())
        configured_actions = tk.Frame(side, bg=BG)
        configured_actions.pack(fill="x", pady=(6, 0))
        self.button(configured_actions, "+  ADD DEVICE", self.open_device_dialog,
                    GREEN).pack(side="left", fill="x", expand=True, padx=(0, 3))
        self.button(configured_actions, "EDIT SELECTED", self.edit_selected,
                    CYAN).pack(side="left", fill="x", expand=True, padx=3)
        self.button(configured_actions, "DELETE", self.remove_selected,
                    RED).pack(side="left", fill="x", expand=True, padx=(3, 0))
        self.calibration_button = self.button(
            side, "CALIBRATE UWB FROM XYZ", self.calibrate_from_coordinates, AMBER)
        self.calibration_button.pack(fill="x", pady=(6, 0))
        self.button(side, "VIEW CIR - IMU SELECTED DEVICE", self.open_cir, CYAN).pack(
            fill="x", pady=(6, 0))

        self.section(side, "CONTINUOUS NETWORK SCANNER")
        self.discovered_tree = ttk.Treeview(
            side, columns=("mac", "ip", "role"), show="headings", height=6)
        for column, title, width in (("mac", "MAC", 165), ("ip", "IP", 115),
                                     ("role", "ROLE", 65)):
            self.discovered_tree.heading(column, text=title)
            self.discovered_tree.column(column, width=width, anchor="center")
        self.discovered_tree.pack(fill="x")
        self.discovered_tree.bind("<Double-1>", lambda _event: self.use_discovered())
        self.button(side, "ADD SELECTED DEVICE", self.use_discovered, AMBER).pack(
            fill="x", pady=(6, 0))

        self.section(side, "LOCALIZATION STATUS")
        self.details = tk.Text(side, height=8, bg=PANEL, fg=TEXT, relief="flat",
                               highlightbackground=BORDER, highlightthickness=1,
                               font=("Consolas", 9), padx=10, pady=8, state="disabled")
        self.details.pack(fill="both", expand=True)
        self.refresh_tables()
        self.draw_map()
        self.draw_imu_graph()

    @staticmethod
    def section(parent, text):
        tk.Label(parent, text=text, bg=BG, fg=MUTED,
                 font=("Segoe UI", 9, "bold")).pack(anchor="w", pady=(10, 4))

    @staticmethod
    def button(parent, text, command, color):
        return tk.Button(parent, text=text, command=command, bg=PANEL_2, fg=color,
                         activebackground="#cfeef5", activeforeground=TEXT,
                         relief="flat", borderwidth=0, cursor="hand2",
                         padx=12, pady=8, font=("Segoe UI", 9, "bold"))

    @staticmethod
    def field(parent, title, variable, row, column, width=10, span=1):
        tk.Label(parent, text=title, bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 8, "bold")).grid(
                     row=row, column=column, columnspan=span, sticky="w",
                     padx=(0, 7), pady=(0, 3))
        tk.Entry(parent, textvariable=variable, width=width, bg="#f9fbfc", fg=TEXT,
                 relief="flat", highlightbackground=BORDER, highlightcolor=CYAN,
                 highlightthickness=1, font=("Consolas", 10)).grid(
                     row=row + 1, column=column, columnspan=span, sticky="ew",
                     padx=(0, 7), ipady=5)
        parent.grid_columnconfigure(column, weight=1)

    @staticmethod
    def sync_tree(tree, rows):
        """Update Treeview rows without destroying its selection or focus."""
        wanted = {key for key, _ in rows}
        for old in tree.get_children():
            if old not in wanted:
                tree.delete(old)
        for index, (key, values) in enumerate(rows):
            if tree.exists(key):
                tree.item(key, values=values)
            else:
                tree.insert("", "end", iid=key, values=values)
            tree.move(key, "", index)

    def close_device_dialog(self):
        if self.device_dialog and self.device_dialog.winfo_exists():
            self.device_dialog.grab_release()
            self.device_dialog.destroy()
        self.device_dialog = None

    def open_device_dialog(self, device=None, selected_key=None):
        self.close_device_dialog()
        self.selected_key = selected_key
        if selected_key is None:
            self.configured.selection_remove(*self.configured.selection())
        self.address.set((device or {}).get("mac") or (device or {}).get("ip", ""))
        self.role.set((device or {}).get("role", "anchor"))
        self.x.set(f"{(device or {}).get('x', 0):g}")
        self.y.set(f"{(device or {}).get('y', 0):g}")
        self.z.set(f"{(device or {}).get('z', 0):g}")
        self.yaw.set(f"{(device or {}).get('yaw_offset', 0):g}")

        dialog = tk.Toplevel(self.root, bg=BG)
        self.device_dialog = dialog
        dialog.title("Edit device" if selected_key else "Add device")
        dialog.resizable(False, False)
        dialog.transient(self.root)
        dialog.protocol("WM_DELETE_WINDOW", self.close_device_dialog)
        dialog.bind("<Escape>", lambda _event: self.close_device_dialog())

        tk.Label(dialog,
                 text="EDIT DEVICE" if selected_key else "ADD DEVICE",
                 bg=BG, fg=TEXT, font=("Segoe UI", 13, "bold")).pack(
                     anchor="w", padx=16, pady=(14, 6))
        form = tk.Frame(dialog, bg=PANEL, padx=12, pady=10,
                        highlightbackground=BORDER, highlightthickness=1)
        form.pack(fill="both", expand=True, padx=16, pady=(0, 16))
        self.field(form, "MAC OR IP", self.address, 0, 0, width=27, span=3)
        tk.Label(form, text="ROLE", bg=PANEL, fg=MUTED,
                 font=("Segoe UI", 8, "bold")).grid(row=0, column=3, sticky="w")
        role = tk.Menubutton(form, text=f"{self.role.get()}   ▼",
                             indicatoron=False, bg=PANEL_2, fg=TEXT,
                             activebackground="#cfeef5", activeforeground=TEXT,
                             relief="flat", borderwidth=0, width=10,
                             font=("Segoe UI", 9))
        role_options = tk.Menu(role, tearoff=False, bg=PANEL, fg=TEXT,
                               activebackground="#cfeef5", activeforeground=TEXT)
        def choose_role(value):
            self.role.set(value)
            role.configure(text=f"{value}   ▼")
        for value in ("anchor", "tag"):
            role_options.add_command(label=value, command=lambda item=value: choose_role(item))
        role.configure(menu=role_options)
        role.grid(row=1, column=3, sticky="ew", padx=(8, 0))
        self.field(form, "X (m)", self.x, 2, 0)
        self.field(form, "Y (m)", self.y, 2, 1)
        self.field(form, "Z / TAG HEIGHT (m)", self.z, 2, 2)
        self.field(form, "YAW OFFSET (deg)", self.yaw, 2, 3)
        buttons = tk.Frame(form, bg=PANEL)
        buttons.grid(row=4, column=0, columnspan=4, sticky="ew", pady=(10, 0))
        self.button(buttons, "APPLY", self.apply_role, GREEN).pack(
            side="left", fill="x", expand=True, padx=(0, 4))
        delete = self.button(buttons, "DELETE", self.remove_selected, RED)
        delete.configure(state="normal" if selected_key else "disabled")
        delete.pack(side="left", fill="x", expand=True, padx=(4, 0))
        dialog.bind("<Return>", lambda _event: self.apply_role())
        dialog.update_idletasks()
        x = self.root.winfo_rootx() + (self.root.winfo_width() - dialog.winfo_width()) // 2
        y = self.root.winfo_rooty() + (self.root.winfo_height() - dialog.winfo_height()) // 2
        dialog.geometry(f"+{max(0, x)}+{max(0, y)}")
        dialog.grab_set()
        dialog.focus_force()

    def device_snapshot(self):
        with self.device_lock:
            return [dict(item) for item in self.devices]

    def scanner_loop(self):
        while not self.stop_event.is_set():
            try:
                found = discover_devices()
                with self.discovery_lock:
                    self.discovery_cache.update({item["mac"]: item for item in found})
                self.post("scan", found)
            except OSError as error:
                self.post("notice", f"Scanner: {error}")
            interval = CONNECTED_SCAN_SECONDS if self.online_event.is_set() else FAST_SCAN_SECONDS
            self.scan_now.wait(interval)
            self.scan_now.clear()

    def poll_loop(self):
        with ThreadPoolExecutor(max_workers=8, thread_name_prefix="telemetry-http") as pool:
            while not self.stop_event.is_set():
                devices = self.device_snapshot()
                with self.discovery_lock:
                    discovered = dict(self.discovery_cache)
                jobs = {}
                for device in devices:
                    ip = device.get("ip") or discovered.get(device.get("mac"), {}).get("ip")
                    if ip:
                        jobs[pool.submit(fetch_telemetry, ip)] = (record_key(device), ip)
                for future in as_completed(jobs):
                    key, ip = jobs[future]
                    try:
                        self.post("sample", (key, ip, future.result()))
                    except (OSError, URLError, ValueError) as error:
                        self.post("offline", (key, str(error)))
                self.stop_event.wait(0.18)

    def post(self, kind, payload):
        try:
            self.events.put_nowait((kind, payload))
        except queue.Full:
            try:
                self.events.get_nowait()
                self.events.put_nowait((kind, payload))
            except queue.Empty:
                pass

    def process_events(self):
        changed = False
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "scan":
                    self.apply_scan(payload)
                elif kind == "sample":
                    self.apply_sample(*payload)
                elif kind == "offline":
                    key, error = payload
                    self.states.setdefault(key, {})["error"] = error
                elif kind == "notice":
                    self.status.set(payload)
                changed = True
        except queue.Empty:
            pass
        if changed:
            self.solve_positions()
            self.refresh_tables()
            self.draw_map()
            self.draw_imu_graph()
        if not self.stop_event.is_set():
            self.root.after(80, self.process_events)

    def apply_scan(self, found):
        added = {record_key(item) for item in self.devices}
        self.scan_status.set(
            f"Scanner: {len(found)} found • {'10 s connected mode' if self.online_event.is_set() else '2 s search mode'}")
        for device in found:
            if device["mac"] in added:
                self.fill_identity(device["mac"], device["mac"], device["ip"])

    def fill_identity(self, key, mac, ip):
        changed = False
        with self.device_lock:
            for device in self.devices:
                if record_key(device) == key or device.get("mac") == mac:
                    if mac and device.get("mac") != mac:
                        device["mac"] = mac
                        changed = True
                    if ip and device.get("ip") != ip:
                        device["ip"] = ip
                        changed = True
                    break
        if changed and not self.demo:
            save_devices(self.devices)
        if changed and self.selected_key == key:
            self.selected_key = mac or ip
        if changed and self.imu_selected_key == key:
            self.imu_selected_key = mac or ip

    def apply_sample(self, key, ip, sample):
        mac = normalize_mac(sample.get("mac", "")) or ""
        self.fill_identity(key, mac, ip)
        state = self.states.setdefault(key, {})
        state.update({"online": True, "seen": time.monotonic(), "sample": sample,
                      "error": ""})
        streaming = any(item.get("online") and item.get("sample", {}).get("streaming", True)
                        for item in self.states.values())
        self.pose_button.configure(text="PAUSE TRACKING" if streaming else "START TRACKING",
                                   fg=RED if streaming else GREEN)
        source_role = str(sample.get("role", "")).lower()
        source_mac = mac or key
        received = time.monotonic()
        sample_id = (sample.get("sequence"), sample.get("timestamp_ms"),
                     sample.get("imu_sequence"), sample.get("imu_timestamp_ms"))
        samples = sample.get("ranges")
        if not isinstance(samples, list) or not samples:
            samples = [sample] if sample.get("valid") else []
        for item in samples:
            peer = normalize_mac(item.get("peer", ""))
            distance = item.get("distance_m")
            if not peer or not isinstance(distance, (int, float)) or distance <= 0:
                continue
            age = max(0.0, float(item.get("age_ms", 0)) / 1000.0)
            if source_role == "tag":
                anchor, tag = peer, source_mac
            else:
                anchor, tag = source_mac, peer
            self.ranges[(anchor, tag)] = {
                "distance": float(distance), "rx": item.get("rx_power_dbm"),
                "time": received - age}
            if source_role == "anchor":
                target = self.calibration_pairs.get((anchor, tag))
                stamp = item.get("timestamp_ms")
                marker = (anchor, tag, stamp)
                if target and stamp is not None and marker not in self.calibration_seen:
                    self.calibration_seen.add(marker)
                    target["samples"].append(float(distance))
                    counts = [len(value["samples"])
                              for value in self.calibration_pairs.values()]
                    self.status.set(
                        f"CALIBRATING {len(counts)} ANCHORS  •  "
                        f"{min(counts)}/{CALIBRATION_SAMPLES}  •  KEEP TAG STILL")
                    if all(count >= CALIBRATION_SAMPLES for count in counts):
                        self.finish_coordinate_calibration()
        if source_role == "tag" and sample.get("imu_valid"):
            orientation = self.orientations.setdefault(source_mac, Orientation())
            orientation_sample = dict(sample)
            orientation_sample["timestamp_ms"] = sample.get(
                "imu_timestamp_ms", sample.get("timestamp_ms", 0))
            orientation.update(orientation_sample)
        if sample.get("imu_valid"):
            accel, gyro = sample.get("accel", ()), sample.get("gyro", ())
            values = tuple(accel) + tuple(gyro)
            if len(values) == 6 and all(isinstance(value, (int, float)) and
                                        math.isfinite(value) for value in values):
                stamp = float(sample.get(
                    "imu_timestamp_ms", sample.get("timestamp_ms", 0))) / 1000.0
                history = self.imu_histories.setdefault(source_mac, deque(maxlen=240))
                if history and stamp < history[-1][0]:
                    history.clear()
                if not history or stamp != history[-1][0]:
                    history.append((stamp,) + values)
        if (self.collecting and
                any(identity in self.collection_sources
                    for identity in (source_mac, key, ip)) and
                (sample_id == (None, None) or
                 self.collection_ids.get(source_mac) != sample_id)):
            self.collection_sources.add(source_mac)
            self.solve_positions()
            captured_at, captured_mono = time.time(), time.monotonic()
            ranges = [{"anchor": anchor, "tag": tag,
                       "distance_m": data["distance"],
                       "rx_power_dbm": data.get("rx"),
                       "measurement_unix_s": captured_at -
                       max(0, captured_mono - data["time"])}
                      for (anchor, tag), data in self.ranges.items()]
            positions = {
                tag: {"x_m": data["x"], "y_m": data["y"], "z_m": data["z"],
                      "rms_error_m": data["error"], "anchors": data["anchors"],
                      "calculated_unix_s": captured_at -
                      max(0, captured_mono - data["time"])}
                for tag, data in self.positions.items()
            }
            record = dict(sample)
            record.update({
                "timestamp": system_timestamp(captured_at),
                "dashboard_received_iso": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "dashboard_received_unix_s": captured_at,
                "source_address": source_mac,
                "source_ip": ip,
                "dashboard_devices": [dict(device) for device in self.devices],
                "dashboard_ranges": ranges,
                "dashboard_positions": positions,
            })
            self.collection_records.append(record)
            self.collection_ids[source_mac] = sample_id

    def solve_positions(self):
        now = time.monotonic()
        anchors = {device.get("mac"): device for device in self.devices
                   if device["role"] == "anchor" and device.get("mac")}
        tags = {tag for _, tag in self.ranges}
        for tag in tags:
            tag_record = next((item for item in self.devices
                               if item.get("mac") == tag and item["role"] == "tag"), None)
            tag_z = tag_record.get("z", 0) if tag_record else 0
            measurements, used = [], []
            for (anchor_id, tag_id), reading in self.ranges.items():
                anchor = anchors.get(anchor_id)
                if tag_id != tag or not anchor or now - reading["time"] > RANGE_MAX_AGE_SECONDS:
                    continue
                vertical = anchor.get("z", 0) - tag_z
                horizontal_sq = reading["distance"] ** 2 - vertical ** 2
                if horizontal_sq <= 0:
                    continue
                measurements.append((anchor["x"], anchor["y"], math.sqrt(horizontal_sq)))
                used.append(anchor_id)
            try:
                x, y, error = trilaterate_2d(measurements)
                self.positions[tag] = {"x": x, "y": y, "z": tag_z,
                                       "error": error, "anchors": used, "time": now}
            except ValueError:
                self.positions.pop(tag, None)

    def refresh_online_state(self):
        now = time.monotonic()
        online = 0
        for state in self.states.values():
            state["online"] = now - state.get("seen", 0) < 2.5
            online += bool(state["online"])
        if online:
            self.online_event.set()
        else:
            self.online_event.clear()
        self.scan_status.set(
            f"Scanner: {'10 s connected mode' if online else '2 s search mode'} • {online} online")
        self.refresh_tables()
        self.draw_map()
        self.draw_imu_graph()
        if not self.stop_event.is_set():
            self.root.after(1000, self.refresh_online_state)

    def device_online(self, device):
        state = self.states.get(record_key(device), {})
        if state.get("online"):
            return True
        mac = device.get("mac")
        return any(state.get("online") and
                   normalize_mac(state.get("sample", {}).get("mac", "")) == mac
                   for state in self.states.values())

    def refresh_tables(self):
        selected = self.selected_key
        names = visual_device_names(self.devices, self.positions)
        configured_rows = []
        for device in self.devices:
            key = record_key(device)
            values = (names[key], device["role"].upper(),
                      device.get("mac") or device.get("ip"),
                      f"{device['x']:.2f}, {device['y']:.2f}, {device['z']:.2f}",
                      "ONLINE" if self.device_online(device) else "OFFLINE")
            configured_rows.append((key, values))
        self.sync_tree(self.configured, configured_rows)
        self.refresh_imu_selector(names)
        if (selected and self.configured.exists(selected) and
                not self.configured.selection()):
            self.configured.selection_set(selected)

        configured_macs = {item.get("mac") for item in self.devices
                           if item.get("mac")}
        configured_ips = {endpoint(item.get("ip", "")) for item in self.devices
                          if item.get("ip")}
        with self.discovery_lock:
            discovered = sorted(self.discovery_cache.values(), key=lambda item: item["mac"])
        discovered_rows = []
        for item in discovered:
            if (item["mac"] in configured_macs or
                    endpoint(item.get("ip", "")) in configured_ips):
                continue
            discovered_rows.append((item["mac"],
                                    (item["mac"], item["ip"],
                                     item["role"].upper())))
        self.sync_tree(self.discovered_tree, discovered_rows)
        self.update_details()

    def refresh_imu_selector(self, names):
        choices = [(names[record_key(device)], record_key(device))
                   for device in self.devices]
        self.imu_name_to_key = dict(choices)
        self.imu_selector.configure(values=[name for name, _key in choices])
        available = {key for _name, key in choices}
        if self.imu_selected_key not in available:
            preferred = next((record_key(device) for device in self.devices
                              if device["role"] == "tag"), None)
            self.imu_selected_key = preferred or (choices[0][1] if choices else None)
        selected_name = next((name for name, key in choices
                              if key == self.imu_selected_key), "")
        self.imu_device_name.set(selected_name)
        selected_device = next((device for device in self.devices
                                if record_key(device) == self.imu_selected_key), None)
        identity = ((selected_device.get("mac") or record_key(selected_device))
                    if selected_device else "")
        self.imu_device_details.set(
            f"{selected_name} • {identity}" if selected_name else "")

    def select_imu_device(self, _event=None):
        self.imu_selected_key = self.imu_name_to_key.get(self.imu_device_name.get())
        self.refresh_imu_selector(visual_device_names(self.devices, self.positions))
        self.draw_imu_graph()
        if self.cir_window and self.cir_window.winfo_exists():
            self.open_cir()

    def select_configured(self, _event=None):
        selection = self.configured.selection()
        if not selection:
            return
        self.selected_key = selection[0]

    def edit_selected(self):
        selection = self.configured.selection()
        if not selection:
            self.status.set("Select a configured device to edit")
            return
        key = selection[0]
        device = next(item for item in self.devices if record_key(item) == key)
        self.open_device_dialog(device, key)

    def open_cir(self):
        device = next((item for item in self.devices
                       if record_key(item) == self.imu_selected_key), None)
        if not device:
            self.status.set("Select a device in the IMU selector, then press VIEW CIR")
            return
        with self.discovery_lock:
            discovered = self.discovery_cache.get(device.get("mac"), {})
            ip = device.get("ip") or discovered.get("ip", "")
        if not ip and not self.demo:
            self.status.set("No IP for selected device; wait for discovery or edit its IP")
            return
        if self.cir_window and self.cir_window.winfo_exists():
            self.cir_window.close()
        names = visual_device_names(self.devices)
        identity = f"{names.get(self.imu_selected_key, '')} {self.imu_selected_key}".strip()
        self.cir_window = CIRWindow(self.root, identity, ip, self.demo)

    def use_discovered(self):
        selection = self.discovered_tree.selection()
        if not selection:
            self.status.set("Select a scanned device first")
            return
        with self.discovery_lock:
            device = self.discovery_cache[selection[0]]
        device = dict(device)
        if device["role"] not in ("anchor", "tag"):
            device["role"] = "anchor"
        self.open_device_dialog(device)
        self.status.set(f"Loaded {device['mac']} • enter coordinates, then ADD")

    def form_record(self):
        address = endpoint(self.address.get())
        with self.discovery_lock:
            scanned = self.discovery_cache.get(normalize_mac(address) or "", {})
        return clean_record({"address": address, "mac": normalize_mac(address) or "",
                             "ip": scanned.get("ip") or (address if valid_ip(address) else ""),
                             "role": self.role.get(), "x": self.x.get(), "y": self.y.get(),
                             "z": self.z.get(), "yaw_offset": self.yaw.get()})

    def add_or_update(self, close_dialog=True):
        try:
            record = self.form_record()
        except ValueError as error:
            self.status.set(str(error))
            return False
        new_key = record_key(record)
        replaced = False
        with self.device_lock:
            for index, device in enumerate(self.devices):
                if record_key(device) in (self.selected_key, new_key):
                    record["mac"] = record["mac"] or device.get("mac", "")
                    record["ip"] = record["ip"] or device.get("ip", "")
                    self.devices[index] = record
                    replaced = True
                    break
            if not replaced:
                self.devices.append(record)
        self.selected_key = record_key(record)
        if not self.demo:
            save_devices(self.devices)
        self.status.set(f"{'Updated' if replaced else 'Added'} {self.selected_key}")
        self.scan_now.set()
        self.refresh_tables()
        self.draw_map()
        self.draw_imu_graph()
        if close_dialog:
            self.close_device_dialog()
        return True

    def remove_selected(self):
        if not self.selected_key:
            self.status.set("Select a configured device to remove")
            return
        removed = self.selected_key
        removed_device = next((item for item in self.devices
                               if record_key(item) == removed), None)
        with self.device_lock:
            self.devices = [item for item in self.devices
                            if record_key(item) != self.selected_key]
        if removed_device:
            self.imu_histories.pop(removed_device.get("mac") or removed, None)
        self.selected_key = None
        if not self.demo:
            save_devices(self.devices)
        self.status.set(f"Removed {removed}")
        self.refresh_tables()
        self.draw_map()
        self.draw_imu_graph()
        self.close_device_dialog()

    def clear_all(self):
        if not self.devices:
            return
        if not messagebox.askyesno("Clear all devices",
                                   "Remove every configured anchor and tag?"):
            return
        with self.device_lock:
            self.devices.clear()
        self.selected_key = None
        self.ranges.clear()
        self.positions.clear()
        self.imu_histories.clear()
        if not self.demo:
            save_devices([])
        self.status.set("All configured devices cleared")
        self.refresh_tables()
        self.draw_map()
        self.draw_imu_graph()

    def apply_role(self):
        if not self.add_or_update(close_dialog=False):
            return
        device = next((item for item in self.devices
                       if record_key(item) == self.selected_key), None)
        if not device or not device.get("ip"):
            self.status.set("Configuration saved; scan the device to apply its role")
            self.close_device_dialog()
            return
        payload = json.dumps({"role": device["role"]}).encode()
        def send():
            try:
                request = Request(f"http://{device['ip']}/api/control", data=payload,
                                  headers={"Content-Type": "application/json"}, method="POST")
                with urlopen(request, timeout=1) as response:
                    json.load(response)
                self.post("notice", f"Role applied to {record_key(device)}")
            except (OSError, URLError, ValueError) as error:
                self.post("notice", f"Role update failed: {error}")
        threading.Thread(target=send, daemon=True).start()
        self.close_device_dialog()

    def manual_scan(self):
        self.scan_status.set("Scanner: manual refresh requested")
        self.scan_now.set()

    def toggle_device_streaming(self):
        devices = self.device_snapshot()
        if not devices:
            self.status.set("ADD A DEVICE BEFORE STARTING TRACKING")
            return
        active = any(state.get("sample", {}).get("streaming", True)
                     for state in self.states.values() if state.get("online"))
        streaming = not active
        with self.discovery_lock:
            discovered = dict(self.discovery_cache)
        targets = [(device, device.get("ip") or discovered.get(device.get("mac"), {}).get("ip"))
                   for device in devices]
        targets = [(device, ip) for device, ip in targets if ip]
        if not targets:
            self.status.set("NO DEVICE IP AVAILABLE TO CONTROL TRACKING")
            return
        self.pose_button.configure(text="PAUSE TRACKING" if streaming else "START TRACKING",
                                   fg=RED if streaming else GREEN)
        payload = json.dumps({"streaming": streaming}).encode()
        def send():
            succeeded = 0
            for device, ip in targets:
                try:
                    request = Request(f"http://{ip}/api/control", data=payload,
                                      headers={"Content-Type": "application/json"}, method="POST")
                    with urlopen(request, timeout=1) as response:
                        json.load(response)
                    succeeded += 1
                except (OSError, URLError, ValueError):
                    pass
            action = "started" if streaming else "paused"
            self.post("notice", f"Tracking {action} on {succeeded}/{len(targets)} devices")
        threading.Thread(target=send, daemon=True).start()

    def calibrate_from_coordinates(self):
        if self.calibration_pairs:
            self.calibration_pairs.clear()
            self.calibration_seen.clear()
            self.calibration_button.configure(text="CALIBRATE UWB FROM XYZ")
            self.status.set("UWB CALIBRATION CANCELLED")
            return
        if self.demo:
            self.status.set("Connect real devices before UWB calibration")
            return
        tags = [device for device in self.devices
                if device["role"] == "tag" and device.get("mac")]
        if len(tags) != 1:
            self.status.set("Configure exactly one tag at its measured XYZ position")
            return
        tag = tags[0]
        now = time.monotonic()
        targets = {}
        for anchor in (device for device in self.devices
                       if device["role"] == "anchor" and device.get("mac")):
            pair = (anchor["mac"], tag["mac"])
            reading = self.ranges.get(pair)
            sample = next((state.get("sample", {}) for state in self.states.values()
                           if normalize_mac(state.get("sample", {}).get("mac", "")) ==
                           anchor["mac"]), {})
            ip = anchor.get("ip") or sample.get("ip")
            delay = sample.get("antenna_delay")
            expected = coordinate_distance(anchor, tag)
            if (reading and now - reading["time"] <= RANGE_MAX_AGE_SECONDS and
                    ip and isinstance(delay, int) and .01 <= expected <= 1000):
                targets[pair] = {"ip": ip, "delay": delay,
                                 "expected": expected, "samples": []}
        if not targets:
            self.status.set("No fresh anchor ranges with IP, delay and valid XYZ")
            return
        self.calibration_pairs = targets
        self.calibration_seen.clear()
        self.calibration_button.configure(text="CANCEL CALIBRATION")
        self.status.set(
            f"CALIBRATING {len(targets)} ANCHORS  •  0/{CALIBRATION_SAMPLES}  •  KEEP TAG STILL")

    def finish_coordinate_calibration(self):
        updates = []
        try:
            for (anchor, _tag), target in self.calibration_pairs.items():
                measured = statistics.median(target["samples"])
                delay = calibrated_antenna_delay(
                    target["delay"], measured, target["expected"])
                updates.append((anchor, target["ip"], delay,
                                measured, target["expected"]))
        except ValueError as error:
            self.status.set(f"Calibration failed: {error}")
            updates = []
        self.calibration_pairs.clear()
        self.calibration_seen.clear()
        self.calibration_button.configure(text="CALIBRATE UWB FROM XYZ")
        if not updates:
            return

        def send():
            failures = []
            for anchor, ip, delay, _measured, _expected in updates:
                try:
                    payload = json.dumps({"antenna_delay": delay}).encode()
                    request = Request(f"http://{ip}/api/control", data=payload,
                                      headers={"Content-Type": "application/json"},
                                      method="POST")
                    with urlopen(request, timeout=1) as response:
                        json.load(response)
                except (OSError, URLError, ValueError) as error:
                    failures.append(f"{anchor}: {error}")
            if failures:
                self.post("notice", "Calibration update failed: " + "; ".join(failures))
            else:
                summary = ", ".join(
                    f"{anchor[-5:]} {measured:.3f}→{expected:.3f} m"
                    for anchor, _ip, _delay, measured, expected in updates)
                self.post("notice", f"CALIBRATED {len(updates)} ANCHORS • {summary}")
        threading.Thread(target=send, daemon=True).start()

    def toggle_collection(self):
        if not self.collecting:
            selected = self.choose_collection_sources()
            if not selected:
                return
            self.collection_records.clear()
            self.collection_ids.clear()
            self.collection_sources = {
                identity for device in self.devices
                if record_key(device) in selected
                for identity in (record_key(device), device.get("mac"), device.get("ip"))
                if identity}
            self.collecting = True
            self.collection_button.configure(text="STOP   ⇩", fg=RED)
            names = visual_device_names(self.devices, self.positions)
            selected_names = ",".join(
                names[record_key(device)] for device in self.devices
                if record_key(device) in selected)
            self.status.set(f"DATA COLLECTION RUNNING • {selected_names}")
            return

        self.collecting = False
        self.collection_button.configure(text="SAVE CSV", fg=GREEN)
        self.save_collection()

    def choose_collection_sources(self):
        if not self.devices:
            self.status.set("ADD A DEVICE BEFORE COLLECTING DATA")
            return set()

        dialog = tk.Toplevel(self.root, bg=BG)
        dialog.title("Select devices")
        dialog.resizable(False, False)
        dialog.transient(self.root)
        dialog.grab_set()
        tk.Label(dialog, text="SELECT DEVICES", bg=BG, fg=TEXT,
                 font=("Segoe UI", 13, "bold")).pack(
                     anchor="w", padx=18, pady=(16, 4))
        tk.Label(dialog, text="Tick the devices to record, then click OK.",
                 bg=BG, fg=MUTED, font=("Segoe UI", 9)).pack(
                     anchor="w", padx=18, pady=(0, 10))

        choices = tk.Frame(dialog, bg=PANEL, padx=12, pady=8,
                           highlightbackground=BORDER, highlightthickness=1)
        choices.pack(fill="both", expand=True, padx=18)
        names = visual_device_names(self.devices, self.positions)
        selected = {}
        for device in self.devices:
            key = record_key(device)
            selected[key] = tk.BooleanVar()
            identity = device.get("mac") or device.get("ip") or key
            tk.Checkbutton(
                choices, variable=selected[key], anchor="w", bg=PANEL, fg=TEXT,
                activebackground=PANEL, selectcolor="#f9fbfc",
                text=f"{names[key]:<4} {identity}",
                font=("Consolas", 10)).pack(fill="x", pady=2)

        notice = tk.Label(dialog, text="", bg=BG, fg=RED,
                          font=("Segoe UI", 9, "bold"))
        notice.pack(pady=4)
        result = set()

        def accept():
            result.update(key for key, checked in selected.items() if checked.get())
            if not result:
                notice.configure(text="Select at least one device.")
                return
            dialog.destroy()

        actions = tk.Frame(dialog, bg=BG)
        actions.pack(fill="x", padx=18, pady=(0, 16))
        self.button(actions, "OK", accept, GREEN).pack(
            side="left", fill="x", expand=True, padx=(0, 4))
        self.button(actions, "CANCEL", dialog.destroy, RED).pack(
            side="left", fill="x", expand=True, padx=(4, 0))
        dialog.bind("<Return>", lambda _event: accept())
        dialog.bind("<Escape>", lambda _event: dialog.destroy())
        self.root.wait_window(dialog)
        return result

    def save_collection(self):
        records = list(self.collection_records)
        if not records:
            self.status.set("NO DATA RECEIVED FROM SELECTED DEVICES")
            return
        records = records_for_sources(records, self.collection_sources)
        if not records:
            self.status.set("NO CAPTURED DATA FOR SELECTED DEVICES")
            return
        rows = []
        for record in records:
            row = dict(record)
            row["id"] = row.pop("source_address")
            row["ip"] = row.pop("source_ip")
            rows.append(row)
        path = filedialog.asksaveasfilename(
            title="Save collected localization data",
            defaultextension=".csv",
            initialfile=time.strftime("uwb_localization_%Y%m%d_%H%M%S.csv"),
            filetypes=(("CSV file", "*.csv"),))
        if not path:
            self.status.set(f"DATA COLLECTION STOPPED • {len(records)} SELECTED SAMPLES")
            return
        path = str(Path(path).with_suffix(".csv"))
        try:
            with open(path, "w", newline="", encoding="utf-8") as output:
                fields = csv_fields(rows)
                writer = csv.DictWriter(output, fieldnames=fields)
                writer.writeheader()
                for row in rows:
                    writer.writerow({key: json.dumps(value)
                                     if isinstance(value, (list, dict)) else value
                                     for key, value in row.items()})
            self.status.set(f"SAVED {len(records)} SAMPLES • {path}")
        except OSError as error:
            messagebox.showerror("Save failed", str(error))
            self.status.set("DATA SAVE FAILED")

    def update_details(self):
        lines = []
        now = time.monotonic()
        names = visual_device_names(self.devices, self.positions)
        for tag, position in sorted(self.positions.items()):
            if now - position["time"] > RANGE_MAX_AGE_SECONDS:
                continue
            lines.append(f"{names.get(tag, 'TAG')}  {tag}")
            lines.append(f"  X {position['x']:+8.3f} m   Y {position['y']:+8.3f} m")
            lines.append(f"  Z {position['z']:+8.3f} m   RMS {position['error']:.3f} m")
            lines.append(f"  Anchors {len(position['anchors'])}: " +
                         ", ".join(names.get(item, item[-5:])
                                   for item in position["anchors"]))
        if not lines:
            fresh = sum(now - item["time"] <= RANGE_MAX_AGE_SECONDS
                        for item in self.ranges.values())
            lines = ["No solved tag position.", "",
                     f"Fresh anchor-tag ranges: {fresh}",
                     "Add 3+ non-collinear anchors, set coordinates,",
                     "and add or power a tag using the same PAN ID."]
        self.details.configure(state="normal")
        self.details.delete("1.0", "end")
        self.details.insert("1.0", "\n".join(lines))
        self.details.configure(state="disabled")
        online = sum(self.device_online(item) for item in self.devices)
        self.summary.set(f"{len(self.devices)} configured • {online} online • "
                         f"{sum(item['role'] == 'anchor' for item in self.devices)} anchors • "
                         f"{len(self.positions)} localized tags")

    def draw_imu_graph(self):
        canvas = self.imu_graph
        canvas.delete("all")
        width, height = max(canvas.winfo_width(), 600), max(canvas.winfo_height(), 175)
        device = next((item for item in self.devices
                       if record_key(item) == self.imu_selected_key), None)
        if not device:
            canvas.create_text(width / 2, height / 2,
                               text="SELECT A DEVICE FROM THE LIVE IMU MENU",
                               fill=MUTED, font=("Segoe UI", 11, "bold"))
            return

        identity = device.get("mac") or record_key(device)
        history = list(self.imu_histories.get(identity, ()))
        canvas.create_text(width - 14, 11, anchor="ne",
                           text="X   Y   Z", fill=MUTED,
                           font=("Consolas", 9, "bold"))
        if not history:
            canvas.create_text(width / 2, height / 2 + 10,
                               text="Waiting for IMU telemetry from this device",
                               fill=MUTED, font=("Segoe UI", 10))
            return

        colors = (RED, GREEN, CYAN)
        chart_top, chart_bottom = 55, height - 18

        def chart(left, right, title, columns, minimum):
            latest = history[-1]
            raw_peak = max(minimum, max(abs(sample[column])
                                        for sample in history for column in columns) * 1.15)
            magnitude = 10 ** math.floor(math.log10(raw_peak))
            normalized = raw_peak / magnitude
            peak = magnitude * next(step for step in (1, 1.25, 1.5, 2, 2.5, 5, 7.5, 10)
                                    if normalized <= step)
            canvas.create_text(left, 34, anchor="nw", text=title, fill=MUTED,
                               font=("Segoe UI", 8, "bold"))
            canvas.create_text(right, 34, anchor="ne",
                               text="  ".join(f"{latest[column]:+6.2f}"
                                             for column in columns),
                               fill=TEXT, font=("Consolas", 8, "bold"))
            canvas.create_rectangle(left, chart_top, right, chart_bottom,
                                    outline=BORDER)
            center = (chart_top + chart_bottom) / 2
            for fraction in (.25, .5, .75):
                y = chart_top + (chart_bottom - chart_top) * fraction
                canvas.create_line(left, y, right, y, fill=GRID,
                                   dash=(2, 3) if fraction == .5 else ())
            for axis, column in enumerate(columns):
                points = []
                for index, sample in enumerate(history):
                    x = left + (right - left) * index / max(len(history) - 1, 1)
                    y = (center - sample[column] * (chart_bottom - chart_top) /
                         (2 * peak))
                    points.extend((x, y))
                if len(history) == 1:
                    canvas.create_oval(points[0] - 2, points[1] - 2,
                                       points[0] + 2, points[1] + 2,
                                       fill=colors[axis], outline="")
                else:
                    canvas.create_line(*points, fill=colors[axis], width=2)
            canvas.create_text(left + 4, chart_top + 3, anchor="nw",
                               text=f"+{peak:.1f}", fill=MUTED,
                               font=("Consolas", 7))
            canvas.create_text(left + 4, chart_bottom - 3, anchor="sw",
                               text=f"-{peak:.1f}", fill=MUTED,
                               font=("Consolas", 7))

        middle = width / 2
        chart(42, middle - 12, "ACCELEROMETER (g)", (1, 2, 3), 1.2)
        chart(middle + 30, width - 14, "GYROSCOPE (°/s)", (4, 5, 6), 0.5)

    def zoom_map(self, event):
        direction = (1 if getattr(event, "delta", 0) > 0 or
                     getattr(event, "num", 0) == 4 else -1)
        self.map_zoom = adjusted_zoom(self.map_zoom, direction)
        self.draw_map()
        return "break"

    def fit_map(self, _event=None):
        self.map_zoom = 1.0
        self.draw_map()
        return "break"

    @staticmethod
    def grid_step(span):
        raw = max(span / 10, 0.01)
        base = 10 ** math.floor(math.log10(raw))
        return base * (5 if raw / base > 2 else 2 if raw / base > 1 else 1)

    def draw_map(self):
        canvas = self.map
        canvas.delete("all")
        width, height = max(canvas.winfo_width(), 600), max(canvas.winfo_height(), 450)
        anchors = [item for item in self.devices if item["role"] == "anchor"]
        names = visual_device_names(self.devices, self.positions)
        points = [(item["x"], item["y"]) for item in anchors]
        points += [(item["x"], item["y"]) for item in self.positions.values()]
        if not points:
            canvas.create_text(width / 2, height / 2 - 15,
                               text="ADD ANCHORS TO CREATE A LOCALIZATION REGION",
                               fill=TEXT, font=("Segoe UI", 15, "bold"))
            canvas.create_text(width / 2, height / 2 + 18,
                               text="Use manual MAC/IP entry or double-click a scanned device",
                               fill=MUTED, font=("Segoe UI", 10))
            return
        margin, top_margin = 55, 25
        min_x, max_x, min_y, max_y = fitted_map_bounds(
            points, width, height, margin, top_margin)
        center_x, center_y = (min_x + max_x) / 2, (min_y + max_y) / 2
        half_x = (max_x - min_x) / (2 * self.map_zoom)
        half_y = (max_y - min_y) / (2 * self.map_zoom)
        min_x, max_x = center_x - half_x, center_x + half_x
        min_y, max_y = center_y - half_y, center_y + half_y
        scale = (width - 2 * margin) / (max_x - min_x)
        def screen(x, y):
            return margin + (x - min_x) * scale, height - margin - (y - min_y) * scale
        canvas.create_rectangle(margin, top_margin, width - margin, height - margin,
                                outline=BORDER, tags="plot_border")
        step = self.grid_step(min(max_x - min_x, max_y - min_y))
        x = math.ceil(min_x / step) * step
        while x <= max_x:
            sx, _ = screen(x, 0)
            canvas.create_line(sx, top_margin, sx, height - margin, fill=GRID)
            label = 0 if abs(x) < step * 1e-6 else x
            canvas.create_text(sx + 3, height - margin + 8, text=f"{label:g}", anchor="nw",
                               fill=MUTED, font=("Consolas", 8), tags="axis_label")
            x += step
        y = math.ceil(min_y / step) * step
        while y <= max_y:
            _, sy = screen(0, y)
            canvas.create_line(margin, sy, width - margin, sy, fill=GRID)
            label = 0 if abs(y) < step * 1e-6 else y
            canvas.create_text(margin - 7, sy, text=f"{label:g}", anchor="e",
                               fill=MUTED, font=("Consolas", 8), tags="axis_label")
            y += step

        if min_y <= 0 <= max_y:
            _x, axis_y = screen(0, 0)
            canvas.create_line(margin, axis_y, width - margin, axis_y,
                               fill=BORDER, width=2, arrow="last")
            canvas.create_text(width - margin - 7, axis_y - 6, text="X",
                               anchor="se", fill=TEXT,
                               font=("Consolas", 9, "bold"), tags="axis_label")
        if min_x <= 0 <= max_x:
            axis_x, _y = screen(0, 0)
            canvas.create_line(axis_x, height - margin, axis_x, top_margin,
                               fill=BORDER, width=2, arrow="last")
            canvas.create_text(axis_x + 7, top_margin + 5, text="Y",
                               anchor="nw", fill=TEXT,
                               font=("Consolas", 9, "bold"), tags="axis_label")

        for tag, position in self.positions.items():
            tx, ty = screen(position["x"], position["y"])
            for anchor_id in position["anchors"]:
                anchor = next((item for item in anchors if item.get("mac") == anchor_id), None)
                if anchor:
                    ax, ay = screen(anchor["x"], anchor["y"])
                    canvas.create_line(ax, ay, tx, ty, fill="#acd9df", dash=(4, 4))

        for index, anchor in enumerate(anchors, 1):
            x, y = screen(anchor["x"], anchor["y"])
            color = GREEN if self.device_online(anchor) or self.demo else MUTED
            canvas.create_polygon(x, y - 12, x - 11, y + 10, x + 11, y + 10,
                                  fill=color, outline=TEXT, width=1)
            visual_name = names.get(record_key(anchor), f"A{index}")
            canvas.create_text(x, y + 19,
                               text=f"{visual_name}  {anchor['x']:.2f}, {anchor['y']:.2f}",
                               anchor="n", fill=TEXT, font=("Consolas", 9, "bold"))

        for tag, position in self.positions.items():
            x, y = screen(position["x"], position["y"])
            error_radius = max(7, min(60, position["error"] * scale))
            canvas.create_oval(x - error_radius, y - error_radius,
                               x + error_radius, y + error_radius,
                               outline="#8cd0db", width=2)
            canvas.create_oval(x - 9, y - 9, x + 9, y + 9,
                               fill=CYAN, outline=TEXT, width=2)
            yaw = self.orientations.get(tag)
            yaw_offset = next((item["yaw_offset"] for item in self.devices
                               if item.get("mac") == tag), 0)
            angle = math.radians((yaw.yaw if yaw else 0) + yaw_offset)
            canvas.create_line(x, y, x + 30 * math.cos(angle), y - 30 * math.sin(angle),
                               fill=AMBER, width=3, arrow="last")
            canvas.create_text(x + 13, y - 16,
                               text=f"{names.get(tag, 'TAG')}  {tag[-5:]}\n"
                                    f"X {position['x']:.3f}  Y {position['y']:.3f}\n"
                                    f"RMS {position['error']:.3f} m",
                               anchor="sw", justify="left", fill=TEXT,
                               font=("Consolas", 9, "bold"))

        background = canvas.cget("background")
        for box in ((0, 0, width, top_margin - 1),
                    (0, height - margin + 1, width, height),
                    (0, top_margin, margin - 1, height - margin),
                    (width - margin + 1, top_margin, width, height - margin)):
            canvas.create_rectangle(*box, fill=background, outline="",
                                    tags="plot_mask")
        canvas.tag_raise("plot_border")
        canvas.tag_raise("axis_label")

    def demo_tick(self, phase=0):
        tag = next((item for item in self.devices if item["role"] == "tag"), None)
        if not tag:
            return
        px = 4 + 2.7 * math.cos(phase * .035)
        py = 3 + 1.8 * math.sin(phase * .052)
        now = time.monotonic()
        history = self.imu_histories.setdefault(tag["mac"], deque(maxlen=240))
        history.append((now,
                        .08 * math.sin(phase * .08),
                        .06 * math.cos(phase * .06),
                        1 + .04 * math.sin(phase * .04),
                        8 * math.sin(phase * .05),
                        6 * math.cos(phase * .07),
                        18 * math.sin(phase * .035)))
        for index, anchor in enumerate(item for item in self.devices
                                       if item["role"] == "anchor"):
            distance = math.sqrt((px - anchor["x"]) ** 2 + (py - anchor["y"]) ** 2 +
                                 (tag["z"] - anchor["z"]) ** 2)
            distance += .015 * math.sin(phase * .17 + index)
            self.ranges[(anchor["mac"], tag["mac"])] = {
                "distance": distance, "rx": -70, "time": now}
            self.states[record_key(anchor)] = {"online": True, "seen": now}
        self.states[record_key(tag)] = {"online": True, "seen": now}
        self.solve_positions()
        self.refresh_tables()
        self.draw_map()
        self.draw_imu_graph()
        if not self.stop_event.is_set():
            self.root.after(80, lambda: self.demo_tick(phase + 1))

    def close(self):
        self.stop_event.set()
        self.scan_now.set()
        if self.cir_window and self.cir_window.winfo_exists():
            self.cir_window.close()
        self.root.destroy()


def self_test():
    stamp = datetime(2026, 9, 9, 11, 50, 47, 123000)
    assert system_timestamp(stamp.timestamp()) == "09/09/2026 11:50:47:123"
    assert csv_fields([{"z": 1, "ip": "10.0.0.1", "timestamp": "now",
                        "id": "A1", "a": 2}]) == [
                            "timestamp", "id", "ip", "a", "z"]
    records = [{"source_address": "anchor-1", "value": 1},
               {"source_address": "tag-1", "value": 2},
               {"source_address": "anchor-1", "value": 3}]
    assert records_for_sources(records, {"tag-1"}) == [records[1]]
    assert records_for_sources(records, {"anchor-1"}) == [records[0], records[2]]
    measurements = [(0, 0, 5), (8, 0, 5), (8, 6, 5), (0, 6, 5)]
    x, y, error = trilaterate_2d(measurements)
    assert abs(x - 4) < 1e-9 and abs(y - 3) < 1e-9 and error < 1e-9
    try:
        trilaterate_2d([(0, 0, 1), (1, 0, 1), (2, 0, 1)])
        raise AssertionError("collinear anchors must fail")
    except ValueError:
        pass
    assert endpoint("HTTP://192.168.1.20/") == "192.168.1.20"
    assert valid_ip("192.168.1.20") and not valid_ip("192.168.1.999")
    assert coordinate_distance({"x": 0, "y": 0, "z": 0},
                               {"x": 3, "y": 4, "z": 12}) == 13
    bounds = fitted_map_bounds([(0, 0), (8, 0), (8, 6), (0, 6)], 1500, 700)
    min_x, max_x, min_y, max_y = bounds
    x_scale = (1500 - 110) / (max_x - min_x)
    y_scale = (700 - 110) / (max_y - min_y)
    assert abs(x_scale - y_scale) < 1e-9 and min_x < -1 and max_x > 9
    small_bounds = fitted_map_bounds([(0, 0), (0, .6), (.3, .3)], 1200, 500)
    assert small_bounds[3] - small_bounds[2] < 1.0
    assert .6 * (1200 - 110) / (small_bounds[1] - small_bounds[0]) > 250
    compact_top = fitted_map_bounds([(0, 0), (1, 1)], 1200, 500, 55, 25)
    assert abs((1200 - 110) / (compact_top[1] - compact_top[0]) -
               (500 - 80) / (compact_top[3] - compact_top[2])) < 1e-9
    named_devices = [
        clean_record({"mac": "02:00:00:00:00:01", "role": "anchor"}),
        clean_record({"mac": "02:00:00:00:00:02", "role": "anchor"}),
        clean_record({"mac": "02:00:00:00:01:01", "role": "tag"}),
    ]
    names = visual_device_names(named_devices, ["02:00:00:00:01:02"])
    assert names["02:00:00:00:00:01"] == "A1"
    assert names["02:00:00:00:00:02"] == "A2"
    assert names["02:00:00:00:01:01"] == "T1"
    assert names["02:00:00:00:01:02"] == "T2"
    assert "name" not in named_devices[0]
    with TemporaryDirectory() as folder:
        path = Path(folder) / "devices.json"
        devices = [clean_record({"mac": "aa-bb-cc-dd-ee-ff", "role": "anchor",
                                 "x": 1, "y": 2, "z": 3})]
        save_devices(devices, path)
        assert load_devices(path) == devices
    print("localization dashboard self-test passed")


def main():
    parser = argparse.ArgumentParser(description="Multi-anchor UWB localization dashboard")
    parser.add_argument("--demo", action="store_true",
                        help="show a four-anchor simulated localization region")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    root = tk.Tk()
    LocalizationDashboard(root, args.demo)
    root.mainloop()


if __name__ == "__main__":
    main()
