/* ESP-IDF port of the over-the-air flow used by thotro/DW1000Ranging. */
#include "uwb_ranging.h"

#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <string.h>
#include "dw1000.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "sensor_coordinator.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define FRAME_LEN       98
#define SEQUENCE_OFFSET 86
#define RESERVED_OFFSET 90
#define RESERVED_LEN    8
#define SHORT_MAC_LEN   9
#define LONG_MAC_LEN    15
#define ID_EXTENSION    80
#define INACTIVE_MS     5000
#define MAX_TAG_ANCHORS 8
#define DISCOVERY_SLOTS 8
#define DISCOVERY_BASE_US 5000
#define DISCOVERY_SLOT_US 12000
#define DISCOVERY_WINDOW_MS 120
#define DISCOVERY_INTERVAL_MS 3000

enum {
    MSG_POLL = 0,
    MSG_POLL_ACK = 1,
    MSG_RANGE = 2,
    MSG_RANGE_REPORT = 3,
    MSG_BLINK = 4,
    MSG_RANGING_INIT = 5,
};

typedef struct {
    uint8_t eui[8];
    uint8_t short_address[2];
    uwb_ranging_peer_t peer;
    int64_t last_activity_ms;
    uint32_t last_sequence;
    bool has_sequence;
} tag_anchor_t;

typedef struct {
    uwb_ranging_config_t config;
    uint8_t eui[8];
    uint8_t short_address[2];
    uint8_t sequence;
    bool has_peer;
    uint8_t peer_eui[8];
    uint8_t peer_short[2];
    uint32_t last_sequence;
    bool has_sequence;
    uwb_ranging_peer_t peer;
    int64_t last_activity_ms;
    int64_t last_heartbeat_ms;
    tag_anchor_t anchors[MAX_TAG_ANCHORS];
    unsigned anchor_count;
    unsigned next_anchor;
    int64_t last_discovery_ms;
} ranging_context_t;

static const char *TAG = "uwb_ranging";
static uint32_t tx_sequence;
static uint32_t get_u32(const uint8_t *in);
static void put_u32(uint8_t *out, uint32_t value);
static portMUX_TYPE runtime_lock = portMUX_INITIALIZER_UNLOCKED;
static bool runtime_paused;
static uwb_ranging_role_t runtime_role;
static bool role_initialized;
static uint32_t runtime_period_ms;
static uint16_t runtime_antenna_delay;
static bool antenna_delay_initialized;
static bool antenna_delay_pending;

void uwb_ranging_set_paused(bool paused)
{
    portENTER_CRITICAL(&runtime_lock);
    runtime_paused = paused;
    portEXIT_CRITICAL(&runtime_lock);
}

esp_err_t uwb_ranging_set_role(uwb_ranging_role_t role)
{
    if (role != UWB_RANGING_TAG && role != UWB_RANGING_ANCHOR)
        return ESP_ERR_INVALID_ARG;
    portENTER_CRITICAL(&runtime_lock);
    runtime_role = role;
    role_initialized = true;
    portEXIT_CRITICAL(&runtime_lock);
    return ESP_OK;
}

esp_err_t uwb_ranging_set_period(uint32_t period_ms)
{
    if (period_ms < 20 || period_ms > 60000) return ESP_ERR_INVALID_ARG;
    portENTER_CRITICAL(&runtime_lock);
    runtime_period_ms = period_ms;
    portEXIT_CRITICAL(&runtime_lock);
    return ESP_OK;
}

esp_err_t uwb_ranging_set_antenna_delay(uint16_t antenna_delay)
{
    portENTER_CRITICAL(&runtime_lock);
    runtime_antenna_delay = antenna_delay;
    antenna_delay_initialized = true;
    antenna_delay_pending = true;
    portEXIT_CRITICAL(&runtime_lock);
    return ESP_OK;
}

static void apply_antenna_delay(void)
{
    portENTER_CRITICAL(&runtime_lock);
    bool pending = antenna_delay_pending;
    uint16_t delay = runtime_antenna_delay;
    portEXIT_CRITICAL(&runtime_lock);
    if (!pending) return;
    esp_err_t err = dw1000_set_antenna_delay(delay);
    if (err == ESP_OK) {
        portENTER_CRITICAL(&runtime_lock);
        if (runtime_antenna_delay == delay) antenna_delay_pending = false;
        portEXIT_CRITICAL(&runtime_lock);
    } else ESP_LOGE(TAG, "antenna delay update failed: %s", esp_err_to_name(err));
}

