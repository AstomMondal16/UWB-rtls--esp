#include "time_sync.h"

#include <ctype.h>
#include <stdbool.h>
#include <errno.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include "esp_crt_bundle.h"
#include "esp_http_client.h"
#include "esp_netif_sntp.h"
#include "esp_log.h"
#include "freertos/task.h"

/* The documented Time.Now response is a flat object of scalar values. */
static const char *json_space(const char *p)
{
    return p + strspn(p, " \t\r\n");
}

static bool json_string(const char **cursor, char *value, size_t capacity)
{
    const char *p = *cursor;
    if (*p++ != '"') return false;
    size_t used = 0;
    while (*p && *p != '"') {
        unsigned char ch = (unsigned char)*p++;
        if (ch < 0x20 || used + 1 >= capacity) return false;
        if (ch == '\\') {
            ch = (unsigned char)*p++;
            if (ch != '/' && ch != '\\' && ch != '"') return false;
        }
        value[used++] = (char)ch;
    }
    if (*p != '"') return false;
    value[used] = '\0';
    *cursor = p + 1;
    return true;
}

static bool json_number(const char **cursor)
{
    const char *p = *cursor;
    if (*p == '-') ++p;
    if (*p == '0') ++p;
    else {
        if (*p < '1' || *p > '9') return false;
        do { ++p; } while (isdigit((unsigned char)*p));
    }
    if (*p == '.') {
        ++p;
        if (!isdigit((unsigned char)*p)) return false;
        do { ++p; } while (isdigit((unsigned char)*p));
    }
    if (*p == 'e' || *p == 'E') {
        ++p;
        if (*p == '+' || *p == '-') ++p;
        if (!isdigit((unsigned char)*p)) return false;
        do { ++p; } while (isdigit((unsigned char)*p));
    }
    *cursor = p;
    return true;
}

static bool parse_api_time(const char *body, struct timeval *result)
{
    const char *p = json_space(body);
    if (*p++ != '{') return false;
    unsigned seen = 0;
    int64_t seconds = 0;
    char utc[48] = "";
    for (;;) {
        char key[48], text[96];
        p = json_space(p);
        if (!json_string(&p, key, sizeof(key))) return false;
        p = json_space(p);
        if (*p++ != ':') return false;
        p = json_space(p);
        bool string = *p == '"';
        const char *start = p;
        if (string) {
            if (!json_string(&p, text, sizeof(text))) return false;
        } else if (!strncmp(p, "null", 4) || !strncmp(p, "true", 4)) p += 4;
        else if (!strncmp(p, "false", 5)) p += 5;
        else if (!json_number(&p)) return false;
        unsigned bit = !strcmp(key, "timezone") ? 1 : !strcmp(key, "unixtime") ? 2 :
                       !strcmp(key, "utc_datetime") ? 4 : !strcmp(key, "utc_offset") ? 8 : 0;
        if (bit && (seen & bit)) return false;
        seen |= bit;
        if (bit == 1 && (!string || strcmp(text, "Asia/Kolkata"))) return false;
        if (bit == 8 && (!string || strcmp(text, "+05:30"))) return false;
        if (bit == 2) {
            errno = 0;
            char *end;
            seconds = strtoll(start, &end, 10);
            if (string || errno || end != p || seconds < 1577836800 || seconds >= 4102444800LL) return false;
        }
        if (bit == 4) {
            if (!string || strlen(text) >= sizeof(utc)) return false;
            memcpy(utc, text, strlen(text) + 1);
        }
        p = json_space(p);
        if (*p == '}') { ++p; break; }
        if (*p++ != ',') return false;
    }
    if (*json_space(p) || seen != 15) return false;
    time_t epoch = (time_t)seconds;
    struct tm date;
    char prefix[24];
    if (!gmtime_r(&epoch, &date) || !strftime(prefix, sizeof(prefix), "%Y-%m-%dT%H:%M:%S", &date) ||
        strncmp(utc, prefix, 19)) return false;
    const char *fraction = utc + 19;
    long micros = 0;
    unsigned digits = 0;
    if (*fraction == '.') {
        ++fraction;
        while (isdigit((unsigned char)*fraction)) {
            if (++digits > 6) return false;
            micros = micros * 10 + (*fraction++ - '0');
        }
        if (!digits) return false;
    }
    if (strcmp(fraction, "Z")) return false;
    while (digits++ < 6) micros *= 10;
    result->tv_sec = epoch;
    result->tv_usec = micros;
    return true;
}

