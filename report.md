# Android App Security Warning: UWB Localization

**Status:** Investigation and remediation plan. No transport-security fix has been implemented yet.
**Last updated:** 2026-09-30

## Summary

On a Huawei phone, Phone Manager displayed: “Risks detected with ‘UWB Localization’ … This app may lead to serious risks such as privacy breaches and financial loss.” This is a system security warning, not a notification created by the app UI.

The app and UWB devices currently communicate over the local network using UDP discovery and plain HTTP. The Android app explicitly permits cleartext traffic. The firmware's HTTP control endpoint also has no client authentication. These facts create real network risks and are plausible reasons for a broad security scanner warning, but the phone's exact detection reason is not available from the screenshot. Do not claim that this specific configuration is definitely the scanner's sole trigger.

Huawei's security documentation describes scanning user-installed apps and displaying warnings when a risk is detected. The exact checks and wording can vary by Phone Manager, EMUI/HarmonyOS version, and scan result: [Huawei Mobile Services security white paper](https://consumer.huawei.com/dam/content/dam/huawei-cbg-site/common/mkt/privacy/overview-new/downen/huawei-mobile-services-%28hms%29-security-technical-white-paper-v2.0.pdf).

## What the current code does

1. **Find devices:** Android broadcasts `UWB_DISCOVER` to `255.255.255.255:4210` over UDP and accepts JSON replies with device identity and IP address.
2. **Read telemetry:** Android requests `http://<device-ip>/api/telemetry` and `http://<device-ip>/api/cir`.
3. **Control devices:** Android sends JSON to `http://<device-ip>/api/control` to change streaming and device configuration.
4. **Serve requests:** Firmware starts the regular ESP-IDF `esp_http_server` and registers those three routes. It does not currently start an HTTPS server.
5. **Share the protocol:** The Python dashboard and CIR viewer also use the firmware's HTTP endpoints. A firmware protocol change must account for those clients as well as Android.

The control handler checks the JSON content type and validates control values. Its source comment explicitly says this is not authentication. A device on the same network can still make requests to the open endpoints.

## Verified facts versus assumptions

### Verified in this repository

- `android/app/src/main/AndroidManifest.xml` declares `INTERNET`, `ACCESS_NETWORK_STATE`, and `ACCESS_LOCAL_NETWORK`.
- The same manifest sets `android:usesCleartextTraffic="true"` for the whole app.
- `DeviceRepository.kt` uses plain HTTP for telemetry, CIR, and control requests, plus UDP broadcast discovery.
- `components/telemetry/telemetry.c` serves the endpoints with `esp_http_server`; `/api/control` does not authenticate the caller.
- `dashboard/localization_dashboard.py`, `dashboard/dashboard.py`, and `dashboard/cir_viewer.py` also use the HTTP API.
- The manifest does not request contacts, SMS, or Android location permissions. However, the app handles UWB position, IMU, CIR, and device identity data; location-like telemetry can itself be sensitive.

### Not yet known

- Which exact rule Phone Manager matched.
- Whether the phone flags the app because it was installed as a development/sideloaded APK, because of network behavior, or for another reason.
- Whether a secure transport-only build will clear the warning. A code change cannot guarantee Huawei will remove a scan result.

To identify the phone's specific reason, open **Fix now** and inspect the app entry under Phone Manager's scan/risk details. Record the exact reason and phone OS/security-app version. Do not disable phone security globally to hide the message.

## Why the current setup has security risk

- **Confidentiality:** Another device on the same Wi-Fi/LAN may be able to read telemetry, CIR, device IDs, or positions because HTTP is unencrypted.
- **Integrity:** A peer on the LAN may send control requests to change streaming, role, timing, or antenna-delay settings because the firmware endpoint does not authenticate callers.
- **Discovery trust:** UDP broadcast replies are not authenticated. Discovery should locate a device, but must not be treated as proof that the responding device is genuine.
- **Distribution trust:** A locally built or sideloaded APK may be treated differently from an app installed through a trusted store. This is a possible contributing factor, not a confirmed diagnosis.

## Recommended fix

The real fix is a coordinated protocol change across the ESP firmware, Android app, and Python clients. Simply setting `usesCleartextTraffic` to `false` will break the current HTTP calls; it does not convert them to HTTPS.

### Phase 1 — Confirm the alert

1. Read the exact Phone Manager finding under **Fix now**.
2. Record APK source (Android Studio/debug or signed release), app version, phone model, and EMUI/HarmonyOS/Phone Manager version.
3. Keep the warning text separate from facts verified in this repository. A generic warning is not proof that the app stole data, but it should not be dismissed without reviewing its details.

### Phase 2 — Encrypt and authenticate device traffic

1. Add TLS/HTTPS support to the ESP device server.
2. Give every device an identity that the clients can validate. Prefer per-device certificates or a controlled local certificate authority with certificate pinning. Do not use a trust-all TLS verifier or disable certificate validation.
3. Add a pairing/provisioning method that grants authorized clients access. Require authentication on sensitive routes, especially `/api/control`; protect telemetry and CIR as well if they should be private. Avoid one shared secret embedded in every public APK as the only protection.
4. Update Android and all Python clients to use HTTPS and validate the device identity. Preserve UDP discovery only as a locator; perform authenticated TLS before accepting device data or sending commands.
5. Remove the broad `usesCleartextTraffic="true"` allowance and default to denying cleartext after every required client/device has migrated. Do not do this before the HTTPS path works end to end.

### Phase 3 — Release and recheck

1. Build a release APK signed with the project's intended release key; protect and back up that key.
2. Install the release build and retest Phone Manager. If it still flags the app, use the recorded scan details and Huawei's review/support process; signing or TLS alone may not change the scanner's decision.

## Acceptance criteria for a future implementation

- Android, the dashboard, and CIR viewer still discover and communicate with all supported UWB devices.
- Telemetry, CIR, and control use TLS with certificate validation; no trust-all fallback exists.
- Unpaired/unauthorized LAN clients cannot change device settings or streaming state.
- HTTP requests to device APIs fail after migration; no broad cleartext exception remains.
- UDP discovery cannot be used by itself to impersonate a trusted device.
- Firmware memory use and connection behavior are checked on actual ESP hardware.
- Phone Manager's exact result is recorded after installing the signed release; warning removal is reported only if it is actually observed.

## Files to inspect when continuing

- `android/app/src/main/AndroidManifest.xml` — Android network permissions and cleartext policy.
- `android/app/src/main/java/com/example/myapplication/DeviceRepository.kt` — discovery and Android HTTP client.
- `android/app/src/main/java/com/example/myapplication/MainActivity.kt` — local-network permission flow.
- `components/telemetry/telemetry.c` — firmware HTTP routes and control validation.
- `dashboard/localization_dashboard.py` — primary Python dashboard client.
- `dashboard/dashboard.py` — alternate dashboard client.
- `dashboard/cir_viewer.py` — CIR client.

## Handoff for a future AI assistant

Read this report and the files above before changing the protocol. First inspect the current branch because code may have changed since this report. Preserve Android, Python dashboard, and CIR viewer compatibility as one migration. Do not claim the Huawei warning is fixed until tested on the affected phone. Do not silence the warning, remove required network permissions, disable TLS verification, or turn off cleartext before the device firmware and all clients have a working authenticated HTTPS path. Ask for device certificate/pairing constraints only if the repository and hardware setup do not establish a safe choice.


screenshort:
C:\Users\Astom\Desktop\codes\pcb_v1\Screenshot_20260930_001125.png