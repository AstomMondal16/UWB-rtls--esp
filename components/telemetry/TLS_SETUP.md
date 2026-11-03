# Device HTTPS setup

By default, the dashboard API uses HTTP on the device's local network, so a
phone hotspot can be used without creating a certificate or entering a
fingerprint. HTTP has no transport encryption or client authentication; use it
only on a trusted network. To use HTTPS instead, enable **Use HTTPS for the
dashboard API** in `idf.py menuconfig` and provide a per-device certificate
and key. The Android app then needs that certificate's SHA-256 fingerprint in
the device settings. BLE Wi-Fi provisioning uses an encrypted Security 1
session without a proof-of-possession check.

## Create a device TLS certificate

Run OpenSSL from the repository root for each device:

```powershell
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -sha256 -days 3650 `
  -keyout components/telemetry/certs/device_key.pem `
  -out components/telemetry/certs/device_cert.pem `
  -subj "/CN=UWB-AA-BB-CC-DD-EE-FF"
openssl x509 -in components/telemetry/certs/device_cert.pem -noout -fingerprint -sha256
```

The certificate is self-signed. Enter its SHA-256 fingerprint in the Android
device settings or Python dashboard credentials. Clients pin that fingerprint
to identify the device. Never reuse a device certificate or private key on
another unit. The `certs/.gitignore` excludes these files.

## Configure Python dashboards

Set `UWB_DEVICE_CREDENTIALS` in the process environment to a JSON object keyed
by normalized device MAC (or by IP when no MAC is configured):

```json
{"AA:BB:CC:DD:EE:FF":{"tls_fingerprint":"<64-hex-SHA256-fingerprint>"}}
```

The value is read from the environment and is not written by the dashboards.
Add an entry for each device the dashboard will contact.
