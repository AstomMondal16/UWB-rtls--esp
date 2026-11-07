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
   coordinates for anchors so the app can map tag positions.
5. Select a device in **Device Selection** to view its IMU and CIR data.

A new board without Wi-Fi credentials opens Bluetooth setup automatically.
Use **Wi-Fi setup** in Devices; press the board's setup button (GPIO17) when
changing an existing network. After adding a board, the app sends its clock if
the board is waiting for time. Sensor initialization and ranging then start.
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
  Choose **Export CSV** to save a streaming export. The export reports its row
  count, capture time range, and missed telemetry sequence numbers; corrupted
  session rows fail the export instead of being omitted. UWB, IMU and observable
  battery updates are recorded independently for all configured devices.
  Exports include solved tag positions and freeze their end row at Stop.
- **Tag trails:** Recent valid positions form a separate bounded trail per tag;
  disconnects, invalid positions and long gaps start new segments.
- **Calibrate:** Follow the in-app calibration prompt with a measured distance
  between the devices being calibrated.

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
