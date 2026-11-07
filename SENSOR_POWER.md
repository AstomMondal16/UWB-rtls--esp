# Pause/Play sensor power

Flash the updated C firmware. The existing app already sends
`{"streaming":false}` for Pause and `{"streaming":true}` for Play/Start;
no app update, pairing reset or extra setup is needed for this feature.

- **Pause:** the DW1000 enters DEEPSLEEP with SPI-CS wake enabled and its internal
  wake counter disabled. The MPU6050 sets the SLEEP bit in PWR_MGMT_1.
- **Play:** the DW1000 wakes through a 600 microsecond CS-low pulse, then runs the
  existing full radio initialization to restore its profile, address and current
  calibrated antenna delay. Peers are rediscovered. The MPU6050 clears SLEEP,
  waits 100 ms for settling, and resumes with its existing gyro offsets and
  scale/filter settings.

The ESP32 itself stays awake. Wi-Fi, HTTPS, the clock and battery monitoring
remain available, so the app can send Play remotely. This is sensor-chip sleep,
not ESP32 deep sleep. The whole board's current depends on its MCU, regulators,
LEDs and other loads; no particular board-current reduction is claimed without
measurement.

Power changes run only in each sensor's owning task, at safe transaction
boundaries. Long sensor delays are interruptible by the control notifications.
An in-progress ranging exchange or initial gyro calibration may finish before
sleep is applied. If paused before initial calibration, the IMU is put to sleep
and calibrated once on its first Play; ordinary wake cycles do not recalibrate.
Bus/initialization errors are logged and retried, with no claimed successful
sleep state after a failed register write.

The requested Pause state survives Wi-Fi disconnection/reconnection. Reconnecting
wakes sensors only if monitoring is requested. Runtime Pause is not persisted
through an ESP32 reboot; startup follows the existing enabled-monitoring default.
Control and connection-state updates share a short critical section to avoid
one overwriting the other's desired power state.

BLE provisioning still coordinates safe sensor task pauses. Sleeping sensor
tasks can acknowledge those requests without touching their buses, and wake
only after the coordinator releases them. Radio configuration changes made
while paused are applied on wake. Transient peer filters are cleared across
sleep, and cached CIR is invalidated so it cannot appear as a fresh capture.

While paused, HTTPS/status responses and battery readings may continue. The
terminal suppresses sensor data rows and prints `Device is paused: UWB deep
sleep, IMU sleep mode.` once per observed pause. Normal rows resume on Play.
No new valid UWB/IMU samples are acquired once the sleep transition
has completed. Sleep invalidates the current IMU reading until a real sample is
read after wake. CSV schema, units, calibration values and recording controls
are unchanged.

## Verification

The ESP-IDF build and host C tests cover register sequences, injected bus failures,
idempotent sleep, no reads while the IMU sleeps, calibration/settings retention,
first-Play calibration, retry/backoff, owner-task notification routing, pending
antenna settings, and Pause/Play across Wi-Fi reconnects. These use mocked buses
and RTOS events. No board was flashed or phone modified by the agent.

After flashing, verify on hardware: active samples -> Pause -> no fresh UWB/IMU
acquisition while HTTPS remains reachable -> Play -> fresh samples/ranges again.
Repeat several cycles and test a Wi-Fi reconnect while paused. Measure actual
current if power savings are required. Real wake timing, current draw and RF
rediscovery have not been measured by these simulated checks.

Hardware references: [DW1000 driver API guide, sleep/wake sections](https://forum.qorvo.com/uploads/short-url/jQPkxawHn3LzbJln0QqjPShspZP.pdf),
[DW1000 AON register definitions](https://pigweed.googlesource.com/third_party/github/zephyrproject-rtos/zephyr/+/refs/tags/v3.0.0/drivers/ieee802154/ieee802154_dw1000_regs.h),
[InvenSense MPU-6000/MPU-6050 register map, PWR_MGMT_1](https://invensense.tdk.com/wp-content/uploads/2015/02/MPU-6000-Register-Map1.pdf).
