#pragma once

#include "esp_err.h"
#include <stdbool.h>
#include <stdint.h>

typedef enum {
    SENSOR_COORDINATOR_NORMAL_OPERATION,
    SENSOR_COORDINATOR_BLE_REQUESTED,
    SENSOR_COORDINATOR_PAUSING_SENSORS,
    SENSOR_COORDINATOR_BLE_ACTIVE,
    SENSOR_COORDINATOR_BLE_TIMEOUT,
    SENSOR_COORDINATOR_BLE_DISCONNECTED,
    SENSOR_COORDINATOR_BLE_FAILED,
    SENSOR_COORDINATOR_BLE_FINISHED,
    SENSOR_COORDINATOR_RESUMING_SENSORS,
    SENSOR_COORDINATOR_ERROR_RECOVERY,
} sensor_coordinator_state_t;

enum {
    SENSOR_COORDINATOR_UWB = 1u << 0,
    SENSOR_COORDINATOR_IMU = 1u << 1,
    SENSOR_COORDINATOR_BATTERY = 1u << 2,
};

esp_err_t sensor_coordinator_init(void);
esp_err_t sensor_coordinator_register(uint32_t sensor);
void sensor_coordinator_checkpoint(uint32_t sensor);
/* Interrupt sensor task delays so their owners can apply new runtime controls. */
void sensor_coordinator_notify(uint32_t sensors);
/* Owner task waits only for the unused part of its period; controls can interrupt it. */
void sensor_coordinator_wait_period(uint32_t period_ms, int64_t cycle_started_us);
esp_err_t sensor_coordinator_pause_for_ble(uint32_t timeout_ms);
void sensor_coordinator_resume_after_ble(sensor_coordinator_state_t reason);
bool sensor_coordinator_ble_active(void);
