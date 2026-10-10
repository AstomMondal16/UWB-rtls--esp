# Android App Security Finding: OnePlus Fake-App Detection

**Status:** Detection recorded; cause not established. Network-security remediation remains separate.
**Last updated:** 2026-10-05

## Summary

On a OnePlus Nord CE 5 running OxygenOS 16.0.5, the built-in security scan reports one high risk. The screenshot names it `Android.Riskware.Deceptive.FakeApp.ADW.FMYY.AppImppers` and alleges that the app imitates another app. This is the scanner's classification, not evidence that the app actually impersonates another app.

The user suspects the app's HTTP traffic caused the warning. The repository does use cleartext HTTP and has a separate, genuine network-security weakness, but the screenshot's deceptive/fake-app classification does not identify HTTP as its reason. Keep these as separate findings unless OnePlus provides evidence linking them.

The Android project currently uses the default-looking application ID `com.example.myapplication`, while its visible name and custom launcher icon identify it as UWB Localization. This identity mismatch is worth correcting before broader distribution, but it is only a possible contributing factor; it does not establish why the scan matched. Android source review found no obvious app-masquerading or dynamic-code-loading implementation. Source review cannot prove the exact APK that was installed is clean or explain the vendor scanner's signature.

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
- `android/app/build.gradle.kts` sets both namespace and application ID to `com.example.myapplication`; the app label is `UWB Localization` and the launcher icon is a custom UWB/location design.
- `DeviceRepository.kt` uses plain HTTP for telemetry, CIR, and control requests, plus UDP broadcast discovery.
- `components/telemetry/telemetry.c` serves the endpoints with `esp_http_server`; `/api/control` does not authenticate the caller.
- `dashboard/localization_dashboard.py`, `dashboard/dashboard.py`, and `dashboard/cir_viewer.py` also use the HTTP API.
- The manifest does not request contacts, SMS, or Android location permissions. However, the app handles UWB position, IMU, CIR, and device identity data; location-like telemetry can itself be sensitive.

### Not yet known

- Why the OnePlus scanner matched this exact detection, whether it is a false positive, and which APK/signature or reputation data it analyzed.
- Whether a default application ID, debug/sideloaded build, signing identity, icon/name similarity, or another property contributed. These are investigation leads, not confirmed causes.
- Whether changing app identity or signing a release build will clear the warning. Only a rescan of the exact resulting APK on the affected phone can establish that.

The screenshot already provides the risk name and alleged behavior. To continue diagnosis, record the APK's origin and build type, its package ID and signing-certificate fingerprint, and the OnePlus security-app version. Keep the APK used for the scan so its identity can be checked against the build. Do not treat an allowlist action as a diagnosis or claim a code change clears the finding until the phone is rescanned.

## Why the current setup has security risk

- **Confidentiality:** Another device on the same Wi-Fi/LAN may be able to read telemetry, CIR, device IDs, or positions because HTTP is unencrypted.
- **Integrity:** A peer on the LAN may send control requests to change streaming, role, timing, or antenna-delay settings because the firmware endpoint does not authenticate callers.
- **Discovery trust:** UDP broadcast replies are not authenticated. Discovery should locate a device, but must not be treated as proof that the responding device is genuine.
- **Distribution trust:** A locally built or sideloaded APK may be treated differently from an app installed through a trusted store. This is a possible contributing factor, not a confirmed diagnosis.

## Recommended actions

### Investigate the deceptive-app finding

1. Confirm the installed package ID and signing certificate for the exact APK that triggered the alert, and record how that APK was built and delivered.
2. Before distributing beyond development, replace the template application ID with an identifier controlled by the project owner, and use a consistent project name and release signing key. Changing an application ID installs as a different Android app, so decide this before relying on upgrades or stored app data.
3. Rebuild and rescan that exact signed APK on the OnePlus. If the same detection remains and the app has no impersonation behavior, submit the APK and detection label to OnePlus/OPlus security support for review. Do not promise that package renaming or signing alone removes a vendor detection.

