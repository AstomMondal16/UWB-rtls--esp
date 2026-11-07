#pragma once
#include "esp_err.h"

typedef struct {
    char *certificate;
    char *private_key;
} device_identity_t;

esp_err_t device_identity_load(const char *expected_mac, device_identity_t *identity);
void device_identity_free(device_identity_t *identity);
