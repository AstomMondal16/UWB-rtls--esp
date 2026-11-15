#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "uwb_raw.h"

typedef enum {
    UWB_RANGING_TAG,
    UWB_RANGING_ANCHOR,
} uwb_ranging_role_t;

typedef struct {
    uint8_t device_id[6];
    uint32_t setup_generation;
    uint16_t short_address;
    float range_m;
    float rx_power_dbm;
    const uwb_raw_t *raw; /* Only valid for the duration of on_range(). */
} uwb_ranging_peer_t;

typedef void (*uwb_ranging_callback_t)(const uwb_ranging_peer_t *peer);
typedef void (*uwb_ranging_waiting_callback_t)(uwb_ranging_role_t role);

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
    uint32_t waiting_log_interval_ms;
    uwb_ranging_waiting_callback_t on_waiting;
} uwb_ranging_config_t;

/* Initializes the DW1000, allows 1 s settling, then runs the selected role. */
esp_err_t uwb_ranging_run(const uwb_ranging_config_t *config);

/* Runtime controls used by the LAN dashboard. */
void uwb_ranging_set_paused(bool paused);
void uwb_ranging_set_sleep_allowed(bool allowed);
void uwb_ranging_set_setup(uint32_t generation, const uint8_t peers[][6], unsigned count);
uint32_t uwb_ranging_setup_generation(void);
esp_err_t uwb_ranging_set_role(uwb_ranging_role_t role);
esp_err_t uwb_ranging_set_period(uint32_t period_ms);
/* Shared UWB/IMU target period, including the startup default before ranging starts. */
uint32_t uwb_ranging_get_period(void);
esp_err_t uwb_ranging_set_antenna_delay(uint16_t antenna_delay);
