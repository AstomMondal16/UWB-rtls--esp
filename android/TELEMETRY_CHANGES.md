# HTTPS, recording, and tag trails

## Network inventory and migration

There is no remote backend API. `/api/telemetry`, `/api/cir`, `/api/control`
and `/api/time` all use HTTPS. Android blocks cleartext; Python clients also
require HTTPS. Each board creates a unique self-signed identity in C on first
boot and stores it persistently. No factory CA, factory tool, per-IP certificate
or private key embedded in shared firmware is required.

The chosen policy is trust on first use: initial pairing must happen on trusted
private Wi-Fi and can be impersonated at that moment. Android validates the
certificate's validity, self-signature and exact MAC-derived DNS name, then
saves its public-key fingerprint in encrypted durable storage before API data
is allowed. Later key changes are blocked until explicit Pair again confirmation.
Concurrent first connections and storage errors fail closed. DHCP changes do
not change identity. Redirects and failed-TLS fallback are not allowed. Legacy
manual fingerprints preserve their existing validity/IP/DNS checks.

The identity uses a separate partition; existing Wi-Fi/calibration and app
partition offsets are preserved. UDP discovery and BLE provisioning retain their
protocols; discovery alone does not authenticate first pairing. Time.Now remains
HTTPS. API client authorization and physical key protection are separate from
this transport migration. No real board or phone has been modified.

See [TLS setup](../components/telemetry/TLS_SETUP.md) and [customer setup](../CUSTOMER_SETUP.md).

## What CSV records

The path is firmware collectors -> latest `/api/telemetry` snapshot -> repository
poll worker -> identity validation/range ingestion -> `TelemetryRecorder` ->
app-private append-only `uwb_session.jsonl` -> streaming CSV export. Recording
does not depend on Compose rendering, the selected device, visible panel, or
dashboard/fullscreen mode. Its scope remains **all configured devices**. The
export filename uses `session`, rather than implying it contains one selected
device.

| Stream / fields received | Old recording behavior | Current recording behavior / limits |
| --- | --- | --- |
| UWB `sequence`, `timestamp_ms`, `age_ms`, `valid`, `peer`, distance, speed, RX power, per-peer `ranges` | Entire payload recorded only when the global UWB cursor changed | Records each distinct valid received UWB cursor, retaining the entire payload and all peer ranges. |
| Raw UWB exchange (`uwb_raw`) | Unfiltered inputs and frame bytes were not propagated to the app | Captured before the dashboard median filter and retained in private session payloads. The requested CSV/terminal layout exposes named timing/receiver fields; frame hex remains in private storage. See [current data coverage](CSV_FORMAT.md). |
| IMU `imu_sequence`, `imu_timestamp_ms`, valid/enabled, `accel[3]`, `gyro[3]`, temperature | Values included in UWB rows, but independent IMU updates discarded when UWB was unchanged | Independent IMU cursor; every valid received IMU update is retained even when UWB is unchanged or absent. |
| Battery valid, voltage, percent | Values included only alongside a changing UWB cursor | Independent observable value changes are retained, including battery-only devices. Firmware supplies no battery sample sequence/timestamp, so identical successive battery measurements cannot be distinguished. |
| Device MAC/IP/role, streaming, dropped count, configured interval/delay fields | Copied as received | Copied as received; source identity/IP and app receipt timestamp are included. |
| Solved tag XYZ/RMS/anchor IDs | Computed by the app but absent from CSV | Added as `positions` in private JSONL storage at ingestion. The requested 44-column CSV layout omits this derived context; it is not another physical sensor stream. |
| CIR IQ and capture metadata | Separate on-demand endpoint; not in Android telemetry CSV | Full raw I/Q and metadata are now included in telemetry and recorded independently, with separate named CSV fields. During recording, older firmware is queried through `/api/cir` for all configured devices. Python manual export is unchanged. |
| Roll/pitch/relative yaw | Calculated by the UI, not sent by the device | Raw IMU remains logged; UI orientation/filter state is not presented as device data. |

One CSV row is a received snapshot containing at least one new valid intended
stream, not one row per individual sensor inside that snapshot. Identical stream
sequence/timestamp pairs are repeated latest snapshots and are skipped. A new
recording starts with fresh cursors. Invalid vectors/ranges are not accepted as
new valid samples; validity flags remain in rows containing other valid streams.

The firmware retains only the latest global and latest-per-peer UWB sample
(up to eight peers), latest IMU/CIR samples, and latest battery values. It does not
send a replayable sample queue to the app. The nominal 350 ms poll cannot recover
all faster physical sensor measurements. Those are **not recorder losses**.
Sequence gaps are reported independently for UWB and IMU; reboots/wraps are not
expanded into huge false gaps. Invalid/unobserved samples can also contribute
to those gaps. Obtaining every physical sample requires a firmware streaming or
cursor/replay API with independent sequence/timestamps, including battery.

### Format, precision, pause, and export

- The requested CSV contains exactly the 44 named sensor columns. Other metadata remains in private JSONL storage.
  UTF-8 BOM, quoted cells, nested JSON arrays, CRLF rows, and the 512 MiB session
  limit are retained. See [current CSV and serial format](CSV_FORMAT.md).
