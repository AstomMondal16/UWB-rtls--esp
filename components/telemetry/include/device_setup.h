#pragma once

#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"

#define DEVICE_SETUP_MAX_PEERS 8
#define DEVICE_SETUP_LEASE_MS 15000

typedef struct {
    uint32_t version;
    bool configured;
    bool coordinates_valid;
    char role[8];
    double x, y, z;
} device_setup_config_t;

typedef struct {
    device_setup_config_t config;
    bool active;
    char id[33];
    char role[8];
    uint32_t generation;
    uint32_t config_revision;
    int64_t expires_ms;
    uint8_t peers[DEVICE_SETUP_MAX_PEERS][6];
    unsigned peer_count;
} device_setup_snapshot_t;

typedef struct {
    bool running;
    bool sleep_allowed;
} device_setup_sensor_policy_t;

esp_err_t device_setup_init(const char *default_role);
void device_setup_get(device_setup_snapshot_t *snapshot);
bool device_setup_pending(const device_setup_snapshot_t *snapshot);
device_setup_sensor_policy_t device_setup_sensor_policy(const device_setup_snapshot_t *snapshot,
    bool connected, bool clock_ready, bool monitoring);
/* Flat JSON protocol; temporary state is never written to NVS. */
esp_err_t device_setup_execute(const char *body);
/* Called by the startup supervisor even when no HTTP/radio traffic arrives. */
void device_setup_tick(bool connected);
