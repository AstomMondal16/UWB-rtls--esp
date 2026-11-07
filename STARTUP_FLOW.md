# Startup and task coordination

The firmware follows the supplied power-on flow. Existing calibration, HTTPS
identity, CSV fields, map trails and Pause/Play behavior are retained.

```text
Power on
  Wi-Fi: saved credentials, then optional first-boot menuconfig credentials
    no credentials -> BLE setup -> receive/test/save credentials -> Wi-Fi
  Time sync: Time.Now/SNTP or phone/computer clock
  Initialize enabled sensors
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

In phone-clock mode, add/connect the board in the Android app or a desktop
dashboard to send time automatically. In API mode, five HTTPS attempts are
separated by five seconds; SNTP then gets up to ten seconds. If both fail, the
same phone/computer path is used. The local API and discovery are running while
waiting, but UWB, IMU, battery initialization and terminal sample rows do not
start until valid calendar time arrives. Waiting without a client is deliberate,
not a scheduler deadlock. The clock remains set across a Wi-Fi change while the
board stays powered; power loss requires synchronization again.

IMU bus/device initialization happens before starting its sampling task. The
owner task performs calibration and handles later retries and sleep/wake. A
missing IMU is logged and retried; it does not prevent a working UWB radio from
running. After radio initialization completes, a one-second settling delay
precedes the first ranging loop. It yields in 50 ms slices with BLE checkpoints,
so GPIO17 requests remain responsive. A full radio reinitialization repeats the
delay; ordinary Pause/Play wake cycles do not add this startup delay.

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
early/late clock signals, and startup refusing to launch sensors before time.
Desktop loopback TLS tests use mock servers and test-only certificates.
UI construction checks use simulated demo data. None of these are a physical
board, current-draw measurement, phone deployment or visual phone review.

After flashing, test these on hardware:

1. No Wi-Fi credentials: BLE setup appears; sensors wait until Wi-Fi and time.
2. Saved Wi-Fi plus working API: time synchronization precedes sensor logs.
3. API unavailable or phone mode: connecting the app/dashboard sends time and
   releases startup. Pause/Play still works before and after this transition.
4. Press GPIO17 repeatedly, finish/change Wi-Fi, disconnect during setup, and
   exercise the BLE timeout. Acquisition must resume only after safe release.
5. Run multiple tags and full CIR at the intended rates; check reported drops,
   free heap and task stack high-water marks over a long session.
