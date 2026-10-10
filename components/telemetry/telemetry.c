#include "telemetry.h"

#include <errno.h>
#include <inttypes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>
#include "esp_check.h"
#include "esp_http_server.h"
#include "esp_https_server.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"
#include "lwip/inet.h"
#include "lwip/sockets.h"
#include "sdkconfig.h"
#ifndef CONFIG_UWB_DEVICE_SECRET
#define CONFIG_UWB_DEVICE_SECRET ""
#endif
#ifdef CONFIG_UWB_CIR_ENABLE
#include "dw1000.h"
#endif

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
    telemetry_sample_t peer_latest[TELEMETRY_MAX_PEERS];
    uint8_t peer_count;
} shared_state_t;

static const char *TAG = "telemetry";
static QueueHandle_t samples;
static TaskHandle_t collector_task_handle;
static httpd_handle_t api_server;
static portMUX_TYPE state_lock = portMUX_INITIALIZER_UNLOCKED;
static shared_state_t state = {.streaming = true};
static char device_mac[18];
static char device_ip[16];
static telemetry_control_callback_t control_callback;
#ifdef CONFIG_UWB_TLS_ENABLE
extern const uint8_t device_cert_pem_start[] asm("_binary_device_cert_pem_start");
extern const uint8_t device_cert_pem_end[] asm("_binary_device_cert_pem_end");
extern const uint8_t device_key_pem_start[] asm("_binary_device_key_pem_start");
extern const uint8_t device_key_pem_end[] asm("_binary_device_key_pem_end");
#endif

static bool request_authorized(httpd_req_t *request)
{
    const size_t secret_len = strlen(CONFIG_UWB_DEVICE_SECRET);
    const size_t expected_len = sizeof("Bearer ") - 1 + secret_len;
    size_t header_len = httpd_req_get_hdr_value_len(request, "Authorization");
    if (header_len != expected_len || header_len > 80) return false;
    char header[81];
    if (httpd_req_get_hdr_value_str(request, "Authorization", header,
                                    sizeof(header)) != ESP_OK)
        return false;
    if (memcmp(header, "Bearer ", sizeof("Bearer ") - 1)) return false;
    const char *secret = CONFIG_UWB_DEVICE_SECRET;
    unsigned difference = 0;
    for (size_t i = 0; i < secret_len; ++i)
        difference |= (unsigned char)header[sizeof("Bearer ") - 1 + i] ^
                      (unsigned char)secret[i];
    return difference == 0;
}

static esp_err_t require_authorized(httpd_req_t *request)
{
    if (request_authorized(request)) return ESP_OK;
    httpd_resp_set_hdr(request, "WWW-Authenticate", "Bearer");
    return httpd_resp_send_err(request, HTTPD_401_UNAUTHORIZED,
                               "valid device key required");
}

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

static esp_err_t telemetry_handler(httpd_req_t *request)
{
    esp_err_t auth_err = require_authorized(request);
    if (auth_err != ESP_OK) return auth_err;
    shared_state_t snapshot;
    portENTER_CRITICAL(&state_lock);
    snapshot = state;
    portEXIT_CRITICAL(&state_lock);

    char peer[18] = "00:00:00:00:00:00";
    if (snapshot.valid) {
        snprintf(peer, sizeof(peer), "%02X:%02X:%02X:%02X:%02X:%02X",
                 snapshot.latest.peer_id[0], snapshot.latest.peer_id[1],
                 snapshot.latest.peer_id[2], snapshot.latest.peer_id[3],
                 snapshot.latest.peer_id[4], snapshot.latest.peer_id[5]);
    }
    uint64_t age_ms = snapshot.valid
        ? (uint64_t)(esp_timer_get_time() / 1000) - snapshot.latest.timestamp_ms
        : 0;
    char rx_power[24] = "null";
    if (isfinite(snapshot.latest.rx_power_dbm))
        snprintf(rx_power, sizeof(rx_power), "%.2f", snapshot.latest.rx_power_dbm);
    char json[2600];
    int length = snprintf(
        json, sizeof(json),
        "{\"valid\":%s,\"streaming\":%s,\"sequence\":%" PRIu32
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
        snapshot.valid ? "true" : "false",
        snapshot.streaming ? "true" : "false", snapshot.sequence,
        snapshot.dropped, snapshot.latest.timestamp_ms, age_ms, device_mac,
        device_ip, snapshot.role, peer, snapshot.latest.distance_m,
        snapshot.latest.speed_mps, rx_power,
        snapshot.imu_enabled ? "true" : "false",
        snapshot.imu_valid ? "true" : "false", snapshot.imu_sequence,
        snapshot.imu_timestamp_ms,
        snapshot.imu_accel[0], snapshot.imu_accel[1],
        snapshot.imu_accel[2], snapshot.imu_gyro[0],
        snapshot.imu_gyro[1], snapshot.imu_gyro[2],
        snapshot.imu_temperature_c,
        snapshot.latest.battery_valid ? "true" : "false",
        snapshot.sensing_interval_ms, snapshot.send_interval_ms,
        snapshot.antenna_delay,
        snapshot.latest.battery_voltage, snapshot.latest.battery_percent);
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
    for (unsigned i = 0; i < snapshot.peer_count; ++i) {
        const telemetry_sample_t *peer_sample = &snapshot.peer_latest[i];
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
    return httpd_resp_send(request, json, used);
}

/* This endpoint accepts only a flat object of these five literal keys, with
 * boolean, unsigned decimal integer, or role values. No nesting or escapes.
 * Consume the entire body; reject duplicates and unknown fields. */
static bool parse_control(const char *body, shared_state_t *updated)
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
    esp_err_t auth_err = require_authorized(request);
    if (auth_err != ESP_OK) return auth_err;
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
    shared_state_t updated;
    portENTER_CRITICAL(&state_lock);
    updated = state;
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

static esp_err_t cir_handler(httpd_req_t *request)
{
    esp_err_t auth_err = require_authorized(request);
    if (auth_err != ESP_OK) return auth_err;
    httpd_resp_set_type(request, "application/json");
    httpd_resp_set_hdr(request, "Cache-Control", "no-store");
#ifndef CONFIG_UWB_CIR_ENABLE
    return httpd_resp_sendstr(request, "{\"enabled\":false,\"valid\":false}");
#else
    /* A full CIR is larger than the HTTP task's spare stack. Network sends
     * use a private copy and never hold the radio task's snapshot lock. */
    dw1000_cir_t *snapshot = malloc(sizeof(*snapshot));
    if (!snapshot)
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "no CIR buffer");
    if (!dw1000_get_cir(snapshot)) {
        free(snapshot);
        return httpd_resp_sendstr(request, "{\"enabled\":true,\"valid\":false}");
    }
    /* Encode on CPU0 and send one bounded response, instead of many tiny
     * chunked socket writes. On dual-core chips the radio's CPU1 never
     * formats or sends JSON; single-core chips use the same separate tasks. */
    const size_t capacity = 512 + 16 * CONFIG_UWB_CIR_SAMPLES + 3;
    char *json = malloc(capacity);
    if (!json) {
        free(snapshot);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "no CIR JSON buffer");
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
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "CIR encoding failed");
    }
    size_t used = length;
    for (unsigned i = 0; i < CONFIG_UWB_CIR_SAMPLES; ++i) {
        length = snprintf(json + used, capacity - used, "%s[%d,%d]",
                          i ? "," : "", snapshot->iq[i][0], snapshot->iq[i][1]);
        if (length < 0 || (size_t)length >= capacity - used) {
            free(json);
            free(snapshot);
            return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "CIR encoding failed");
        }
        used += length;
    }
    free(snapshot);
    /* capacity includes the closing delimiters and NUL at worst-case width. */
    if (capacity - used < 3) {
        free(json);
        return httpd_resp_send_err(request, HTTPD_500_INTERNAL_SERVER_ERROR, "CIR encoding failed");
    }
    memcpy(json + used, "]}", 3);
    esp_err_t err = httpd_resp_send(request, json, used + 2);
    free(json);
    return err;
