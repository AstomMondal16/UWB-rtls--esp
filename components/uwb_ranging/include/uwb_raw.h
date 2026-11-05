#pragma once

#include <stdbool.h>
#include <stdint.h>

#define UWB_RAW_FRAME_BYTES 98
#define UWB_RAW_FRAME_COUNT 4
#define UWB_RAW_TIMESTAMP_COUNT 8

/* Timestamp order: poll_tx, poll_rx, ack_tx, ack_rx, range_tx, range_rx,
 * report_tx, report_rx. Frame order: POLL, ACK, RANGE, RANGE_REPORT.
 * Frames are the actual application bytes; the DW1000 generates/strips FCS. */
typedef struct {
    bool valid;
    bool source_is_tag;
    uint8_t timestamp_valid_mask;
    uint8_t expected_timestamp_mask;
    uint8_t tx_success_mask;
    uint64_t timestamps[UWB_RAW_TIMESTAMP_COUNT];
    bool tof_valid;
    double tof_ticks;
    float distance_m;
    float peer_rx_power_dbm;
    float local_rx_power_dbm;
    uint8_t rx_diagnostics_mask;
    uint16_t cir_power;
    uint32_t rx_frame_info;
    uint8_t frame_lengths[UWB_RAW_FRAME_COUNT];
    uint8_t frames[UWB_RAW_FRAME_COUNT][UWB_RAW_FRAME_BYTES];
} uwb_raw_t;
