# CSV and terminal output

Saved CSV exports use these 44 columns; terminal output labels the corresponding values with the same field names:

```csv
timestamp,source_address,ip_address,role,range,distance_m_unfiltered,poll_tx,poll_rx,ack_tx,ack_rx,range_tx,range_rx,report_tx,report_rx,local_rx_power_dbm,peer_rx_power_dbm,cir_power,rx_frame_info,preamble_count,tof_ticks,tx_power,iq,sequence,timestamp_ms,rx_timestamp,peer_address,frame_type,first_path_index,start_index,sample_count,cir_preamble_count,age_ms,capture_us,capture_interval_ms,sample_period_ns,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,battery,battery_voltage,speed_mps
```

`range` is the existing filtered UWB distance in meters; `distance_m_unfiltered` is the raw distance in meters. The spelling `Renge` was normalized to `range`. `frame_type` and `first_path_index` are separate fields. The first `preamble_count` is the UWB receiver count; `cir_preamble_count` is the CIR capture count, avoiding duplicate column names.

## Values and units

- `timestamp`: `DD-MM-YYYY HH:MM:SS` in IST (UTC+05:30), for example `05-11-2026 22:03:59`, matching the terminal's calendar display. Phone/system mode uses app receipt time; API mode uses the synchronized device snapshot epoch, falling back to receipt time if unavailable. The CSV cell contains the date/time only; its column header already says `timestamp`. Unknown or malformed calendar time is `0`, never a boot-uptime value disguised as a date. Second-level formatting can repeat across samples; it does not deduplicate or discard records. Full UTC milliseconds remain in private `received_at_utc` and `device_time_unix_ms` capture fields.
- `source_address`, `ip_address`, `role`: originating device context.
- `poll_tx` through `report_rx`: 40-bit DW1000 ranging timestamps, in device ticks; `tof_ticks`: raw time of flight in ticks. Unavailable remote timestamps use zero. Timestamp masks remain in the original private session payload.
- `local_rx_power_dbm`, `peer_rx_power_dbm`: received power in dBm. `cir_power`, `rx_frame_info`, `preamble_count`: DW1000 receiver diagnostics.
- `tx_power`: configured TX_POWER register in hex, currently `0x67676767`; it is not a measured dBm value.
- `iq`: every configured signed `[real,imaginary]` CIR pair, stored as a quoted JSON array in one CSV cell. Currently 1,016 pairs; no magnitude reduction or truncation.
- `sequence`, `timestamp_ms`, `rx_timestamp`, `peer_address`, `frame_type`, `first_path_index`, `start_index`, `sample_count`, `cir_preamble_count`, `age_ms`, `capture_us`, `capture_interval_ms`, `sample_period_ns`: CIR capture metadata. These explicitly read from the nested CIR object, not the similarly named top-level UWB fields. Times are milliseconds except `capture_us` (microseconds), `sample_period_ns` (nanoseconds), and `rx_timestamp` (DW1000 ticks). The first path index retains the firmware's raw register representation.
- `battery_voltage`: volts, three decimal places in terminal output. `speed_mps`: existing UWB-derived movement speed in meters/second, four decimal places in terminal output. These columns follow `battery`; invalid readings use `0`.
- `accel_x/y/z`: g. `gyro_x/y/z`: degrees/second. `battery`: percent.

Missing, invalid, blank and unavailable measurements use `0`, never `NA`. Valid zero readings and numeric precision are retained. UWB and CIR can have independent capture times; a row does not claim they came from the same RF frame.

## Storage and compatibility

CSV exports now contain exactly these 44 columns. Extra metadata, status masks, raw frame hex and solved position columns from the previous layout are omitted from this requested presentation. The original app-private JSONL remains unchanged and still retains received raw payloads and positions; earlier sessions can be re-exported. Existing Downloads files are not rewritten, and data absent from an old file cannot be reconstructed.

