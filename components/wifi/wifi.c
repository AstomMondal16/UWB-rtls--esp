#include "wifi.h"

#include <stdio.h>
#include <string.h>
#include "sdkconfig.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "ble_provisioning.h"
#include "nvs.h"
#include "nvs_flash.h"

#define CONNECTED BIT0
#define RETRY BIT1
#define BUTTON_GPIO GPIO_NUM_17
#define TXN_NS "wifi_txn"
#define TXN_CONFIG "known_good"
#define TXN_PENDING "pending"

static const char *TAG = "wifi";
static EventGroupHandle_t events;
static esp_netif_t *station;
static wifi_info_t *info_out;
static wifi_connection_callback_t connection_cb;
static void *connection_arg;
static bool previous_valid;
static bool provisioning_transaction_pending;
static volatile bool retry_saved_network;
static wifi_config_t previous_config;
static uint8_t saved_ble_octet;
static bool ble_octet_saved;

static void load_ble_octet(void)
{
    nvs_handle_t storage;
    esp_err_t err = nvs_open("wifi_ble", NVS_READONLY, &storage);
    if (err == ESP_OK) {
        err = nvs_get_u8(storage, "last_octet", &saved_ble_octet);
        nvs_close(storage);
    }
    ble_octet_saved = err == ESP_OK;
    if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND)
        ESP_LOGW(TAG, "could not load last BLE IP suffix: %s", esp_err_to_name(err));
}

static void update_ble_octet(uint8_t last_octet)
{
    char name[8];
    ble_provisioning_name_from_ip(last_octet, name);
    esp_err_t err = ble_provisioning_set_service_name(name);
    if (err != ESP_OK)
        ESP_LOGW(TAG, "could not update next BLE name: %s", esp_err_to_name(err));
    else ESP_LOGI(TAG, "next BLE provisioning name: %s", name);
    if (ble_octet_saved && saved_ble_octet == last_octet) return;
    nvs_handle_t storage;
    err = nvs_open("wifi_ble", NVS_READWRITE, &storage);
    if (err == ESP_OK) {
        err = nvs_set_u8(storage, "last_octet", last_octet);
        if (err == ESP_OK) err = nvs_commit(storage);
        nvs_close(storage);
    }
    if (err == ESP_OK) {
        saved_ble_octet = last_octet;
        ble_octet_saved = true;
    } else ESP_LOGW(TAG, "could not save BLE IP suffix: %s", esp_err_to_name(err));
}

void wifi_set_connection_callback(wifi_connection_callback_t callback, void *arg)
{
    connection_cb = callback;
    connection_arg = arg;
}

static void notify_connection(bool connected)
{
    if (connection_cb) connection_cb(connected, connection_arg);
}

static esp_err_t save_transaction(const wifi_config_t *config, bool pending);

static esp_err_t write_station_config(wifi_config_t *config)
{
    esp_err_t err = esp_wifi_set_storage(WIFI_STORAGE_FLASH);
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, config);
    return err;
}

static esp_err_t restore_station_config(const wifi_config_t *config)
{
    if (!config) return ESP_ERR_INVALID_ARG;
    wifi_config_t restored = *config;
    esp_err_t err = esp_wifi_set_storage(WIFI_STORAGE_RAM);
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &restored);
    if (err == ESP_OK) err = esp_wifi_set_storage(WIFI_STORAGE_FLASH);
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &restored);
    if (err == ESP_OK) err = save_transaction(&restored, false);
    return err;
}

static esp_err_t load_menuconfig_credentials(wifi_config_t *config)
{
    if (!config) return ESP_ERR_INVALID_ARG;
    size_t ssid_len = strlen(CONFIG_UWB_WIFI_SSID);
    size_t password_len = strlen(CONFIG_UWB_WIFI_PASSWORD);
    if (ssid_len == 0) return ESP_ERR_NOT_FOUND;
    if (ssid_len > sizeof(config->sta.ssid) ||
        password_len > sizeof(config->sta.password)) {
        ESP_LOGE(TAG, "menuconfig Wi-Fi credentials exceed ESP32 limits");
        return ESP_ERR_INVALID_SIZE;
    }

    memset(config, 0, sizeof(*config));
    memcpy(config->sta.ssid, CONFIG_UWB_WIFI_SSID, ssid_len);
    memcpy(config->sta.password, CONFIG_UWB_WIFI_PASSWORD, password_len);
    config->sta.threshold.authmode = password_len ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;
    return write_station_config(config);
}

