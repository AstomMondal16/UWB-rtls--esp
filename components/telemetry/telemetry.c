#include "telemetry.h"

#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/time.h>
#include <unistd.h>
#include "esp_check.h"
#include "esp_http_server.h"
#include "esp_https_server.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "lwip/inet.h"
#include "lwip/sockets.h"
#include "sdkconfig.h"
#include "dw1000.h"
#include "device_identity.h"

#define DISCOVERY_PORT 4210
#define TELEMETRY_MAX_PEERS 8

typedef struct {
    telemetry_sample_t latest;
    uint32_t sequence;
    uint32_t dropped;
    bool valid;
    bool streaming;
    uint32_t sensing_interval_ms;
    uint32_t send_interval_ms;
    uint32_t antenna_delay;
    char role[8];
    bool imu_enabled;
    bool imu_valid;
    uint32_t imu_sequence;
    uint64_t imu_timestamp_ms;
    float imu_accel[3];
    float imu_gyro[3];
    float imu_temperature_c;
    bool battery_valid;
    float battery_voltage;
    float battery_percent;
    telemetry_sample_t peer_latest[TELEMETRY_MAX_PEERS];
    uint8_t peer_count;
} shared_state_t;

typedef struct {
    bool streaming;
    uint32_t sensing_interval_ms;
    uint32_t send_interval_ms;
    uint32_t antenna_delay;
    char role[8];
} control_values_t;

static const char *TAG = "telemetry";
static QueueHandle_t samples;
static TaskHandle_t collector_task_handle;
static httpd_handle_t api_server;
static portMUX_TYPE state_lock = portMUX_INITIALIZER_UNLOCKED;
static shared_state_t state = {.streaming = true};
static char device_mac[18];
static char device_ip[16];
static bool clock_synchronized;
static bool use_phone_clock;
static SemaphoreHandle_t clock_ready_signal;
static telemetry_control_callback_t control_callback;


static BaseType_t network_core(void)
{
    return portNUM_PROCESSORS > 1 ? 0 : tskNO_AFFINITY;
}

static void collector_task(void *argument)
{
    (void)argument;
    telemetry_sample_t sample;
    for (;;) {
        if (xQueueReceive(samples, &sample, portMAX_DELAY) == pdTRUE) {
            portENTER_CRITICAL(&state_lock);
            unsigned slot = state.peer_count;
            for (unsigned i = 0; i < state.peer_count; ++i) {
                if (!memcmp(state.peer_latest[i].peer_id, sample.peer_id, 6)) {
                    slot = i;
                    break;
                }
            }
            if (slot == state.peer_count) {
                if (state.peer_count < TELEMETRY_MAX_PEERS) {
                    state.peer_count++;
                } else {
                    slot = 0;
                    for (unsigned i = 1; i < TELEMETRY_MAX_PEERS; ++i)
                        if (state.peer_latest[i].timestamp_ms <
                            state.peer_latest[slot].timestamp_ms)
                            slot = i;
                }
            }
            state.peer_latest[slot] = sample;
            state.latest = sample;
            state.sequence++;
            state.valid = true;
            portEXIT_CRITICAL(&state_lock);
        }
    }
}

bool telemetry_publish(const telemetry_sample_t *sample)
{
    if (!sample || !samples) return false;
    portENTER_CRITICAL(&state_lock);
    bool streaming = state.streaming;
    portEXIT_CRITICAL(&state_lock);
    if (!streaming) return true;
    if (xQueueSend(samples, sample, 0) == pdTRUE) return true;

    telemetry_sample_t discarded;
    xQueueReceive(samples, &discarded, 0);
    bool queued = xQueueSend(samples, sample, 0) == pdTRUE;
    portENTER_CRITICAL(&state_lock);
    state.dropped++;
    portEXIT_CRITICAL(&state_lock);
    return queued;
}