static bool is_paused(void)
{
    portENTER_CRITICAL(&runtime_lock);
    bool paused = runtime_paused;
    portEXIT_CRITICAL(&runtime_lock);
    return paused;
}

static uwb_ranging_role_t current_role(void)
{
    portENTER_CRITICAL(&runtime_lock);
    uwb_ranging_role_t role = runtime_role;
    portEXIT_CRITICAL(&runtime_lock);
    return role;
}

static uint32_t current_period_ms(void)
{
    portENTER_CRITICAL(&runtime_lock);
    uint32_t period_ms = runtime_period_ms;
    portEXIT_CRITICAL(&runtime_lock);
    return period_ms;
}

static bool valid_frame_length(size_t length)
{
    return length == FRAME_LEN;
}

static bool accept_sequence(const uint8_t *frame, uint32_t *last, bool *seen)
{
    uint32_t sequence = get_u32(frame + SEQUENCE_OFFSET);
    if (*seen && (int32_t)(sequence - *last) <= 0) return false;
    *last = sequence;
    *seen = true;
    return true;
}

static esp_err_t transmit_frame(uint8_t frame[FRAME_LEN], uint64_t transmit_at,
                                uint64_t *tx_timestamp)
{
    put_u32(frame + SEQUENCE_OFFSET, ++tx_sequence);
    memset(frame + RESERVED_OFFSET, 0, RESERVED_LEN);
    return dw1000_transmit(frame, FRAME_LEN, transmit_at, tx_timestamp);
}

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static void put_u16(uint8_t *out, uint16_t value)
{
    out[0] = value;
    out[1] = value >> 8;
}

static uint16_t get_u16(const uint8_t *in)
{
    return (uint16_t)in[0] | ((uint16_t)in[1] << 8);
}

static uint32_t get_u32(const uint8_t *in)
{
    return (uint32_t)in[0] | ((uint32_t)in[1] << 8) |
           ((uint32_t)in[2] << 16) | ((uint32_t)in[3] << 24);
}

static void put_u32(uint8_t *out, uint32_t value)
{
    for (unsigned i = 0; i < 4; ++i) out[i] = value >> (8 * i);
}

static void put_u40(uint8_t out[5], uint64_t value)
{
    for (unsigned i = 0; i < 5; ++i) out[i] = value >> (8 * i);
}

static uint64_t get_u40(const uint8_t in[5])
{
    uint64_t value = 0;
    for (unsigned i = 0; i < 5; ++i) value |= (uint64_t)in[i] << (8 * i);
    return value;
}

static void add_id_extension(const ranging_context_t *ctx, uint8_t frame[FRAME_LEN])
{
    memcpy(frame + ID_EXTENSION, ctx->config.device_id, 6);
}

static void make_blink(ranging_context_t *ctx, uint8_t frame[FRAME_LEN])
{
    memset(frame, 0, FRAME_LEN);
    frame[0] = 0xc5;
    frame[1] = ctx->sequence++;
    for (unsigned i = 0; i < 8; ++i) frame[2 + i] = ctx->eui[7 - i];
    frame[10] = ctx->short_address[1];
    frame[11] = ctx->short_address[0];
    add_id_extension(ctx, frame);
}

static void make_short_header(ranging_context_t *ctx, uint8_t frame[FRAME_LEN],
                              const uint8_t destination[2], uint8_t type)
{
    memset(frame, 0, FRAME_LEN);
    frame[0] = 0x41;
    frame[1] = 0x88;
    frame[2] = ctx->sequence++;
    frame[3] = ctx->config.pan_id;
    frame[4] = ctx->config.pan_id >> 8;
    frame[5] = destination[1];
    frame[6] = destination[0];
    frame[7] = ctx->short_address[1];
    frame[8] = ctx->short_address[0];
    frame[SHORT_MAC_LEN] = type;
    add_id_extension(ctx, frame);
}

