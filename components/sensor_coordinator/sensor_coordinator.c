#include "sensor_coordinator.h"

#include <inttypes.h>
#include "esp_log.h"
#include "esp_heap_caps.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "freertos/task.h"

#define PAUSE_UWB BIT0
#define ACK_UWB   BIT1
#define PAUSE_IMU BIT2
#define ACK_IMU   BIT3
#define PAUSE_BATTERY BIT4
#define ACK_BATTERY   BIT5
#define SENSOR_ACKS (ACK_UWB | ACK_IMU | ACK_BATTERY)

static const char *TAG = "task_manager";
static EventGroupHandle_t sync_events;
static SemaphoreHandle_t state_mutex;
static TaskHandle_t sensor_tasks[3];
static uint32_t registered_sensors;
static sensor_coordinator_state_t state = SENSOR_COORDINATOR_NORMAL_OPERATION;

static EventBits_t request_bit(uint32_t sensor)
{
    if (sensor == SENSOR_COORDINATOR_UWB) return PAUSE_UWB;
    if (sensor == SENSOR_COORDINATOR_IMU) return PAUSE_IMU;
    return PAUSE_BATTERY;
}

static EventBits_t acknowledgement_bit(uint32_t sensor)
{
    if (sensor == SENSOR_COORDINATOR_UWB) return ACK_UWB;
    if (sensor == SENSOR_COORDINATOR_IMU) return ACK_IMU;
    return ACK_BATTERY;
}

static unsigned sensor_index(uint32_t sensor)
{
    if (sensor == SENSOR_COORDINATOR_UWB) return 0;
    if (sensor == SENSOR_COORDINATOR_IMU) return 1;
    return 2;
}

static const char *sensor_name(uint32_t sensor)
{
    if (sensor == SENSOR_COORDINATOR_UWB) return "UWB";
    if (sensor == SENSOR_COORDINATOR_IMU) return "IMU";
    return "battery";
}

static void wake_registered_sensors(TaskHandle_t tasks[3])
{
    for (unsigned i = 0; i < 3; ++i)
        if (tasks[i]) xTaskNotifyGive(tasks[i]);
}

esp_err_t sensor_coordinator_init(void)
{
    if (sync_events || state_mutex) return ESP_ERR_INVALID_STATE;
    sync_events = xEventGroupCreate();
    state_mutex = xSemaphoreCreateMutex();
    if (!sync_events || !state_mutex) {
        if (sync_events) vEventGroupDelete(sync_events);
        if (state_mutex) vSemaphoreDelete(state_mutex);
        sync_events = NULL;
        state_mutex = NULL;
        return ESP_ERR_NO_MEM;
    }
    ESP_LOGI(TAG, "initialized; UWB/IMU/battery pause acknowledgements enabled");
    return ESP_OK;
}

esp_err_t sensor_coordinator_register(uint32_t sensor)
{
    if (!sync_events || (sensor != SENSOR_COORDINATOR_UWB &&
                         sensor != SENSOR_COORDINATOR_IMU &&
                         sensor != SENSOR_COORDINATOR_BATTERY))
        return ESP_ERR_INVALID_ARG;
    if (xSemaphoreTake(state_mutex, portMAX_DELAY) != pdTRUE)
        return ESP_ERR_TIMEOUT;

    sensor_tasks[sensor_index(sensor)] = xTaskGetCurrentTaskHandle();
    registered_sensors |= sensor;
    if (state == SENSOR_COORDINATOR_PAUSING_SENSORS ||
        state == SENSOR_COORDINATOR_BLE_ACTIVE)
        xEventGroupSetBits(sync_events, request_bit(sensor));
    xSemaphoreGive(state_mutex);
    return ESP_OK;
}

void sensor_coordinator_checkpoint(uint32_t sensor)
{
    if (!sync_events || (sensor != SENSOR_COORDINATOR_UWB &&
                         sensor != SENSOR_COORDINATOR_IMU &&
                         sensor != SENSOR_COORDINATOR_BATTERY))
        return;

    EventBits_t request = request_bit(sensor);
    EventBits_t ack = acknowledgement_bit(sensor);
    if (!(xEventGroupGetBits(sync_events) & request)) return;

    ESP_LOGI(TAG, "%s acquisition stopped at a safe boundary; stack high-water=%u",
             sensor_name(sensor),
             (unsigned)uxTaskGetStackHighWaterMark(NULL));
    xEventGroupSetBits(sync_events, ack);
    while (xEventGroupGetBits(sync_events) & request)
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    xEventGroupClearBits(sync_events, ack);
}

