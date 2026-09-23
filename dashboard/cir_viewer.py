"""Live DW1000 CIR viewer; standard library only."""
import csv
from datetime import datetime
from http.client import HTTPConnection, HTTPException
import json
import math
import queue
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from urllib.error import HTTPError, URLError

from dashboard import BG, PANEL, TEXT, MUTED, CYAN, AMBER, BORDER

POLL_SECONDS = 0.1
TABLE_PAGE_SIZE = 64


def validate_cir(data):
    if not isinstance(data, dict) or type(data.get("enabled")) is not bool:
        raise ValueError("Invalid CIR response")
    if type(data.get("valid")) is not bool:
        raise ValueError("Invalid CIR status")
    if not data["enabled"] or not data["valid"]:
        return data
    for field, maximum in (("sample_count", 1016), ("start_index", 1015),
                           ("first_path_index", 1016 * 64 - 1),
                           ("peer_address", 65535), ("frame_type", 255),
                           ("sequence", 2**32 - 1), ("age_ms", 2**64 - 1)):
        value = data.get(field)
        if type(value) is not int or not 0 <= value <= maximum:
            raise ValueError(f"Invalid CIR {field}")
    iq = data.get("iq")
    if (not isinstance(iq, list) or not iq or len(iq) != data["sample_count"]
            or data["start_index"] + len(iq) > 1016):
        raise ValueError("Invalid CIR sample window")
    for pair in iq:
        if (not isinstance(pair, list) or len(pair) != 2
                or any(type(value) is not int or not -32768 <= value <= 32767
                       for value in pair)):
            raise ValueError("Invalid signed CIR I/Q samples")
    return data


def fetch_cir(ip, connection=None):
    own_connection = connection is None
    connection = connection or HTTPConnection(ip, timeout=1.0)
    try:
        connection.request("GET", "/api/cir", headers={"Cache-Control": "no-cache"})
        response = connection.getresponse()
        if response.status != 200:
            raise HTTPError(f"http://{ip}/api/cir", response.status,
                            response.reason, response.headers, None)
        body = response.read(32769)
    finally:
        if own_connection:
            connection.close()
    if len(body) > 32768:
        raise ValueError("CIR response exceeds 32 KiB")
    return validate_cir(json.loads(body))


def snapshot_key(data):
    if not data or not data["enabled"] or not data["valid"]:
        return None
    return tuple(data.get(field) for field in
                 ("sequence", "timestamp_ms", "rx_timestamp", "peer_address",
                  "start_index", "first_path_index", "sample_count"))


def cir_rows(snapshot):
    return [(snapshot["start_index"] + i, real, imag, math.hypot(real, imag))
            for i, (real, imag) in enumerate(snapshot["iq"])]


def demo_cir(sequence=1):
    iq = []
    for i in range(64):
        amplitude = (12000 * math.exp(-((i - 18) / 3)**2)
                     + 6000 * math.exp(-((i - 34) / 5)**2))
        phase = i * .4 + sequence * .1
        iq.append([round(amplitude * math.cos(phase)),
                   round(amplitude * math.sin(phase))])
    return {"enabled": True, "valid": True, "sequence": sequence,
            "age_ms": 0, "timestamp_ms": sequence * 1000,
            "peer_address": 257, "frame_type": 2,
            "first_path_index": 750 * 64 + 32, "start_index": 734,
            "sample_count": 64, "iq": iq, "demo": True}


