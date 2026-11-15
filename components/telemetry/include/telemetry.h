#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "uwb_raw.h"

typedef struct {
    uint64_t timestamp_ms;
    uint32_t setup_generation;
    uint8_t peer_id[6];
    float distance_m;
    float speed_mps;
    float rx_power_dbm;
    uwb_raw_t uwb_raw;
    bool imu_valid;
    float accel[3];
    float gyro[3];
    float temperature_c;
    bool battery_valid;
    float battery_voltage;
    float battery_percent;
} telemetry_sample_t;

typedef void (*telemetry_control_callback_t)(bool streaming,
                                             uint32_t sensing_interval_ms,
                                             uint32_t send_interval_ms,
                                             uint16_t antenna_delay,
                                             const char *role);

typedef struct {
    uint8_t device_id[6];
    const char *ip_address;
    const char *role;
    uint16_t buffer_length;
    uint32_t sensing_interval_ms;
    uint32_t send_interval_ms;
    uint16_t antenna_delay;
    telemetry_control_callback_t on_control;
    bool clock_synchronized;
} telemetry_config_t;

/* Starts the CPU0 queue consumer, dashboard API, and UDP MAC discovery service. */
esp_err_t telemetry_start(const telemetry_config_t *config);

/* Never blocks sensing. The oldest queued sample is discarded if full. */
bool telemetry_publish(const telemetry_sample_t *sample);

/* Update independent IMU telemetry; it does not require a UWB range sample. */
void telemetry_update_imu(bool valid, const float accel[3],
                          const float gyro[3], float temperature_c);

/* Battery readings are independent of UWB range samples. */
void telemetry_update_battery(float voltage, float percent);

/* Copies latest independent sensor values; return value indicates a valid UWB range.
 * No SPI or I/O is performed. role must point to an 8-byte buffer. */
bool telemetry_read_snapshot(telemetry_sample_t *sample, char role[8]);

/* UTC system-clock milliseconds, or 0 until the selected source synchronizes. */
int64_t telemetry_clock_unix_ms(void);

/* Startup task waits without holding a mutex; the time endpoint signals readiness. */
bool telemetry_wait_for_clock(uint32_t timeout_ms);
void telemetry_set_clock_status(bool allowed, bool synchronized, bool phone);
void telemetry_set_sensor_state(const char *role, bool streaming, uint32_t period_ms,
                                uint32_t setup_generation);

/* Complete raw CIR JSON, or NULL on failure. Optional capture_valid reports valid I/Q. Caller frees(). */
char *telemetry_copy_cir_json(bool *capture_valid);

/* Raw JSON allocated for the caller to free(); missing_as_zero is for console presentation only. */
char *telemetry_encode_uwb_raw_json(const uwb_raw_t *raw, bool missing_as_zero);
