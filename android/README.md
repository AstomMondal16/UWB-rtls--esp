# UWB Localization Android App

Android companion app for the ESP32 UWB localization project. It discovers UWB
devices on the local network and displays their status, 2D positions, IMU
telemetry, and channel impulse response (CIR) data.

## Features

- Discover ESP32 devices over UDP or add a device manually by MAC address and IP.
- Configure device roles and X/Y/Z map coordinates.
- View anchor and tag positions on a zoomable, pannable 2D map.
- Select a device to view its accelerometer, gyroscope, and CIR graphs.
- Start or pause data flow on configured devices.
- Calibrate UWB antenna delay and record/export telemetry to CSV.
- Provision a device's Wi-Fi over its encrypted BLE provisioning service,
  with nearby network scanning and manual SSID entry.
- Store Wi-Fi credentials and TLS certificate pins encrypted with Android Keystore.
- Save configured devices on the phone between app launches.

## Requirements

- Android Studio with Android SDK Platform 37 installed.
- JDK 17 or newer (Android Studio's bundled JDK is recommended).
- An Android phone or emulator running Android 7.0 (API 24) or newer.
- ESP32 devices running this repository's compatible firmware.

The project uses Gradle Wrapper 9.6. The first build may need internet access to
download Gradle and Android dependencies.

## Open and run in Android Studio

1. Open Android Studio and choose **Open**.
2. Select this `android` folder (the one containing `settings.gradle.kts`).
3. Wait for Gradle sync to finish. Install Android SDK Platform 37 if Android
   Studio prompts you.
4. Connect an Android phone with USB debugging enabled, or start an emulator.
5. Select the `app` run configuration and press **Run**.
6. On Android 17 / API 37, allow **Local network** access when prompted so the
   app can discover and connect to ESP32 devices.

## Build from a terminal

From the repository root, run:

```powershell
cd android
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Connect to the UWB devices

1. Connect the phone and ESP32 devices to the same Wi-Fi network. The network
   must allow devices to communicate with each other; guest Wi-Fi and access
   point isolation can block this.
2. Power on the ESP32 devices and allow the app's local-network permission if
   Android requests it.
3. Open **Devices** and use **Scan network**. Devices advertise themselves over
   UDP port `4210`.
4. Configure discovered devices, or use **Add device** and enter its MAC and IP
   address. Assign each device an **Anchor** or **Tag** role. Set measured
   coordinates for anchors so the app can map tag positions, or use
   **Auto-assign coordinates** below X/Y in Add Device. The first Anchor becomes
   `(0,0)`, the second defines positive X, and the third defines positive Y.
   Place the third so its intended X and Y are both positive. Further Anchors
   use at least three non-collinear references. Keep the new device still while
   ranging, review the filled fields, then confirm **Add Device** to save.
   Blank Z means `0 m`; leave Z blank for anchors on the same plane. Enter known
   heights when they differ. Manual coordinates remain available after errors
   or cancellation, and existing anchors are never repositioned automatically.
   Tags also offer **Auto-assign coordinates** in Add Device and Edit Device.
   A Tag needs at least three saved anchors that are not in a straight line;
   it is located within their existing coordinate frame and remains a Tag.
   Keep it still during ranging, review X/Y, and confirm Add Device or Save.
   Blank Z remains zero, and manual entry is always available. This saves a
   setup position; normal live Tag tracking continues afterward.
5. Select a device in **Device Selection** to view its IMU and CIR data.

A new board without Wi-Fi credentials opens Bluetooth setup automatically.
Use **Wi-Fi setup** in Devices; press the board's setup button (GPIO17) when
changing an existing network. After adding a board, the app sends its clock if
the board is waiting for time. Normal sensing then starts; temporary coordinate
ranging is available before time sync. Confirmed roles/coordinates are saved on
the board and restored at restart. Offline manual entries synchronize when the
board reconnects. Automatic setup requires the updated firmware on every
participating board; it reports an error rather than switching older firmware
without a cleanup lease.
See [startup flow](../STARTUP_FLOW.md).

In **Settings > Device configuration**, **Samples per second** sets the shared
UWB/IMU target with one whole-number entry (1–20). Decimal and invalid values
cannot be applied. The field explains actual UWB limits and tag-controlled
ranging. See [sampling rate behavior](../SAMPLING_RATE.md).

The app requires HTTPS for every device. Each board generates its own identity
in C on first boot. Add it on trusted private Wi-Fi: the app remembers its public
key automatically in encrypted storage and blocks later key changes. No factory
tools, certificates, QR labels or PINs are needed. Initial trust is not authenticated:
an attacker impersonating a board during first pairing could be trusted. Certificate
validity, self-signature and exact MAC-based DNS identity are checked before pairing
or sending API payloads. DHCP changes need no re-pairing. Use Edit > Pair again only
after an expected identity reset. See [customer setup](../CUSTOMER_SETUP.md) and
[TLS details](../components/telemetry/TLS_SETUP.md). Older HTTP-only firmware needs
the updated firmware/partition table before this HTTPS-only app connects.
Wi-Fi provisioning no longer asks for the 64-character API key. Its BLE
Security 1 session encrypts traffic without a proof-of-possession check, so a
nearby device can attempt provisioning while the device's service is open.
The dashboard API has no client API key; use it only on a trusted local network.

## Using the app

- **Map:** Add at least three anchors with known coordinates. Pinch to zoom,
  drag to pan, and tap the home icon to fit the visible anchors and tags.
- **IMU:** Select a device with valid IMU telemetry. IMU support must be enabled
  in that device's firmware and the sensor must be connected correctly.
- **CIR:** CIR capture must be enabled in the firmware of the device being
  viewed. A successful UWB exchange with another device is needed to produce
  new samples. The graph follows the strongest peak and centers its view on it.
- **Start / Pause:** Uses the existing device control endpoint. With the updated
  firmware, Pause puts UWB/IMU chips to sleep and Start wakes them; Wi-Fi/HTTPS
  remain active. The command applies to configured reachable devices. See
  [sensor power behavior](../SENSOR_POWER.md).
- **CSV:** Start recording from **Actions**. Recording appends to an app-private
  session file with a 512 MiB limit and retains prior records across stops.
  In **Settings > Files > CSV fields**, device timestamp, MAC, IP and role are
  always included. Choose three optional groups: **IMU**, **Battery** (percentage
  and voltage), and **UWB** (ranging, raw CIR and movement speed). Changes save
  automatically. All groups default to on for the original 44-column export;
  turning them all off saves device details only. Stop CSV saves the current
  session. **Settings > Files > Export all stored data** exports every retained
  session, including recordings whose save picker was cancelled, and stops any
  active recording. Both use the field settings directly, without a selection
  popup. The dashboard footer only shows device and stored-record counts.
  Cancelling the file destination keeps captured data available. Exports report
  count, capture time range, and missed telemetry sequence numbers; corrupted
  session rows fail the export instead of being omitted. UWB, IMU and observable
  battery updates are recorded independently for all configured devices.
  An interrupted final append is repaired on the next app start: complete JSON
  is retained, while incomplete trailing bytes are preserved in a private
  `.interrupted` file before truncation. Failed writes roll back that append.
  **Settings > Files > Clear recorded history** frees recording capacity after
  confirmation; export anything you need first. It includes unexported records
  and leaves saved devices and paths intact. Recording is disabled during
  export or clearing; no old measurements are deleted automatically.
  Selected columns keep the existing schema order, precision, units, timestamp
  formatting and zero-for-missing values. All rows in the frozen recording
  range are exported. Selecting fields changes only the downloaded CSV; raw
  captures remain intact for later exports. **iq** includes the full raw I/Q
  array. Both the row range and chosen fields are frozen before the file picker
  and retained through activity recreation. The end row stays fixed at Stop.
- **Tag trails:** Recent valid positions form a separate bounded trail per tag;
  disconnects, invalid positions and long gaps start new segments.
  Live positions reject nearly collinear geometry and any range whose fitted
  3D distance differs by more than the greater of 0.20 m or 5% of that range.
  Saved heights are used; existing manual coordinate frames remain supported.
  In **Settings > Appearance > Localization map > Trail disappears after**,
  choose 5 seconds, 10 seconds, 30 seconds, 1 minute or 2 minutes (default).
  Each point ages out on both live maps, including while paused or disconnected.
  The choice persists across restarts. The bounded live buffer holds up to 2,400
  points per tag, enough for two minutes at 20 position updates/s. At higher
  combined update rates, the live preview is thinned to preserve the time span
  and disconnect gaps. Automatic one-month recording still receives every new
  valid position; the saved-history viewer is independent of this setting.
- **Calibrate:** Follow the in-app calibration prompt with a measured distance
  between the devices being calibrated.

Main navigation and dashboard tabs use short directional slide/fade transitions. In Settings, swipe left/right between **Appearance**, **Files**,
**About** and **Device**, or tap a tab to slide to it. The highlighted tab follows
the pager; each page scrolls vertically. Settings remembers the last settled tab
when you leave and return. Raw IMU readings and device diagnostics expand/collapse
smoothly. **Settings > Appearance > Behaviour > Reduce animations** makes tab taps
instant and removes the animated snap after a swipe; direct finger dragging stays
available. Effects also follow Android's system animation duration scale.
Telemetry updates do not restart page transitions.

Startup opens the repository and counts saved CSV rows on an I/O worker. The
dashboard draws underneath the opaque splash before its 160 ms fade, instead of
being first composed during the fade. The cold-start logo wave lasts 650 ms;
returning from a restored activity skips that minimum display time. Loading
failures show Retry. Leaving during startup cancels loading and closes any
repository it created; pending CSV destinations survive activity recreation.

Dashboard sections clip their contents and keep live values inside their bounds.
While any saved device is online, the map reserves its 320 dp viewport, legend
and position readout, even while waiting. Motion has a 600 dp scrollable viewport; Battery has a 144 dp
scrollable viewport so optional readings and large fonts stay reachable without
moving adjacent sections. These bounds apply in tabs or scrolling mode and with
or without cards. Numeric/status labels do not wrap as readings change.

While connected, the CIR panel reserves a 260 dp plot viewport in live, waiting
and error states.
Its header and capture/status lines keep stable bounds; full status messages
open from the info icon. Magnitude and waterfall rendering are clipped to their
own plot rectangles. Chart contents and orientation readouts are also clipped
to their owning sections.

When no saved device is online (including an empty device list), only the
localization map section is shown. Motion, CIR, Battery and their tab bar are
hidden, including when the dashboard preference is Scrolling. The bounded map,
legend, history, fit and fullscreen actions remain available with configured
anchors/cached positions; without anchors it shows setup guidance. Cached data
is retained. When any saved device reconnects, the user's previous tab/scrolling
view and selected tab return automatically. Discovery results or stale states
for removed devices do not count as online. A paused device that remains online
keeps the normal layout.

For ESP-IDF setup, firmware configuration, IMU wiring, CIR enablement, and API
details, see the repository's [main README](../README.md).

## Troubleshooting

### Scan finds no devices

- Confirm the phone and devices are on the same Wi-Fi network and the devices
  have booted and received an IP address.
- When using the phone's hotspot, keep it enabled and make sure the device
  joined that hotspot; the provisioning form takes the hotspot name/password
  manually.
- Allow local-network access if Android asks. On Android 17 / API 37, this
  permission is required for local UDP discovery and HTTP connections.
- Turn off VPN temporarily and avoid guest Wi-Fi or networks with client
  isolation enabled.
- Use **Add device** with the ESP32's current IP address to configure it
  manually. Check that UDP discovery port `4210` is not blocked if scanning is
  expected to work.

### A configured device shows offline

Verify its IP address and Wi-Fi connection. The app updates a configured
device's address from discovery when its MAC address matches; otherwise edit
the saved device and update its IP.

### The map is empty

Configure at least three devices as anchors and enter their measured map
coordinates. A tag position appears after the anchors receive enough valid UWB
range measurements.

### IMU or CIR data is missing

Check the firmware's IMU/CIR configuration, sensor wiring, and device boot
output. CIR is captured by each receiver, so enable it on every device whose
local CIR you want to inspect; the selected device also needs a successful UWB
exchange with a peer.

Wi-Fi credentials and TLS pins are encrypted at rest with Android
Keystore AES/GCM. Android backup rules exclude their preference file and the
local telemetry session file, so credentials must be entered again on a new
phone.

## Automatic one-month path history

Open **Dashboard > Map > History** to view travelled paths. Recording, saving
and expiry are automatic; there are no Record, Save or Delete controls. The
current path can be viewed while data keeps arriving and updates every five
seconds. Choose all tags or one tag, then pinch/drag to zoom/pan or **Fit path**.
Outlined dots mark starts; solid markers mark the last observed positions.

Every new valid solved 2D UWB position received by this app is saved, across all
located tags, independently of selected device, visible tab and CSV recording.
There is no one-second sampling reduction. Three anchors with measured
coordinates are needed to solve positions. Duplicate range evidence is ignored;
invalid positions, disconnects, Pause and missing measurement gaps break lines.
This shows measured positions, not an interpolated or IMU-only route. Times are
phone receipt times, displayed as `dd-MM-yyyy HH:mm:ss` in the phone's time zone.
Keep the app open to receive telemetry; the cleanup job does not collect data.

Files rotate automatically after 100,000 positions, 64 tags or a UTC day change;
the position that starts the next file is retained. Each file keeps the anchor
configuration used for its coordinates. Setup changes seal the current file;
the next valid position starts another automatically. Closing normally seals
the file; interrupted complete rows are recovered on the next app startup.
The old 50-session/64-MiB cap is removed. Full-rate monthly history can use many
GiB. A 256-MiB free-space reserve protects the phone: if storage is low, saving
stops with a visible error and retries automatically after 30 seconds. Positions
received during an I/O failure or retry window cannot be recovered. Newer
history is not silently deleted to make room.

A file expires **one calendar month after its last position**, calculated in
UTC (e.g. January 31 expires February 28, or February 29 in a leap year). Cleanup
runs on startup, hourly while the app is running and in a persisted daily
Android JobScheduler job, including after a reboot without opening History.
Android may defer background execution in Doze or after force-stop; cleanup
catches up when the OS runs it or the app reopens. Therefore expiry is not an
exact alarm. Unknown files and active writers are not removed. Interrupted or
unreadable files expire based on their last filesystem modification time.

Files remain app-private, excluded from cloud backup and device transfer;
clearing app data or uninstalling removes them. Long previews show roughly
5,000 positions plus per-tag endpoints and preserve gaps. Preview sampling uses
each Tag's own point count so alternating Tags retain their paths. The saved file keeps
all accepted positions. No firmware protocol or CSV schema changes are needed.

JobScheduler lifecycle and persistence follow the Android references:
[JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder)
and [JobService](https://developer.android.com/reference/android/app/job/JobService).
Disk cleanup runs on a worker thread; the manifest grants RECEIVE_BOOT_COMPLETED
for the persisted job and protects the service with BIND_JOB_SERVICE.

## Project files

- `app/src/main/java/com/example/myapplication/MainActivity.kt` — Compose UI,
  map, IMU/CIR graphs, device setup, and CSV save action.
- `app/src/main/java/com/example/myapplication/DeviceRepository.kt` — UDP
  discovery, telemetry polling, device configuration storage, calibration, and
  control/API requests.
- `app/src/main/java/com/example/myapplication/LocalizationModels.kt` — device
  and telemetry data models.
- `app/src/main/AndroidManifest.xml` — app identity, network permissions, and
  local HTTP setting.

See [HTTPS, CSV and trail details](TELEMETRY_CHANGES.md) for the endpoint inventory,
CSV schema/units, firmware delivery limits and verification results.