class CIRWindow(tk.Toplevel):
    def __init__(self, parent, identity, ip, demo=False):
        super().__init__(parent)
        self.identity, self.ip, self.demo = identity, ip, demo
        self.snapshot = None
        self.rows = []
        self.table_rows = []
        self.table_offset = 0
        self.results = queue.Queue(maxsize=1)
        self.stopped = threading.Event()
        self.title(f"CIR - {identity}")
        self.geometry("900x670")
        self.minsize(660, 500)
        self.configure(bg=BG)
        self.protocol("WM_DELETE_WINDOW", self.close)
        tk.Label(self, text=f"CHANNEL IMPULSE RESPONSE  |  {identity}",
                 bg=BG, fg=TEXT, font=("Segoe UI", 13, "bold")).pack(
                     anchor="w", padx=16, pady=(14, 4))
        tk.Label(self, text=f"{'SIMULATED DATA' if demo else ip + ' /api/cir'}"
                 "  |  Polling up to 10 times/sec", bg=BG, fg=MUTED).pack(
                     anchor="w", padx=16)
        self.status = tk.StringVar(value="Connecting to CIR endpoint...")
        tk.Label(self, textvariable=self.status, bg=BG, fg=TEXT,
                 wraplength=830, justify="left").pack(fill="x", padx=16, pady=8)
        self.graph = tk.Canvas(self, bg=PANEL, height=260,
                               highlightbackground=BORDER, highlightthickness=1)
        self.graph.pack(fill="both", expand=True, padx=16)
        self.graph.bind("<Configure>", lambda _event: self.draw())
        table = tk.Frame(self, bg=BG)
        table.pack(fill="both", expand=True, padx=16, pady=8)
        self.samples = ttk.Treeview(table, columns=("index", "real", "imag", "magnitude"),
                                    show="headings", height=7)
        for column, heading in (("index", "SAMPLE INDEX"), ("real", "REAL (I)"),
                                ("imag", "IMAGINARY (Q)"), ("magnitude", "MAGNITUDE")):
            self.samples.heading(column, text=heading)
            self.samples.column(column, width=150, anchor="center")
        scroll = ttk.Scrollbar(table, orient="vertical", command=self.samples.yview)
        self.samples.configure(yscrollcommand=scroll.set)
        scroll.pack(side="right", fill="y")
        self.samples.pack(side="left", fill="both", expand=True)
        controls = tk.Frame(self, bg=BG)
        controls.pack(fill="x", padx=16, pady=(0, 12))
        self.previous_button = ttk.Button(controls, text="Previous samples",
                                          command=lambda: self.change_page(-1), state="disabled")
        self.previous_button.pack(side="left")
        self.next_button = ttk.Button(controls, text="Next samples",
                                      command=lambda: self.change_page(1), state="disabled")
        self.next_button.pack(side="left", padx=6)
        self.page_label = tk.StringVar(value="No samples")
        tk.Label(controls, textvariable=self.page_label, bg=BG, fg=MUTED).pack(side="left")
        self.save_button = ttk.Button(controls, text="SAVE CIR SNAPSHOT", command=self.save,
                                       state="disabled")
        self.save_button.pack(side="right")
        threading.Thread(target=self.poll, name="cir-network-processing", daemon=True).start()
        self.refresh_id = self.after(30, self.refresh)

    def poll(self):
        sequence = 0
        cached_key, rows, table_rows = None, [], []
        connection = None if self.demo else HTTPConnection(self.ip, timeout=1.0)
        try:
            while not self.stopped.is_set():
                started = time.monotonic()
                sequence += 1
                data, error = None, ""
                try:
                    data = demo_cir(sequence) if self.demo else fetch_cir(self.ip, connection)
                except HTTPError as exc:
                    error = ("CIR endpoint missing: rebuild and flash CIR-capable firmware."
                             if exc.code == 404 else f"CIR HTTP error: {exc.code}")
                except (OSError, URLError, HTTPException, ValueError, RecursionError) as exc:
                    error = f"CIR unavailable: {exc}"
                http_ms = (time.monotonic() - started) * 1000
                if error and connection:
                    connection.close()  # Reconnect on the next request after errors.
                key = snapshot_key(data)
                if key != cached_key:
                    rows = cir_rows(data) if key is not None else []
                    table_rows = [(i, real, imag, f"{magnitude:.2f}")
                                  for i, real, imag, magnitude in rows]
                    cached_key = key
                result = (data, error, rows, table_rows, http_ms)
                try:
                    self.results.put_nowait(result)
                except queue.Full:
                    try:
                        self.results.get_nowait()
                    except queue.Empty:
                        pass
                    self.results.put_nowait(result)
                # Include request/processing time in the period; never queue old frames.
                period = 1.0 if error or key is None else POLL_SECONDS
                if self.stopped.wait(max(0, period - (time.monotonic() - started))):
                    break
        finally:
            if connection:
                connection.close()

    def refresh(self):
        self.refresh_id = None
        if self.stopped.is_set():
            return
        try:
            data, error, rows, table_rows, http_ms = self.results.get_nowait()
        except queue.Empty:
            pass
        else:
            previous = self.snapshot
            self.snapshot = data if data and data["enabled"] and data["valid"] else None
            changed = snapshot_key(self.snapshot) != snapshot_key(previous)
            if error:
                self.status.set(error)
            elif not data["enabled"]:
                self.status.set("CIR is OFF. Enable UWB ranging > Enable DW1000 channel impulse "
                                "response (CIR) capture in menuconfig, then rebuild and flash.")
            elif not data["valid"]:
                self.status.set("CIR is ON. Waiting for a successful ranging exchange/capture.")
            else:
                age = data["age_ms"] / 1000
                capture = data.get("capture_us")
                capture_text = (f"SPI {capture / 1000:.1f} ms" if type(capture) is int
                                and capture >= 0 else "SPI timing: older firmware")
                interval = data.get("capture_interval_ms")
                interval_text = (f" | Capture limit {interval} ms" if type(interval) is int else "")
                self.status.set(
                    f"{'DEMO | ' if self.demo else ''}Sequence {data['sequence']} | "
                    f"Age {age:.1f} s | Peer 0x{data['peer_address']:04X} | "
                    f"RX frame {data['frame_type']} | {data['sample_count']} samples | "
                    f"First path {data['first_path_index'] / 64:.2f}\n"
                    f"HTTP+decode {http_ms:.0f} ms | {capture_text}{interval_text}")
            self.save_button.configure(state="normal" if self.snapshot else "disabled")
            if changed:
                self.rows, self.table_rows = rows, table_rows
                if self.table_offset >= len(rows):
                    self.table_offset = 0
                self.update_table()
                self.draw()
        self.refresh_id = self.after(30, self.refresh)

    def change_page(self, direction):
        self.table_offset = max(0, min(self.table_offset + direction * TABLE_PAGE_SIZE,
                                      max(0, (len(self.rows) - 1) // TABLE_PAGE_SIZE * TABLE_PAGE_SIZE)))
        self.update_table()

    def update_table(self):
        # Only one page touches Tk; full 1016-sample plots/exports stay available.
        page = self.table_rows[self.table_offset:self.table_offset + TABLE_PAGE_SIZE]
        children = self.samples.get_children()
        for i, values in enumerate(page):
            if i < len(children):
                self.samples.item(children[i], values=values)
            else:
                self.samples.insert("", "end", values=values)
        if len(children) > len(page):
            self.samples.delete(*children[len(page):])
        self.previous_button.configure(state="normal" if self.table_offset else "disabled")
        self.next_button.configure(state="normal" if self.table_offset + len(page) < len(self.rows) else "disabled")
        self.page_label.set(f"{self.table_offset + 1}-{self.table_offset + len(page)} / {len(self.rows)}"
                            if page else "No samples")

    def draw(self):
        canvas = self.graph
        canvas.delete("all")
        width, height = canvas.winfo_width(), canvas.winfo_height()
        if not self.snapshot:
            canvas.create_text(width / 2, height / 2, text="No CIR samples to display",
                               fill=MUTED, font=("Segoe UI", 12))
            return
        rows = self.rows
        left, top, right, bottom = 65, 30, max(66, width - 25), max(31, height - 40)
        peak = max(1, max(row[3] for row in rows)) * 1.1
        start, span = rows[0][0], max(1, len(rows) - 1)
        for tick in range(5):
            fraction = tick / 4
            y = bottom - fraction * (bottom - top)
            canvas.create_line(left, y, right, y, fill=BORDER)
            canvas.create_text(left - 6, y, text=f"{fraction * peak:.0f}", anchor="e", fill=MUTED)
            x = left + fraction * (right - left)
            canvas.create_text(x, bottom + 13, text=f"{start + fraction * span:.0f}", fill=MUTED)
        points = []
        for index, _real, _imag, magnitude in rows:
            points.extend((left + (index - start) / span * (right - left),
                           bottom - magnitude / peak * (bottom - top)))
        if len(points) >= 4:
            canvas.create_line(*points, fill=CYAN, width=2)
        fp = self.snapshot["first_path_index"] / 64
        if start <= fp <= start + span:
            x = left + (fp - start) / span * (right - left)
            canvas.create_line(x, top, x, bottom, fill=AMBER, dash=(5, 3), width=2)
        canvas.create_text(left, 13, text="Magnitude (raw)    |    Amber: detected first path",
                           anchor="w", fill=TEXT)
        canvas.create_text((left + right) / 2, height - 10, text="CIR sample index", fill=TEXT)

    def save(self):
        if not self.snapshot:
            return
        # Copy before opening the native dialog, which can run Tk callbacks.
        snapshot = dict(self.snapshot, source_device=self.identity, source_ip=self.ip,
                        saved_at=datetime.now().isoformat(timespec="milliseconds"))
        path = filedialog.asksaveasfilename(
            parent=self, title="Save current CIR capture", defaultextension=".json",
            initialfile=datetime.now().strftime("uwb_cir_%Y%m%d_%H%M%S.json"),
            filetypes=(("JSON (samples and metadata)", "*.json"), ("CSV samples", "*.csv")))
        if not path:
            return
        try:
            with open(path, "w", newline="", encoding="utf-8") as output:
                if path.lower().endswith(".csv"):
                    writer = csv.writer(output)
                    writer.writerow(("source_device", "source_ip", "sequence", "peer_address",
                                     "first_path_index", "index", "real", "imaginary", "magnitude"))
                    for row in cir_rows(snapshot):
                        writer.writerow((self.identity, self.ip, snapshot["sequence"],
                                         snapshot["peer_address"], snapshot["first_path_index"], *row))
                else:
                    json.dump(snapshot, output, indent=2, allow_nan=False)
        except (OSError, ValueError) as error:
            messagebox.showerror("CIR save failed", str(error), parent=self)

    def close(self):
        self.stopped.set()
        if self.refresh_id is not None:
            self.after_cancel(self.refresh_id)
            self.refresh_id = None
        self.destroy()
