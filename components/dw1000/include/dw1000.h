/* SPDX-License-Identifier: Apache-2.0
 * Minimal ESP-IDF port of thotro/arduino-dw1000.
 */
#pragma once

#include <stddef.h>
#include <stdbool.h>
#include <stdint.h>
#include "esp_err.h"
#include "sdkconfig.h"

#define DW1000_TIMESTAMP_MASK ((1ULL << 40) - 1)

esp_err_t dw1000_init(const uint8_t eui[8], uint16_t pan_id, uint16_t short_address,
                      uint16_t antenna_delay);
esp_err_t dw1000_deinit(void);
esp_err_t dw1000_set_antenna_delay(uint16_t antenna_delay);
uint32_t dw1000_device_id(void);

/* delayed_time == 0 sends immediately. Timestamps are 40-bit DW1000 ticks. */
esp_err_t dw1000_transmit(const void *data, size_t length,
                          uint64_t delayed_time, uint64_t *actual_tx_time);
esp_err_t dw1000_receive(void *data, size_t capacity, size_t *length,
                         uint64_t *rx_time, uint32_t timeout_ms);
float dw1000_receive_power(void);

#ifdef CONFIG_UWB_CIR_ENABLE
typedef struct {
    uint64_t timestamp_ms;       /* Local monotonic time of capture. */
    uint64_t rx_timestamp;       /* DW1000 timestamp of the captured frame. */
    uint32_t sequence;
    uint32_t capture_us;         /* Register/SPI read duration, excluding HTTP. */
    uint16_t peer_address;
    uint16_t first_path_index;   /* Raw FP_INDEX, units of 1/64 sample. */
    uint16_t start_index;
    uint16_t preamble_count;
    uint8_t frame_type;
    int16_t iq[CONFIG_UWB_CIR_SAMPLES][2]; /* [real, imaginary]. */
} dw1000_cir_t;

/* Sensing task only, after the last RX and any reply TX, before re-enabling RX.
 * Rate-limited; CIR errors must not invalidate an otherwise successful range. */
esp_err_t dw1000_capture_cir(uint16_t peer_address, uint8_t frame_type);
/* Copies the latest complete snapshot; safe from the HTTP task, no SPI access. */
bool dw1000_get_cir(dw1000_cir_t *snapshot);
#endif

uint64_t dw1000_future_tx_time(uint32_t delay_us);
uint64_t dw1000_expected_tx_time(uint64_t delayed_time);
uint64_t dw1000_timestamp_diff(uint64_t newer, uint64_t older);
double dw1000_ticks_to_meters(double ticks);