static void make_ranging_init(ranging_context_t *ctx, uint8_t frame[FRAME_LEN])
{
    memset(frame, 0, FRAME_LEN);
    frame[0] = 0x41;
    frame[1] = 0x8c;
    frame[2] = ctx->sequence++;
    frame[3] = ctx->config.pan_id;
    frame[4] = ctx->config.pan_id >> 8;
    for (unsigned i = 0; i < 8; ++i) frame[5 + i] = ctx->peer_eui[7 - i];
    frame[13] = ctx->short_address[1];
    frame[14] = ctx->short_address[0];
    frame[LONG_MAC_LEN] = MSG_RANGING_INIT;
    add_id_extension(ctx, frame);
}

static int message_type(const uint8_t *frame, size_t length)
{
    if (length >= 12 && frame[0] == 0xc5) return MSG_BLINK;
    if (length > LONG_MAC_LEN && frame[0] == 0x41 && frame[1] == 0x8c)
        return frame[LONG_MAC_LEN] == MSG_RANGING_INIT ? MSG_RANGING_INIT : -1;
    if (length > SHORT_MAC_LEN && frame[0] == 0x41 && frame[1] == 0x88) {
        switch (frame[SHORT_MAC_LEN]) {
        case MSG_POLL:
        case MSG_RANGE:
            return length >= SHORT_MAC_LEN + 2 ? frame[SHORT_MAC_LEN] : -1;
        case MSG_POLL_ACK:
            return MSG_POLL_ACK;
        case MSG_RANGE_REPORT:
            return length >= SHORT_MAC_LEN + 9 ? MSG_RANGE_REPORT : -1;
        }
    }
    return -1;
}

static void decode_blink(const uint8_t *frame, uint8_t eui[8], uint8_t short_address[2])
{
    for (unsigned i = 0; i < 8; ++i) eui[i] = frame[9 - i];
    short_address[0] = frame[11];
    short_address[1] = frame[10];
}

static void decode_short_source(const uint8_t *frame, uint8_t short_address[2])
{
    short_address[0] = frame[8];
    short_address[1] = frame[7];
}

static bool short_for_us(const ranging_context_t *ctx, const uint8_t *frame)
{
    return get_u16(frame + 3) == ctx->config.pan_id &&
           ((frame[5] == 0xff && frame[6] == 0xff) ||
            (frame[5] == ctx->short_address[1] && frame[6] == ctx->short_address[0]));
}

static bool long_for_us(const ranging_context_t *ctx, const uint8_t *frame)
{
    if (get_u16(frame + 3) != ctx->config.pan_id) return false;
    for (unsigned i = 0; i < 8; ++i) {
        if (frame[5 + i] != ctx->eui[7 - i]) return false;
    }
    return true;
}

static bool from_peer(const ranging_context_t *ctx, const uint8_t *frame)
{
    uint8_t source[2];
    decode_short_source(frame, source);
    return !memcmp(source, ctx->peer_short, 2);
}

static void update_peer_id(ranging_context_t *ctx, const uint8_t *frame, size_t length)
{
    if (length >= ID_EXTENSION + 6) {
        memcpy(ctx->peer.device_id, frame + ID_EXTENSION, 6);
        if (!(ctx->peer.device_id[0] | ctx->peer.device_id[1] |
              ctx->peer.device_id[2] | ctx->peer.device_id[3] |
              ctx->peer.device_id[4] | ctx->peer.device_id[5]))
            memcpy(ctx->peer.device_id, ctx->peer_eui, 6);
    }
    ctx->peer.short_address = ((uint16_t)ctx->peer_short[0] << 8) | ctx->peer_short[1];
}

static void peer_active(ranging_context_t *ctx)
{
    ctx->last_activity_ms = now_ms();
}

static void forget_inactive_peer(ranging_context_t *ctx)
{
    if (ctx->has_peer && now_ms() - ctx->last_activity_ms > INACTIVE_MS) {
        if (ctx->config.on_inactive_device) ctx->config.on_inactive_device(&ctx->peer);
        ctx->has_peer = false;
        memset(&ctx->peer, 0, sizeof(ctx->peer));
    }
}