void telemetry_update_imu(bool valid, const float accel[3],
                          const float gyro[3], float temperature_c)
{
    uint64_t timestamp_ms = esp_timer_get_time() / 1000;
    portENTER_CRITICAL(&state_lock);
    state.imu_valid = valid;
    state.imu_sequence++;
    state.imu_timestamp_ms = timestamp_ms;
    if (valid && accel && gyro) {
        memcpy(state.imu_accel, accel, sizeof(state.imu_accel));
        memcpy(state.imu_gyro, gyro, sizeof(state.imu_gyro));
        state.imu_temperature_c = temperature_c;
    } else {
        memset(state.imu_accel, 0, sizeof(state.imu_accel));
        memset(state.imu_gyro, 0, sizeof(state.imu_gyro));
        state.imu_temperature_c = 0;
    }
    portEXIT_CRITICAL(&state_lock);
}

void telemetry_update_battery(float voltage, float percent)
{
    portENTER_CRITICAL(&state_lock);
    state.battery_valid = true;
    state.battery_voltage = voltage;
    state.battery_percent = percent;
    portEXIT_CRITICAL(&state_lock);
}

bool telemetry_read_snapshot(telemetry_sample_t *sample, char role[8])
{
    if (!sample || !role) return false;
    portENTER_CRITICAL(&state_lock);
    *sample = state.latest;
    sample->imu_valid = state.imu_valid;
    memcpy(sample->accel, state.imu_accel, sizeof(sample->accel));
    memcpy(sample->gyro, state.imu_gyro, sizeof(sample->gyro));
    sample->temperature_c = state.imu_temperature_c;
    sample->battery_valid = state.battery_valid;
    sample->battery_voltage = state.battery_voltage;
    sample->battery_percent = state.battery_percent;
    strlcpy(role, state.role, 8);
    bool valid = state.valid;
    portEXIT_CRITICAL(&state_lock);
    return valid;
}

int64_t telemetry_clock_unix_ms(void)
{
    portENTER_CRITICAL(&state_lock);
    bool ready = clock_synchronized;
    portEXIT_CRITICAL(&state_lock);
    struct timeval now;
    if (!ready || gettimeofday(&now, NULL) != 0 || now.tv_sec < 1577836800 || now.tv_sec >= 4102444800LL) return 0;
    return (int64_t)now.tv_sec * 1000 + now.tv_usec / 1000;
}

bool telemetry_wait_for_clock(uint32_t timeout_ms)
{
    if (telemetry_clock_unix_ms() > 0) return true;
    if (!clock_ready_signal) return false;
    xSemaphoreTake(clock_ready_signal, pdMS_TO_TICKS(timeout_ms));
    return telemetry_clock_unix_ms() > 0;
}

/* A single bounded decimal UTC epoch value; no extra keys or trailing content. */
static bool parse_clock_update(const char *body, int64_t *epoch_ms)
{
    const char *p = body + strspn(body, " \t\r\n");
    if (*p++ != '{') return false;
    p += strspn(p, " \t\r\n");
    if (strncmp(p, "\"unix_ms\"", 9)) return false;
    p += 9;
    p += strspn(p, " \t\r\n");
    if (*p++ != ':') return false;
    p += strspn(p, " \t\r\n");
    if (*p < '1' || *p > '9') return false;
    errno = 0;
    char *end;
    unsigned long long value = strtoull(p, &end, 10);
    if (errno || value < 1577836800000ULL || value >= 4102444800000ULL) return false;
    p = end + strspn(end, " \t\r\n");
    if (*p++ != '}' || p[strspn(p, " \t\r\n")]) return false;
    *epoch_ms = (int64_t)value;
    return true;
}

