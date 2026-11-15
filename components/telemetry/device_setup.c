#include "device_setup.h"

#include <ctype.h>
#include <errno.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "nvs.h"

static SemaphoreHandle_t mutex;
static device_setup_snapshot_t state;
static uint32_t next_generation;

static bool valid_role(const char *role)
{
    return !strcmp(role, "anchor") || !strcmp(role, "tag");
}

bool device_setup_pending(const device_setup_snapshot_t *s)
{
    return !s->config.configured ||
           (!strcmp(s->config.role, "anchor") && !s->config.coordinates_valid);
}

device_setup_sensor_policy_t device_setup_sensor_policy(const device_setup_snapshot_t *s,
    bool connected, bool clock_ready, bool monitoring)
{
    bool pending = device_setup_pending(s);
    return (device_setup_sensor_policy_t) {
        .running = connected && (s->active || (!pending && clock_ready && monitoring)),
        .sleep_allowed = !pending && !s->active,
    };
}

esp_err_t device_setup_init(const char *default_role)
{
    if (mutex || !valid_role(default_role)) return ESP_ERR_INVALID_STATE;
    mutex = xSemaphoreCreateMutex();
    if (!mutex) return ESP_ERR_NO_MEM;
    state.config.version = 1;
    strcpy(state.config.role, default_role);
    nvs_handle_t storage;
    esp_err_t err = nvs_open("device_setup", NVS_READONLY, &storage);
    if (err == ESP_ERR_NVS_NOT_FOUND) return ESP_OK;
    if (err != ESP_OK) return err;
    device_setup_config_t saved = {0};
    size_t size = sizeof(saved);
    err = nvs_get_blob(storage, "config", &saved, &size);
    nvs_close(storage);
    if (err == ESP_ERR_NVS_NOT_FOUND || err == ESP_ERR_NVS_INVALID_LENGTH) return ESP_OK;
    if (err != ESP_OK) return err;
    if (size == sizeof(saved) && saved.version == 1 &&
        memchr(saved.role, 0, sizeof(saved.role)) && valid_role(saved.role) &&
        isfinite(saved.x) && isfinite(saved.y) && isfinite(saved.z))
        state.config = saved;
    return ESP_OK;
}

void device_setup_get(device_setup_snapshot_t *snapshot)
{
    xSemaphoreTake(mutex, portMAX_DELAY);
    *snapshot = state;
    xSemaphoreGive(mutex);
}

static void end_session(void)
{
    state.active = false;
    state.generation = 0;
    state.peer_count = 0;
}

void device_setup_tick(bool connected)
{
    xSemaphoreTake(mutex, portMAX_DELAY);
    if (state.active && (!connected || esp_timer_get_time() / 1000 >= state.expires_ms))
        end_session();
    xSemaphoreGive(mutex);
}

static const char *space(const char *p) { return p + strspn(p, " \t\r\n"); }

static bool string_value(const char **cursor, char *out, size_t capacity)
{
    const char *p = *cursor;
    if (*p++ != '"') return false;
    size_t n = 0;
    while (*p && *p != '"') {
        if ((unsigned char)*p < 32 || *p == '\\' || n + 1 >= capacity) return false;
        out[n++] = *p++;
    }
    if (*p++ != '"') return false;
    out[n] = 0;
    *cursor = p;
    return true;
}

static bool number_value(const char **cursor, double *out)
{
    const char *start = *cursor, *p = start;
    if (*p == '-') ++p;
    if (*p == '0') ++p;
    else {
        if (*p < '1' || *p > '9') return false;
        while (isdigit((unsigned char)*p)) ++p;
    }
    if (*p == '.') {
        if (!isdigit((unsigned char)*++p)) return false;
        while (isdigit((unsigned char)*p)) ++p;
    }
    if (*p == 'e' || *p == 'E') {
        ++p;
        if (*p == '+' || *p == '-') ++p;
        if (!isdigit((unsigned char)*p)) return false;
        while (isdigit((unsigned char)*p)) ++p;
    }
    errno = 0;
    char *end;
    *out = strtod(start, &end);
    if (errno || end != p || !isfinite(*out)) return false;
    *cursor = p;
    return true;
}

static bool peers_value(const char *text, uint8_t peers[][6], unsigned *count)
{
    *count = 0;
    while (*text) {
        if (*count >= DEVICE_SETUP_MAX_PEERS) return false;
        for (unsigned i = 0; i < 6; ++i) {
            if (!isxdigit((unsigned char)text[0]) || !isxdigit((unsigned char)text[1])) return false;
            char byte[3] = {text[0], text[1], 0};
            peers[*count][i] = (uint8_t)strtoul(byte, NULL, 16);
            text += 2;
            if (i != 5 && *text++ != ':') return false;
        }
        for (unsigned i = 0; i < *count; ++i)
            if (!memcmp(peers[i], peers[*count], 6)) return false;
        ++*count;
        if (!*text) break;
        if (*text++ != ',' || !*text) return false;
    }
    return true;
}

