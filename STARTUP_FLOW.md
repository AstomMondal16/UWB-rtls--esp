# Startup and task coordination

The firmware follows the supplied power-on flow. Existing calibration, HTTPS
identity, CSV fields, map trails and Pause/Play behavior are retained.

```text
Power on
  Wi-Fi: saved credentials, then optional first-boot menuconfig credentials
    no credentials -> BLE setup -> receive/test/save credentials -> Wi-Fi
  Start HTTPS API and UDP discovery
  First use: confirm role and coordinates in Android Add Device
    manual entry or temporary tag/anchor ranging; blank Z -> 0 metres
    saved valid configuration -> skip coordinate setup
  Time sync: Time.Now/SNTP or phone/computer clock
  Initialize remaining enabled sensors
  Allow 1 second for sensor settling
  Start anchor/tag ranging and live telemetry

GPIO17 setup button -> safe sensor pause -> BLE setup -> Wi-Fi recovery -> resume
```

The setup button is active-low, connected between GPIO17 and ground. It is
debounced and handled by a task, not by running BLE or I/O in an interrupt.
Existing credential rollback and the BLE inactivity timeout are retained.

After Wi-Fi connects, BLE stays available for the phone's connected-status
request. The provisioning SDK then closes the service after its response
(one-second cleanup delay, or a 30-second fallback if no status request arrives).
The app shows success only after that confirmation. A Bluetooth disconnect or
two-minute confirmation timeout clears the spinner and suggests checking Scan
network; it does not claim success merely because the board may be online.

On first use, add the board in Android before supplying time. Android restores
existing saved device maps to firmware automatically on reconnect. Confirmed
roles and coordinates live in the `device_setup/config` NVS blob; an Anchor
without valid coordinates remains in setup, while a confirmed Tag skips them.
Offline manual app entries are synchronized when the device becomes reachable.

In phone-clock mode, the Android app or a desktop dashboard sends time
automatically after configuration. In API mode, five HTTPS attempts are
separated by five seconds; SNTP then gets up to ten seconds. If both fail, the
same phone/computer path is used. The local API and discovery are running while
waiting. Normal sensing and terminal rows wait for calendar time, but automatic
coordinate assignment can initialize sensors and run UWB before time sync.
Ranging uses DW1000 timestamps and monotonic uptime, not calendar time. Waiting
without a client is deliberate,
not a scheduler deadlock. The clock remains set across a Wi-Fi change while the
board stays powered; power loss requires synchronization again.

## Coordinate setup sessions

Android's **Auto-assign coordinates** previews X/Y without storing them. The
first Anchor uses `(0,0)` with no ranging. The second defines positive X from
the origin range; the third uses both ranges and the positive-Y solution.
Later Anchors use three or more non-collinear references and all additional
valid ranges collected. Existing coordinates are never moved or re-framed.
Tags support the same preview in Add/Edit Device, always requiring at least
three non-collinear saved Anchors. A Tag never establishes an origin or axis.
The candidate remains a Tag; its reference devices use temporary Anchor leases.
Confirmed Tag coordinates are synchronized and saved in NVS, but coordinates
remain optional for ordinary Tag startup. Live tracking is unchanged.
Blank Z defaults to zero; explicit heights compensate the horizontal ranges.
Automatic positions must lie in 0–1,000 metres on each axis, and each 3D range
residual must be within `max(0.20 m, 5% of that range)`. Manual entry retains
its existing finite-number behavior.

`POST /api/setup` accepts flat JSON (maximum 512 bytes):

- `{"action":"commit","role":"anchor","x":0,"y":0,"z":0}` saves the
  confirmed configuration atomically in NVS. A Tag only requires its role.
- `begin` requires a 32-character hex `id`, `role`, and `peers`: a comma-separated
  list of up to eight MACs. It temporarily overrides role and sensing period.
- `renew` and `end` require that `id`. A 15-second monotonic lease expires even
  without radio/HTTP traffic; Wi-Fi loss ends it as well. Android renews every
  five seconds, limits an attempt to 60 seconds, and ends every attempted lease
  on success, failure, cancellation or dismissal. Larger reference sets use batches.

References accept the candidate's discovery frames; the candidate accepts only
the selected references. Radio packet layouts remain unchanged. Setup generation
tags reject queued samples from earlier roles/sessions. Telemetry exposes
`setup_state`, saved configuration, `setup_id`, `setup_generation`, and
`setup_ready`. Clients wait for readiness and five fresh distinct ranges per
reference. Setup data is excluded from normal app trails and recordings.
Competing setup/control requests receive HTTP 409. Invalid bodies receive 400;
failed NVS writes receive 500 and retain the previous in-memory configuration.
Requested controls are stored separately from temporary effective settings.
Partial controls immediately after setup retain the requested role, pause and
rate. A configuration commit updates the requested role before acknowledging;
the supervisor applies the latest requested interval when setup ends. Cached
setup ranges are invalidated before they can appear as normal telemetry.