static void heartbeat(ranging_context_t *ctx)
{
    int64_t now = now_ms();
    uint32_t interval_ms = ctx->config.waiting_log_interval_ms;
    if (!interval_ms) interval_ms = 2000;
    if (now - ctx->last_heartbeat_ms >= interval_ms) {
        ctx->last_heartbeat_ms = now;
        if (ctx->config.on_waiting)
            ctx->config.on_waiting(ctx->config.role);
    }
}

static bool role_changed(ranging_context_t *ctx)
{
    if (current_role() == ctx->config.role) return false;
    if (ctx->config.on_inactive_device) {
        if (ctx->config.role == UWB_RANGING_TAG) {
            for (unsigned i = 0; i < ctx->anchor_count; ++i)
                ctx->config.on_inactive_device(&ctx->anchors[i].peer);
        } else if (ctx->has_peer) {
            ctx->config.on_inactive_device(&ctx->peer);
        }
    }
    return true;
}

static int find_our_entry(const ranging_context_t *ctx, const uint8_t *frame,
                          size_t length, size_t stride)
{
    if (length < SHORT_MAC_LEN + 2 || stride < 2) return -1;
    unsigned count = frame[SHORT_MAC_LEN + 1];
    if (count > (length - SHORT_MAC_LEN - 2) / stride) return -1;
    for (unsigned i = 0; i < count; ++i) {
        size_t offset = SHORT_MAC_LEN + 2 + i * stride;
        if (frame[offset] == ctx->short_address[0] &&
            frame[offset + 1] == ctx->short_address[1]) return (int)offset;
    }
    return -1;
}

static double asymmetric_tof(uint64_t poll_tx, uint64_t poll_rx,
                             uint64_t ack_tx, uint64_t ack_rx,
                             uint64_t range_tx, uint64_t range_rx)
{
    double round1 = dw1000_timestamp_diff(ack_rx, poll_tx);
    double reply1 = dw1000_timestamp_diff(ack_tx, poll_rx);
    double round2 = dw1000_timestamp_diff(range_rx, ack_tx);
    double reply2 = dw1000_timestamp_diff(range_tx, ack_rx);
    double denominator = round1 + round2 + reply1 + reply2;
    return denominator ? (round1 * round2 - reply1 * reply2) / denominator : -1;
}

