# Firmware and dashboard review

Scope: source review of the ESP-IDF components, main application and Python
dashboards during the optional CIR implementation. This is not a penetration
test, dependency CVE audit, or proof that all vulnerabilities have been found.
The Arduino `anchor/` and `tag/` reference sketches were preserved.

## Fixed

| Finding | Impact and correction |
| --- | --- |
| Short UWB packets used bytes beyond the received payload | `message_type()` now enforces frame families and minimum lengths. `find_our_entry()` checks the count byte and all advertised entries before use. Short RANGE_REPORT frames no longer reuse stale floats from the 90-byte buffer. These were reads outside the received payload, not demonstrated reads outside the allocated buffer. |
| UWB PAN ID was not checked in software | `short_for_us()` and `long_for_us()` now reject another PAN. Source/destination checks are retained. This is filtering, not authentication. |
| Remote range values were trusted | Tag rejects nonfinite/negative/over-1000 m ranges and impossible finite RX powers/infinity. NaN power remains an allowed unavailable-diagnostic marker, encoded as JSON `null`. Remote reply delays must be 2000..20000 us. |
| HTTP control used substring matching | The entire bounded body must match the five-field flat control schema. Duplicate/unknown keys, nesting, escapes, trailing data, wrong types, overflow, negative/fractional integers and out-of-range values are rejected before state changes. |
| Simple cross-origin browser POSTs could change controls | `/api/control` requires `application/json`, as both desktop clients already send. Form/text requests are rejected; no cross-origin preflight handler is enabled. This does not replace authentication or prevent all browser/network attacks. |
| Failed TX timestamp read published an uninitialized value | Output is assigned only on a successful register read. RX rejects invalid frame lengths and clears the caller's length before receiving. Small integer register helpers enforce their eight-byte buffer limits. |
| Unexpected UDP discovery JSON could crash dashboard workers | Both discovery loops ignore non-object JSON; MAC normalization rejects nonstrings. Connection address comes from the UDP sender rather than an arbitrary advertised hostname/URL. This does not authenticate that sender. |

## Remaining security limitations

1. **High on an untrusted LAN: unauthenticated HTTP controls and telemetry.**
   `components/telemetry/telemetry.c` registers `/api/control`, `/api/telemetry`
   and `/api/cir` without authentication or TLS. A host that can reach the
   device can stop sensing, change roles or calibration, and retrieve data.
   Calibration changes can also cause repeated NVS writes. Use a restricted
   device network. A deployment needing untrusted clients requires a coordinated
   authentication/TLS design for firmware and both dashboards; none was added
   implicitly because that changes connection behavior and credential handling.

2. **High where ranging is a security decision: unauthenticated UWB protocol.**
   Addresses, PAN IDs and frame CRC do not establish sender identity. Radio
   peers can spoof discovery, replay packets, submit plausible false ranges,
   or disrupt exchanges. The existing Arduino-compatible protocol has no
   cryptographic authentication or replay protection. The validation changes
   above cannot make these distances safe for access control or adversarial use.

3. **Medium: discovery spoofing and oversized desktop HTTP responses.**
   A LAN sender can still claim another MAC. Both dashboards use HTTP and read
   JSON responses without an explicit byte limit (`json.load(response)`). A
   malicious endpoint can consume desktop memory or provide fabricated data.
   Sender IP selection reduces redirection through discovery fields; it does
   not verify a device identity or bound telemetry responses.

4. **Physical access: secrets and firmware are not protected at rest.**
   The current sdkconfig disables secure boot, flash encryption and NVS
   encryption. Wi-Fi credentials are compiled into firmware. Keep sdkconfig,
   firmware images, build outputs and backups private. These ESP security
   options were not enabled automatically; provisioning can involve irreversible
   eFuse operations.

## Additional reliability observation

`uwb_ranging_set_antenna_delay()` and `apply_antenna_delay()` communicate through
volatile variables. A dashboard update arriving during a previous SPI update
can have its pending flag cleared by the older update. Volatile is not a
cross-core synchronization protocol. This pre-existing race remains; no
multi-core stress test or control-state redesign was performed in this task.

## Conservative cleanup

- Removed the unused `wait_status()` output parameter and unused RX status local.
- Removed unreachable returns after the anchor/tag infinite loops.
- Replaced the three permissive HTTP substring parsers with one bounded-schema
  parser; no new library dependency was introduced.
- Kept sensor enable/disable branches, both ranging roles, GPIO/LED setup,
  calibration helpers, sensor lifetime APIs, reference sketches, and dashboard
  functions used by callbacks, properties, self-tests or the second dashboard.
- At the user's request, standalone tests, the extra CIR verification build,
  old source/configuration backups, Python caches, and the unreferenced
  `comlogo-enhanced.png` image were removed from the project. Firmware,
  dashboard runtime files, reference sketches and the active logo were kept.
  Recovery copies are under
  `C:/Users/Astom/AppData/Local/Temp/pcb_v1-cleanup-20260909-153210/`.

## Verification

Before cleanup, ESP-IDF 6.0.1 ESP32-S3 builds passed with CIR disabled and enabled
at 1016 samples using a separate verification build. That extra build has since
been removed. The disabled driver archive contained no capture,
snapshot getter or CIR storage symbols. Both dashboard self-tests passed.
The toolchain printed existing SDK Git-ownership/version metadata warnings;
they did not prevent compilation, linking or partition-size checks.

The removed standalone firmware tests compiled the actual C function bodies with
mock SPI/register and HTTP functions using host `cl` or `cc`. They exercised
malformed control bodies, short/misclassified frames, PAN rejection, CIR window
boundaries, signed decoding, every chunk failure position, clock restoration,
capture throttling, stale-snapshot preservation and valid HTTP JSON output.
Five configurations covered off, 32/64/1016 samples and zero/nonzero intervals.
Those mocks did not verify electrical SPI timing, accumulator retention through
TX, multicore behavior, or real CIR quality; those require the boards.

Both Python dashboards have `--self-test` modes. After flashing an enabled
build, check `/api/cir` for increasing sequence numbers and plausible I/Q
samples, verify stable ranging on both roles, then disable the checkbox and
confirm the endpoint returns `enabled:false`. Full captures intentionally
delay the next RX; measure packet loss with the intended anchor count/rate.
