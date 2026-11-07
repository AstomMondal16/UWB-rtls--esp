# Samples per second

In the Android app, open **Settings > Device configuration**, select a device,
enter **Samples per second**, then choose **Apply changes**. The two millisecond
fields are replaced by this one entry. It accepts **whole numbers 1 through 20**,
inclusive. Decimal, invalid, blank and out-of-range values show an error and
cannot be applied; values are not silently clamped. The field uses a number
keyboard. Existing intervals display the nearest whole target: 80 ms displays
13. Reading the configuration does not change the device; Apply sends the
chosen whole-number target.

This is the shared **configuration target**, not a measured hardware guarantee.
The upper setting follows the existing 50 ms minimum reporting interval.
Examples: 1/s = 1000 ms, 5/s = 200 ms, 13/s = 77 ms, 20/s = 50 ms. Other values
are rounded up to a whole millisecond so the configured interval is not faster
than requested. Firmware tick granularity and real work also limit precision.

One entry sends the same acquisition/reporting interval to the selected board.
Its IMU reads and UWB tag cycles use the same live period, including changes
made while paused. Work time is subtracted before waiting, rather than adding
a whole extra period after an exchange. Controls can interrupt the wait; an
overrun yields for one tick instead of building a catch-up queue. The MPU6050's
internal output/filter configuration is unchanged.

An anchor responds to the tag's requests; changing the anchor's setting cannot
force a remote tag's timing. Set the tag's rate too. Several anchors divide a
tag's exchanges between peers. Signal conditions, retries, discovery, full CIR
capture and other work can lower actual UWB rates. Fresh CIR has its own
firmware capture interval, which this setting does not change. These limits are
explained beside the field; production sustained rates still require hardware
measurement for the actual configuration.

For example, a tag targeting 10 total cycles/s across three anchors gives each
anchor about one third of those UWB updates. An anchor set to 10/s does not
change the tag's setting. Set the tag's rate in Device configuration too.

Android polls each device according to its configured intervals, with one
in-flight poll per device. Deadlines keep their phase across worker delays;
the timer no longer waits a whole extra 25ms slot because a worker started late.
Long overruns start a fresh period without catch-up bursts. Explicit refresh and a successful settings update
request an immediate read. UI notifications are coalesced to at most about
10/s, measured from the last displayed update. An eligible update is delivered
immediately instead of adding another 100ms delay. Telemetry acceptance and CSV recording run before that UI refresh and do
not depend on which panel is visible. Slow legacy configurations retain their
intervals and are not marked offline merely between expected polls.

Polling remains a latest-snapshot transport: network delays can miss device
samples before reception. Existing CSV sequence-gap counters still report that;
this change does not claim lossless hardware-to-phone streaming. CSV schema,
units, recording scope, Pause/Play, HTTPS trust and boot sequencing are retained.
Terminal rows remain separately rate-limited diagnostic snapshots; this field
does not make the serial console print every hardware sample.
The rate is a runtime setting, with the existing firmware startup default after
reboot. Desktop clients retain their existing interval controls/API compatibility.

Host checks cover shared period updates, sleep/wake, work-time subtraction,
tick rounding and overruns. Android unit checks cover bounds, every whole-number
value in the allowed range, rejection of decimals, matching wire intervals, locale
formatting and polling/online-timeout bounds. No physical sampling rate or phone
appearance is claimed from those simulated tests.

The polling regression test uses a 25ms scheduler, a 100ms requested period and
a 1ms worker-start delay: over ten simulated seconds, the previous gate makes
80 polls, and the fixed schedule makes 100. This checks app dispatch timing,
not physical sensor throughput or ten valid ranges per anchor each second.
