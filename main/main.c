#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "dw1000.h"
#include <inttypes.h>
#include <string.h>
#include <time.h>
#include "sdkconfig.h"
#include "esp_check.h"
#include "esp_log.h"
#include "time_sync.h"
#include "esp_timer.h"
#include "sensor_coordinator.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "nvs.h"
#include "telemetry.h"
#include "device_setup.h"
#include "uwb_ranging.h"
#include "wifi.h"

#ifdef CONFIG_UWB_MPU6050_ENABLE
#include "driver/i2c_master.h"
#include "mpu6050.h"
#endif

#ifdef CONFIG_UWB_BATTERY_MONITOR_ENABLE
#include "battery_monitor.h"
#endif

static const char *TAG = "UWB";
static wifi_info_t network;
static volatile bool wifi_connected = false;
static volatile bool monitoring_requested = true;
static uint32_t requested_sensing_interval_ms = CONFIG_UWB_TAG_PERIOD_MS;
static volatile bool sensor_sleep_allowed;
static portMUX_TYPE sensor_control_lock = portMUX_INITIALIZER_UNLOCKED;
#define BATTERY_LOG_INTERVAL_MS 5000
#define RANGE_FILTER_COUNT 8
typedef struct {
    bool used;
    uint8_t peer_id[6];
    bool has_previous_range;
    float previous_distance;
    uint64_t previous_range_ms;
    float filtered_speed;
    float range_window[5];
    unsigned range_window_count;
    unsigned range_window_next;
} range_filter_t;
static range_filter_t range_filters[RANGE_FILTER_COUNT];
static uint16_t active_antenna_delay = CONFIG_UWB_ANTENNA_DELAY;
#ifdef CONFIG_UWB_MPU6050_ENABLE
static volatile bool imu_streaming = true;
#endif
#ifdef CONFIG_UWB_ROLE_ANCHOR
static uwb_ranging_role_t active_role = UWB_RANGING_ANCHOR;
#else
static uwb_ranging_role_t active_role = UWB_RANGING_TAG;
#endif

static const char *role_name(uwb_ranging_role_t role)
{
    return role == UWB_RANGING_ANCHOR ? "anchor" : "tag";
}

static void sensor_delay_with_checkpoint(uint32_t sensor, uint32_t delay_ms)
{
    while (delay_ms) {
        sensor_coordinator_checkpoint(sensor);
        uint32_t slice_ms = delay_ms > 50 ? 50 : delay_ms;
        vTaskDelay(pdMS_TO_TICKS(slice_ms));
        delay_ms -= slice_ms;
    }
    sensor_coordinator_checkpoint(sensor);
}

static void load_antenna_delay(void)
{
    nvs_handle_t storage;
    if (nvs_open("uwb_cal", NVS_READONLY, &storage) != ESP_OK) return;
    uint16_t saved;
    if (nvs_get_u16(storage, "ant_delay", &saved) == ESP_OK) {
        active_antenna_delay = saved;
        ESP_LOGI(TAG, "loaded calibrated antenna delay=%u DTU", saved);
    }
    nvs_close(storage);
}

static void save_antenna_delay(uint16_t antenna_delay)
{
    nvs_handle_t storage = 0;
    esp_err_t err = nvs_open("uwb_cal", NVS_READWRITE, &storage);
    if (err == ESP_OK) err = nvs_set_u16(storage, "ant_delay", antenna_delay);
    if (err == ESP_OK) err = nvs_commit(storage);
    if (err == ESP_OK) nvs_close(storage);
    else {
        if (storage) nvs_close(storage);
        ESP_LOGE(TAG, "could not save antenna delay: %s", esp_err_to_name(err));
    }
}

static void apply_dashboard_control(bool streaming, uint32_t sensing_interval_ms,
                                    uint32_t send_interval_ms,
                                    uint16_t antenna_delay, const char *role)
{
    /* Sensor chips sleep; Wi-Fi/HTTPS remain available for the Play command. */
    uwb_ranging_role_t requested_role = !strcmp(role, "anchor")
                                            ? UWB_RANGING_ANCHOR : UWB_RANGING_TAG;
    portENTER_CRITICAL(&sensor_control_lock);
    monitoring_requested = streaming;
    active_role = requested_role;
    requested_sensing_interval_ms = sensing_interval_ms;
    portEXIT_CRITICAL(&sensor_control_lock);
    uwb_ranging_set_antenna_delay(antenna_delay);
    if (antenna_delay != active_antenna_delay) {
        active_antenna_delay = antenna_delay;
        save_antenna_delay(antenna_delay);
    }
    sensor_coordinator_notify(SENSOR_COORDINATOR_UWB | SENSOR_COORDINATOR_IMU);
    ESP_LOGI(TAG, "sensors %s (Wi-Fi active) sensing=%" PRIu32 " ms network=%" PRIu32
             " ms antenna_delay=%u role=%s",
             streaming ? "resumed" : "paused", sensing_interval_ms,
             send_interval_ms, antenna_delay, role_name(requested_role));
}