static esp_err_t run_anchor(ranging_context_t *ctx)
{
    uint16_t own_short = ((uint16_t)ctx->short_address[0] << 8) | ctx->short_address[1];
    ESP_LOGI(TAG, "role=ANCHOR ID=%02x:%02x:%02x:%02x:%02x:%02x short=0x%04x",
             ctx->config.device_id[0], ctx->config.device_id[1], ctx->config.device_id[2],
             ctx->config.device_id[3], ctx->config.device_id[4], ctx->config.device_id[5],
             own_short);
    uint8_t frame[FRAME_LEN];

    for (;;) {
        sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);
        if (role_changed(ctx)) return ESP_OK;
        apply_antenna_delay();
        if (is_paused()) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }
        vTaskDelay(1); /* Let CPU0's idle task feed the task watchdog. */
        heartbeat(ctx);
        forget_inactive_peer(ctx);
        size_t length = 0;
        uint64_t poll_rx = 0;
        esp_err_t err = dw1000_receive(frame, sizeof(frame), &length, &poll_rx, 250);
        if (err == ESP_ERR_TIMEOUT) continue;
        if (err != ESP_OK || !valid_frame_length(length)) continue;

        int type = message_type(frame, length);
        if (type == MSG_BLINK) {
            uint8_t previous_eui[8];
            memcpy(previous_eui, ctx->peer_eui, sizeof(previous_eui));
            bool is_new = !ctx->has_peer;
            uint8_t blink_sequence = frame[1];
            decode_blink(frame, ctx->peer_eui, ctx->peer_short);
            is_new |= memcmp(previous_eui, ctx->peer_eui, sizeof(previous_eui)) != 0;
            if (is_new) ctx->has_sequence = false;
            if (!accept_sequence(frame, &ctx->last_sequence,
                                 &ctx->has_sequence)) continue;
            ctx->has_peer = true;
            update_peer_id(ctx, frame, length);
            peer_active(ctx);
            if (is_new && ctx->config.on_new_device) ctx->config.on_new_device(&ctx->peer);
            make_ranging_init(ctx, frame);
            uint32_t identity = ((uint32_t)ctx->short_address[0] << 8) |
                                ctx->short_address[1];
            identity ^= (uint32_t)blink_sequence *
                        (ctx->short_address[1] | 1u);
            identity ^= identity >> ((blink_sequence & 7u) + 1u);
            uint32_t slot = identity % DISCOVERY_SLOTS;
            uint64_t response_at = dw1000_future_tx_time(
                DISCOVERY_BASE_US + slot * DISCOVERY_SLOT_US);
            if (response_at && transmit_frame(frame, response_at, NULL) == ESP_OK) {
#ifdef CONFIG_UWB_CIR_ENABLE
                /* Save CIR from the tag's discovery blink as well as ranging.
                 * Capture after the scheduled reply so SPI reads cannot delay it. */
                esp_err_t cir_err = dw1000_capture_cir(ctx->peer.short_address,
                                                       MSG_BLINK);
                if (cir_err != ESP_OK)
                    ESP_LOGW(TAG, "CIR discovery capture: %s", esp_err_to_name(cir_err));
#endif
            }
            continue;
        }
        if (!ctx->has_peer || type != MSG_POLL || !short_for_us(ctx, frame) || !from_peer(ctx, frame))
            continue;
        if (!accept_sequence(frame, &ctx->last_sequence,
                             &ctx->has_sequence)) continue;

        int entry = find_our_entry(ctx, frame, length, 4);
        if (entry < 0) continue;
        uint16_t reply_us = get_u16(frame + entry + 2);
        if (reply_us < 2000 || reply_us > 20000) continue;
        update_peer_id(ctx, frame, length);
        peer_active(ctx);

        uint64_t ack_at = dw1000_future_tx_time(reply_us);
        uint64_t ack_tx = 0;
        make_short_header(ctx, frame, ctx->peer_short, MSG_POLL_ACK);
        if (!ack_at || transmit_frame(frame, ack_at, &ack_tx) != ESP_OK) continue;

        uint64_t range_rx = 0;
        err = dw1000_receive(frame, sizeof(frame), &length, &range_rx, 150);
        if (err != ESP_OK || !valid_frame_length(length) ||
            message_type(frame, length) != MSG_RANGE ||
            !short_for_us(ctx, frame) || !from_peer(ctx, frame)) continue;
        if (!accept_sequence(frame, &ctx->last_sequence,
                             &ctx->has_sequence)) continue;
        entry = find_our_entry(ctx, frame, length, 17);
        if (entry < 0) continue;

        uint64_t poll_tx = get_u40(frame + entry + 2);
        uint64_t ack_rx = get_u40(frame + entry + 7);
        uint64_t range_tx = get_u40(frame + entry + 12);
        double tof = asymmetric_tof(poll_tx, poll_rx, ack_tx, ack_rx, range_tx, range_rx);
        float distance = (float)dw1000_ticks_to_meters(tof);
        float rx_power = dw1000_receive_power();
        if (!isfinite(distance) || tof < 0 || distance > 1000.0f) continue;

        ctx->peer.range_m = distance;
        ctx->peer.rx_power_dbm = rx_power;
        peer_active(ctx);
        make_short_header(ctx, frame, ctx->peer_short, MSG_RANGE_REPORT);
        memcpy(frame + SHORT_MAC_LEN + 1, &distance, sizeof(distance));
        memcpy(frame + SHORT_MAC_LEN + 5, &rx_power, sizeof(rx_power));
        uint64_t report_at = dw1000_future_tx_time(ctx->config.reply_delay_us);
        if (report_at) transmit_frame(frame, report_at, NULL);
#ifdef CONFIG_UWB_CIR_ENABLE
        /* TX does not overwrite the last RX accumulator. Read after the reply
         * so CIR SPI traffic cannot make this exchange's delayed TX late. */
        esp_err_t cir_err = dw1000_capture_cir(ctx->peer.short_address, MSG_RANGE);
        if (cir_err != ESP_OK) ESP_LOGW(TAG, "CIR capture: %s", esp_err_to_name(cir_err));
