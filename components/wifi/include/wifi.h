#pragma once

#include <stdint.h>
#include "esp_err.h"

typedef struct {
    uint8_t mac[6];
    char ip_address[16];
} wifi_info_t;

/* Connects to the station network and returns its MAC and IPv4 address. */
esp_err_t wifi_connect(wifi_info_t *info);