#ifdef CONFIG_UWB_MPU6050_ENABLE
static mpu6050_handle_t imu;
static i2c_master_bus_handle_t imu_bus;

static esp_err_t init_imu(void)
{
    i2c_master_bus_config_t bus_config = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = CONFIG_UWB_MPU6050_SDA_GPIO,
        .scl_io_num = CONFIG_UWB_MPU6050_SCL_GPIO,
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags.enable_internal_pullup = true,
    };
    ESP_RETURN_ON_ERROR(i2c_new_master_bus(&bus_config, &imu_bus), TAG,
                        "MPU6050 I2C initialization");

    const mpu6050_config_t config = {
        .accel_range = MPU6050_ACCEL_RANGE_2G,
        .gyro_range = MPU6050_GYRO_RANGE_250DPS,
        .sample_rate_divider = 9,
    };
    esp_err_t err = mpu6050_create(imu_bus, 0x68, &config, &imu);
    uint8_t address = 0x68;
    if (err != ESP_OK) {
        address = 0x69;
        err = mpu6050_create(imu_bus, address, &config, &imu);
    }
    if (err != ESP_OK) {
        i2c_del_master_bus(imu_bus);
        imu_bus = NULL;
        ESP_LOGE(TAG, "MPU6050 not found at 0x68 or 0x69: %s", esp_err_to_name(err));
        return err;
    }
    ESP_LOGI(TAG, "MPU6050 detected at 0x%02x on SDA=%d SCL=%d", address,
             CONFIG_UWB_MPU6050_SDA_GPIO, CONFIG_UWB_MPU6050_SCL_GPIO);
    return ESP_OK;
}

static void imu_sampling_task(void *argument)
{
    (void)argument;
    sensor_coordinator_register(SENSOR_COORDINATOR_IMU);
    uint32_t retry_ms = 1000;
    bool sleeping = false;
    bool calibrated = false;
    for (;;) {
        sensor_coordinator_checkpoint(SENSOR_COORDINATOR_IMU);
        if (!imu) {
            esp_err_t init_err = init_imu();
            if (init_err != ESP_OK) {
                ESP_LOGW(TAG, "IMU initialization failed: %s; retrying in %" PRIu32 " ms",
                         esp_err_to_name(init_err), retry_ms);
                sensor_delay_with_checkpoint(SENSOR_COORDINATOR_IMU, retry_ms);
                if (retry_ms < 30000) retry_ms *= 2;
                if (retry_ms > 30000) retry_ms = 30000;
                continue;
            }
            sleeping = calibrated = false;
        }
        if (!imu_streaming && sensor_sleep_allowed) {
            if (imu && !sleeping) {
                esp_err_t err = mpu6050_set_sleep(imu, true);
                if (err != ESP_OK) {
                    ESP_LOGW(TAG, "MPU6050 sleep failed: %s; retrying", esp_err_to_name(err));
                    sensor_delay_with_checkpoint(SENSOR_COORDINATOR_IMU, 100);
                    continue;
                }
                sleeping = true;
                telemetry_update_imu(false, NULL, NULL, 0);
            }
            ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(1000));
            continue;
        }
        if (sleeping) {
            esp_err_t err = mpu6050_set_sleep(imu, false);
            if (err != ESP_OK) {
                ESP_LOGW(TAG, "MPU6050 wake failed: %s; retrying", esp_err_to_name(err));
                sensor_delay_with_checkpoint(SENSOR_COORDINATOR_IMU, 100);
                continue;
            }
            sleeping = false;
        }
        if (!imu_streaming) {
            ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(100));
            continue;
        }
        if (!calibrated) {
            ESP_LOGI(TAG, "Keep MPU6050 still in any orientation: calibrating gyro");
            esp_err_t err = mpu6050_calibrate(imu, 300, 5);
            if (err != ESP_OK) {
                telemetry_update_imu(false, NULL, NULL, 0);
                ESP_LOGW(TAG, "MPU6050 calibration failed: %s; reinitializing", esp_err_to_name(err));
                mpu6050_delete(imu);
                imu = NULL;
                i2c_del_master_bus(imu_bus);
                imu_bus = NULL;
                sensor_delay_with_checkpoint(SENSOR_COORDINATOR_IMU, retry_ms);
                if (retry_ms < 30000) retry_ms *= 2;
                if (retry_ms > 30000) retry_ms = 30000;
                continue;
            }
            calibrated = true;
            ESP_LOGI(TAG, "MPU6050 ready; gyro calibration retained across sleep");
        }
        if (!imu_streaming) continue;

        int64_t cycle_started_us = esp_timer_get_time();
        mpu6050_data_t data;
        esp_err_t err = mpu6050_read(imu, &data);
        if (err == ESP_OK) {
            const float accel[3] = {data.accel_x, data.accel_y, data.accel_z};
            const float gyro[3] = {data.gyro_x, data.gyro_y, data.gyro_z};
            telemetry_update_imu(true, accel, gyro, data.temperature);

        } else {
            telemetry_update_imu(false, NULL, NULL, 0);
            ESP_LOGW(TAG, "MPU6050 read failed: %s; reinitializing", esp_err_to_name(err));
            mpu6050_delete(imu);
            imu = NULL;
            i2c_del_master_bus(imu_bus);
            imu_bus = NULL;
            sensor_delay_with_checkpoint(SENSOR_COORDINATOR_IMU, retry_ms);
            if (retry_ms < 30000) retry_ms *= 2;
            if (retry_ms > 30000) retry_ms = 30000;
            continue;
        }
        retry_ms = 1000;
        sensor_coordinator_wait_period(uwb_ranging_get_period(), cycle_started_us);
    }
}
#endif

