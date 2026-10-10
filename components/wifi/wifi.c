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

#ifndef CONFIG_UWB_BLE_DEVICE_ID
#define CONFIG_UWB_BLE_DEVICE_ID ""
#endif

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
static volatile bool retry_saved_network;
static wifi_config_t previous_config;

void wifi_set_connection_callback(wifi_connection_callback_t callback, void *arg)
{
    connection_cb = callback;
    connection_arg = arg;
}

static void notify_connection(bool connected)
{
    if (connection_cb) connection_cb(connected, connection_arg);
}

static esp_err_t write_station_config(wifi_config_t *config)
{
    esp_err_t err = esp_wifi_set_storage(WIFI_STORAGE_FLASH);
    if (err == ESP_OK) err = esp_wifi_set_config(WIFI_IF_STA, config);
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

static void stop_provisioning_task(void *arg)
{
    (void)arg;
    vTaskDelay(pdMS_TO_TICKS(10000));
    ble_provisioning_stop();
    vTaskDelete(NULL);
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
        ESP_LOGI(TAG, "Wi-Fi credentials received (SSID length %u)",
                 (unsigned)strnlen((const char *)received->ssid, sizeof(received->ssid)));
    } else if (event == BLE_PROVISIONING_CREDENTIALS_SUCCESS) {
        retry_saved_network = false;
        wifi_config_t current = {0};
        esp_err_t err = esp_wifi_get_config(WIFI_IF_STA, &current);
        if (err == ESP_OK) err = save_transaction(&current, false);
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "could not commit Wi-Fi credentials: %s", esp_err_to_name(err));
            if (previous_valid) write_station_config(&previous_config);
        } else {
            ESP_LOGI(TAG, "provisioned Wi-Fi connection succeeded");
            if (xTaskCreate(stop_provisioning_task, "prov_stop", 2048, NULL, 3, NULL) != pdPASS) {
                ESP_LOGW(TAG, "could not schedule BLE shutdown; stopping provisioning now");
                ble_provisioning_stop();
            }
        }
    } else if (event == BLE_PROVISIONING_CREDENTIALS_FAILED) {
        retry_saved_network = previous_valid;
        ESP_LOGW(TAG, "new Wi-Fi credentials failed; restoring previous network");
        wifi_config_t empty = {0};
        wifi_config_t *restore = previous_valid ? &previous_config : &empty;
        if (esp_wifi_set_storage(WIFI_STORAGE_RAM) == ESP_OK) {
            esp_wifi_set_config(WIFI_IF_STA, restore);
            esp_wifi_set_storage(WIFI_STORAGE_FLASH);
            write_station_config(restore);
            if (previous_valid) esp_wifi_connect();
        }
        save_transaction(restore, false);
        if (previous_valid) xEventGroupSetBits(events, RETRY);
    } else if (event == BLE_PROVISIONING_START_FAILED) {
        const esp_err_t *start_error = data;
        wifi_config_t backup = previous_valid ? previous_config : (wifi_config_t){0};
        save_transaction(&backup, false);
        ESP_LOGE(TAG, "BLE provisioning start failed: %s",
                 start_error ? esp_err_to_name(*start_error) : "unknown error");
    } else if (event == BLE_PROVISIONING_STOPPED) {
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

    uint8_t mac[6];
    if ((err = esp_wifi_get_mac(WIFI_IF_STA, mac)) != ESP_OK) return err;
    char service_name[33];
    if (CONFIG_UWB_BLE_DEVICE_ID[0]) {
        if (strlen(CONFIG_UWB_BLE_DEVICE_ID) > sizeof(service_name) - sizeof("PROV_"))
            return ESP_ERR_INVALID_SIZE;
        snprintf(service_name, sizeof(service_name), "PROV_%s", CONFIG_UWB_BLE_DEVICE_ID);
    } else {
        snprintf(service_name, sizeof(service_name), "PROV_%02X%02X%02X",
                 mac[3], mac[4], mac[5]);
    }
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

    if (xTaskCreate(retry_task, "wifi_retry", 3072, NULL, 4, NULL) != pdPASS) return ESP_ERR_NO_MEM;

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