static esp_err_t clock_handler(httpd_req_t *request)
{
    if (!use_phone_clock)
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "clock is synchronized by API/SNTP");
    char content_type[64], body[64];
    if (httpd_req_get_hdr_value_str(request, "Content-Type", content_type, sizeof(content_type)) != ESP_OK ||
        strncasecmp(content_type, "application/json", 16) || (content_type[16] && content_type[16] != ';') ||
        request->content_len <= 0 || request->content_len >= sizeof(body))
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "invalid clock request");
    size_t used = 0;
    while (used < request->content_len) {
        int count = httpd_req_recv(request, body + used, request->content_len - used);
        if (count <= 0) return ESP_FAIL;
        used += (size_t)count;
    }
    body[used] = '\0';
    int64_t ms;
    if (strlen(body) != used || !parse_clock_update(body, &ms))
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "invalid UTC unix_ms");
    struct timeval utc = {.tv_sec = ms / 1000, .tv_usec = (ms % 1000) * 1000};
    if (settimeofday(&utc, NULL) != 0)
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "could not set clock");
    portENTER_CRITICAL(&state_lock);
    clock_synchronized = true;
    portEXIT_CRITICAL(&state_lock);
    xSemaphoreGive(clock_ready_signal);
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    return httpd_resp_sendstr(request, "{\"ok\":true}");
}

