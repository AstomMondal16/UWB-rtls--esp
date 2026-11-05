# ESP32 + AI-Thinker BU01 UWB ranging and MPU6050 IMU

Native ESP-IDF anchor/tag firmware ported from the frame flow used by
[thotro/arduino-dw1000](https://github.com/thotro/arduino-dw1000). It uses the
same `MODE_LONGDATA_RANGE_ACCURACY` profile: channel 2, 110 kb/s, 64 MHz PRF,
2048-symbol preamble and preamble code 10.

The startup and runtime flow is intentional:

1. Connect to the configured Wi-Fi station and wait for `GOT_IP`.
2. Read the timestamp, six-byte station MAC and IPv4 address.
3. Start CPU0's buffered Wi-Fi telemetry/API service.
4. Start UWB, IMU and battery sensing on CPU1 at the configured ranging period.
5. Put each complete sample into a bounded queue; CPU0 consumes it and the PC
   dashboard reads it over the same network.

ESP32-S3 uses both cores. Wi-Fi/HTTP and queue consumption run on CPU0 while
UWB/IMU/ADC sensing runs on CPU1. ESP32-C3 has one core, so the identical tasks
run unpinned on that core.

The last two MAC bytes form the 16-bit UWB short address. The complete MAC is
also carried in bytes 80-85 of the existing 90-byte Arduino ranging frame.

## ESP-IDF components

- `components/dw1000`: BU01/DW1000 register and radio driver.
- `components/uwb_ranging`: Arduino-compatible discovery and asymmetric TWR.
- `components/mpu6050`: I2C accelerometer and gyroscope driver.
- `components/battery_monitor`: calibrated ADC1 battery voltage and percentage.
- `components/ble_provisioning`: BLE provisioning manager and GPIO17 button request task.
- `components/wifi`: saved Wi-Fi credentials, connection/recovery, and station-MAC identity.
- `components/telemetry`: sample queue, HTTP API and UDP MAC discovery.
- `main`: Kconfig values, callbacks and startup ordering.
- `dashboard/dashboard.py`: standalone Python desktop visualization.
- `dashboard/localization_dashboard.py`: multi-anchor device manager and 2D
  localization map.

## Default wiring

| BU01 / DW1000 | ESP32-S3 |
|---|---:|
| SCK | GPIO 5 |
| MISO | GPIO 6 |
| MOSI | GPIO 7 |
| CS | GPIO 15 |
| IRQ | GPIO 4 |
| RST | GPIO 16 |

All six DW1000 pins can be changed under
`UWB ranging` > `DW1000 GPIO pins` in menuconfig.

The `Enable MPU6050 IMU sensor` option under `UWB ranging` turns the sensor on
or off. It is enabled by default for the tag role and disabled by default for
the anchor role. Its I2C pins are configurable under
`UWB ranging` > `MPU6050 GPIO pins`; the defaults are GPIO 47/48 for ESP32-S3
and GPIO 0/1 for ESP32-C3.

| MPU6050 | ESP32-S3 default | ESP32-C3 default |
|---|---:|---:|
| SDA | GPIO 47 | GPIO 0 |
| SCL | GPIO 48 | GPIO 1 |
| VCC | 3.3 V | 3.3 V |
| GND | GND | GND |

The firmware detects MPU6050 address `0x68` or `0x69`. Keep the device still
in any orientation during the startup zero-rate gyro calibration. The
dashboard uses quaternion Mahony fusion and stationary bias tracking, avoiding
the Euler-angle singularity at 90 degrees.

Battery monitoring can be enabled or disabled under `UWB ranging` > `Enable
battery voltage monitoring`. Connect the divider to the configured ADC1 pin:

```text
VBAT --- 39k ---+--- configured ADC1 GPIO
                |
               100k
                |
               GND
```

The divider produces `VADC = VBAT × 100 / 139`. The dashboard percentage uses
the configurable empty/full voltages (defaults: 3.0 V and 4.2 V). GPIO39 does
not work on ESP32-S3 because GPIO39 is digital-only; it is not an ADC pin. The
default is GPIO1. Any free GPIO1 through GPIO10 can be selected on ESP32-S3;
ESP32-C3 uses GPIO0 through GPIO4. Battery capacity in mAh does not affect the
voltage conversion.

Power the BU01 from 3.3 V and connect grounds. RST is pulled low and then
released as a floating input; it is never driven high.

## Configure and build

```text
idf.py set-target esp32s3
idf.py menuconfig
```

Under `UWB ranging`, select the startup `Tag` or `Anchor` role. The firmware
uses saved Wi-Fi credentials first. If none exist, it uses the SSID and
password configured in menuconfig and saves them to NVS. BLE provisioning
starts only when those menuconfig fields are blank. The
Android app opens this from `Device Configuration` > `Set up device Wi-Fi with
Bluetooth`. Press the active-low button on GPIO17 (button to GND) to provision
or replace the saved network. Failed replacement credentials restore the prior network when one was saved.
Use the same PAN ID, antenna delay and reply delay on both devices. By default,
this menuconfig role is applied on every boot. The dashboard can change the
running role at any time; after a reboot the device returns to its menuconfig
role. Dashboard role changes are never stored in NVS.

```text
idf.py build
idf.py -p COMx flash monitor
```

For pair calibration, connect the dashboard to one device, enter an accurately
measured antenna-to-antenna distance, keep both modules stationary with clear
line of sight, and press `CALIBRATE UWB`. The dashboard takes 100 readings and
updates that device's DW1000 TX/RX antenna-delay registers. A five-reading
median rejects impulsive range jitter. Calibrate each production device; one
anchor/tag pair identifies only their aggregate delay, not two independent
per-device delays. The result is stored in NVS and restored after reboot. Very
short spacing can saturate the receiver, so use a longer measured baseline
when practical.

After initialization, every successful range prints distance, receive power,
radial speed, acceleration, angular velocity and battery state on one line.
`Tag ranging period` and `Dashboard network interval` set the startup sensing
and network request periods. Both can be changed at runtime from the dashboard.
The sensing interval must be changed on the tag because it initiates ranging.
`Sensor-to-Wi-Fi buffer length` controls queue capacity; a full queue drops its
oldest sample instead of delaying UWB.

## Optional CIR capture

In `idf.py menuconfig`, open `UWB ranging` and check
`Enable DW1000 channel impulse response (CIR) capture`, then build and flash.
The checkbox defaults to off. When off, there are no accumulator reads or CIR
sample buffers. When on, the default is 64 complex samples, starting up to 16
samples before the detected first path, with a 100 ms minimum interval per device.
The window is clamped to the 1016-sample accumulator boundaries. Set
`CIR samples per capture` to 1016 for the full accumulator, and set
`Minimum CIR capture interval` to 0 to capture every completed exchange.

Read the latest snapshot using HTTP GET `http://DEVICE_IP/api/cir`, for example:

```powershell
Invoke-RestMethod http://192.168.1.42/api/cir | ConvertTo-Json -Depth 4
```

The response has `enabled` and `valid` flags. Once a capture succeeds, it also
contains `sequence`, local monotonic `timestamp_ms`, `age_ms`, the 40-bit
`rx_timestamp`, `peer_address` (16-bit short address), `frame_type`,
`first_path_index`, `start_index`, `sample_count`, `preamble_count`,
`sample_period_ns`, `capture_us` (register/SPI capture duration),
`capture_interval_ms`, and `iq` as pairs of signed integers `[real, imaginary]`.
`first_path_index / 64.0` gives the absolute first-path sample position;
subtract `start_index` to locate it in the returned window. Sample magnitude
is `sqrt(real*real + imaginary*imaginary)`; normalize by preamble accumulation
count when comparing amplitudes between captures. Sample positions describe
relative path delays, not absolute tag-to-anchor distance.

Each device captures its own receiver: an anchor captures the tag's RANGE
frame (`frame_type=2`) after sending RANGE_REPORT; a tag captures the anchor's
RANGE_REPORT (`frame_type=3`). Enable the checkbox separately on whichever
devices need CIR. The endpoint returns the latest capture across all peers;
check `peer_address`, `sequence`, and `age_ms` before using it. Failed reads
leave the previous complete snapshot intact, so its age keeps increasing.

CIR is copied after the reply transmission and before the next RX. At the
existing 2 MHz SPI speed, the raw transfer alone costs about 1 ms for 64
samples or 16.3 ms for 1016 samples, plus register and transaction overhead.
This can delay the next listening window. The HTTP task copies a cached
snapshot and never accesses SPI; existing telemetry samples and ranging
frames do not carry these large arrays. The localization dashboard can plot
CIR using the viewer described below.
There is no automatic NLOS classification or distance correction.

In `dashboard/localization_dashboard.py`, select a row under **CONFIGURED
DEVICES** and click **VIEW CIR - SELECTED DEVICE**. A separate dashboard window
shows a live magnitude plot, the detected first-path marker, raw I/Q samples,
peer address, sequence and capture age. It polls only that device up to ten times
per second while open, reusing one HTTP connection. **SAVE CIR SNAPSHOT** exports the displayed capture as JSON
(including metadata) or CSV. These files are separate from localization session
exports. Reopen the viewer to choose another device or a changed IP address.
The viewer explains disabled CIR, missing firmware endpoints and unavailable
captures. Dashboard `--demo` mode provides clearly labeled simulated CIR.

On ESP32-S3, CPU1 owns ranging and CIR register reads; CPU0 handles the cached
snapshot, JSON encoding and HTTP. SPI stays with ranging because starting a
new reception would overwrite the accumulator. CIR JSON uses a single bounded
response (up to about 17 KiB for a full CIR) instead of many small HTTP chunks.
Single-core ESP32 variants retain separate scheduled tasks on their one core.

On the PC, the `cir-network-processing` thread handles requests, validation,
magnitude calculations and table formatting. Tk drawing stays on the main
thread. Only the latest result is queued; repeated sequences are not replotted.
The table displays 64 samples per page using **Previous/Next samples**, while
the plot and exported snapshot contain all samples. Discovery and normal
telemetry have their own worker threads. PC threads are scheduled by the OS;
they are not pinned to individual CPU cores.

The viewer shows **HTTP+decode** time, **SPI** capture time and the firmware's
**Capture limit**. HTTP timing includes server and transport time, not just
Wi-Fi latency. A large capture limit requires adjusting menuconfig and
reflashing; a large HTTP time points to transport/server load; a large SPI time
points to capture overhead. Capture age is reported at the server. The actual
fresh-capture rate is limited by successful ranging exchanges, so 100 ms is a
target minimum interval, not a guarantee of ten new captures each second.

Accumulator format and sequencing follow the
[Decawave driver API guide](https://forum.qorvo.com/uploads/short-url/9wTyf4vAaBkReECig7wIppI9Ek9.pdf)
and [DW1000 driver](https://github.com/Decawave/dwm1001-examples/blob/master/deca_driver/deca_device.c).

## PC dashboard

The dashboard uses only Python's standard library. Connect the PC to the same
network and start it with the device MAC printed by the firmware:

```text
python dashboard/dashboard.py AA:BB:CC:DD:EE:FF
```

It discovers the matching IP over UDP port 4210. An IP address can also be
used directly. The interface shows a fused 3D orientation, distance and speed
plots, raw IMU data, temperature, receive power, battery voltage/percentage,
device identity, queue drops and connection state. `PAUSE/RESUME` now stops or
starts UWB sensing on the device through its HTTP API. `ZERO` makes the current
orientation the visual reference; `GRID ON/OFF` controls its reference grid.
After telemetry arrives, `CONNECT` becomes `DISCONNECT`. Select `TAG` or
`ANCHOR`, enter the sensing/network intervals, and press `APPLY` to change the
running ESP role. This runtime selection is intentionally cleared by a reboot.
Press `SAVE` to export the collected session as CSV or JSON with a timestamped
default filename.

Preview and test it without hardware:

```text
python dashboard/dashboard.py --demo
python dashboard/dashboard.py --self-test
```

## Multi-anchor localization

The current radio network supports one active tag and up to eight anchors. The
tag ranges anchors sequentially so their replies do not collide. Anchor
discovery uses address-derived response slots and repeats every three seconds.
`/api/telemetry` remains compatible
with the original dashboard and also exposes a `ranges` array containing the
latest measurement for every peer.

Start the localization dashboard:

```text
python dashboard/localization_dashboard.py
```

Use `REFRESH SCAN` or wait for continuous UDP discovery, select a discovered
device, enter its measured X/Y/Z coordinates, and add it. Devices can also be
added manually by MAC or IPv4 address. Configure at least three non-collinear
anchors for 2D localization; four well-spaced corner anchors are recommended.
Add the tag to see its online state and IMU heading, although anchor telemetry
can localize an observed tag even before it is added. `APPLY ROLE` changes the
selected ESP-IDF device between anchor and tag, while yaw offset is stored in
the dashboard as its mounting-orientation correction. `CLEAR ALL DEVICES`
removes the saved map configuration after confirmation.

For coordinate-based antenna-delay calibration, place the stationary tag at
the X/Y/Z position entered for it, make sure each anchor's X/Y/Z position is
also accurately measured, and press `CALIBRATE UWB FROM XYZ`. The dashboard
computes each known 3D anchor-to-tag distance from those coordinates, takes
100 fresh range samples per online anchor, updates each anchor's DW1000 antenna
delay through `/api/control`, and the firmware saves it in NVS. This calibrates
the anchors relative to that tag, so keep the same tag as the reference or
first calibrate it against a known-good device.

Preview and test localization without hardware:

```text
python dashboard/localization_dashboard.py --demo
python dashboard/localization_dashboard.py --self-test
```

2D localization needs fresh distances to at least three anchors plus accurately
measured anchor coordinates. Calibrate each anchor/tag antenna delay before
relying on absolute position. MPU6050 has no
magnetometer, so absolute yaw cannot be corrected while moving; stationary
drift is suppressed, but long-term heading requires a magnetometer such as an
MPU9250/ICM-20948 or an external compass.

Calibration references: [Qorvo DW1000 resources](https://www.qorvo.com/products/p/DW1000),
[Decawave antenna-delay guidance](https://forum.qorvo.com/t/antenna-delay-calibration/826/14),
[thotro/arduino-dw1000](https://github.com/thotro/arduino-dw1000), and
[Decawave/uwb-core](https://github.com/Decawave/uwb-core).

## Timestamp clock source

Choose `UWB ranging ? Timestamp clock source` in `idf.py menuconfig`. Phone / computer clock is the default and needs no Internet on the ESP. Optional API mode tries five HTTPS requests five seconds apart, then SNTP fallback. See [clock setup and timestamp meanings](components/time_sync/README.md).

World Time API by [Time.Now](https://time.now/developer).
