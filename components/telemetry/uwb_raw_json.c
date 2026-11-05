#include "telemetry.h"

#include <inttypes.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

static bool append(char *json, size_t capacity, size_t *used, const char *format, ...)
{
    va_list args;
    va_start(args, format);
    int count = vsnprintf(json + *used, capacity - *used, format, args);
    va_end(args);
    if (count < 0 || (size_t)count >= capacity - *used) return false;
    *used += (size_t)count;
    return true;
}

char *telemetry_encode_uwb_raw_json(const uwb_raw_t *raw, bool missing_as_zero)
{
    if (!raw) return NULL;
    const char *missing = missing_as_zero ? "0" : "null";
    const size_t capacity = 4096;
    char *json = malloc(capacity);
    if (!json) return NULL;
    size_t used = 0;
#define ADD(...) do { if (!append(json, capacity, &used, __VA_ARGS__)) goto fail; } while (0)
    ADD("{\"valid\":%s,\"format_version\":1", raw->valid ? "true" : "false");
    if (!raw->valid) { ADD("}"); return json; }
    ADD(",\"distance_m_unfiltered\":");
    if (isfinite(raw->distance_m)) ADD("%.9g", (double)raw->distance_m); else ADD("%s", missing);
    ADD(",\"peer_rx_power_dbm\":");
    if (isfinite(raw->peer_rx_power_dbm)) ADD("%.9g", (double)raw->peer_rx_power_dbm); else ADD("%s", missing);
    ADD(",\"local_rx_power_dbm\":");
    if (isfinite(raw->local_rx_power_dbm)) ADD("%.9g", (double)raw->local_rx_power_dbm); else ADD("%s", missing);
    ADD(",\"tof_ticks\":");
    if (raw->tof_valid && isfinite(raw->tof_ticks)) ADD("%.17g", raw->tof_ticks); else ADD("%s", missing);
    ADD(",\"timestamp_units\":\"dw1000_ticks\",\"timestamps\":{");
    static const char *const names[] = {"poll_tx", "poll_rx", "ack_tx", "ack_rx",
                                       "range_tx", "range_rx", "report_tx", "report_rx"};
    for (unsigned i = 0; i < UWB_RAW_TIMESTAMP_COUNT; ++i) {
        ADD("%s\"%s\":", i ? "," : "", names[i]);
        if (raw->timestamp_valid_mask & (1u << i)) ADD("%" PRIu64, raw->timestamps[i]); else ADD("%s", missing);
    }
    ADD("},\"timestamp_valid_mask\":%u,\"expected_timestamp_mask\":%u",
        raw->timestamp_valid_mask, raw->expected_timestamp_mask);
    ADD(",\"rx_diagnostics\":{\"cir_power\":");
    if (raw->rx_diagnostics_mask & 1) ADD("%u", raw->cir_power); else ADD("%s", missing);
    ADD(",\"rx_frame_info\":");
    if (raw->rx_diagnostics_mask & 2) ADD("%" PRIu32, raw->rx_frame_info); else ADD("%s", missing);
    ADD(",\"preamble_count\":");
    if (raw->rx_diagnostics_mask & 2) ADD("%u", (unsigned)((raw->rx_frame_info >> 20) & 0xfff)); else ADD("%s", missing);
    ADD("},\"frames\":[");
    static const char *const kinds[] = {"poll", "ack", "range", "range_report"};
    static const char hex[] = "0123456789ABCDEF";
    for (unsigned i = 0; i < UWB_RAW_FRAME_COUNT; ++i) {
        unsigned length = raw->frame_lengths[i];
        if (length > UWB_RAW_FRAME_BYTES) goto fail;
        bool tx = raw->source_is_tag ? (i % 2 == 0) : (i % 2 != 0);
        ADD("%s{\"kind\":\"%s\",\"direction\":\"%s\",\"length\":%u,\"tx_sent\":",
            i ? "," : "", kinds[i], tx ? "tx" : "rx", length);
        ADD("%s,\"hex\":", tx ? ((raw->tx_success_mask & (1u << i)) ? "true" : "false") : missing);
        if (!length && missing_as_zero) ADD("0");
        else {
            ADD("\"");
            for (unsigned b = 0; b < length; ++b) {
                uint8_t byte = raw->frames[i][b];
                ADD("%c%c", hex[byte >> 4], hex[byte & 15]);
            }
            ADD("\"");
        }
        ADD("}");
    }
    ADD("]}");
#undef ADD
    return json;
fail:
    free(json);
    return NULL;
}