static esp_err_t send_telemetry_snapshot(httpd_req_t *request, const shared_state_t *snapshot)
{

    char peer[18] = "00:00:00:00:00:00";
    if (snapshot->valid) {
        snprintf(peer, sizeof(peer), "%02X:%02X:%02X:%02X:%02X:%02X",
                 snapshot->latest.peer_id[0], snapshot->latest.peer_id[1],
                 snapshot->latest.peer_id[2], snapshot->latest.peer_id[3],
                 snapshot->latest.peer_id[4], snapshot->latest.peer_id[5]);
    }
    uint64_t age_ms = snapshot->valid
        ? (uint64_t)(esp_timer_get_time() / 1000) - snapshot->latest.timestamp_ms
        : 0;
    char rx_power[24] = "null";
    if (isfinite(snapshot->latest.rx_power_dbm))
        snprintf(rx_power, sizeof(rx_power), "%.2f", snapshot->latest.rx_power_dbm);
#ifdef CONFIG_UWB_TIME_SOURCE_API
    const char *configured_clock_source = "api";
#else
    const char *configured_clock_source = "phone";
#endif
    const char *clock_source = use_phone_clock ? "phone" : "api";
    char json[2600];
    int length = snprintf(
        json, sizeof(json),
        "{\"configured_clock_source\":\"%s\",\"clock_source\":\"%s\",\"device_time_unix_ms\":%" PRId64 ",\"valid\":%s,\"streaming\":%s,\"sequence\":%" PRIu32
        ",\"dropped\":%" PRIu32 ",\"timestamp_ms\":%" PRIu64
        ",\"age_ms\":%" PRIu64 ",\"mac\":\"%s\",\"ip\":\"%s\""
        ",\"role\":\"%s\",\"peer\":\"%s\",\"distance_m\":%.4f"
        ",\"speed_mps\":%.4f,\"rx_power_dbm\":%s,\"imu_enabled\":%s"
        ",\"imu_valid\":%s,\"imu_sequence\":%" PRIu32
        ",\"imu_timestamp_ms\":%" PRIu64
        ",\"accel\":[%.4f,%.4f,%.4f],\"gyro\":[%.4f,%.4f,%.4f]"
        ",\"temperature_c\":%.2f,\"battery_valid\":%s"
        ",\"sensing_interval_ms\":%" PRIu32
        ",\"send_interval_ms\":%" PRIu32
        ",\"antenna_delay\":%" PRIu32
        ",\"battery_voltage\":%.3f,\"battery_percent\":%.1f",
        configured_clock_source, clock_source, telemetry_clock_unix_ms(), snapshot->valid ? "true" : "false",
        snapshot->streaming ? "true" : "false", snapshot->sequence,
        snapshot->dropped, snapshot->latest.timestamp_ms, age_ms, device_mac,
        device_ip, snapshot->role, peer, snapshot->latest.distance_m,
        snapshot->latest.speed_mps, rx_power,
        snapshot->imu_enabled ? "true" : "false",
        snapshot->imu_valid ? "true" : "false", snapshot->imu_sequence,
        snapshot->imu_timestamp_ms,
        snapshot->imu_accel[0], snapshot->imu_accel[1],
        snapshot->imu_accel[2], snapshot->imu_gyro[0],
        snapshot->imu_gyro[1], snapshot->imu_gyro[2],
        snapshot->imu_temperature_c,
        snapshot->battery_valid ? "true" : "false",
        snapshot->sensing_interval_ms, snapshot->send_interval_ms,
        snapshot->antenna_delay,
        snapshot->battery_voltage, snapshot->battery_percent);
    if (length < 0 || (size_t)length >= sizeof(json))
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR,
                                   "telemetry encoding failed");
    size_t used = (size_t)length;
    length = snprintf(json + used, sizeof(json) - used, ",\"ranges\":[");
    if (length < 0 || (size_t)length >= sizeof(json) - used)
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR,
                                   "telemetry encoding failed");
    used += (size_t)length;
    uint64_t now = esp_timer_get_time() / 1000;
    for (unsigned i = 0; i < snapshot->peer_count; ++i) {
        const telemetry_sample_t *peer_sample = &snapshot->peer_latest[i];
        char peer_id[18];
        snprintf(peer_id, sizeof(peer_id), "%02X:%02X:%02X:%02X:%02X:%02X",
                 peer_sample->peer_id[0], peer_sample->peer_id[1],
                 peer_sample->peer_id[2], peer_sample->peer_id[3],
                 peer_sample->peer_id[4], peer_sample->peer_id[5]);
        uint64_t peer_age = now - peer_sample->timestamp_ms;
        strcpy(rx_power, "null");
        if (isfinite(peer_sample->rx_power_dbm))
            snprintf(rx_power, sizeof(rx_power), "%.2f", peer_sample->rx_power_dbm);
        length = snprintf(
            json + used, sizeof(json) - used,
            "%s{\"peer\":\"%s\",\"distance_m\":%.4f,"
            "\"speed_mps\":%.4f,\"rx_power_dbm\":%s,"
            "\"timestamp_ms\":%" PRIu64 ",\"age_ms\":%" PRIu64 "}",
            i ? "," : "", peer_id, peer_sample->distance_m,
            peer_sample->speed_mps, rx_power,
            peer_sample->timestamp_ms, peer_age);
        if (length < 0 || (size_t)length >= sizeof(json) - used)
            return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR,
                                       "telemetry encoding failed");
        used += (size_t)length;
    }
    length = snprintf(json + used, sizeof(json) - used, "]}");
    if (length < 0 || (size_t)length >= sizeof(json) - used)
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR,
                                   "telemetry encoding failed");
    used += (size_t)length;
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    char *raw = telemetry_encode_uwb_raw_json(&snapshot->latest.uwb_raw, false);
    if (!raw) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "UWB encoding failed");
    char *cir = telemetry_copy_cir_json(NULL);
    if (!cir) {
        free(raw);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "CIR encoding failed");
    }
    const size_t capacity = used + strlen(raw) + strlen(cir) + 96;
    char *combined = malloc(capacity);
    if (!combined) {
        free(raw);
        free(cir);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "no telemetry buffer");
    }
    int encoded = snprintf(combined, capacity, "%.*s,\"tx_power\":\"0x%08" PRIX32 "\",\"uwb_raw\":%s,\"cir\":%s}",
                           (int)used - 1, json, DW1000_TX_POWER_SETTING, raw, cir);
    free(raw);
    free(cir);
    esp_err_t err = encoded < 0 || (size_t)encoded >= capacity
        ? httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "telemetry encoding failed")
        : httpd_resp_send(request, combined, encoded);
    free(combined);
    return err;
}

static esp_err_t telemetry_handler(httpd_req_t *request)
{
    // Raw exchange bytes enlarge the history; keep the HTTP stack bounded.
    shared_state_t *snapshot = malloc(sizeof(*snapshot));
    if (!snapshot) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "no snapshot buffer");
    portENTER_CRITICAL(&state_lock);
    *snapshot = state;
    portEXIT_CRITICAL(&state_lock);
    esp_err_t err = send_telemetry_snapshot(request, snapshot);
    free(snapshot);
    return err;
}

