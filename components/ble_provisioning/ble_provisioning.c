#include "ble_provisioning.h"

#include <stdbool.h>
#include <string.h>
#include "driver/gpio.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "network_provisioning/manager.h"
#include "network_provisioning/scheme_ble.h"

#define BUTTON_GPIO GPIO_NUM_17
#define BUTTON_RETRY_LIMIT 150
#define BUTTON_RETRY_DELAY_MS 100

static const char *TAG = "ble_provisioning";
static SemaphoreHandle_t request_sem;
static ble_provisioning_prepare_cb_t prepare_cb;
static ble_provisioning_event_cb_t app_event_cb;
static void *app_arg;
static char service_name[33];
static volatile bool active;

static void notify_app(ble_provisioning_event_t event, const void *data)
{
    if (app_event_cb) app_event_cb(event, data, app_arg);
}

static void manager_event(void *arg, network_prov_cb_event_t event, void *data)
{
    (void)arg;
    switch (event) {
    case NETWORK_PROV_START:
        active = true;
        ESP_LOGI(TAG, "provisioning manager started");
        notify_app(BLE_PROVISIONING_STARTED, NULL);
        break;
    case NETWORK_PROV_WIFI_CRED_RECV:
        notify_app(BLE_PROVISIONING_CREDENTIALS_RECEIVED, data);
        break;
    case NETWORK_PROV_WIFI_CRED_SUCCESS:
        notify_app(BLE_PROVISIONING_CREDENTIALS_SUCCESS, NULL);
        break;
    case NETWORK_PROV_WIFI_CRED_FAIL: {
        esp_err_t err = network_prov_mgr_reset_wifi_sm_state_on_failure();
        if (err != ESP_OK)
            ESP_LOGW(TAG, "could not reset Wi-Fi provisioning state: %s",
                     esp_err_to_name(err));
        notify_app(BLE_PROVISIONING_CREDENTIALS_FAILED, NULL);
        break;
    }
    case NETWORK_PROV_END:
        active = false;
        notify_app(BLE_PROVISIONING_STOPPED, NULL);
        break;
    default:
        break;
    }
}

static void request_task(void *arg)
{
    (void)arg;
    for (;;) {
        xSemaphoreTake(request_sem, portMAX_DELAY);
        if (active) {
            ESP_LOGI(TAG, "BLE provisioning is already active");
            continue;
        }

        esp_err_t err = ESP_ERR_INVALID_STATE;
        bool started = false;
        for (unsigned retry = 0; retry < BUTTON_RETRY_LIMIT; ++retry) {
            if (active) {
                ESP_LOGI(TAG, "BLE provisioning is already active");
                started = true;
                break;
            }
            if (!network_prov_mgr_is_sm_idle()) {
                vTaskDelay(pdMS_TO_TICKS(BUTTON_RETRY_DELAY_MS));
                continue;
            }

            err = prepare_cb ? prepare_cb(app_arg) : ESP_OK;
            if (err != ESP_OK) break;

            active = true; /* Keep Wi-Fi callbacks from reconnecting during startup. */
            err = network_prov_mgr_start_provisioning(NETWORK_PROV_SECURITY_1,
                                                       NULL, service_name, NULL);
            if (err == ESP_OK) {
                started = true;
                ESP_LOGI(TAG, "BLE provisioning requested as %s", service_name);
                break;
            }

            active = false;
            notify_app(BLE_PROVISIONING_START_FAILED, &err);
            if (err != ESP_ERR_INVALID_STATE) break;
            vTaskDelay(pdMS_TO_TICKS(BUTTON_RETRY_DELAY_MS));
        }

        if (!started) {
            if (err == ESP_ERR_INVALID_STATE && !network_prov_mgr_is_sm_idle())
                ESP_LOGE(TAG, "BLE manager stayed busy for 15 seconds");
            else
                ESP_LOGE(TAG, "could not start BLE provisioning: %s",
                         esp_err_to_name(err));
        }
    }
}

static void button_task(void *arg)
{
    (void)arg;
    bool pressed = false;
    for (;;) {
        bool now = gpio_get_level(BUTTON_GPIO) == 0;
        if (now && !pressed) {
            vTaskDelay(pdMS_TO_TICKS(35));
            if (gpio_get_level(BUTTON_GPIO) == 0) {
                ESP_LOGI(TAG, "GPIO17 provisioning button pressed");
                ble_provisioning_request();
                pressed = true;
            }
        } else if (!now) {
            pressed = false;
        }
        vTaskDelay(pdMS_TO_TICKS(25));
    }
}

esp_err_t ble_provisioning_init(const char *name,
                                ble_provisioning_prepare_cb_t prepare,
                                ble_provisioning_event_cb_t event_cb,
                                void *arg)
{
    if (!name || name[0] == '\0' || strlen(name) >= sizeof(service_name))
        return ESP_ERR_INVALID_ARG;
    if (request_sem) return ESP_ERR_INVALID_STATE;

    network_prov_mgr_config_t config = {
        .scheme = network_prov_scheme_ble,
        .scheme_event_handler = NETWORK_PROV_SCHEME_BLE_EVENT_HANDLER_FREE_BLE,
        .app_event_handler = { .event_cb = manager_event, .user_data = NULL },
        .network_prov_wifi_conn_cfg = { .wifi_conn_attempts = 5 },
    };
    esp_err_t err = network_prov_mgr_init(config);
    if (err != ESP_OK) return err;
    if ((err = network_prov_mgr_disable_auto_stop(1000)) != ESP_OK) {
        ESP_LOGE(TAG, "could not configure manager lifecycle: %s", esp_err_to_name(err));
        return err;
    }

    gpio_config_t button = {
        .pin_bit_mask = 1ULL << BUTTON_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    if ((err = gpio_config(&button)) != ESP_OK) return err;

    request_sem = xSemaphoreCreateBinary();
    if (!request_sem) return ESP_ERR_NO_MEM;
    strlcpy(service_name, name, sizeof(service_name));
    prepare_cb = prepare;
    app_event_cb = event_cb;
    app_arg = arg;

    if (xTaskCreate(request_task, "ble_provision", 4096, NULL, 5, NULL) != pdPASS)
        return ESP_ERR_NO_MEM;
    if (xTaskCreate(button_task, "ble_button", 3072, NULL, 4, NULL) != pdPASS)
        return ESP_ERR_NO_MEM;
    ESP_LOGI(TAG, "BLE provisioning component ready; button GPIO%d", BUTTON_GPIO);
    return ESP_OK;
}

esp_err_t ble_provisioning_request(void)
{
    if (!request_sem) return ESP_ERR_INVALID_STATE;
    xSemaphoreGive(request_sem);
    return ESP_OK;
}

bool ble_provisioning_is_active(void)
{
    return active;
}

esp_err_t ble_provisioning_stop(void)
{
    if (!request_sem) return ESP_ERR_INVALID_STATE;
    network_prov_mgr_stop_provisioning();
    return ESP_OK;
}
