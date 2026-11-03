# Application security and reliability notes

Scope: source review of the ESP-IDF UWB firmware, Android companion, and Python
dashboards. This is not a penetration test or dependency audit. BLE and Wi-Fi
behavior still needs verification on target hardware.

## Current protections

- HTTPS clients pin the configured SHA-256 certificate fingerprint and bound
  response sizes. The default HTTP mode is unencrypted and intended only for a
  trusted local network such as a phone hotspot.
- BLE Wi-Fi provisioning uses ESP-IDF Security 1 with an empty
  proof-of-possession value. The session encrypts traffic, but does not
  authenticate the provisioning phone.
- Firmware validates packet lengths and frame fields before decoding; control
  request bodies use a bounded schema parser.
- Android CSV collection is append-only in app-private storage, has a 512 MiB
  cap, records source and receive timestamps, and exports by streaming. Export
  verifies the row count and fails on malformed or missing rows.

## Risks

1. **Unauthenticated local API:** The telemetry and control routes have no
   client API key. In default HTTP mode, any client on the local network can
   read data or issue controls without encryption. Keep devices on a trusted,
   isolated network; enable HTTPS and certificate pinning for other networks.
2. **Unauthenticated UWB frames:** Ranging frames are not cryptographically
   authenticated. A nearby radio can inject or replay frames and may affect
   reported ranges. Do not use these measurements alone for safety-critical
   access decisions.
3. **BLE provisioning access:** Any nearby BLE client can attempt to submit
   Wi-Fi credentials while provisioning is open. The encrypted session
   protects against passive capture, but does not authenticate the phone.
4. **Device secrets in flash:** Wi-Fi credentials and TLS private keys can be
   exposed if an attacker can read flash. Secure boot, flash encryption, and
   NVS encryption are not enabled in the checked-in configuration.
5. **Certificate rotation:** Clients pin the exact certificate. Replacing a
   device certificate requires updating its Android fingerprint and the
   `UWB_DEVICE_CREDENTIALS` entry used by Python clients. Discovery does not
   prove device identity.
6. **Android process access:** Keystore encryption protects stored credentials
   from straightforward backup or file extraction, but a rooted or compromised
   phone running the app can access credentials in use.

## Reliability notes

- CSV sequence-gap counts are estimates from observed sequence values. Device
  reboot and counter reset behavior can affect the estimate.
- No connected UWB device was available during source review, so BLE
  provisioning, TLS setup, and telemetry quality need on-device checks.