- Private capture fields include `updated_streams`, `missed_imu_sequence_count`, `positions`.
  `positions` entries contain `tag_id`, `x_m`, `y_m`, `z_m`, `rms_error_m`, `anchors`.
  These fields remain in private capture payloads; they are omitted from the requested CSV layout.
- Device timestamps are uptime milliseconds. `_received_at` is app epoch
  milliseconds; `received_at_utc` is its UTC representation. Clocks across
  devices are not assumed synchronized.
- Distance/XYZ/RMS are meters, speed m/s, power dBm, acceleration g, gyro deg/s,
  temperature Celsius, voltage V, battery percent %. Firmware rounds ranges,
  speed and vectors to 4 decimals, power/temperature to 2, voltage to 3, and
  percent to 1 before transmission. The recorder adds no rounding; JSON numeric
  notation may change while numeric values are retained.
- Device Start/Pause and Record/Stop CSV remain separate. Firmware pauses UWB
  publishing but independently updates IMU/battery; valid updates actually
  received during CSV recording remain recordable. Stopping CSV does not send
  a device control command.
- Appends finish and close under the repository ingestion lock. Stop obtains
  that same lock and returns authoritative row bounds, avoiding a stale UI
  count. The export uses that frozen half-open interval even if a later
  recording starts while the file picker/export worker is pending. Pending
  picker bounds survive activity recreation. There is no asynchronous writer
  backlog. File rows are authoritative after restart, rather than a possibly
  stale preferences counter. Invalid stored JSON fails export explicitly.

## Map trails

The repository appends solved finite positions when their contributing range
timestamps change. Republishing UI state or receiving unrelated IMU updates
does not manufacture trail points. Each tag retains at most 128 points and
120 seconds; at most 64 tag histories are retained. Histories clear on map
configuration changes so moved anchors do not leave trails in old coordinates.

A failed device poll, invalid/missing solved position, device uptime reset, or
gap over 2.5 seconds starts a new segment. Invalid range observations are removed
and break the affected tag's line. Old points can remain visible, but are not
connected to the returning tag across that gap. History expires even when no
new positions arrive.

Both dashboard and fullscreen pass the same histories into `LocalizationMap`.
Paths and markers call the same `screen()`/`MapProjection`, including Y reversal,
pan, and zoom. Trails use stable tag colors, stronger strokes than the grid,
and draw behind anchor/current-tag markers and labels. They are clipped to the
canvas; the existing fit bounds, grid, range links, and gestures are retained.

## Reproducible verification

Android: `assembleDebug lintDebug testDebugUnitTest` using the repository Gradle
wrapper. The test-only real JSON implementation exercises parsing and export
instead of Android's JVM JSON stubs.

- `TelemetryRecordingTest`: two sources, 202 valid snapshots each, including
  a 400-update burst and two final samples immediately before Stop. Expected
  **404** saved/exported rows. Repeated snapshots add zero rows; inactive samples
  add zero; a subsequent recording adds row 405 in storage but is excluded from
  the frozen 404-row export. Every incoming field, vector and derived coordinate
  is compared numerically without rounding. The generated CSV is additionally
  parsed with Python's standard CSV reader (embedded commas, quotes and CRLF).
- Additional recording checks cover IMU-only/battery-only data, independent
  sequence gaps, paused UWB with arriving IMU, reboot, storage limit and corrupt
  session errors.
- `TagTrailStoreTest`: single/multiple tags, duplicate measurement evidence,
  128-point policy via a smaller injected bound, expiry, invalid/missing
  positions, explicit reconnect and elapsed gaps. A known three-anchor solution
  (1 m, 2 m) projects identically for marker/trail at dashboard/fullscreen sizes,
  three zoom levels, and nonzero pan.
- `DeviceHttpClientTest`: loopback HTTPS telemetry/CIR/control, correct pin,
  wrong pin, system-untrusted certificate, expiry, mismatched hostname before
  POST data, legacy HTTP, and rejected redirects. Python has three independent
  loopback pin/hostname checks plus the existing dashboard/localization self-tests.

All of the above are **simulated/computer checks**. The normal HTTP firmware
configuration was built for ESP32-S3. A read-only UDP discovery probe from this
computer found **zero responding devices**. Physical discovery/provisioning,
live hardware telemetry, TLS firmware, and actual on-phone map rendering were
therefore not verified. No phone actions or installations were performed.

Final command results, CSV counts, logs and rollback patch are stored with
`checkpoints/https-csv-trails-before-20261105-104909/`.

## Requested CSV order and direct console output

The current export supersedes earlier column layouts: see `CSV_FORMAT.md` for the exact 44-column header. CIR fields use their plain names except `cir_preamble_count`, which distinguishes the second preamble count. Battery voltage (`battery_voltage`, volts) and movement speed (`speed_mps`, meters/second) follow battery percent. No additional metadata columns are appended. Original private captures remain unchanged. The terminal uses direct `printf`, one header per boot and CSV rows; routine waiting/IMU/battery/range logs are removed.