#ifdef CONFIG_UWB_BATTERY_MONITOR_ENABLE
static SemaphoreHandle_t battery_read_lock;

static esp_err_t init_battery_monitor(void)
{
    const battery_monitor_config_t config = {
        .gpio = CONFIG_UWB_BATTERY_ADC_GPIO,
        .empty_mv = CONFIG_UWB_BATTERY_EMPTY_MV,
        .full_mv = CONFIG_UWB_BATTERY_FULL_MV,
        .divider_ratio = 1.39f,
        .sample_count = 16,
    };
    battery_read_lock = xSemaphoreCreateMutex();
    if (!battery_read_lock) return ESP_ERR_NO_MEM;
    esp_err_t err = battery_monitor_init(&config);
    if (err != ESP_OK) {
        vSemaphoreDelete(battery_read_lock);
        battery_read_lock = NULL;
    }
    return err;
}

static bool read_battery(float *voltage, float *percent)
{
    battery_monitor_data_t data;
    if (sensor_coordinator_ble_active() || !voltage || !percent || !battery_read_lock ||
        xSemaphoreTake(battery_read_lock, pdMS_TO_TICKS(100)) != pdTRUE)
        return false;
    esp_err_t err = battery_monitor_read(&data);
    xSemaphoreGive(battery_read_lock);
    if (err != ESP_OK) return false;
    *voltage = data.voltage;
    *percent = data.percent;
    return true;
}

static void battery_logging_task(void *argument)
{
    (void)argument;
    sensor_coordinator_register(SENSOR_COORDINATOR_BATTERY);
    for (;;) {
        sensor_coordinator_checkpoint(SENSOR_COORDINATOR_BATTERY);
        float voltage;
        float percent;
        if (read_battery(&voltage, &percent)) {
            telemetry_update_battery(voltage, percent);
        } else {
            ESP_LOGW(TAG, "battery sample unavailable");
        }
        /* Sleep until the next sample, or wake early for a BLE pause request. */
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(BATTERY_LOG_INTERVAL_MS));
    }
}
#endif

static void print_peer_id(const char *event, const uwb_ranging_peer_t *peer)
{
    ESP_LOGI(TAG, "%s ID=%02x:%02x:%02x:%02x:%02x:%02x short=0x%04x",
             event, peer->device_id[0], peer->device_id[1], peer->device_id[2],
             peer->device_id[3], peer->device_id[4], peer->device_id[5],
             peer->short_address);
}

static void on_new_device(const uwb_ranging_peer_t *peer)
{
    print_peer_id("NEW PEER", peer);
}

static void on_inactive_device(const uwb_ranging_peer_t *peer)
{
    for (unsigned i = 0; i < RANGE_FILTER_COUNT; ++i) {
        if (range_filters[i].used &&
            !memcmp(range_filters[i].peer_id, peer->device_id, 6)) {
            memset(&range_filters[i], 0, sizeof(range_filters[i]));
            break;
        }
    }
    print_peer_id("PEER INACTIVE", peer);
}

static range_filter_t *filter_for_peer(const uint8_t peer_id[6])
{
    range_filter_t *free_slot = NULL;
    for (unsigned i = 0; i < RANGE_FILTER_COUNT; ++i) {
        if (range_filters[i].used &&
            !memcmp(range_filters[i].peer_id, peer_id, 6))
            return &range_filters[i];
        if (!range_filters[i].used && !free_slot) free_slot = &range_filters[i];
    }
    if (!free_slot) free_slot = &range_filters[0];
    memset(free_slot, 0, sizeof(*free_slot));
    free_slot->used = true;
    memcpy(free_slot->peer_id, peer_id, 6);
    return free_slot;
}