static esp_err_t save_transaction(const wifi_config_t *config, bool pending)
{
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(TXN_NS, NVS_READWRITE, &nvs);
    if (err != ESP_OK) return err;
    err = nvs_set_blob(nvs, TXN_CONFIG, config, sizeof(*config));
    if (err == ESP_OK) err = nvs_set_u8(nvs, TXN_PENDING, pending ? 1 : 0);
    if (err == ESP_OK) err = nvs_commit(nvs);
    nvs_close(nvs);
    return err;
}

/* The provisioning manager writes candidate credentials before testing them. */
static esp_err_t recover_interrupted_transaction(void)
{
    nvs_handle_t nvs;
    esp_err_t err = nvs_open(TXN_NS, NVS_READWRITE, &nvs);
    if (err == ESP_ERR_NVS_NOT_FOUND) return ESP_OK;
    if (err != ESP_OK) return err;
    uint8_t pending = 0;
    err = nvs_get_u8(nvs, TXN_PENDING, &pending);
    wifi_config_t saved = {0};
    size_t size = sizeof(saved);
    if (err == ESP_OK && pending) err = nvs_get_blob(nvs, TXN_CONFIG, &saved, &size);
    if (err == ESP_OK && pending && size == sizeof(saved)) {
        err = esp_wifi_set_storage(WIFI_STORAGE_RAM);
        if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &saved);
        if (err == ESP_OK) err = esp_wifi_set_storage(WIFI_STORAGE_FLASH);
        if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, &saved);
        if (err == ESP_OK) err = nvs_set_u8(nvs, TXN_PENDING, 0);
        if (err == ESP_OK) err = nvs_commit(nvs);
    } else if (err == ESP_ERR_NVS_NOT_FOUND) {
        err = ESP_OK;
    }
    nvs_close(nvs);
    return err;
}

static esp_err_t prepare_wifi_provisioning(void *arg)
{
    (void)arg;
    wifi_config_t current = {0};
    esp_err_t err = esp_wifi_get_config(WIFI_IF_STA, &current);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "could not read current Wi-Fi config: %s", esp_err_to_name(err));
        return err;
    }

    previous_config = current;
    previous_valid = current.sta.ssid[0] != '\0';
    wifi_config_t backup = previous_valid ? current : (wifi_config_t){0};
    err = save_transaction(&backup, true);
    if (err == ESP_OK) provisioning_transaction_pending = true;
    if (err != ESP_OK)
        ESP_LOGE(TAG, "could not save Wi-Fi rollback config: %s", esp_err_to_name(err));
    return err;
}

