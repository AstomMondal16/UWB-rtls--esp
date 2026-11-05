# Device HTTPS setup

By default, the dashboard API uses HTTP on the device's local network, so a
phone hotspot can be used without creating a certificate or entering a
fingerprint. HTTP has no transport encryption or client authentication; use it
only on a trusted network. To use HTTPS instead, enable **Use HTTPS for the
dashboard API** in `idf.py menuconfig` and provide a per-device certificate
and key. TLS builds advertise `api_scheme: "https"` in UDP discovery. Android upgrades
all three API routes automatically and remembers HTTPS, without falling back
on a failed handshake. For older TLS builds, enable **Require HTTPS** or enter
the certificate fingerprint in the device editor. Publicly trusted certificates
need no pin; self-signed certificates need their SHA-256 fingerprint. BLE Wi-Fi provisioning uses an encrypted Security 1
session without a proof-of-possession check.

## Create a device TLS certificate

Run OpenSSL from the repository root for each device:

```powershell
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -sha256 -days 3650 `
  -keyout components/telemetry/certs/device_key.pem `
  -out components/telemetry/certs/device_cert.pem `
  -subj "/CN=UWB-AA-BB-CC-DD-EE-FF" `
  -addext "subjectAltName=IP:192.168.1.42"
openssl x509 -in components/telemetry/certs/device_cert.pem -noout -fingerprint -sha256
```

Replace `192.168.1.42` with the device's reserved DHCP/static IP before issuing
its certificate. Every IP or DNS name used to connect must appear in a
`subjectAltName` (IP entries must use `IP:`, not `DNS:`). Changing an IP requires
a matching certificate and an updated pin. The old CN-only example does not
pass hostname verification. Certificate validity and hostname checks stay enabled.

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

## Current repository configuration

The checked local `sdkconfig` has `CONFIG_UWB_TLS_ENABLE` disabled and the
per-device PEM files are absent. `/api/telemetry`, `/api/control`, and `/api/cir`
therefore remain HTTP on that firmware. To finish the migration, supply a
unique certificate/key with matching SANs, enable the existing TLS option,
rebuild and flash each device, and configure trust/pins in its clients. Do not
turn on TLS in a build without those files. No physical device was flashed by
this task. UDP discovery and encrypted BLE Wi-Fi provisioning are different
protocols and are not converted to HTTPS.
