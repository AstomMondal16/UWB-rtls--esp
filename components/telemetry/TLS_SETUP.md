# Per-device telemetry security setup

The HTTP API starts only when HTTPS is enabled and a device certificate/key pair is present. The firmware also requires a per-device API/BLE secret and a shared UWB ranging key. Keep all three secrets unique to their intended scope and out of source control.

## Create a device TLS certificate

Run OpenSSL from the repository root for each device. Use the device MAC in the certificate name so certificate files cannot be confused during flashing:

```powershell
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -sha256 -days 3650 `
  -keyout components/telemetry/certs/device_key.pem `
  -out components/telemetry/certs/device_cert.pem `
  -subj "/CN=UWB-AA-BB-CC-DD-EE-FF"
openssl x509 -in components/telemetry/certs/device_cert.pem -noout -fingerprint -sha256
```

The certificate is self-signed. Copy its SHA-256 fingerprint into that device's Android entry. The app pins the exact certificate and rejects a mismatch. Never reuse a device certificate or private key on another unit. The `certs/.gitignore` excludes these files.

Generate the per-device API/BLE secret and the deployment-wide UWB key with a cryptographically secure generator, for example `openssl rand -hex 32`. Set the first value as `CONFIG_UWB_DEVICE_SECRET` on one device and enter it in the app. Set the second as `CONFIG_UWB_RANGING_AUTH_KEY` on every tag and anchor. Do not put either value in `sdkconfig.defaults` or commit it.

## Configure firmware

In `idf.py menuconfig`, set:

- **Per-device BLE proof of possession and API key**: unique 64-character hex value. Enter it in the Android Wi-Fi provisioning dialog and device entry.
- **Require HTTPS for the dashboard API**: enabled.
- **Shared UWB ranging key**: one random 64-character hex value shared only by the tags and anchors in this deployment.

The UWB frame protocol now includes an 8-byte truncated HMAC-SHA256 tag and rejects old per-peer sequence numbers. All UWB peers must be updated together; Arduino/legacy peers are not compatible. Changing the shared key requires reflashing every peer with the new key.

The ESP-IDF `sdkconfig` contains secrets and is ignored by Git. Do not copy its secret values into `sdkconfig.defaults` or commit the generated file.

## Configure Python dashboards

Set `UWB_DEVICE_CREDENTIALS` in the process environment to a JSON object keyed by normalized device MAC (or by IP when no MAC is configured):

```json
{"AA:BB:CC:DD:EE:FF":{"api_key":"<64-hex-device-api-key>","tls_fingerprint":"<64-hex-SHA256-fingerprint>"}}
```

The value is read from the environment and is not written by the dashboards. Add an entry for each device the dashboard will contact.