static void provisioning_event(ble_provisioning_event_t event, const void *data, void *arg)
{
    (void)arg;
    if (event == BLE_PROVISIONING_STARTED) {
        ESP_LOGI(TAG, "BLE provisioning started");
    } else if (event == BLE_PROVISIONING_CREDENTIALS_RECEIVED) {
        retry_saved_network = false;
        xEventGroupClearBits(events, RETRY);
        const wifi_sta_config_t *received = data;
        if (received)
            ESP_LOGI(TAG, "Wi-Fi credentials received (SSID length %u)",
                     (unsigned)strnlen((const char *)received->ssid, sizeof(received->ssid)));
    } else if (event == BLE_PROVISIONING_CREDENTIALS_SUCCESS) {
        retry_saved_network = false;
        wifi_config_t current = {0};
        esp_err_t err = esp_wifi_get_config(WIFI_IF_STA, &current);
        if (err == ESP_OK) err = save_transaction(&current, false);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "could not commit Wi-Fi credentials: %s", esp_err_to_name(err));
            wifi_config_t empty = {0};
            const wifi_config_t *restore = previous_valid ? &previous_config : &empty;
            esp_err_t restore_err = restore_station_config(restore);
            if (restore_err != ESP_OK)
                ESP_LOGE(TAG, "Wi-Fi rollback failed; recovery remains pending: %s",
                         esp_err_to_name(restore_err));
            else provisioning_transaction_pending = false;
            ble_provisioning_request_stop();
        } else {
            ESP_LOGI(TAG, "provisioned Wi-Fi connection succeeded");
            provisioning_transaction_pending = false;
            /* Keep BLE available until the provisioning manager sends confirmation. */
        }
    } else if (event == BLE_PROVISIONING_CREDENTIALS_FAILED) {
        retry_saved_network = false;
        ESP_LOGW(TAG, "new Wi-Fi credentials failed; restoring previous network");
        wifi_config_t empty = {0};
        const wifi_config_t *restore = previous_valid ? &previous_config : &empty;
        esp_err_t err = restore_station_config(restore);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Wi-Fi rollback failed; recovery remains pending: %s",
                     esp_err_to_name(err));
        } else {
            provisioning_transaction_pending = false;
            if (previous_valid) {
                retry_saved_network = true;
                esp_wifi_connect();
                xEventGroupSetBits(events, RETRY);
            }
        }
    } else if (event == BLE_PROVISIONING_START_FAILED) {
        const esp_err_t *start_error = data;
        wifi_config_t backup = previous_valid ? previous_config : (wifi_config_t){0};
        esp_err_t restore_err = restore_station_config(&backup);
        if (restore_err != ESP_OK)
            ESP_LOGE(TAG, "could not clear provisioning rollback after start failure: %s",
                     esp_err_to_name(restore_err));
        else provisioning_transaction_pending = false;
        ESP_LOGE(TAG, "BLE provisioning start failed: %s",
                 start_error ? esp_err_to_name(*start_error) : "unknown error");
    } else if (event == BLE_PROVISIONING_STOPPED) {
        if (provisioning_transaction_pending) {
            wifi_config_t empty = {0};
            const wifi_config_t *restore = previous_valid ? &previous_config : &empty;
            esp_err_t err = restore_station_config(restore);
            if (err == ESP_OK) provisioning_transaction_pending = false;
            else ESP_LOGE(TAG, "could not rollback interrupted provisioning: %s",
                          esp_err_to_name(err));
        }
        ESP_LOGI(TAG, "BLE provisioning stopped");
    }
}

static void wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        if (!ble_provisioning_is_active()) esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(events, CONNECTED);
        notify_connection(false);
        if (!ble_provisioning_is_active() || retry_saved_network) {
            xEventGroupSetBits(events, RETRY);
            ESP_LOGW(TAG, "disconnected; reconnecting with bounded backoff");
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        const ip_event_got_ip_t *got_ip = data;
        update_ble_octet(esp_ip4_addr4(&got_ip->ip_info.ip));
        ESP_LOGI(TAG, "Wi-Fi connected at " IPSTR, IP2STR(&got_ip->ip_info.ip));
        if (info_out) snprintf(info_out->ip_address, sizeof(info_out->ip_address), IPSTR,
                                IP2STR(&got_ip->ip_info.ip));
        xEventGroupClearBits(events, RETRY);
        xEventGroupSetBits(events, CONNECTED);
        notify_connection(true);
    }
}