Recording still occurs at ingestion for all configured devices, independently of panel visibility or selection. Pause behavior, deduplication, frozen export bounds, synchronous pending-write completion and the 512 MiB session limit are preserved. UTF-8 BOM, quoted cells and CRLF CSV rows are retained. Array commas do not create extra columns.

Firmware exposes latest snapshots, not a replayable queue of every physical exchange. Failed/CRC-rejected exchanges are not part of successful-ranging raw captures. Tags do not receive all anchor-side timing inputs.

## Terminal

The literal `printf` is in `main/main.c`. Each terminal block has the existing underscore separator and one labeled line: `timestamp:05-11-2026 16:40, source_address:A4:CB:8F:E0:D3:C4, ip_address:192.168.1.133, ...`. Field order is retained, full I/Q is unchanged, missing measurements use `0`, and there is no separate header row. Newlines embedded in a value are displayed as escaped text to keep each sample on one line. One `printf` emits the whole block. Separator lines and labels are terminal-only; saved CSV format is unchanged.

`source_address` remains the device MAC and `ip_address` remains its IP. Menuconfig selects the default phone/system clock or optional HTTPS API with SNTP fallback. Phone mode starts immediately; the Android app or desktop dashboard posts UTC milliseconds to `/api/time` when the device asks for time. API mode attempts Time.Now five times with five-second gaps, then waits up to ten seconds for SNTP before starting the remaining tasks. If API and SNTP fail, firmware requests a phone/computer clock over the local API; uptime remains visible only until a client sends a real clock. The build-date estimate used for TLS startup is never exported as synchronized device time.

See [clock setup](../components/time_sync/README.md). No process-wide timezone is changed. Acquisition, UWB/CIR metadata and scheduling still use monotonic uptime or radio ticks. Routine sensor logs remain removed; startup/connection/fault diagnostics remain.

A separate low-priority CPU0 task formats cached snapshots without SPI or acquisition callbacks. Large CIR snapshots and I/Q strings are allocated off-stack and freed. Serial output delays one second after each block and is independent of app recording. Full CIR rows can exceed 16 KB and take over one second at 115200 baud. Hardware timing impact is unverified.

## Verification

Android build/lint/tests check the exact header, filtered/raw distance distinction, CIR/top-level name collisions, zero placeholders, original private payload retention and a full 1,016-pair CIR. A known 404-snapshot burst is exported and compared with its input. Host C tests compile the actual printer and formatters and verify all 44 labels, raw numeric fields, I/Q pairs, escaped line breaks and absence of recurring sensor log strings. Calendar tests check IST, date/year rollover, leap day and unsynchronized uptime fallback. The original raw JSON encoder still has its full-frame preservation test.

Evidence, example output and rollback are in `checkpoints/api-clock-before-20261105-172318/`. No phone installation, firmware flashing or physical output check is performed.


## Saved-file confirmation

After a successful export closes its output stream, the app shows a compact Material dialog with a success check, the actual document name and exported reading count. This covers both the system Save As picker and the configured folder. Cancelled or failed exports do not show success.

- **View** opens the saved content URI as `text/csv`, granting the viewer read access. The confirmation remains available when returning from the viewer. Missing viewers or access failures are shown inside the dialog without losing the saved file.
- **Done**, Back or dismiss returns to the dashboard and closes the confirmation. The saved URI/name/count are retained across activity recreation after the confirmation has appeared.

The folder and frozen recording bounds are unchanged. No storage-wide permission or FileProvider is required because both save paths use Storage Access Framework content URIs. The dialog uses the app's existing success colors/icons and theme. No physical phone interaction or installation is performed during computer-side validation.

Change evidence and rollback: `checkpoints/csv-saved-dialog-before-20261105-221240/`.


CSV timestamps are plain text such as `05-11-2026 22:26:09`, with literal hyphens and seconds. Some CSV/spreadsheet viewers infer a date type and apply their own slash-based display; CSV itself cannot specify a spreadsheet cell format. No Excel formulas or apostrophes are added to force viewer formatting.