#endif
        if (ctx->config.on_range) ctx->config.on_range(&ctx->peer);
    }
}

static int find_tag_anchor(const ranging_context_t *ctx,
                           const uint8_t short_address[2])
{
    for (unsigned i = 0; i < ctx->anchor_count; ++i)
        if (!memcmp(ctx->anchors[i].short_address, short_address, 2))
            return (int)i;
    return -1;
}

static bool remember_tag_anchor(ranging_context_t *ctx, const uint8_t *frame,
                                size_t length)
{
    uint8_t short_address[2] = {frame[14], frame[13]};
    int found = find_tag_anchor(ctx, short_address);
    if (found < 0 && ctx->anchor_count >= MAX_TAG_ANCHORS) return false;
    bool is_new = found < 0;
    unsigned index = is_new ? ctx->anchor_count++ : (unsigned)found;
    tag_anchor_t *anchor = &ctx->anchors[index];
    if (is_new) memset(anchor, 0, sizeof(*anchor));
    if (!accept_sequence(frame, &anchor->last_sequence,
                         &anchor->has_sequence)) return false;
    memcpy(anchor->short_address, short_address, 2);
    memcpy(anchor->eui, frame + ID_EXTENSION,
           length >= ID_EXTENSION + 6 ? 6 : 0);
    if (length >= ID_EXTENSION + 6)
        memcpy(anchor->peer.device_id, frame + ID_EXTENSION, 6);
    else
        memcpy(anchor->peer.device_id, short_address, 2);
    anchor->peer.short_address = ((uint16_t)short_address[0] << 8) |
                                 short_address[1];
    anchor->last_activity_ms = now_ms();
    if (is_new && ctx->config.on_new_device)
        ctx->config.on_new_device(&anchor->peer);
    return true;
}

static void discover_anchors(ranging_context_t *ctx, uint8_t frame[FRAME_LEN])
{
    make_blink(ctx, frame);
    if (transmit_frame(frame, 0, NULL) != ESP_OK) return;
    int64_t deadline = now_ms() + DISCOVERY_WINDOW_MS;
    uint16_t cir_peer_address = 0;
    while (now_ms() < deadline) {
        size_t length = 0;
        uint32_t remaining_ms = (uint32_t)(deadline - now_ms());
        esp_err_t err = dw1000_receive(frame, FRAME_LEN, &length, NULL,
                                      remaining_ms ? remaining_ms : 1);
        if (err == ESP_ERR_TIMEOUT) break;
        if (err != ESP_OK || !valid_frame_length(length) ||
            message_type(frame, length) != MSG_RANGING_INIT ||
            !long_for_us(ctx, frame))
            continue;
        if (!remember_tag_anchor(ctx, frame, length)) continue;
        cir_peer_address = ((uint16_t)frame[14] << 8) | frame[13];
    }
#ifdef CONFIG_UWB_CIR_ENABLE
    /* Capture after discovery so CIR SPI reads cannot make us miss another
     * anchor's response in the same discovery window. */
    if (cir_peer_address) {
        esp_err_t cir_err = dw1000_capture_cir(cir_peer_address, MSG_RANGING_INIT);
        if (cir_err != ESP_OK)
            ESP_LOGW(TAG, "CIR discovery capture: %s", esp_err_to_name(cir_err));
    }
#endif
    ctx->last_discovery_ms = now_ms();
}

static void load_tag_anchor(ranging_context_t *ctx, unsigned index)
{
    tag_anchor_t *anchor = &ctx->anchors[index];
    memcpy(ctx->peer_eui, anchor->eui, sizeof(ctx->peer_eui));
    memcpy(ctx->peer_short, anchor->short_address, sizeof(ctx->peer_short));
    ctx->peer = anchor->peer;
    ctx->last_activity_ms = anchor->last_activity_ms;
    ctx->last_sequence = anchor->last_sequence;
    ctx->has_sequence = anchor->has_sequence;
    ctx->has_peer = true;
}

