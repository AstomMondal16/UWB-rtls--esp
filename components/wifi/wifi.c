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
#include "nvs_flash.h"

#define WIFI_CONNECTED_BIT BIT0

static const char *TAG = "wifi";
static EventGroupHandle_t events;

static void wifi_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        esp_wifi_connect();
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(events, WIFI_CONNECTED_BIT);
        ESP_LOGW(TAG, "disconnected; reconnecting");
        esp_wifi_connect();
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = data;
        ESP_LOGW(TAG, "UWB IP ADDRESS=" IPSTR, IP2STR(&event->ip_info.ip));
        xEventGroupSetBits(events, WIFI_CONNECTED_BIT);
    }
}

esp_err_t wifi_connect(wifi_info_t *info)
{
    if (!info || !CONFIG_UWB_WIFI_SSID[0]) {
        ESP_LOGE(TAG, "configure a Wi-Fi SSID in menuconfig");
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    if (err != ESP_OK) return err;
    if ((err = esp_netif_init()) != ESP_OK) return err;
    if ((err = esp_event_loop_create_default()) != ESP_OK) return err;
    events = xEventGroupCreate();
    if (!events) return ESP_ERR_NO_MEM;
    esp_netif_t *station = esp_netif_create_default_wifi_sta();
    if (!station) return ESP_ERR_NO_MEM;

    wifi_init_config_t init = WIFI_INIT_CONFIG_DEFAULT();
    if ((err = esp_wifi_init(&init)) != ESP_OK) return err;
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_event, NULL));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));

    wifi_config_t config = {0};
    strlcpy((char *)config.sta.ssid, CONFIG_UWB_WIFI_SSID, sizeof(config.sta.ssid));
    strlcpy((char *)config.sta.password, CONFIG_UWB_WIFI_PASSWORD, sizeof(config.sta.password));
    config.sta.threshold.authmode = CONFIG_UWB_WIFI_PASSWORD[0] ? WIFI_AUTH_WPA2_PSK : WIFI_AUTH_OPEN;
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &config));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));

    ESP_LOGI(TAG, "waiting for Wi-Fi before UWB initialization");
    xEventGroupWaitBits(events, WIFI_CONNECTED_BIT, pdFALSE, pdTRUE, portMAX_DELAY);
    ESP_ERROR_CHECK(esp_wifi_get_mac(WIFI_IF_STA, info->mac));
    esp_netif_ip_info_t ip_info;
    ESP_ERROR_CHECK(esp_netif_get_ip_info(station, &ip_info));
    snprintf(info->ip_address, sizeof(info->ip_address), IPSTR,
             IP2STR(&ip_info.ip));
    ESP_LOGW(TAG, "UWB MAC/DEVICE ID=%02x:%02x:%02x:%02x:%02x:%02x",
             info->mac[0], info->mac[1], info->mac[2], info->mac[3],
             info->mac[4], info->mac[5]);
    return ESP_OK;
}