esp_err_t device_setup_execute(const char *body)
{
    char action[12] = "", id[33] = "", role[8] = "", peers_text[144] = "";
    double coordinates[3] = {0};
    const char *names[] = {"action", "id", "role", "peers", "x", "y", "z"};
    const char *p = space(body);
    unsigned seen = 0;
    if (*p++ != '{') return ESP_ERR_INVALID_ARG;
    for (;;) {
        char key[16];
        p = space(p);
        if (!string_value(&p, key, sizeof(key))) return ESP_ERR_INVALID_ARG;
        unsigned field = 0;
        while (field < 7 && strcmp(key, names[field])) ++field;
        if (field == 7 || (seen & (1u << field))) return ESP_ERR_INVALID_ARG;
        seen |= 1u << field;
        p = space(p);
        if (*p++ != ':') return ESP_ERR_INVALID_ARG;
        p = space(p);
        bool ok;
        if (field >= 4) ok = number_value(&p, &coordinates[field - 4]);
        else {
            char *values[] = {action, id, role, peers_text};
            size_t capacities[] = {sizeof(action), sizeof(id), sizeof(role), sizeof(peers_text)};
            ok = string_value(&p, values[field], capacities[field]);
        }
        if (!ok) return ESP_ERR_INVALID_ARG;
        p = space(p);
        if (*p == '}') { ++p; break; }
        if (*p++ != ',') return ESP_ERR_INVALID_ARG;
    }
    if (*space(p)) return ESP_ERR_INVALID_ARG;
    bool commit = !strcmp(action, "commit");
    bool begin = !strcmp(action, "begin");
    bool renew = !strcmp(action, "renew");
    bool end = !strcmp(action, "end");
    uint8_t peers[DEVICE_SETUP_MAX_PEERS][6] = {{0}};
    unsigned peer_count = 0;
    if (commit) {
        if ((seen & ~0x75u) || !(seen & 4) || !valid_role(role) ||
            (!strcmp(role, "anchor") && (seen & 0x30) != 0x30)) return ESP_ERR_INVALID_ARG;
    } else {
        if ((!begin && !renew && !end) || strlen(id) != 32 || strspn(id, "0123456789abcdefABCDEF") != 32)
            return ESP_ERR_INVALID_ARG;
        if (begin) {
            if ((seen & ~0x0fu) || !valid_role(role) || !peers_value(peers_text, peers, &peer_count) ||
                !peer_count) return ESP_ERR_INVALID_ARG;
        } else if (seen != 3) return ESP_ERR_INVALID_ARG;
    }
    esp_err_t err = ESP_OK;
    xSemaphoreTake(mutex, portMAX_DELAY);
    if (state.active && esp_timer_get_time() / 1000 >= state.expires_ms) end_session();
    if (commit) {
        if (state.active) err = ESP_ERR_INVALID_STATE;
        else {
            device_setup_config_t config = {.version = 1, .configured = true,
                .coordinates_valid = (seen & 0x30u) == 0x30u,
                .x = coordinates[0], .y = coordinates[1], .z = coordinates[2]};
            strcpy(config.role, role);
            nvs_handle_t storage;
            err = nvs_open("device_setup", NVS_READWRITE, &storage);
            if (err == ESP_OK) {
                err = nvs_set_blob(storage, "config", &config, sizeof(config));
                if (err == ESP_OK) err = nvs_commit(storage);
                nvs_close(storage);
            }
            if (err == ESP_OK) { state.config = config; ++state.config_revision; }
        }
    } else if (state.active && strcmp(state.id, id)) err = ESP_ERR_INVALID_STATE;
    else if (end) end_session();
    else if (renew && !state.active) err = ESP_ERR_INVALID_STATE;
    else {
        if (begin && (!state.active || strcmp(state.role, role) || state.peer_count != peer_count ||
                      memcmp(state.peers, peers, sizeof(peers)))) {
            state.active = true;
            strcpy(state.id, id);
            strcpy(state.role, role);
            memcpy(state.peers, peers, sizeof(peers));
            state.peer_count = peer_count;
            if (!++next_generation) ++next_generation;
            state.generation = next_generation;
        }
        state.expires_ms = esp_timer_get_time() / 1000 + DEVICE_SETUP_LEASE_MS;
    }
    xSemaphoreGive(mutex);
    return err;
}