static void save_tag_anchor(ranging_context_t *ctx, unsigned index)
{
    ctx->anchors[index].peer = ctx->peer;
    ctx->anchors[index].last_activity_ms = ctx->last_activity_ms;
    ctx->anchors[index].last_sequence = ctx->last_sequence;
    ctx->anchors[index].has_sequence = ctx->has_sequence;
}

static void forget_inactive_anchors(ranging_context_t *ctx)
{
    int64_t now = now_ms();
    for (unsigned i = 0; i < ctx->anchor_count;) {
        if (now - ctx->anchors[i].last_activity_ms <= INACTIVE_MS) {
            ++i;
            continue;
        }
        if (ctx->config.on_inactive_device)
            ctx->config.on_inactive_device(&ctx->anchors[i].peer);
        memmove(&ctx->anchors[i], &ctx->anchors[i + 1],
                (ctx->anchor_count - i - 1) * sizeof(ctx->anchors[0]));
        --ctx->anchor_count;
        if (ctx->next_anchor > i) --ctx->next_anchor;
    }
    if (ctx->next_anchor >= ctx->anchor_count) ctx->next_anchor = 0;
}

static esp_err_t range_once(ranging_context_t *ctx, uint8_t frame[FRAME_LEN])
{
    const uint8_t broadcast[2] = {0xff, 0xff};
    make_short_header(ctx, frame, broadcast, MSG_POLL);
    frame[SHORT_MAC_LEN + 1] = 1;
    memcpy(frame + SHORT_MAC_LEN + 2, ctx->peer_short, 2);
    put_u16(frame + SHORT_MAC_LEN + 4, ctx->config.reply_delay_us);
    uint64_t poll_tx = 0;
    ESP_RETURN_ON_ERROR(transmit_frame(frame, 0, &poll_tx), TAG, "POLL transmit");

    size_t length = 0;
    uint64_t ack_rx = 0;
    esp_err_t err = dw1000_receive(frame, FRAME_LEN, &length, &ack_rx, 150);
    if (err != ESP_OK || !valid_frame_length(length) ||
        message_type(frame, length) != MSG_POLL_ACK ||
        !short_for_us(ctx, frame) || !from_peer(ctx, frame)) return ESP_ERR_INVALID_RESPONSE;
    if (!accept_sequence(frame, &ctx->last_sequence,
                         &ctx->has_sequence)) return ESP_ERR_INVALID_RESPONSE;

    uint64_t range_at = dw1000_future_tx_time(ctx->config.reply_delay_us);
    if (!range_at) return ESP_FAIL;
    make_short_header(ctx, frame, broadcast, MSG_RANGE);
    frame[SHORT_MAC_LEN + 1] = 1;
    memcpy(frame + SHORT_MAC_LEN + 2, ctx->peer_short, 2);
    put_u40(frame + SHORT_MAC_LEN + 4, poll_tx);
    put_u40(frame + SHORT_MAC_LEN + 9, ack_rx);
    put_u40(frame + SHORT_MAC_LEN + 14, dw1000_expected_tx_time(range_at));
    ESP_RETURN_ON_ERROR(transmit_frame(frame, range_at, NULL), TAG, "RANGE transmit");

    err = dw1000_receive(frame, FRAME_LEN, &length, NULL, 150);
    if (err != ESP_OK || !valid_frame_length(length) ||
        message_type(frame, length) != MSG_RANGE_REPORT ||
        !short_for_us(ctx, frame) || !from_peer(ctx, frame)) return ESP_ERR_INVALID_RESPONSE;
    if (!accept_sequence(frame, &ctx->last_sequence,
                         &ctx->has_sequence)) return ESP_ERR_INVALID_RESPONSE;
    float distance, rx_power;
    memcpy(&distance, frame + SHORT_MAC_LEN + 1, sizeof(distance));
    memcpy(&rx_power, frame + SHORT_MAC_LEN + 5, sizeof(rx_power));
    if (!isfinite(distance) || distance < 0 || distance > 1000.0f ||
        isinf(rx_power) || (isfinite(rx_power) && (rx_power < -200 || rx_power > 0)))
        return ESP_ERR_INVALID_RESPONSE;
    ctx->peer.range_m = distance;
    ctx->peer.rx_power_dbm = rx_power; /* NaN denotes unavailable diagnostics. */
    update_peer_id(ctx, frame, length);
    peer_active(ctx);
#ifdef CONFIG_UWB_CIR_ENABLE
    esp_err_t cir_err = dw1000_capture_cir(ctx->peer.short_address, MSG_RANGE_REPORT);
    if (cir_err != ESP_OK) ESP_LOGW(TAG, "CIR capture: %s", esp_err_to_name(cir_err));
#endif
    if (ctx->config.on_range) ctx->config.on_range(&ctx->peer);
    return ESP_OK;
}

