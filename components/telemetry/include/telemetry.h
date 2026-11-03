#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

typedef struct {
    uint64_t timestamp_ms;
    uint8_t peer_id[6];
    float distance_m;
    float speed_mps;
    float rx_power_dbm;
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
} telemetry_config_t;

/* Starts the CPU0 queue consumer, HTTP API, and UDP MAC discovery service. */
esp_err_t telemetry_start(const telemetry_config_t *config);

/* Never blocks sensing. The oldest queued sample is discarded if full. */
bool telemetry_publish(const telemetry_sample_t *sample);

/* Update independent IMU telemetry; it does not require a UWB range sample. */
void telemetry_update_imu(bool valid, const float accel[3],
                          const float gyro[3], float temperature_c);

/* Battery readings are independent of UWB range samples. */
void telemetry_update_battery(float voltage, float percent);
