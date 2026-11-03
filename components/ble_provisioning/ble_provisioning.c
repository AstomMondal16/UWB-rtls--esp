#include "ble_provisioning.h"

#include <stdbool.h>
#include <stddef.h>
#include <string.h>
#include "driver/gpio.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "freertos/timers.h"
#include "network_provisioning/manager.h"
#include "network_provisioning/scheme_ble.h"
#include "protocomm_ble.h"
#include "sensor_coordinator.h"

#define BUTTON_GPIO GPIO_NUM_17
#define BLE_SESSION_IDLE_TIMEOUT_MS 120000
#define CMD_START BIT0
#define CMD_STOP BIT1
#define CMD_SESSION_END BIT2
#define CMD_MASK (CMD_START | CMD_STOP | CMD_SESSION_END)

static const char *TAG = "ble_provisioning";
static EventGroupHandle_t commands;
static TimerHandle_t inactivity_timer;
static TaskHandle_t request_task_handle;
static SemaphoreHandle_t init_lock;
static ble_provisioning_prepare_cb_t prepare_cb;
static ble_provisioning_event_cb_t app_event_cb;
static void *app_arg;
static char service_name[33];
static bool active;
static sensor_coordinator_state_t stop_reason = SENSOR_COORDINATOR_BLE_FINISHED;
static portMUX_TYPE state_lock = portMUX_INITIALIZER_UNLOCKED;

static bool is_active(void)
{
    portENTER_CRITICAL(&state_lock);
    bool value = active;
    portEXIT_CRITICAL(&state_lock);
    return value;
}

static void set_active(bool value)
{
    portENTER_CRITICAL(&state_lock);
    active = value;
    portEXIT_CRITICAL(&state_lock);
}

static void notify_app(ble_provisioning_event_t event, const void *data)
{
    if (app_event_cb) app_event_cb(event, data, app_arg);
}

static void request_stop(sensor_coordinator_state_t reason)
{
    portENTER_CRITICAL(&state_lock);
    stop_reason = reason;
    portEXIT_CRITICAL(&state_lock);
    if (commands) xEventGroupSetBits(commands, CMD_STOP);
}

static void inactivity_timer_expired(TimerHandle_t timer)
{
    (void)timer;
    ESP_LOGW(TAG, "no valid Wi-Fi credentials received for 120 seconds");
    request_stop(SENSOR_COORDINATOR_BLE_TIMEOUT);
}

static void ble_transport_event(void *arg, esp_event_base_t base, int32_t id,
                                void *event_data)
{
    (void)arg;
    (void)base;
    const protocomm_ble_event_t *event = event_data;
    if (id == PROTOCOMM_TRANSPORT_BLE_CONNECTED) {
        ESP_LOGI(TAG, "BLE client connected; waiting for valid application data");
    } else if (id == PROTOCOMM_TRANSPORT_BLE_DISCONNECTED && is_active()) {
        ESP_LOGW(TAG, "BLE client disconnected, reason=%u",
                 event ? event->disconnect_reason : 0);
        request_stop(SENSOR_COORDINATOR_BLE_DISCONNECTED);
    }
}

static void manager_event(void *arg, network_prov_cb_event_t event, void *data)
{
    (void)arg;
    switch (event) {
    case NETWORK_PROV_START:
        if (xTimerStart(inactivity_timer, 0) != pdPASS) {
            ESP_LOGE(TAG, "could not start BLE inactivity timer");
            request_stop(SENSOR_COORDINATOR_BLE_FAILED);
        }
        ESP_LOGI(TAG, "BLE provisioning started");
        notify_app(BLE_PROVISIONING_STARTED, NULL);
        break;
    case NETWORK_PROV_WIFI_CRED_RECV:
        if (data) {
            const wifi_sta_config_t *credentials = data;
            size_t ssid_length = strnlen((const char *)credentials->ssid,
                                         sizeof(credentials->ssid));
            if (ssid_length > 0) {
                if (xTimerReset(inactivity_timer, 0) == pdPASS)
                    ESP_LOGI(TAG, "valid Wi-Fi credentials received; inactivity timer reset");
                else
                    ESP_LOGW(TAG, "could not reset BLE inactivity timer");
            }
        }
        notify_app(BLE_PROVISIONING_CREDENTIALS_RECEIVED, data);
        break;
    case NETWORK_PROV_WIFI_CRED_SUCCESS:
        notify_app(BLE_PROVISIONING_CREDENTIALS_SUCCESS, NULL);
        request_stop(SENSOR_COORDINATOR_BLE_FINISHED);
        break;
    case NETWORK_PROV_WIFI_CRED_FAIL: {
        esp_err_t err = network_prov_mgr_reset_wifi_sm_state_on_failure();
        if (err != ESP_OK)
            ESP_LOGW(TAG, "could not reset Wi-Fi provisioning state: %s",
                     esp_err_to_name(err));
        notify_app(BLE_PROVISIONING_CREDENTIALS_FAILED, NULL);
        request_stop(SENSOR_COORDINATOR_BLE_FAILED);
        break;
    }
    case NETWORK_PROV_END: {
        xTimerStop(inactivity_timer, 0);
        portENTER_CRITICAL(&state_lock);
        sensor_coordinator_state_t reason = stop_reason;
        stop_reason = SENSOR_COORDINATOR_BLE_FINISHED;
        portEXIT_CRITICAL(&state_lock);
        ESP_LOGI(TAG, "BLE provisioning stopped");
        notify_app(BLE_PROVISIONING_STOPPED, NULL);
        sensor_coordinator_resume_after_ble(reason);
        set_active(false);
        xEventGroupSetBits(commands, CMD_SESSION_END);
        break;
    }
    default:
        break;
    }
}