static esp_err_t run_tag(ranging_context_t *ctx)
{
    uint16_t own_short = ((uint16_t)ctx->short_address[0] << 8) | ctx->short_address[1];
    ESP_LOGI(TAG, "role=TAG ID=%02x:%02x:%02x:%02x:%02x:%02x short=0x%04x",
             ctx->config.device_id[0], ctx->config.device_id[1], ctx->config.device_id[2],
             ctx->config.device_id[3], ctx->config.device_id[4], ctx->config.device_id[5],
             own_short);
    uint8_t frame[FRAME_LEN];
    for (;;) {
        sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);
        if (role_changed(ctx)) return ESP_OK;
        apply_antenna_delay();
        if (is_paused()) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }
        heartbeat(ctx);
        forget_inactive_anchors(ctx);
        if (!ctx->anchor_count ||
            now_ms() - ctx->last_discovery_ms >= DISCOVERY_INTERVAL_MS)
            discover_anchors(ctx, frame);
        if (ctx->anchor_count) {
            unsigned index = ctx->next_anchor++ % ctx->anchor_count;
            load_tag_anchor(ctx, index);
            if (range_once(ctx, frame) == ESP_OK)
                save_tag_anchor(ctx, index);
        }
        vTaskDelay(pdMS_TO_TICKS(current_period_ms()));
    }
}

esp_err_t uwb_ranging_run(const uwb_ranging_config_t *config)
{
    if (!config || !config->reply_delay_us || !config->tag_period_ms) return ESP_ERR_INVALID_ARG;
    sensor_coordinator_register(SENSOR_COORDINATOR_UWB);
    sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);
    portENTER_CRITICAL(&runtime_lock);
    if (!runtime_period_ms) runtime_period_ms = config->tag_period_ms;
    if (!role_initialized) {
        runtime_role = config->role;
        role_initialized = true;
    }
    if (!antenna_delay_initialized) {
        runtime_antenna_delay = config->antenna_delay;
        antenna_delay_initialized = true;
    }
    uint16_t initial_antenna_delay = runtime_antenna_delay;
    portEXIT_CRITICAL(&runtime_lock);
    ranging_context_t ctx = {.config = *config};
    memcpy(ctx.eui, config->device_id, 6);
    ctx.short_address[0] = config->device_id[4];
    ctx.short_address[1] = config->device_id[5];
    uint16_t short_address = ((uint16_t)ctx.short_address[0] << 8) | ctx.short_address[1];
    ESP_RETURN_ON_ERROR(dw1000_init(ctx.eui, config->pan_id, short_address,
                                   initial_antenna_delay),
                        TAG, "DW1000 initialization");
    portENTER_CRITICAL(&runtime_lock);
    if (runtime_antenna_delay == initial_antenna_delay)
        antenna_delay_pending = false;
    portEXIT_CRITICAL(&runtime_lock);
    for (;;) {
        ctx.config.role = current_role();
        ctx.has_peer = false;
        ctx.anchor_count = ctx.next_anchor = 0;
        ctx.last_discovery_ms = 0;
        memset(&ctx.peer, 0, sizeof(ctx.peer));
        ctx.last_activity_ms = ctx.last_heartbeat_ms = now_ms();
        esp_err_t err = ctx.config.role == UWB_RANGING_ANCHOR
                            ? run_anchor(&ctx) : run_tag(&ctx);
        if (err != ESP_OK) {
            esp_err_t deinit_err = dw1000_deinit();
            if (deinit_err != ESP_OK)
                ESP_LOGE(TAG, "DW1000 cleanup failed: %s", esp_err_to_name(deinit_err));
            return err;
        }
    }
}