/* This endpoint accepts only a flat object of these five literal keys, with
 * boolean, unsigned decimal integer, or role values. No nesting or escapes.
 * Consume the entire body; reject duplicates and unknown fields. */
static bool parse_control(const char *body, control_values_t *updated)
{
    static const char *const names[] = {
        "\"streaming\"", "\"sensing_interval_ms\"", "\"send_interval_ms\"",
        "\"antenna_delay\"", "\"role\"",
    };
    const char *p = body + strspn(body, " \t\r\n");
    if (*p != '{') return false;
    ++p;
    unsigned seen = 0;
    for (;;) {
        p += strspn(p, " \t\r\n");
        unsigned field;
        for (field = 0; field < 5; ++field)
            if (!strncmp(p, names[field], strlen(names[field]))) break;
        if (field == 5 || (seen & (1u << field))) return false;
        seen |= 1u << field;
        p += strlen(names[field]);
        p += strspn(p, " \t\r\n");
        if (*p != ':') return false;
        ++p;
        p += strspn(p, " \t\r\n");
        if (field == 0) {
            if (!strncmp(p, "true", 4)) {
                updated->streaming = true;
                p += 4;
            } else if (!strncmp(p, "false", 5)) {
                updated->streaming = false;
                p += 5;
            } else return false;
        } else if (field == 4) {
            if (!strncmp(p, "\"tag\"", 5)) {
                strcpy(updated->role, "tag");
                p += 5;
            } else if (!strncmp(p, "\"anchor\"", 8)) {
                strcpy(updated->role, "anchor");
                p += 8;
            } else return false;
        } else {
            if (*p < '0' || *p > '9' ||
                (*p == '0' && p[1] >= '0' && p[1] <= '9')) return false;
            errno = 0;
            char *end;
            unsigned long value = strtoul(p, &end, 10);
            if (errno == ERANGE || value > UINT32_MAX) return false;
            if (field == 1) updated->sensing_interval_ms = value;
            if (field == 2) updated->send_interval_ms = value;
            if (field == 3) updated->antenna_delay = value;
            p = end;
        }
        p += strspn(p, " \t\r\n");
        if (*p == '}') {
            ++p;
            p += strspn(p, " \t\r\n");
            return *p == '\0' && updated->sensing_interval_ms >= 20 &&
                   updated->sensing_interval_ms <= 60000 &&
                   updated->send_interval_ms >= 50 && updated->send_interval_ms <= 60000 &&
                   updated->antenna_delay <= UINT16_MAX;
        }
        if (*p != ',') return false;
        ++p;
    }
}

static esp_err_t control_handler(httpd_req_t *request)
{
    /* Desktop clients already use JSON. Reject browser form/text requests;
     * this blocks simple cross-origin POSTs but is not authentication. */
    char content_type[64];
    if (httpd_req_get_hdr_value_str(request, "Content-Type", content_type,
                                    sizeof(content_type)) != ESP_OK ||
        strncasecmp(content_type, "application/json", 16) ||
        (content_type[16] && content_type[16] != ';'))
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "Content-Type must be application/json");
    if (request->content_len <= 0 || request->content_len > 192)
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST,
                                   "invalid control body");
    char body[193];
    size_t received = 0;
    while (received < request->content_len) {
        int count = httpd_req_recv(request, body + received,
                                   request->content_len - received);
        if (count <= 0) return ESP_FAIL;
        received += count;
    }
    body[received] = '\0';
    if (strlen(body) != received)
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST, "invalid control body");
    control_values_t updated;
    portENTER_CRITICAL(&state_lock);
    updated.streaming = state.streaming;
    updated.sensing_interval_ms = state.sensing_interval_ms;
    updated.send_interval_ms = state.send_interval_ms;
    updated.antenna_delay = state.antenna_delay;
    memcpy(updated.role, state.role, sizeof(updated.role));
    portEXIT_CRITICAL(&state_lock);
    if (!parse_control(body, &updated))
        return httpd_resp_send_err(request, HTTPD_400_BAD_REQUEST,
                                   "invalid control fields or values");
    portENTER_CRITICAL(&state_lock);
    if (strcmp(state.role, updated.role)) state.peer_count = 0;
    state.streaming = updated.streaming;
    state.sensing_interval_ms = updated.sensing_interval_ms;
    state.send_interval_ms = updated.send_interval_ms;
    state.antenna_delay = updated.antenna_delay;
    strlcpy(state.role, updated.role, sizeof(state.role));
    portEXIT_CRITICAL(&state_lock);
    if (control_callback)
        control_callback(updated.streaming, updated.sensing_interval_ms,
                         updated.send_interval_ms,
                         (uint16_t)updated.antenna_delay, updated.role);
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    char response[224];
    snprintf(response, sizeof(response),
             "{\"ok\":true,\"streaming\":%s,\"sensing_interval_ms\":%" PRIu32
             ",\"send_interval_ms\":%" PRIu32
             ",\"antenna_delay\":%" PRIu32 ",\"role\":\"%s\"}",
             updated.streaming ? "true" : "false",
             updated.sensing_interval_ms, updated.send_interval_ms,
             updated.antenna_delay, updated.role);
    return httpd_resp_sendstr(request, response);
}