Android uses `applicationId` as the app's device/store identity; changing it changes the app identity. Release updates also depend on a consistent signing key. See [Android application ID documentation](https://developer.android.com/build/configure-app-module) and [Android app signing documentation](https://developer.android.com/studio/publish/app-signing).

### Address the separate local-network risk

The HTTP exposure remains real, but changing it is not a targeted fix for the screenshot's fake-app classification. The network remediation is a coordinated protocol change across ESP firmware, Android, and Python clients. Simply setting `usesCleartextTraffic` to `false` will break current calls; it does not convert them to HTTPS.

### Phase 1 — Confirm the alert

1. Preserve the exact screenshot finding, APK source/build type, app version, phone model, and OxygenOS/security-app version.
2. Keep the scanner's claim separate from facts verified in this repository. A risk label is not proof of malicious behavior, but it should be investigated rather than attributed to HTTP without evidence.

### Phase 2 — Encrypt and authenticate device traffic

1. Add TLS/HTTPS support to the ESP device server.
2. Give every device an identity that the clients can validate. Prefer per-device certificates or a controlled local certificate authority with certificate pinning. Do not use a trust-all TLS verifier or disable certificate validation.
3. Add a pairing/provisioning method that grants authorized clients access. Require authentication on sensitive routes, especially `/api/control`; protect telemetry and CIR as well if they should be private. Avoid one shared secret embedded in every public APK as the only protection.
4. Update Android and all Python clients to use HTTPS and validate the device identity. Preserve UDP discovery only as a locator; perform authenticated TLS before accepting device data or sending commands.
5. Remove the broad `usesCleartextTraffic="true"` allowance and default to denying cleartext after every required client/device has migrated. Do not do this before the HTTPS path works end to end.

### Phase 3 — Release and recheck

1. Build a release APK signed with the project's intended release key; protect and back up that key.
2. Install the release build and retest the OnePlus scan. If it still flags the app, use the recorded detection details and OnePlus/OPlus review process; a transport change is not expected to address a fake-app signature by itself.

## Acceptance criteria for a future implementation

- Android, the dashboard, and CIR viewer still discover and communicate with all supported UWB devices.
- Telemetry, CIR, and control use TLS with certificate validation; no trust-all fallback exists.
- Unpaired/unauthorized LAN clients cannot change device settings or streaming state.
- HTTP requests to device APIs fail after migration; no broad cleartext exception remains.
- UDP discovery cannot be used by itself to impersonate a trusted device.
- Firmware memory use and connection behavior are checked on actual ESP hardware.
- The OnePlus risk result is recorded after installing the exact signed release; warning removal is reported only if it is actually observed.

## Files to inspect when continuing

- `android/app/src/main/AndroidManifest.xml` — Android network permissions and cleartext policy.
- `android/app/src/main/java/com/example/myapplication/DeviceRepository.kt` — discovery and Android HTTP client.
- `android/app/src/main/java/com/example/myapplication/MainActivity.kt` — local-network permission flow.
- `components/telemetry/telemetry.c` — firmware HTTP routes and control validation.
- `dashboard/localization_dashboard.py` — primary Python dashboard client.
- `dashboard/dashboard.py` — alternate dashboard client.
- `dashboard/cir_viewer.py` — CIR client.

## Handoff for a future AI assistant

Read this report and the files above before changing the protocol. First inspect the current branch because code may have changed since this report. Preserve Android, Python dashboard, and CIR viewer compatibility as one migration. Do not claim the OnePlus fake-app detection is fixed until the exact signed APK is rescanned on the affected phone. Do not describe HTTP as the trigger without evidence. Do not remove required network permissions, disable TLS verification, or turn off cleartext before the firmware and every client have a working authenticated HTTPS path. Ask for a project-owned Android application ID and device certificate/pairing constraints only when needed for those respective changes.



Screenshot evidence: user-provided OnePlus Nord CE 5 security-scan screen (OxygenOS 16.0.5).