static void retry_task(void *arg)
{
    (void)arg;
    uint32_t delay_ms = 1000;
    for (;;) {
        xEventGroupWaitBits(events, RETRY, pdFALSE, pdTRUE, portMAX_DELAY);
        if (ble_provisioning_is_active() && !retry_saved_network) {
            vTaskDelay(pdMS_TO_TICKS(500));
            continue;
        }
        ESP_LOGI(TAG, "reconnect attempt (backoff %u ms)", (unsigned)delay_ms);
        esp_wifi_connect();
        EventBits_t bits = xEventGroupWaitBits(events, CONNECTED,
                                                pdFALSE, pdTRUE, pdMS_TO_TICKS(delay_ms));
        if (bits & CONNECTED) delay_ms = 1000;
        else if (delay_ms < 30000) delay_ms *= 2;
        if (delay_ms > 30000) delay_ms = 30000;
    }
}

esp_err_t wifi_connect(wifi_info_t *info)
{
    if (!info) return ESP_ERR_INVALID_ARG;
    info_out = info;
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_LOGE(TAG, "NVS init needs repair; refusing to erase saved credentials");
        return err;
    }
    if (err != ESP_OK) return err;
    load_ble_octet();
    if ((err = esp_netif_init()) != ESP_OK) return err;
    if ((err = esp_event_loop_create_default()) != ESP_OK && err != ESP_ERR_INVALID_STATE) return err;
    events = xEventGroupCreate();
    if (!events) return ESP_ERR_NO_MEM;
    station = esp_netif_create_default_wifi_sta();
    if (!station) return ESP_ERR_NO_MEM;
    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    if ((err = esp_wifi_init(&init)) != ESP_OK) return err;
    if ((err = recover_interrupted_transaction()) != ESP_OK) return err;
    if ((err = esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event, NULL)) != ESP_OK) return err;
    if ((err = esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event, NULL)) != ESP_OK) return err;
    if ((err = esp_wifi_set_mode(WIFI_MODE_STA)) != ESP_OK) return err;
    if ((err = esp_wifi_set_ps(WIFI_PS_NONE)) != ESP_OK) return err;

    char service_name[8];
    ble_provisioning_name_from_ip(saved_ble_octet, service_name);
    ESP_LOGI(TAG, "BLE provisioning advertised name: %s", service_name);
    if ((err = ble_provisioning_init(service_name, prepare_wifi_provisioning,
                                     provisioning_event, NULL)) != ESP_OK)
        return err;
    wifi_config_t current = {0};
    if ((err = esp_wifi_get_config(WIFI_IF_STA, &current)) != ESP_OK) return err;
    if (current.sta.ssid[0]) {
        ESP_LOGI(TAG, "saved Wi-Fi credentials found; connecting");
        if ((err = esp_wifi_start()) != ESP_OK) return err;
    } else {
        err = load_menuconfig_credentials(&current);
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "using first-boot Wi-Fi credentials from menuconfig");
            if ((err = esp_wifi_start()) != ESP_OK) return err;
        } else if (err == ESP_ERR_NOT_FOUND) {
            ESP_LOGI(TAG, "no saved or menuconfig credentials; opening BLE provisioning");
            if ((err = ble_provisioning_request()) != ESP_OK) return err;
        } else {
            return err;
        }
    }

    if (xTaskCreatePinnedToCore(retry_task, "wifi_retry", 3072, NULL, 4, NULL,
                                portNUM_PROCESSORS > 1 ? 0 : tskNO_AFFINITY) != pdPASS)
        return ESP_ERR_NO_MEM;

    ESP_LOGI(TAG, "waiting for Wi-Fi before UWB initialization");
    xEventGroupWaitBits(events, CONNECTED, pdFALSE, pdTRUE, portMAX_DELAY);
    if ((err = esp_wifi_get_mac(WIFI_IF_STA, info->mac)) != ESP_OK) return err;
    esp_netif_ip_info_t ip_info;
    if ((err = esp_netif_get_ip_info(station, &ip_info)) != ESP_OK) return err;
    snprintf(info->ip_address, sizeof(info->ip_address), IPSTR, IP2STR(&ip_info.ip));
    ESP_LOGI(TAG, "device MAC=%02x:%02x:%02x:%02x:%02x:%02x", info->mac[0], info->mac[1],
             info->mac[2], info->mac[3], info->mac[4], info->mac[5]);
    return ESP_OK;
}