char *telemetry_copy_cir_json(bool *capture_valid)
{
    if (capture_valid) *capture_valid = false;
#ifndef CONFIG_UWB_CIR_ENABLE
    return strdup("{\"enabled\":false,\"valid\":false}");
#else
    /* A full CIR is larger than the HTTP task's spare stack. Network sends
     * use a private copy and never hold the radio task's snapshot lock. */
    dw1000_cir_t *snapshot = malloc(sizeof(*snapshot));
    if (!snapshot)
        return NULL;
    if (!dw1000_get_cir(snapshot)) {
        free(snapshot);
        return strdup("{\"enabled\":true,\"valid\":false}");
    }
    /* Encode on CPU0 and send one bounded response, instead of many tiny
     * chunked socket writes. On dual-core chips the radio's CPU1 never
     * formats or sends JSON; single-core chips use the same separate tasks. */
    const size_t capacity = 512 + 16 * CONFIG_UWB_CIR_SAMPLES + 3;
    char *json = malloc(capacity);
    if (!json) {
        free(snapshot);
        return NULL;
    }
    int length = snprintf(json, capacity,
        "{\"enabled\":true,\"valid\":true,\"sequence\":%" PRIu32
        ",\"timestamp_ms\":%" PRIu64 ",\"age_ms\":%" PRIu64
        ",\"rx_timestamp\":%" PRIu64 ",\"peer_address\":%u,\"frame_type\":%u"
        ",\"first_path_index\":%u,\"start_index\":%u,\"sample_count\":%u"
        ",\"preamble_count\":%u,\"capture_us\":%" PRIu32
        ",\"capture_interval_ms\":%u,\"sample_period_ns\":1.001602564,\"iq\":[",
        snapshot->sequence, snapshot->timestamp_ms,
        (uint64_t)(esp_timer_get_time() / 1000) - snapshot->timestamp_ms,
        snapshot->rx_timestamp, snapshot->peer_address, snapshot->frame_type,
        snapshot->first_path_index, snapshot->start_index,
        (unsigned)CONFIG_UWB_CIR_SAMPLES, snapshot->preamble_count,
        snapshot->capture_us, (unsigned)CONFIG_UWB_CIR_INTERVAL_MS);
    if (length < 0 || (size_t)length >= capacity) {
        free(json);
        free(snapshot);
        return NULL;
    }
    size_t used = length;
    for (unsigned i = 0; i < CONFIG_UWB_CIR_SAMPLES; ++i) {
        length = snprintf(json + used, capacity - used, "%s[%d,%d]",
                          i ? "," : "", snapshot->iq[i][0], snapshot->iq[i][1]);
        if (length < 0 || (size_t)length >= capacity - used) {
            free(json);
            free(snapshot);
            return NULL;
        }
        used += length;
    }
    free(snapshot);
    /* capacity includes the closing delimiters and NUL at worst-case width. */
    if (capacity - used < 3) {
        free(json);
        return NULL;
    }
    memcpy(json + used, "]}", 3);
    if (capture_valid) *capture_valid = true;
    return json;
#endif
}

