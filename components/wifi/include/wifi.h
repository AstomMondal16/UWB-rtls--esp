#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

typedef struct {
    uint8_t mac[6];
    char ip_address[16];
} wifi_info_t;

typedef void (*wifi_connection_callback_t)(bool connected, void *arg);

/* Connects or provisions Wi-Fi and returns station identity/address. */
esp_err_t wifi_connect(wifi_info_t *info);
void wifi_set_connection_callback(wifi_connection_callback_t callback, void *arg);