static void start_provisioning(void)
{
    ESP_LOGI(TAG, "handling BLE provisioning start request");
    if (is_active()) {
        ESP_LOGI(TAG, "BLE provisioning is already active");
        return;
    }

    esp_err_t err = sensor_coordinator_pause_for_ble(3000);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "sensors did not pause safely: %s", esp_err_to_name(err));
        notify_app(BLE_PROVISIONING_START_FAILED, &err);
        return;
    }
    ESP_LOGI(TAG, "sensor pause acknowledged; starting provisioning service %s",
             service_name);
    err = prepare_cb ? prepare_cb(app_arg) : ESP_OK;
    if (err != ESP_OK) {
        sensor_coordinator_resume_after_ble(SENSOR_COORDINATOR_BLE_FAILED);
        notify_app(BLE_PROVISIONING_START_FAILED, &err);
        return;
    }

    portENTER_CRITICAL(&state_lock);
    stop_reason = SENSOR_COORDINATOR_BLE_FINISHED;
    portEXIT_CRITICAL(&state_lock);
    if (is_active()) return;
    if (!network_prov_mgr_is_sm_idle()) {
        err = ESP_ERR_INVALID_STATE;
    } else {
        set_active(true);
        err = network_prov_mgr_start_provisioning(NETWORK_PROV_SECURITY_1,
                                                   NULL, service_name, NULL);
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "BLE provisioning requested as %s", service_name);
            return;
        }
        set_active(false);
    }

    ESP_LOGE(TAG, "could not start BLE provisioning: %s", esp_err_to_name(err));
    sensor_coordinator_resume_after_ble(SENSOR_COORDINATOR_BLE_FAILED);
    notify_app(BLE_PROVISIONING_START_FAILED, &err);
}

static void request_task(void *arg)
{
    (void)arg;
    for (;;) {
        EventBits_t bits = xEventGroupWaitBits(commands, CMD_MASK, pdTRUE,
                                               pdFALSE, portMAX_DELAY);
        ESP_LOGI(TAG, "provisioning worker received command bits=0x%02x",
                 (unsigned)bits);
        if (bits & CMD_STOP) {
            if (is_active()) network_prov_mgr_stop_provisioning();
            if (!(bits & CMD_START)) continue;
        }
        if (bits & CMD_START) {
            if (is_active()) {
                ESP_LOGI(TAG, "deferring credential-change request until BLE session ends");
                xEventGroupWaitBits(commands, CMD_SESSION_END, pdTRUE, pdTRUE,
                                    portMAX_DELAY);
                xEventGroupClearBits(commands, CMD_START | CMD_STOP);
            }
            start_provisioning();
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
                esp_err_t err = ble_provisioning_request();
                if (err != ESP_OK)
                    ESP_LOGE(TAG, "could not queue BLE request: %s", esp_err_to_name(err));
                pressed = true;
            }
        } else if (!now) {
            pressed = false;
        }
        vTaskDelay(pdMS_TO_TICKS(50));
    }
}