#endif
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
            "{\"mac\":\"%s\",\"ip\":\"%s\",\"role\":\"%s\"}",
            device_mac, device_ip, role);
        sendto(socket_fd, response, response_length, 0,
               (struct sockaddr *)&source, source_length);
    }
}

esp_err_t telemetry_start(const telemetry_config_t *config)
{
    if (!config || !config->ip_address || !config->role ||
        config->buffer_length < 2 || config->sensing_interval_ms < 20 ||
        config->send_interval_ms < 50)
        return ESP_ERR_INVALID_ARG;
    if (strlen(CONFIG_UWB_DEVICE_SECRET) != 64 ||
        strspn(CONFIG_UWB_DEVICE_SECRET, "0123456789abcdefABCDEF") != 64) {
        ESP_LOGE(TAG, "configure a unique 64-character hex device secret in menuconfig");
        return ESP_ERR_INVALID_ARG;
    }
    if (samples || collector_task_handle || api_server)
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
    samples = xQueueCreate(config->buffer_length, sizeof(telemetry_sample_t));
    if (!samples) return ESP_ERR_NO_MEM;

    if (xTaskCreatePinnedToCore(collector_task, "telemetry_buffer", 3072, NULL,
                                4, &collector_task_handle, network_core()) != pdPASS) {
        collector_task_handle = NULL;
        vQueueDelete(samples);
        samples = NULL;
        return ESP_ERR_NO_MEM;
    }

    esp_err_t err;
#ifdef CONFIG_UWB_TLS_ENABLE
    httpd_ssl_config_t https_config = HTTPD_SSL_CONFIG_DEFAULT();
    https_config.httpd.core_id = network_core();
    https_config.httpd.stack_size = 12288;
    https_config.httpd.lru_purge_enable = true;
    https_config.httpd.max_uri_handlers = 4;
    https_config.servercert = device_cert_pem_start;
    https_config.servercert_len = device_cert_pem_end - device_cert_pem_start;
    https_config.prvtkey_pem = device_key_pem_start;
    https_config.prvtkey_len = device_key_pem_end - device_key_pem_start;
    https_config.port_secure = 443;
    err = httpd_ssl_start(&api_server, &https_config);
#else
    ESP_LOGE(TAG, "HTTPS is disabled; set CONFIG_UWB_TLS_ENABLE and provide per-device certificate/key");
    err = ESP_ERR_NOT_SUPPORTED;
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
    err = httpd_register_uri_handler(api_server, &telemetry_uri);
    if (err != ESP_OK) goto fail;
    err = httpd_register_uri_handler(api_server, &control_uri);
    if (err != ESP_OK) goto fail;
    err = httpd_register_uri_handler(api_server, &cir_uri);
    if (err != ESP_OK) goto fail;
    if (xTaskCreatePinnedToCore(discovery_task, "mac_discovery", 3072, NULL,
                                3, NULL, network_core()) != pdPASS)
        ESP_LOGW(TAG, "MAC discovery task unavailable; connect by IP");

    ESP_LOGI(TAG, "dashboard API=https://%s MAC=%s cores=%d",
             device_ip, device_mac, portNUM_PROCESSORS);
    return ESP_OK;

fail:
    ESP_LOGE(TAG, "telemetry service setup failed: %s", esp_err_to_name(err));
    if (api_server) {
        httpd_ssl_stop(api_server);
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
    return err;
}
