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
esp_err_t sensor_coordinator_pause_for_ble(uint32_t timeout_ms);
void sensor_coordinator_resume_after_ble(sensor_coordinator_state_t reason);
bool sensor_coordinator_ble_active(void);