`clock_source` is `pending` before configuration. Phone time updates are rejected
until the coordinate step is complete/skipped and no setup lease is active.
The supervisor remains live during network time requests and clock waiting, so
leases still expire. Missing time cannot put initialized UWB/IMU chips to sleep
while coordinates are pending or a setup session is active. Setup ends by
restoring the previous monitoring request, role and sensing interval; regular
Pause/Play and Wi-Fi sleep behavior then resumes.

Anchors retain independent identity, activity and sequence state for up to eight
Tags; an additional Tag replaces the least recently active entry. This avoids
requiring rediscovery whenever another Tag ranges. Exchanges remain sequential,
so simultaneous radio collisions still require retry. Fatal driver errors and
eight repeated local RX/TX failures leave the ranging loop, clean up the driver
and enter the existing main-task reinitialization/backoff. Packet loss, ordinary
receive timeouts and oversized unrelated frames do not force reinitialization.

IMU bus/device initialization happens before starting its sampling task. The
owner task performs calibration and handles later retries and sleep/wake. A
missing IMU is logged and retried; it does not prevent a working UWB radio from
running. After radio initialization completes, a one-second settling delay
precedes the first ranging loop. It yields in 50 ms slices with BLE checkpoints,
so GPIO17 requests remain responsive. A full radio reinitialization repeats the
delay; ordinary Pause/Play wake cycles do not add this startup delay.

## ROM download mode versus firmware startup

