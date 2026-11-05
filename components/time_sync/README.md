# Clock source and power-on synchronization

Run `idf.py menuconfig`, then open **UWB ranging → Timestamp clock source**.

| Choice | Startup and clock behavior |
| --- | --- |
| Phone / computer clock (default) | Start normally after Wi-Fi. The Android app or desktop dashboard reads `clock_source:"phone"` with `device_time_unix_ms:0` and posts its current UTC epoch milliseconds to `POST /api/time`. No Internet connection on the ESP is needed. The terminal uses uptime until this request succeeds. |
| Time.Now HTTPS API, then SNTP | Before telemetry and sensing start, try the requested Asia/Kolkata endpoint five times, with five seconds between failures. After five failures, try `pool.ntp.org` for up to ten seconds. Continue afterward; if neither source succeeded, request phone/computer time over the local network. Uptime is shown only until that transfer succeeds. |

The setting is compiled into firmware; switching it requires rebuilding and
flashing. It is independent of the existing local-device HTTP/HTTPS setting.
The public time API always uses HTTPS.

## RTC and timestamp meanings

The clock is set with `settimeofday()` in **UTC**, preserving the API's fractional
seconds. ESP-IDF's configured RTC/high-resolution timer maintains the running
system clock; it is not a battery-backed clock through loss of power. Every boot
performs the selected synchronization process again. Saving an old timestamp in
flash cannot account for time spent without power.

Terminal and exported CSV timestamps use `DD-MM-YYYY HH:MM:SS` in IST (UTC+05:30). Private capture metadata retains full ISO 8601 UTC and epoch milliseconds: `2026-11-05T11:18:29.720Z` corresponds to CSV `05-11-2026 16:48:29`.

- Phone/system mode: CSV uses the phone's receipt timestamp as before.
- API mode: CSV uses `device_time_unix_ms`, the device wall clock when the HTTP
  snapshot is generated. If that clock is unavailable, CSV uses phone receipt
  time. This is snapshot time, not a claim that all sensor measurements happened
  at that instant; stream-specific uptime/timestamps and CIR age remain intact.
- Original phone receipt time is always preserved as `received_at_utc` in the
  private JSONL capture. The 44 CSV columns, I/Q precision, device scope and
  recording pause behavior are unchanged.
- Until a source synchronizes, `device_time_unix_ms` is zero and the terminal
  explicitly displays `uptime_ms=...`; the rough TLS bootstrap date is hidden.

`configured_clock_source` reports the menuconfig choice. `clock_source` reports the effective source (`phone` or `api`); failed API/SNTP startup advertises `phone` so clients can recover the clock locally. In API mode,
startup logs distinguish API success from SNTP fallback. No new wall clock is
substituted for monotonic clocks used by ranging, filtering, expiry or scheduling.

## API and TLS

The requested endpoint is
`https://time.now/developer/api/timezone/Asia/Kolkata`. The response is bounded to
2 KiB, must be HTTP 200 and complete, and must contain matching integer
`unixtime`, `utc_datetime`, timezone and offset fields. The documented flat scalar
JSON contract is checked; malformed, duplicate, mismatched or out-of-range clock
fields are rejected. Up to six fractional digits are retained by the RTC clock;
private capture metadata has millisecond resolution; the requested CSV date/time display is second-level. Network transit still limits absolute accuracy.

Certificate chain, hostname and date checks remain enabled. The ESP certificate
bundle supplies roots; redirects and HTTP downgrade are not followed. Before an
API request, a UTC firmware-build date can seed a cold clock sufficiently for
certificate date validation. This estimate is **not** marked synchronized. A
stale build date may cause API TLS validation to fail; SNTP is the bounded
fallback. No certificate checks are bypassed to obtain the clock.

`CONFIG_MBEDTLS_HAVE_TIME_DATE=y` enables certificate date verification. The main
startup task has an 8 KiB stack for the API HTTPS handshake. API code is compiled
only when API mode is selected. There is no background API polling after startup.

## Phone/system transfer

`POST /api/time` accepts only `application/json` with a single bounded integer:

```json
{"unix_ms":1793879430391}
```

It is accepted in phone/system mode or after both API and SNTP startup fail. A successfully synchronized API/SNTP clock is protected from phone updates. Android sends it asynchronously after
validating the configured device identity, with one request in flight per device;
normal ingestion continues independently. Connection errors are reported and a
later poll can retry. The desktop dashboard uses its computer clock. New firmware
advertises its mode, so clients do not send time to older firmware or API-mode
devices. The endpoint uses the existing device API transport and trust settings;
it does not add authentication to an API that already lacks it.

## Checks and limits

Host tests exercise the actual API/phone parsers, five-attempt/four-delay
sequence, SNTP fallback, TLS configuration, full I/Q preservation and timezone
formatting. Android tests exercise the real loopback POST and CSV mode selection.
The endpoint was queried successfully over verified HTTPS from the computer;
this is separate from firmware/hardware validation.

No phone was installed or operated and no hardware was flashed. Real power-cycle,
RTC drift, on-device HTTPS memory/timing and physical clock transfer must be
verified after the user installs/flashes the updated builds.

World Time API by [Time.Now](https://time.now/developer).
RTC behavior and clock functions: [ESP-IDF system time](https://docs.espressif.com/projects/esp-idf/en/v6.0.1/esp32s3/api-reference/system/system_time.html).

## Component and parsed timestamp

The implementation is in `components/time_sync/time_sync.c` with public `include/time_sync.h`; main depends on this ESP-IDF component. API/NTP dependencies and the TLS build-date estimate belong to the component. Existing menuconfig choices remain under UWB ranging.

Only the timestamp is applied to the clock: `unixtime` supplies UTC seconds, and the fraction in `utc_datetime` supplies microseconds. Other fields are not stored as clock data; timezone/offset are validated. For the supplied response, the clock is set to 1793894486 seconds and 252461 microseconds, equivalent to 2026-11-05 21:31:26.252461 IST.

`ESP_ERR_HTTP_CONNECT` occurs before any JSON is read. New diagnostics distinguish HTTPS/socket/TLS connection failures, HTTP response failures and invalid JSON timestamps. Moving or parsing the response cannot fix an unavailable connection by itself.