esp_err_t sensor_coordinator_pause_for_ble(uint32_t timeout_ms)
{
    if (!sync_events || !state_mutex || timeout_ms == 0)
        return ESP_ERR_INVALID_ARG;
    if (xSemaphoreTake(state_mutex, portMAX_DELAY) != pdTRUE)
        return ESP_ERR_TIMEOUT;
    if (state != SENSOR_COORDINATOR_NORMAL_OPERATION) {
        xSemaphoreGive(state_mutex);
        return ESP_ERR_INVALID_STATE;
    }

    state = SENSOR_COORDINATOR_BLE_REQUESTED;
    ESP_LOGI(TAG, "BLE session requested");
    state = SENSOR_COORDINATOR_PAUSING_SENSORS;
    EventBits_t requests = 0;
    EventBits_t acknowledgements = 0;
    if (registered_sensors & SENSOR_COORDINATOR_UWB) {
        requests |= PAUSE_UWB;
        acknowledgements |= ACK_UWB;
    }
    if (registered_sensors & SENSOR_COORDINATOR_IMU) {
        requests |= PAUSE_IMU;
        acknowledgements |= ACK_IMU;
    }
    if (registered_sensors & SENSOR_COORDINATOR_BATTERY) {
        requests |= PAUSE_BATTERY;
        acknowledgements |= ACK_BATTERY;
    }
    TaskHandle_t tasks[3] = {sensor_tasks[0], sensor_tasks[1], sensor_tasks[2]};
    xEventGroupClearBits(sync_events, SENSOR_ACKS);
    xEventGroupSetBits(sync_events, requests);
    xSemaphoreGive(state_mutex);
    wake_registered_sensors(tasks);

    if (acknowledgements) {
        TickType_t timeout = pdMS_TO_TICKS(timeout_ms);
        if (!timeout) timeout = 1;
        EventBits_t reached = xEventGroupWaitBits(sync_events, acknowledgements,
                                                  pdFALSE, pdTRUE, timeout);
        if ((reached & acknowledgements) != acknowledgements) {
            TaskHandle_t tasks[3];
            xSemaphoreTake(state_mutex, portMAX_DELAY);
            state = SENSOR_COORDINATOR_ERROR_RECOVERY;
            xEventGroupClearBits(sync_events, requests);
            tasks[0] = sensor_tasks[0];
            tasks[1] = sensor_tasks[1];
            tasks[2] = sensor_tasks[2];
            state = SENSOR_COORDINATOR_RESUMING_SENSORS;
            wake_registered_sensors(tasks);
            state = SENSOR_COORDINATOR_NORMAL_OPERATION;
            xSemaphoreGive(state_mutex);
            ESP_LOGE(TAG, "sensor pause acknowledgement timed out; BLE was not started");
            return ESP_ERR_TIMEOUT;
        }
    }

    xSemaphoreTake(state_mutex, portMAX_DELAY);
    state = SENSOR_COORDINATOR_BLE_ACTIVE;
    xSemaphoreGive(state_mutex);
    ESP_LOGI(TAG, "all active sensors paused; BLE session may start (free heap=%" PRIu32 ")",
             esp_get_free_heap_size());
    return ESP_OK;
}

void sensor_coordinator_resume_after_ble(sensor_coordinator_state_t reason)
{
    if (!sync_events || !state_mutex) return;
    if (xSemaphoreTake(state_mutex, portMAX_DELAY) != pdTRUE) return;
    if (state == SENSOR_COORDINATOR_NORMAL_OPERATION) {
        xSemaphoreGive(state_mutex);
        return;
    }

    state = reason;
    ESP_LOGI(TAG, "BLE session ended; reason=%d", (int)reason);
    state = SENSOR_COORDINATOR_RESUMING_SENSORS;
    xEventGroupClearBits(sync_events, PAUSE_UWB | PAUSE_IMU | PAUSE_BATTERY);
    TaskHandle_t tasks[3] = {sensor_tasks[0], sensor_tasks[1], sensor_tasks[2]};
    wake_registered_sensors(tasks);
    xEventGroupClearBits(sync_events, SENSOR_ACKS);
    state = SENSOR_COORDINATOR_NORMAL_OPERATION;
    xSemaphoreGive(state_mutex);
    ESP_LOGI(TAG, "normal sensor operation restored");
}

bool sensor_coordinator_ble_active(void)
{
    if (!state_mutex || xSemaphoreTake(state_mutex, 0) != pdTRUE) return false;
    bool active = state == SENSOR_COORDINATOR_BLE_ACTIVE ||
                  state == SENSOR_COORDINATOR_PAUSING_SENSORS ||
                  state == SENSOR_COORDINATOR_BLE_REQUESTED;
    xSemaphoreGive(state_mutex);
    return active;
}
