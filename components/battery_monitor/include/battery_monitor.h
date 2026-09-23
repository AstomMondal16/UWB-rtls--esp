#pragma once

#include <stdint.h>
#include "esp_err.h"

typedef struct {
    int gpio;
    uint32_t empty_mv;
    uint32_t full_mv;
    float divider_ratio;
    uint8_t sample_count;
} battery_monitor_config_t;

typedef struct {
    int raw;
    int pin_mv;
    float voltage;
    float percent;
} battery_monitor_data_t;

esp_err_t battery_monitor_init(const battery_monitor_config_t *config);
esp_err_t battery_monitor_read(battery_monitor_data_t *data);