static esp_err_t cir_handler(httpd_req_t *request)
{
    char *json = telemetry_copy_cir_json(NULL);
    if (!json) return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "CIR encoding failed");
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
    esp_err_t err = httpd_resp_send(request, json, HTTPD_RESP_USE_STRLEN);
    free(json);
    return err;
}

static void discovery_task(void *argument)
{
    (void)argument;
    int socket_fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_IP);
    if (socket_fd < 0) {
        ESP_LOGE(TAG, "MAC discovery socket failed");
        vTaskDelete(NULL);
        return;
    }
    int reuse = 1;
    setsockopt(socket_fd, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));
    struct sockaddr_in address = {
        .sin_family = AF_INET,
        .sin_port = htons(DISCOVERY_PORT),
        .sin_addr.s_addr = htonl(INADDR_ANY),
    };
    if (bind(socket_fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        ESP_LOGE(TAG, "MAC discovery bind failed");
        close(socket_fd);
        vTaskDelete(NULL);
        return;
    }

    char request[32];
    for (;;) {
        struct sockaddr_in source;
        socklen_t source_length = sizeof(source);
        int length = recvfrom(socket_fd, request, sizeof(request) - 1, 0,
                              (struct sockaddr *)&source, &source_length);
        if (length <= 0) continue;
        request[length] = '\0';
        if (strcmp(request, "UWB_DISCOVER")) continue;
        char role[8];
        portENTER_CRITICAL(&state_lock);
        strlcpy(role, state.role, sizeof(role));
        portEXIT_CRITICAL(&state_lock);
        char response[128];
        int response_length = snprintf(
            response, sizeof(response),
            "{\"mac\":\"%s\",\"ip\":\"%s\",\"role\":\"%s\",\"api_scheme\":\"%s\"}",
            device_mac, device_ip, role,
#ifdef CONFIG_UWB_TLS_ENABLE
            "https"
#else
            "http"
#endif
        );
        sendto(socket_fd, response, response_length, 0,
               (struct sockaddr *)&source, source_length);
    }
}

esp_err_t telemetry_start(const telemetry_config_t *config)
{
    if (!config || !config->ip_address || !config->role ||
        config->buffer_length < 2 || config->buffer_length > 256 || config->sensing_interval_ms < 20 ||
        config->send_interval_ms < 50)
        return ESP_ERR_INVALID_ARG;
    if (samples || collector_task_handle || api_server || clock_ready_signal)
        return ESP_ERR_INVALID_STATE;
    snprintf(device_mac, sizeof(device_mac), "%02X:%02X:%02X:%02X:%02X:%02X",
             config->device_id[0], config->device_id[1], config->device_id[2],
             config->device_id[3], config->device_id[4], config->device_id[5]);
    strlcpy(device_ip, config->ip_address, sizeof(device_ip));
    strlcpy(state.role, config->role, sizeof(state.role));
#ifdef CONFIG_UWB_MPU6050_ENABLE
    state.imu_enabled = true;
#else
    state.imu_enabled = false;
#endif
    state.sensing_interval_ms = config->sensing_interval_ms;
    state.send_interval_ms = config->send_interval_ms;
    state.antenna_delay = config->antenna_delay;
    control_callback = config->on_control;
    clock_synchronized = config->clock_synchronized;
    use_phone_clock = !config->clock_synchronized;
    clock_ready_signal = xSemaphoreCreateBinary();
    if (!clock_ready_signal) return ESP_ERR_NO_MEM;
    esp_err_t err = ESP_ERR_NO_MEM;
    samples = xQueueCreate(config->buffer_length, sizeof(telemetry_sample_t));
    if (!samples) goto fail;

    if (xTaskCreatePinnedToCore(collector_task, "telemetry_buffer", 4096, NULL,
                                4, &collector_task_handle, network_core()) != pdPASS) {
        collector_task_handle = NULL;
        goto fail;
    }

    ESP_LOGI(TAG, "sample queue: %u slots, %u bytes", (unsigned)config->buffer_length,
             (unsigned)(config->buffer_length * sizeof(telemetry_sample_t)));
#ifdef CONFIG_UWB_TLS_ENABLE
    httpd_ssl_config_t https_config = HTTPD_SSL_CONFIG_DEFAULT();
    https_config.httpd.core_id = network_core();
    https_config.httpd.stack_size = 12288;
    https_config.httpd.lru_purge_enable = true;
    https_config.httpd.max_uri_handlers = 4;
    device_identity_t identity;
    err = device_identity_load(device_mac, &identity);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "HTTPS identity unavailable: %s; check identity storage (existing keys are never replaced automatically)", esp_err_to_name(err));
        goto fail;
    }
    https_config.servercert = (const uint8_t *)identity.certificate;
    https_config.servercert_len = strlen(identity.certificate) + 1;
    https_config.prvtkey_pem = (const uint8_t *)identity.private_key;
    https_config.prvtkey_len = strlen(identity.private_key) + 1;
    https_config.transport_mode = HTTPD_SSL_TRANSPORT_SECURE;
    https_config.port_secure = 443;
    esp_log_level_set("esp_https_server", ESP_LOG_WARN);
    err = httpd_ssl_start(&api_server, &https_config);
    device_identity_free(&identity);