static float median_range(range_filter_t *filter, float value)
{
    filter->range_window[filter->range_window_next] = value;
    filter->range_window_next = (filter->range_window_next + 1) % 5;
    if (filter->range_window_count < 5) filter->range_window_count++;
    float sorted[5];
    memcpy(sorted, filter->range_window,
           filter->range_window_count * sizeof(float));
    for (unsigned i = 1; i < filter->range_window_count; ++i) {
        float item = sorted[i];
        unsigned j = i;
        while (j && sorted[j - 1] > item) {
            sorted[j] = sorted[j - 1];
            --j;
        }
        sorted[j] = item;
    }
    return sorted[filter->range_window_count / 2];
}

static void on_range(const uwb_ranging_peer_t *peer)
{
    static uint32_t filter_generation;
    if (filter_generation != peer->setup_generation) {
        memset(range_filters, 0, sizeof(range_filters));
        filter_generation = peer->setup_generation;
    }
    range_filter_t *filter = filter_for_peer(peer->device_id);
    telemetry_sample_t sample = {
        .timestamp_ms = esp_timer_get_time() / 1000,
        .setup_generation = peer->setup_generation,
        .distance_m = median_range(filter, peer->range_m),
        .rx_power_dbm = peer->rx_power_dbm,
    };
    if (peer->raw) sample.uwb_raw = *peer->raw;
    memcpy(sample.peer_id, peer->device_id, sizeof(sample.peer_id));
    if (filter->has_previous_range) {
        uint64_t elapsed_ms = sample.timestamp_ms - filter->previous_range_ms;
        if (elapsed_ms > 0 && elapsed_ms < 5000) {
            float instant_speed = (sample.distance_m - filter->previous_distance) *
                                  1000.0f / elapsed_ms;
            filter->filtered_speed = 0.8f * filter->filtered_speed +
                                     0.2f * instant_speed;
            sample.speed_mps = fabsf(filter->filtered_speed) <
                               CONFIG_UWB_SPEED_DEADBAND_MM_S / 1000.0f
                                   ? 0.0f : filter->filtered_speed;
        }
    }
    filter->previous_distance = sample.distance_m;
    filter->previous_range_ms = sample.timestamp_ms;
    filter->has_previous_range = true;

    telemetry_publish(&sample);

}

static void on_wifi_connection_changed(bool connected, void *arg)
{
    (void)arg;
    portENTER_CRITICAL(&sensor_control_lock);
    wifi_connected = connected;
    portEXIT_CRITICAL(&sensor_control_lock);
    sensor_coordinator_notify(SENSOR_COORDINATOR_UWB | SENSOR_COORDINATOR_IMU);
    ESP_LOGI(TAG, "Wi-Fi %s; sensor acquisition %s",
             connected ? "connected" : "disconnected",
             connected && monitoring_requested ? "resumed" : "paused");
}

static void sensing_task(void *argument)
{
    (void)argument;
    ESP_ERROR_CHECK(sensor_coordinator_register(SENSOR_COORDINATOR_UWB));
    sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);
    ESP_LOGI(TAG, "startup: initializing sensors");
#ifdef CONFIG_UWB_MPU6050_ENABLE
    esp_err_t imu_err = init_imu();
    if (imu_err != ESP_OK)
        ESP_LOGW(TAG, "IMU unavailable at startup; its task will retry: %s", esp_err_to_name(imu_err));
    sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);
    if (xTaskCreatePinnedToCore(imu_sampling_task, "imu_sampler", 3072,
                                       NULL, 4, NULL,
                                       portNUM_PROCESSORS > 1 ? 1 : tskNO_AFFINITY) != pdPASS) {
        ESP_LOGE(TAG, "could not start IMU task; continuing with UWB only");
        if (imu) {
            mpu6050_set_sleep(imu, true);
            mpu6050_delete(imu);
            imu = NULL;
        }
        if (imu_bus) {
            i2c_del_master_bus(imu_bus);
            imu_bus = NULL;
        }
    }
#endif
#ifdef CONFIG_UWB_BATTERY_MONITOR_ENABLE
    esp_err_t battery_err = init_battery_monitor();
    if (battery_err != ESP_OK)
        ESP_LOGE(TAG, "battery monitoring unavailable: %s",
                 esp_err_to_name(battery_err));
    else if (xTaskCreatePinnedToCore(battery_logging_task, "battery_log", 2048, NULL,
                         2, NULL, portNUM_PROCESSORS > 1 ? 1 : tskNO_AFFINITY) != pdPASS)
        ESP_LOGE(TAG, "could not start battery logging task");
