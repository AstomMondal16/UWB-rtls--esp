#pragma once

#include <stdbool.h>
#include "esp_err.h"

typedef enum {
    BLE_PROVISIONING_STARTED,
    BLE_PROVISIONING_CREDENTIALS_RECEIVED,
    BLE_PROVISIONING_CREDENTIALS_SUCCESS,
    BLE_PROVISIONING_CREDENTIALS_FAILED,
    BLE_PROVISIONING_START_FAILED,
    BLE_PROVISIONING_STOPPED,
} ble_provisioning_event_t;

typedef esp_err_t (*ble_provisioning_prepare_cb_t)(void *arg);
typedef void (*ble_provisioning_event_cb_t)(ble_provisioning_event_t event,
                                             const void *data, void *arg);

esp_err_t ble_provisioning_init(const char *service_name,
                                ble_provisioning_prepare_cb_t prepare_cb,
                                ble_provisioning_event_cb_t event_cb,
                                void *arg);
esp_err_t ble_provisioning_request(void);
esp_err_t ble_provisioning_request_stop(void);
bool ble_provisioning_is_active(void);
esp_err_t ble_provisioning_stop(void);