#else
    httpd_config_t http_config = HTTPD_DEFAULT_CONFIG();
    http_config.core_id = network_core();
    http_config.stack_size = 8192;
    http_config.lru_purge_enable = true;
    http_config.max_uri_handlers = 4;
    err = httpd_start(&api_server, &http_config);
#endif
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "HTTP server start failed: %s", esp_err_to_name(err));
        goto fail;
    }
    const httpd_uri_t telemetry_uri = {
        .uri = "/api/telemetry",
        .method = HTTP_GET,
        .handler = telemetry_handler,
    };
    const httpd_uri_t control_uri = {
        .uri = "/api/control",
        .method = HTTP_POST,
        .handler = control_handler,
    };
    const httpd_uri_t cir_uri = {
        .uri = "/api/cir",
        .method = HTTP_GET,
        .handler = cir_handler,
    };
    const httpd_uri_t clock_uri = {
        .uri = "/api/time",
        .method = HTTP_POST,
        .handler = clock_handler,
    };
    err = httpd_register_uri_handler(api_server, &telemetry_uri);
    if (err != ESP_OK) goto fail;
    err = httpd_register_uri_handler(api_server, &control_uri);
    if (err != ESP_OK) goto fail;
    err = httpd_register_uri_handler(api_server, &cir_uri);
    if (err == ESP_OK) err = httpd_register_uri_handler(api_server, &clock_uri);
    if (err != ESP_OK) goto fail;
    if (xTaskCreatePinnedToCore(discovery_task, "mac_discovery", 3072, NULL,
                                3, NULL, network_core()) != pdPASS)
        ESP_LOGW(TAG, "MAC discovery task unavailable; connect by IP");

#ifdef CONFIG_UWB_TLS_ENABLE
    ESP_LOGI(TAG, "dashboard API=https://%s MAC=%s cores=%d",
             device_ip, device_mac, portNUM_PROCESSORS);
#else
    ESP_LOGW(TAG, "dashboard API=http://%s (local network only; no TLS)", device_ip);
#endif
    return ESP_OK;

fail:
    ESP_LOGE(TAG, "telemetry service setup failed: %s", esp_err_to_name(err));
    if (api_server) {
#ifdef CONFIG_UWB_TLS_ENABLE
        httpd_ssl_stop(api_server);
#else
        httpd_stop(api_server);
#endif
        api_server = NULL;
    }
    if (collector_task_handle) {
        vTaskDelete(collector_task_handle);
        collector_task_handle = NULL;
    }
    if (samples) {
        vQueueDelete(samples);
        samples = NULL;
    }
    if (clock_ready_signal) {
        vSemaphoreDelete(clock_ready_signal);
        clock_ready_signal = NULL;
    }
    return err;
}