#endif
    sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);

    uwb_ranging_config_t config = {
        .role = active_role,
        .pan_id = CONFIG_UWB_PAN_ID,
        .antenna_delay = active_antenna_delay,
        .reply_delay_us = CONFIG_UWB_REPLY_DELAY_US,
        .tag_period_ms = CONFIG_UWB_TAG_PERIOD_MS,
        .on_range = on_range,
        .on_new_device = on_new_device,
        .on_inactive_device = on_inactive_device,
        .waiting_log_interval_ms = 0,
        .on_waiting = NULL,
    };
    memcpy(config.device_id, network.mac, sizeof(network.mac));
    ESP_LOGI(TAG, "startup: starting %s ranging on CPU%d",
             role_name(config.role),
             xPortGetCoreID());
    uint32_t retry_ms = 1000;
    for (;;) {
        sensor_coordinator_checkpoint(SENSOR_COORDINATOR_UWB);
        esp_err_t ranging_err = uwb_ranging_run(&config);
        ESP_LOGE(TAG, "UWB service stopped: %s; retrying in %" PRIu32 " ms",
                 esp_err_to_name(ranging_err), retry_ms);
        sensor_delay_with_checkpoint(SENSOR_COORDINATOR_UWB, retry_ms);
        if (retry_ms < 30000) retry_ms *= 2;
        if (retry_ms > 30000) retry_ms = 30000;
    }
}

static void serial_number(char value[24], double number, bool valid, int precision)
{
    if (valid && isfinite(number)) snprintf(value, 24, "%.*f", precision, number);
    else strlcpy(value, "0", 24);
}

#define SERIAL_CSV_FIELD_COUNT 44
#define SERIAL_CSV_VALUE_SIZE 48

/* Calendar dates are IST; unsynchronized devices retain an explicit uptime label. */
static void serial_timestamp(char value[SERIAL_CSV_VALUE_SIZE], time_t now, uint64_t uptime_ms)
{
    if (now >= 1577836800) {
        time_t ist = now + 19800;
        struct tm date;
        if (gmtime_r(&ist, &date) && strftime(value, SERIAL_CSV_VALUE_SIZE, "%d-%m-%Y %H:%M:%S", &date)) return;
    }
    snprintf(value, SERIAL_CSV_VALUE_SIZE, "uptime_ms=%" PRIu64, uptime_ms);
}

/* Raw timing and receiver fields use the same order as the saved CSV. */
static void serial_uwb_columns(const uwb_raw_t *raw,
                               char values[SERIAL_CSV_FIELD_COUNT][SERIAL_CSV_VALUE_SIZE])
{
    if (!raw->valid) return;
    if (isfinite(raw->distance_m)) snprintf(values[5], SERIAL_CSV_VALUE_SIZE, "%.9g", (double)raw->distance_m);
    for (unsigned i = 0; i < UWB_RAW_TIMESTAMP_COUNT; ++i)
        if (raw->timestamp_valid_mask & (1u << i)) snprintf(values[6 + i], SERIAL_CSV_VALUE_SIZE, "%" PRIu64, raw->timestamps[i]);
    if (isfinite(raw->local_rx_power_dbm)) snprintf(values[14], SERIAL_CSV_VALUE_SIZE, "%.9g", (double)raw->local_rx_power_dbm);
    if (isfinite(raw->peer_rx_power_dbm)) snprintf(values[15], SERIAL_CSV_VALUE_SIZE, "%.9g", (double)raw->peer_rx_power_dbm);
    if (raw->rx_diagnostics_mask & 1) snprintf(values[16], SERIAL_CSV_VALUE_SIZE, "%u", raw->cir_power);
    if (raw->rx_diagnostics_mask & 2) {
        snprintf(values[17], SERIAL_CSV_VALUE_SIZE, "%" PRIu32, raw->rx_frame_info);
        snprintf(values[18], SERIAL_CSV_VALUE_SIZE, "%u", (unsigned)((raw->rx_frame_info >> 20) & 0xfff));
    }
    if (raw->tof_valid && isfinite(raw->tof_ticks)) snprintf(values[19], SERIAL_CSV_VALUE_SIZE, "%.17g", raw->tof_ticks);
}