esp_err_t ble_provisioning_init(const char *name,
                                ble_provisioning_prepare_cb_t prepare,
                                ble_provisioning_event_cb_t event_cb,
                                void *arg)
{
    if (!name || name[0] == '\0' || strlen(name) >= sizeof(service_name))
        return ESP_ERR_INVALID_ARG;
    if (init_lock && xSemaphoreTake(init_lock, portMAX_DELAY) != pdTRUE)
        return ESP_ERR_TIMEOUT;
    if (commands) {
        if (init_lock) xSemaphoreGive(init_lock);
        return ESP_ERR_INVALID_STATE;
    }

    init_lock = xSemaphoreCreateMutex();
    commands = xEventGroupCreate();
    inactivity_timer = xTimerCreate("ble_idle", pdMS_TO_TICKS(BLE_SESSION_IDLE_TIMEOUT_MS),
                                    pdFALSE, NULL, inactivity_timer_expired);
    if (!init_lock || !commands || !inactivity_timer) {
        if (inactivity_timer) xTimerDelete(inactivity_timer, 0);
        if (commands) vEventGroupDelete(commands);
        if (init_lock) vSemaphoreDelete(init_lock);
        inactivity_timer = NULL;
        commands = NULL;
        init_lock = NULL;
        return ESP_ERR_NO_MEM;
    }
    xSemaphoreTake(init_lock, portMAX_DELAY);

    network_prov_mgr_config_t config = {
        .scheme = network_prov_scheme_ble,
        .scheme_event_handler = NETWORK_PROV_SCHEME_BLE_EVENT_HANDLER_FREE_BLE,
        .app_event_handler = { .event_cb = manager_event, .user_data = NULL },
        .network_prov_wifi_conn_cfg = { .wifi_conn_attempts = 5 },
    };
    esp_err_t err = network_prov_mgr_init(config);
    bool manager_initialized = err == ESP_OK;
    if (err == ESP_OK) err = network_prov_mgr_disable_auto_stop(1000);
    gpio_config_t button = {
        .pin_bit_mask = 1ULL << BUTTON_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    if (err == ESP_OK) err = gpio_config(&button);
    if (err == ESP_OK)
        err = esp_event_handler_register(PROTOCOMM_TRANSPORT_BLE_EVENT,
                                         ESP_EVENT_ANY_ID,
                                         ble_transport_event, NULL);
    bool transport_handler_registered = err == ESP_OK;
    if (err == ESP_OK) {
        strlcpy(service_name, name, sizeof(service_name));
        prepare_cb = prepare;
        app_event_cb = event_cb;
        app_arg = arg;
        if (xTaskCreatePinnedToCore(request_task, "ble_provision", 4096, NULL, 5,
                                    &request_task_handle,
                                    portNUM_PROCESSORS > 1 ? 0 : tskNO_AFFINITY) != pdPASS)
            err = ESP_ERR_NO_MEM;
    }
    if (err == ESP_OK &&
        xTaskCreatePinnedToCore(button_task, "ble_button", 3072, NULL, 4, NULL,
                                portNUM_PROCESSORS > 1 ? 0 : tskNO_AFFINITY) != pdPASS)
        err = ESP_ERR_NO_MEM;

    if (err != ESP_OK) {
        if (request_task_handle) {
            vTaskDelete(request_task_handle);
            request_task_handle = NULL;
        }
        if (transport_handler_registered)
            esp_event_handler_unregister(PROTOCOMM_TRANSPORT_BLE_EVENT,
                                         ESP_EVENT_ANY_ID, ble_transport_event);
        if (manager_initialized) network_prov_mgr_deinit();
        xTimerDelete(inactivity_timer, 0);
        vEventGroupDelete(commands);
        xSemaphoreGive(init_lock);
        vSemaphoreDelete(init_lock);
        inactivity_timer = NULL;
        commands = NULL;
        init_lock = NULL;
        return err;
    }

    ESP_LOGI(TAG, "BLE provisioning component ready; button GPIO%d, name=%s",
             BUTTON_GPIO, service_name);
    xSemaphoreGive(init_lock);
    return ESP_OK;
}

esp_err_t ble_provisioning_request(void)
{
    if (!commands) return ESP_ERR_INVALID_STATE;
    xEventGroupSetBits(commands, CMD_START);
    ESP_LOGI(TAG, "BLE provisioning start request queued");
    return ESP_OK;
}

esp_err_t ble_provisioning_request_stop(void)
{
    if (!commands) return ESP_ERR_INVALID_STATE;
    request_stop(SENSOR_COORDINATOR_BLE_FINISHED);
    return ESP_OK;
}

bool ble_provisioning_is_active(void)
{
    return is_active();
}

esp_err_t ble_provisioning_stop(void)
{
    return ble_provisioning_request_stop();
}
