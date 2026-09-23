#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

typedef enum {
    UWB_RANGING_TAG,
    UWB_RANGING_ANCHOR,
} uwb_ranging_role_t;

typedef struct {
    uint8_t device_id[6];
    uint16_t short_address;
    float range_m;
    float rx_power_dbm;
} uwb_ranging_peer_t;

typedef void (*uwb_ranging_callback_t)(const uwb_ranging_peer_t *peer);

typedef struct {
    uwb_ranging_role_t role;
    uint8_t device_id[6];
    uint16_t pan_id;
    uint16_t antenna_delay;
    uint16_t reply_delay_us;
    uint32_t tag_period_ms;
    uwb_ranging_callback_t on_range;
    uwb_ranging_callback_t on_new_device;
    uwb_ranging_callback_t on_inactive_device;
} uwb_ranging_config_t;

/* Initializes the DW1000 and runs the selected role until a fatal error. */
esp_err_t uwb_ranging_run(const uwb_ranging_config_t *config);

/* Runtime controls used by the LAN dashboard. */
void uwb_ranging_set_paused(bool paused);
esp_err_t uwb_ranging_set_role(uwb_ranging_role_t role);
esp_err_t uwb_ranging_set_period(uint32_t period_ms);
esp_err_t uwb_ranging_set_antenna_delay(uint16_t antenna_delay);