/* Format CIR columns from one coherent snapshot; no SPI access is performed. */
#ifdef CONFIG_UWB_CIR_ENABLE
static bool serial_cir_columns(const dw1000_cir_t *cir, uint64_t now_ms,
                               char values[SERIAL_CSV_FIELD_COUNT][SERIAL_CSV_VALUE_SIZE],
                               const char *fields[SERIAL_CSV_FIELD_COUNT], char **iq)
{
    snprintf(values[22], SERIAL_CSV_VALUE_SIZE, "%" PRIu32, cir->sequence);
    snprintf(values[23], SERIAL_CSV_VALUE_SIZE, "%" PRIu64, cir->timestamp_ms);
    snprintf(values[31], SERIAL_CSV_VALUE_SIZE, "%" PRIu64, now_ms >= cir->timestamp_ms ? now_ms - cir->timestamp_ms : 0);
    snprintf(values[24], SERIAL_CSV_VALUE_SIZE, "%" PRIu64, cir->rx_timestamp);
    snprintf(values[25], SERIAL_CSV_VALUE_SIZE, "%u", cir->peer_address);
    snprintf(values[26], SERIAL_CSV_VALUE_SIZE, "%u", cir->frame_type);
    snprintf(values[27], SERIAL_CSV_VALUE_SIZE, "%u", cir->first_path_index);
    snprintf(values[28], SERIAL_CSV_VALUE_SIZE, "%u", cir->start_index);
    snprintf(values[29], SERIAL_CSV_VALUE_SIZE, "%u", (unsigned)CONFIG_UWB_CIR_SAMPLES);
    snprintf(values[30], SERIAL_CSV_VALUE_SIZE, "%u", cir->preamble_count);
    snprintf(values[32], SERIAL_CSV_VALUE_SIZE, "%" PRIu32, cir->capture_us);
    snprintf(values[33], SERIAL_CSV_VALUE_SIZE, "%u", (unsigned)CONFIG_UWB_CIR_INTERVAL_MS);
    fields[34] = "1.001602564";
    size_t capacity = 16 * CONFIG_UWB_CIR_SAMPLES + 3, used = 1;
    *iq = malloc(capacity);
    if (!*iq) return false;
    (*iq)[0] = '[';
    for (unsigned i = 0; i < CONFIG_UWB_CIR_SAMPLES; ++i) {
        int count = snprintf(*iq + used, capacity - used, "%s[%d,%d]", i ? "," : "", cir->iq[i][0], cir->iq[i][1]);
        if (count < 0 || (size_t)count >= capacity - used) { free(*iq); *iq = NULL; return false; }
        used += (size_t)count;
    }
    (*iq)[used++] = ']'; (*iq)[used] = '\0';
    fields[21] = *iq;
    return true;
}
#endif

/* Keep each terminal sample in one printf call; saved CSV uses its own writer. */
static bool print_csv_sensor_row(const char *const fields[SERIAL_CSV_FIELD_COUNT])
{
    static const char *const names[SERIAL_CSV_FIELD_COUNT] = {
        "timestamp", "source_address", "ip_address", "role", "range",
        "distance_m_unfiltered", "poll_tx", "poll_rx", "ack_tx", "ack_rx",
        "range_tx", "range_rx", "report_tx", "report_rx", "local_rx_power_dbm",
        "peer_rx_power_dbm", "cir_power", "rx_frame_info", "preamble_count", "tof_ticks",
        "tx_power", "iq", "sequence", "timestamp_ms", "rx_timestamp",
        "peer_address", "frame_type", "first_path_index", "start_index", "sample_count",
        "cir_preamble_count", "age_ms", "capture_us", "capture_interval_ms", "sample_period_ns",
        "accel_x", "accel_y", "accel_z", "gyro_x", "gyro_y",
        "gyro_z", "battery", "battery_voltage", "speed_mps"
    };
    size_t capacity = 1;
    for (unsigned i = 0; i < SERIAL_CSV_FIELD_COUNT; ++i) {
        const char *value = fields[i] && fields[i][0] ? fields[i] : "0";
        size_t length = strlen(value), escapes = 0, overhead = strlen(names[i]) + 3;
        for (const char *at = value; *at; ++at) escapes += *at == '\r' || *at == '\n';
        if (capacity > SIZE_MAX - overhead || length > SIZE_MAX - capacity - overhead ||
            escapes > SIZE_MAX - capacity - overhead - length) return false;
        capacity += length + escapes + overhead;
    }
    char *line = malloc(capacity);
    if (!line) return false;
    char *out = line;
    for (unsigned i = 0; i < SERIAL_CSV_FIELD_COUNT; ++i) {
        if (i) { *out++ = ','; *out++ = ' '; }
        size_t length = strlen(names[i]);
        memcpy(out, names[i], length); out += length;
        *out++ = ':';
        const char *value = fields[i] && fields[i][0] ? fields[i] : "0";
        for (const char *at = value; *at; ++at) {
            if (*at == '\r' || *at == '\n') { *out++ = '\\'; *out++ = *at == '\r' ? 'r' : 'n'; }
            else *out++ = *at;
        }
    }
    *out = '\0';
    int written = printf("_________________________________________________________________________________________________________________________\n%s\n", line);
    free(line);
    return written >= 0;
}

