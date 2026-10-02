# DW1000 calibration and dashboard change research

Audience: developer/operator of the ESP32-S3 DW1000 + MPU6050 system  
Date: 2026-08-25  
Scope: connect/disconnect UX, orientation reference grid, timestamped exports,
and a defensible calibration for a single DW1000 anchor/tag pair.  
Assumptions: channel 2, 64 MHz PRF, 110 kb/s and asymmetric double-sided TWR,
as configured in this repository; one accurately measured static pair is
available. Environmental mapping, anechoic testing and multi-anchor position
calibration are excluded.

## Executive answer

The approximately 0.86 m constant error should first be corrected through the
DW1000 antenna-delay registers. The implemented workflow averages 100 static
ranges, uses their median, computes the delay adjustment in DW1000 device-time
units, and safely applies equal TX/RX delay to the connected device from the
sensing task. A five-range median rejects impulsive jitter. This is a pair or
aggregate calibration; a single pair cannot uniquely identify two independent
per-device delays. RX-power bias, antenna orientation and NLOS need additional
measurements and must not be disguised as a universal constant.

## Evidence and implementation decisions

- Qorvo lists APS014 antenna-delay calibration and APS011 sources of TWR error
  as the relevant DW1000 application notes. APS014 explains that internal and
  RF-path delay variations can cause errors of tens of centimetres and calls
  for calibration using the deployed radio settings. APS011 treats
  received-signal-level range bias as a separate correction, on the order of
  centimetres over its published 64 MHz PRF table—not a plausible explanation
  for the entire observed 0.86 m error.
- An official Decawave/Qorvo support answer gives the single-pair adjustment:
  `delay_new = delay_current + (measured - true) / c / 15.65 ps / 2`.
  For 0.975 m reported at 0.110 m, this changes 16384 to approximately 16476
  DTU. The dashboard implements this calculation and range-checks 0..65535.
- The DW1000 manual procedure calls for many static ranges at a known distance
  and adjusting antenna delay until the average agrees. The implementation
  uses 100 samples and a median as a practical interactive compromise; Qorvo's
  production guidance uses larger data sets when production-grade calibration
  is required.
- `thotro/arduino-dw1000` implements the same asymmetric TWR equation already
  present in this repository and describes it as less sensitive to clock drift
  than symmetric TWR. Its declared range-bias tables are marked unused/TODO,
  so they were not copied as an authoritative correction.
- `Decawave/uwb-core` is a hardware/architecture-independent UWB driver and
  ranging-services reference. It supports the architectural choice to keep
  register changes in the radio/sensing path, but it does not supply a
  drop-in calibration for this ESP-IDF port.
- The existing driver loads crystal trim from DW1000 OTP. Crystal production
  calibration, TX-power compliance calibration, temperature models, antenna
  radiation-pattern mapping, NLOS classification and anchor-geometry solving
  require hardware/reference data not available from one short-distance pair.

## Limitations and recommendations

Calibrate antenna-to-antenna distance in clear line of sight with fixed
orientation and a longer baseline when practical; extremely short separation
can place the receiver outside the power region used for range-bias
calibration or cause saturation/multipath. Repeat at several distances and
orientations to measure residual error. Add APS011 RX-power interpolation only
after confirming the receive-power estimator against the configured channel,
PRF and TX power. Multi-anchor localization also requires surveyed anchor
coordinates and geometry calibration.

## Claim-to-source ledger

- **DW1000 application notes and stated sub-10 cm capability.** Qorvo, “DW1000,”
  current product/resource page, accessed 2026-08-25.
  https://www.qorvo.com/products/p/DW1000
- **Antenna-delay update equation and 15.65 ps DTU.** Decawave administrator,
  “Antenna delay calibration,” Qorvo Tech Forum, 2017-07-18, accessed
  2026-08-25. https://forum.qorvo.com/t/antenna-delay-calibration/826/14
- **Asymmetric TWR implementation and relative clock-drift statement.** Thomas
  Trojer et al., `arduino-dw1000`, GitHub, repository currently unmaintained
  by its owner since 2019, accessed 2026-08-25.
  https://github.com/thotro/arduino-dw1000
- **Hardware-agnostic driver/ranging-services architecture.** Decawave,
  `uwb-core`, GitHub, accessed 2026-08-25.
  https://github.com/Decawave/uwb-core
- **Separate TX/RX antenna-delay register APIs and adjusted timestamps.**
  Decawave, `dwm1001-examples/deca_driver/deca_device.c`, GitHub, accessed
  2026-08-25.
  https://github.com/Decawave/dwm1001-examples/blob/master/deca_driver/deca_device.c

Access note: Qorvo's product page exposed current application-note titles and
revisions, but the linked binary files were not text-readable through the
research tool. Consequential formula and register claims were independently
checked against accessible Decawave/Qorvo materials and source repositories.