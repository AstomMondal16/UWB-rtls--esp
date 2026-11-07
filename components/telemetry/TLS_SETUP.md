# Flash-and-use HTTPS with first-use trust

Every board runs ESP-IDF C firmware. Flash it normally; it creates a unique
EC P-256 key and self-signed certificate automatically on first boot. The key
and certificate are saved in the `identity` NVS partition and reused after
reboot. No factory scripts, signing service, CA files, QR/PIN labels, per-IP
certificates, Python runtime or manual certificate entry are required.

All four routes (`/api/telemetry`, `/api/cir`, `/api/control`, `/api/time`) use
HTTPS on port 443. Android requires HTTPS and blocks cleartext. Python API
clients also require HTTPS. There is no HTTP listener in a TLS build, failed
TLS does not trigger fallback, and redirects are rejected. Time.Now continues
using its separate verified HTTPS connection. UDP discovery and BLE Wi-Fi
provisioning retain their existing protocols.

## First pairing and later connections

The owner explicitly chose **trust on first use**. Add a board only while the
phone and board are on your trusted private Wi-Fi/hotspot. The first valid
self-signed identity is remembered automatically; there is no manufacturer CA
or cryptographic authentication of that first contact. An attacker impersonating
a board at that moment could become the trusted identity. HTTPS encrypts the
connection and subsequent connections are protected by the saved identity;
this is not equivalent to authenticated QR/PIN or factory enrollment.

Android verifies certificate validity, its self-signature, and the exact DNS
SAN `uwb-<12-lowercase-MAC-digits>.local`. That is the verified identity, not a
requirement for mDNS resolution: the socket connects to the discovered IP. The
SHA-256 fingerprint of the public key (SPKI) is stored encrypted with Android
Keystore before API payloads are allowed. Storage writes are synchronous and
fail closed; concurrent first connections cannot silently replace one another.
Editing other settings preserves trust, including trust learned while an
editor was open. Normal device removal does not silently forget the saved key.

The identity's DNS name and key do not change when DHCP assigns another IP.
Unknown names, expired certificates, invalid self-signatures, changed public
keys, unreadable trust settings and failed trust writes are rejected. A matching
IP SAN cannot bypass the expected MAC identity. A wildcard DNS name is rejected.

To recover after an expected full identity erase/reset: open the device editor,
choose **Pair again**, confirm the warning, and save while on trusted private
Wi-Fi. This explicitly forgets the old key. Do not use this merely to dismiss
an unexpected identity warning; verify the physical device first. Legacy manual
certificate fingerprints remain optional for older firmware and retain their
original certificate-validity and IP/DNS checks.

Python dashboards remember certificate fingerprints atomically in
`~/.uwb_trusted_certificates.json`, keyed by MAC. They validate the same exact
stable DNS name and validity before saving trust or sending any API payload.
Existing `UWB_DEVICE_CREDENTIALS` manual pins remain optional. Corrupt trust
files and unexpected certificate changes fail closed. A verified reset can be
re-paired by explicitly removing only that MAC's saved entry while the dashboard
is closed; customers using Android use its Pair again control instead.

## Firmware storage and build

`UWB ranging > Use HTTPS for the dashboard API` defaults on and selects the
ESP-IDF HTTPS server and certificate-writing support. It can be disabled only
explicitly; the HTTPS-only app will refuse that firmware's plain HTTP API.

`partitions.csv` keeps the existing Wi-Fi/calibration NVS, PHY and application
offsets, and adds a separate 24 KiB identity partition at `0x190000`. Standard
flashing writes this partition table automatically; there is no separate identity
provisioning step. Existing Wi-Fi settings and calibration data are preserved by
these unchanged offsets. Ordinary app updates preserve the identity. Full flash
erase also erases identity, and the next boot creates a new key requiring
explicit re-pairing. Corrupt or partly written identities are not automatically
erased/replaced. They fail closed and require deliberate service/reset.

Certificates use a 2020-2049 validity window so first boot can precede phone/API
clock synchronization. Clients still enforce validity against their own clocks.
Plan a firmware/identity renewal before 2050. Private keys are never printed or
included as a shared firmware secret. Flash encryption/Secure Boot and API client
authorization are separate hardening work; the current key storage is not claimed
to resist physical extraction. BLE retains its existing Security 1 behavior
without proof of possession.

See [customer setup](../../CUSTOMER_SETUP.md). The superseded factory prototype
is archived only under the task checkpoint and is not part of the product flow.
No physical board or phone was modified by this task.

## Keep-alive and polling performance

Android reuses the TLS socket factory and hostname verifier for each unchanged
device/trust profile, and fully reads/closes response bodies so HTTP/1.1 sockets
remain reusable. Successful requests are not disconnected. Errors or oversized
responses close the connection. Changing saved trust settings (including explicit
Pair again) changes the encrypted profile revision and forces fresh verification;
an old pooled session cannot substitute for the new policy.

Python uses a bounded cache of 32 serialized per-device/endpoint sessions. The
certificate-only probe runs when establishing a profile, rather than on each
poll. A reconnect still verifies the certificate/name before sending API data.
Saved trust changes invalidate cached sockets. Failed responses close the session;
control POSTs are not automatically retried by the Python client. Desktop
Disconnect/Close releases idle sessions. Neither client caches telemetry responses.

The existing Android 350 ms polling schedule and firmware acquisition settings
are preserved. This removes repeated handshake overhead, not the initial handshake
or reconnect costs. The firmware filters esp_https_server INFO messages locally,
including the repetitive handshake line; warnings/errors remain visible. No ESP-IDF
SDK files were modified. Loopback tests cover 20 ordered samples, connection reuse,
policy invalidation and response-size failure recovery. Actual board/Android
performance must still be checked after installing the updated app and flashing
the firmware log change. Restart Python dashboards to load their updated client.