The reported Tag log contains `rst:0x15 (USB_UART_CHIP_RESET)`,
`boot:0x0 (DOWNLOAD(USB/UART0))`, and `waiting for download`. This is the ROM
loader, before the application selects Anchor/Tag roles or initializes Wi-Fi,
UWB or IMU. An application change cannot run while the chip remains there.
The Anchor can run the same source while a different board/USB connection
enters a different boot mode. The exact reset/strapping cause requires hardware
verification; see [Espressif's ESP32-S3 boot-mode guide](https://docs.espressif.com/projects/esptool/en/latest/esp32s3/advanced-topics/boot-mode-selection.html).

The workspace sets `idf.monitorNoReset: true`, supported by the installed
ESP-IDF extension. This suppresses the monitor's automatic startup reset;
it does not change flashing, ports, firmware, or reset strapping. For a manual
Tag monitor using the port shown in the supplied log:

```powershell
& 'C:\Espressif\tools\python\v6.0.1\venv\Scripts\python.exe' 'C:\esp\v6.0.1\esp-idf\tools\idf_monitor.py' --no-reset -p COM7 -b 115200 --target esp32s3 build\pcb_v1.elf
```

This command is documented, not executed by the checks. Close any other monitor
that owns the port. If already in download mode, release BOOT and reset once;
no-reset monitoring does not itself leave the ROM loader. If download mode also
occurs on power-up without a monitor, verify the board's BOOT/GPIO0 and EN/reset
wiring/levels against its schematic. The project's configured UWB/IMU pins do
not use GPIO0 or GPIO46. See [IDF Monitor's no-reset option](https://docs.espressif.com/projects/esp-idf/en/release-v6.1/esp32s3/api-guides/tools/idf-monitor.html).

## Scheduling rules

| Mechanism | Owner and rule |
|---|---|
| Clock binary semaphore | Telemetry creates it before the HTTP handlers start. A successful time update sets the synchronized flag, then gives the semaphore. Startup takes it with a one-second timeout and rechecks readiness. Early updates are retained. |
| Coordinator mutex | Protects task registration, BLE state and pause/ACK transitions. Every take has a matching give. No network, SPI, I2C, UART logging or acknowledgement wait runs while it is held. |
| Pause requests and ACK bits | A task sets its ACK only at a safe acquisition boundary. It keeps the ACK while parked, clearing it under the mutex before returning to acquisition. A rapid second pause can therefore reuse a still-valid ACK. |
| Task notifications | Wake owners when control/BLE state changes. Notifications are sent after releasing the coordinator mutex. Parked tasks also recheck every 250 ms, so a lost/coalesced notification cannot strand them. |
| BLE acknowledgement wait | Runs outside the mutex, with a three-second deadline. Timeout clears all sensor pause requests, including a sensor registered during the wait, and releases tasks without starting BLE. |
| Range queue | A fixed allocation; producers never wait for networking. A full queue discards an old entry and reports drops using the existing telemetry counter. |

Short state copies still use the existing critical sections. SPI and I2C each
have one acquisition owner; adding another bus user must go through that owner.
The lock order for dashboard controls remains sensor-control state, then radio
runtime state. No semaphore wait or hardware operation is added inside those
critical sections. Indefinite waits remain only where a task is intentionally
idle or acquiring a short, locally owned mutex; mutex holders do not perform
blocking I/O or delete themselves.

## Buffer and memory budget

Sizes below were read from this ESP32-S3 build's debug types, not guessed from
the desktop compiler:

| Buffer | Current size / bound |
|---|---:|
| Range queue item | 584 bytes |
| Default 32-slot range queue | 18,688 payload bytes plus FreeRTOS metadata |
| Configurable queue limit | 256 slots; allocation failure stops API startup safely |
| Shared telemetry state | 5,352 bytes |
| Full 1,016-sample CIR snapshot | 4,104 bytes |
| UWB ranging context | 1,016 bytes |

The queue size and payload allocation are printed at startup. Full CIR capture
buffers remain bounded, and request/console copies remain private, checked
allocations: a socket send never holds the radio's snapshot lock. Terminal
formatting now reserves only the bytes needed for fields and escaped newlines,
rather than twice every field's length. The full-range I/Q test checks that its
line buffer stays below 20 KB and that allocation failure produces no partial
row. No new unbounded queue or per-sample allocation was added to sensor tasks.

Desktop event queues remain bounded; their producers tolerate a queue refilled
by another worker during retry. The desktop 100,000-record rolling histories
are unchanged and still evict old records at their limit. Android continues to
use its existing bounded response readers, fixed poll worker pool, per-device
in-flight guard and disk-backed recorder. This change does not turn desktop
rolling history into an unlimited archival recorder.

These rules address tested failure paths; they cannot guarantee every future
change or hardware failure is free from deadlocks, watchdog resets or memory
exhaustion. Measure stack high-water marks and free heap under the actual number
of boards and full-CIR traffic before setting production rates/queue sizes.

## Interface direction and checks

Sense remains a room-localization instrument: calm, precise and readable.
The map, motion axes and CIR plots carry color; controls use one blue accent,
quiet blue-grey surfaces and lighter borders. Existing logo and navigation
options are kept. Both desktop dashboards share their existing color constants
instead of maintaining divergent copies. Setup wording now explains automatic
first setup and the physical setup button; the Android BLE dialog has one
current status message. Tab transitions and the logo loading animation remain.

This polish follows [Unslop](https://heydamir.design/unslop) by
[Damir Sokolovsky](https://x.com/heydamir), CC BY 4.0. The audit found oversized
desktop headings, heavy borders, uppercase action labels, duplicated setup
status and inconsistent surfaces. Spacing and alignment were adjusted first,
then color, labels, type and card shapes; sensor charts remain the main visual.
No generated artwork or new UI dependency was needed.

Host tests exercise real C functions with mocked buses/RTOS events, including
rapid resume/re-pause, lost notifications, timeout with late registration,
early/late clock signals, and normal startup waiting for time. Coordinate setup
tests compile the real setup/ranging C with fake NVS, RTOS and radio I/O; run
`python tests/firmware/run_tests.py`. Android JVM tests cover the first four
additions, heights, geometry/fit rejection, batching and cleanup paths.
Desktop loopback TLS tests use mock servers and test-only certificates.
UI construction checks use simulated demo data. None of these are a physical
board, current-draw measurement, phone deployment or visual phone review.

After flashing, test these on hardware:

1. No Wi-Fi credentials: BLE setup appears; first-use devices wait for Add Device.
2. Add four Anchors, including before time sync; verify measured coordinates,
   positive-Y selection, cancellation, Wi-Fi loss and temporary role restoration.
3. Restart saved Anchors/Tags; verify configuration is retained, coordinate setup
   is skipped, then time sync and normal acquisition resume.
4. API unavailable or phone mode: connecting the app/dashboard sends time and
   releases startup. Pause/Play still works before and after this transition.
5. Press GPIO17 repeatedly, finish/change Wi-Fi, disconnect during setup, and
   exercise the BLE timeout. Acquisition must resume only after safe release.
6. Run multiple tags and full CIR at the intended rates; check reported drops,
   free heap and task stack high-water marks over a long session.