static esp_err_t fetch_api_time(void)
{
    esp_http_client_config_t config = {
        .url = "https://time.now/developer/api/timezone/Asia/Kolkata",
        .timeout_ms = 5000,
        .crt_bundle_attach = esp_crt_bundle_attach,
        .skip_cert_common_name_check = false,
        .disable_auto_redirect = true,
    };
    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (!client) return ESP_ERR_NO_MEM;
    esp_http_client_set_header(client, "Cache-Control", "no-cache");
    esp_err_t err = esp_http_client_open(client, 0);
    if (err != ESP_OK) {
        int tls_error = 0, tls_flags = 0;
        esp_http_client_get_and_clear_last_tls_error(client, &tls_error, &tls_flags);
        ESP_LOGW("clock", "Time API HTTPS connect failed: %s; socket_errno=%d tls_error=0x%x tls_flags=0x%x",
                 esp_err_to_name(err), esp_http_client_get_errno(client), (unsigned)tls_error, (unsigned)tls_flags);
    }
    char *body = NULL;
    if (err == ESP_OK) {
        int64_t length = esp_http_client_fetch_headers(client);
        int status = esp_http_client_get_status_code(client);
        if (length < 0 || length > 2048 || status != 200) {
            ESP_LOGW("clock", "Time API HTTP response invalid: status=%d length=%" PRId64, status, length);
            err = ESP_ERR_INVALID_RESPONSE;
        }
    }
    if (err == ESP_OK) {
        body = malloc(2049);
        if (!body) err = ESP_ERR_NO_MEM;
    }
    if (err == ESP_OK) {
        int length = esp_http_client_read_response(client, body, 2048);
        struct timeval utc;
        if (length <= 0 || length >= 2048 || !esp_http_client_is_complete_data_received(client)) err = ESP_ERR_INVALID_RESPONSE;
        else {
            body[length] = '\0';
            if (memchr(body, '\0', (size_t)length) || !parse_api_time(body, &utc)) {
                ESP_LOGW("clock", "Time API JSON timestamp is missing or invalid");
                err = ESP_ERR_INVALID_RESPONSE;
            }
            else if (settimeofday(&utc, NULL) != 0) err = ESP_FAIL;
        }
    }
    free(body);
    esp_http_client_cleanup(client);
    return err;
}


esp_err_t time_sync_startup(void)
{
    /* A build-date estimate bootstraps certificate dates, never measurement timestamps. */
    if (time(NULL) < UWB_BUILD_UNIX_SECONDS) {
        struct timeval seed = {.tv_sec = UWB_BUILD_UNIX_SECONDS, .tv_usec = 0};
        if (settimeofday(&seed, NULL) != 0) return ESP_FAIL;
    }
    for (unsigned attempt = 1; attempt <= 5; ++attempt) {
        esp_err_t err = fetch_api_time();
        if (err == ESP_OK) {
            ESP_LOGI("clock", "RTC/system clock set from Time.Now");
            return ESP_OK;
        }
        ESP_LOGW("clock", "Time.Now attempt %u/5 failed: %s", attempt, esp_err_to_name(err));
        if (attempt < 5) vTaskDelay(pdMS_TO_TICKS(5000));
    }
    ESP_LOGW("clock", "Time.Now unavailable; trying SNTP fallback");
    esp_sntp_config_t fallback = ESP_NETIF_SNTP_DEFAULT_CONFIG("pool.ntp.org");
    esp_err_t err = esp_netif_sntp_init(&fallback);
    if (err != ESP_OK) return err;
    err = esp_netif_sntp_sync_wait(pdMS_TO_TICKS(10000));
    esp_netif_sntp_deinit();
    return err;
}