/* Console formatting runs on CPU0, never inside the radio/IMU callbacks. */
static void csv_serial_task(void *argument)
{
    (void)argument;
    bool pause_reported = false;
    for (;;) {
        device_setup_snapshot_t setup;
        device_setup_get(&setup);
        if (setup.active || device_setup_pending(&setup)) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        if (!monitoring_requested) {
            if (!pause_reported) {
                ESP_LOGW(TAG, "Device is paused: UWB deep sleep, IMU sleep mode.\n");
                pause_reported = true;
            }
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        pause_reported = false;
        telemetry_sample_t sample;
        char role[8];
        bool range_valid = telemetry_read_snapshot(&sample, role);
        char values[SERIAL_CSV_FIELD_COUNT][SERIAL_CSV_VALUE_SIZE];
        const char *fields[SERIAL_CSV_FIELD_COUNT];
        for (unsigned i = 0; i < SERIAL_CSV_FIELD_COUNT; ++i) { strcpy(values[i], "0"); fields[i] = values[i]; }
        uint64_t now_ms = (uint64_t)(esp_timer_get_time() / 1000);
        serial_timestamp(values[0], (time_t)(telemetry_clock_unix_ms() / 1000), now_ms);
        snprintf(values[1], SERIAL_CSV_VALUE_SIZE, "%02X:%02X:%02X:%02X:%02X:%02X", network.mac[0], network.mac[1], network.mac[2], network.mac[3], network.mac[4], network.mac[5]);
        fields[2] = network.ip_address; fields[3] = role;
        if (range_valid && isfinite(sample.distance_m)) snprintf(values[4], SERIAL_CSV_VALUE_SIZE, "%.9g", (double)sample.distance_m);
        serial_uwb_columns(&sample.uwb_raw, values);
        snprintf(values[20], SERIAL_CSV_VALUE_SIZE, "0x%08" PRIX32, DW1000_TX_POWER_SETTING);
        char *iq = NULL;
#ifdef CONFIG_UWB_CIR_ENABLE
        dw1000_cir_t *cir = malloc(sizeof(*cir));
        if (cir && dw1000_get_cir(cir) && !serial_cir_columns(cir, now_ms, values, fields, &iq))
            ESP_LOGW(TAG, "Could not format CIR samples");
        free(cir);
#endif
        for (unsigned axis = 0; axis < 3; ++axis) {
            serial_number(values[35 + axis], sample.accel[axis], sample.imu_valid, 4);
            serial_number(values[38 + axis], sample.gyro[axis], sample.imu_valid, 4);
        }
        serial_number(values[41], sample.battery_percent, sample.battery_valid, 1);
        serial_number(values[42], sample.battery_voltage, sample.battery_valid, 3);
        serial_number(values[43], sample.speed_mps, range_valid, 4);
        if (monitoring_requested && !print_csv_sensor_row(fields)) ESP_LOGW(TAG, "Could not print CSV sensor row");
        free(iq);
        // Raw CIR can exceed 16 KB; this is a rate-limited console snapshot, not a sample queue.
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

static void setup_supervisor_task(void *argument)
{
    (void)argument;
    bool sensors_started = false;
    bool previous_running = false, previous_sleep = false;
    uint32_t previous_generation = UINT32_MAX;
    uwb_ranging_role_t previous_role = active_role;
    for (;;) {
        device_setup_tick(wifi_connected);
        device_setup_snapshot_t setup;
        device_setup_get(&setup);
        portENTER_CRITICAL(&sensor_control_lock);
        uwb_ranging_role_t desired_role = active_role;
        bool monitoring = monitoring_requested;
        uint32_t desired_period = requested_sensing_interval_ms;
        bool connected = wifi_connected;
        portEXIT_CRITICAL(&sensor_control_lock);
        bool pending = device_setup_pending(&setup);
        bool normal_ready = !pending && telemetry_clock_unix_ms() > 0;
        uwb_ranging_role_t role = setup.active
            ? (!strcmp(setup.role, "anchor") ? UWB_RANGING_ANCHOR : UWB_RANGING_TAG) : desired_role;
        uwb_ranging_set_period(setup.active ? 100 : desired_period);
        device_setup_sensor_policy_t policy = device_setup_sensor_policy(&setup, connected,
            telemetry_clock_unix_ms() > 0, monitoring);
        bool running = policy.running;
        sensor_sleep_allowed = policy.sleep_allowed;
        uwb_ranging_set_sleep_allowed(sensor_sleep_allowed);
        uwb_ranging_set_role(role);
        uwb_ranging_set_setup(setup.generation, setup.peers, setup.peer_count);
        telemetry_set_sensor_state(role_name(role), setup.active || monitoring,
                                   uwb_ranging_get_period(), setup.generation);
        uwb_ranging_set_paused(!running);
#ifdef CONFIG_UWB_MPU6050_ENABLE
        imu_streaming = running;
#endif
        if (running != previous_running || sensor_sleep_allowed != previous_sleep ||
            setup.generation != previous_generation || role != previous_role) {
            sensor_coordinator_notify(SENSOR_COORDINATOR_UWB | SENSOR_COORDINATOR_IMU);
            previous_running = running;
            previous_sleep = sensor_sleep_allowed;
            previous_generation = setup.generation;
            previous_role = role;
        }
        if (!sensors_started && connected && (setup.active || normal_ready) && !sensor_coordinator_ble_active()) {
            if (xTaskCreatePinnedToCore(sensing_task, "uwb_imu_sensing", 8192, NULL, 5, NULL,
                    portNUM_PROCESSORS > 1 ? 1 : tskNO_AFFINITY) == pdPASS) sensors_started = true;
            else ESP_LOGE(TAG, "could not start sensing task; retrying");
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "startup: connecting Wi-Fi; GPIO17 opens Bluetooth setup");
    ESP_ERROR_CHECK(sensor_coordinator_init());
    wifi_set_connection_callback(on_wifi_connection_changed, NULL);
    esp_err_t err = wifi_connect(&network);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Wi-Fi connection failed: %s", esp_err_to_name(err));
        return;
    }
    ESP_ERROR_CHECK(device_setup_init(role_name(active_role)));
    device_setup_snapshot_t setup;
    device_setup_get(&setup);
    active_role = !strcmp(setup.config.role, "anchor") ? UWB_RANGING_ANCHOR : UWB_RANGING_TAG;
    load_antenna_delay();
    ESP_LOGW(TAG, "startup role=%s (saved configuration, or menuconfig before first setup)",
             role_name(active_role));
    ESP_LOGW(TAG, "identity timestamp=%lld ms MAC=%02X:%02X:%02X:%02X:%02X:%02X IP=%s",
             esp_timer_get_time() / 1000, network.mac[0], network.mac[1],
             network.mac[2], network.mac[3], network.mac[4], network.mac[5],
             network.ip_address);

    telemetry_config_t telemetry_config = {
        .ip_address = network.ip_address,
        .role = role_name(active_role),
        .buffer_length = CONFIG_UWB_TELEMETRY_BUFFER_LENGTH,
        .sensing_interval_ms = CONFIG_UWB_TAG_PERIOD_MS,
        .send_interval_ms = CONFIG_UWB_TELEMETRY_SEND_INTERVAL_MS,
        .antenna_delay = active_antenna_delay,
        .on_control = apply_dashboard_control,
        .clock_synchronized = false,
    };
    memcpy(telemetry_config.device_id, network.mac, sizeof(network.mac));
    err = telemetry_start(&telemetry_config);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "startup stopped: dashboard API unavailable: %s", esp_err_to_name(err));
        return;
    }
    if (xTaskCreate(setup_supervisor_task, "setup_supervisor", 4096, NULL, 3, NULL) != pdPASS) {
        ESP_LOGE(TAG, "startup stopped: could not start setup supervisor");
        return;
    }
    ESP_LOGI(TAG, "startup: waiting for confirmed role and required coordinates");
    do {
        device_setup_get(&setup);
        if (!device_setup_pending(&setup) && !setup.active) break;
        vTaskDelay(pdMS_TO_TICKS(100));
    } while (true);
    bool clock_ready = false;
    ESP_LOGI(TAG, "startup: coordinate step complete; synchronizing clock");
    telemetry_set_clock_status(true, false, false);
#ifdef CONFIG_UWB_TIME_SOURCE_API
    clock_ready = time_sync_startup() == ESP_OK;
#endif
    telemetry_set_clock_status(true, clock_ready, !clock_ready);
    if (!clock_ready) ESP_LOGI(TAG, "waiting for phone/computer time; connect the app or dashboard");
    while (!telemetry_wait_for_clock(1000)) {}
    while (!wifi_connected || sensor_coordinator_ble_active()) vTaskDelay(pdMS_TO_TICKS(100));
    ESP_LOGI(TAG, "startup: clock synchronized");
    if (xTaskCreatePinnedToCore(csv_serial_task, "csv_serial", 8192, NULL,
                                    1, NULL, portNUM_PROCESSORS > 1 ? 0 : tskNO_AFFINITY) != pdPASS)
        ESP_LOGW(TAG, "CSV-style serial output unavailable");

}
