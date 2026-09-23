#include <math.h>
#include <inttypes.h>
#include <string.h>
#include "sdkconfig.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "nvs.h"
#include "telemetry.h"
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
#ifdef CONFIG_UWB_ROLE_ANCHOR
static uwb_ranging_role_t active_role = UWB_RANGING_ANCHOR;
#else
static uwb_ranging_role_t active_role = UWB_RANGING_TAG;
#endif

static const char *role_name(uwb_ranging_role_t role)
{
    return role == UWB_RANGING_ANCHOR ? "anchor" : "tag";
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
    /* Pause only UWB/IMU acquisition; Wi-Fi and the HTTP control server stay up. */
    uwb_ranging_set_paused(!streaming);
    uwb_ranging_set_period(sensing_interval_ms);
    uwb_ranging_set_antenna_delay(antenna_delay);
    if (antenna_delay != active_antenna_delay) {
        active_antenna_delay = antenna_delay;
        save_antenna_delay(antenna_delay);
    }
    uwb_ranging_role_t requested_role = !strcmp(role, "anchor")
                                            ? UWB_RANGING_ANCHOR
                                            : UWB_RANGING_TAG;
    if (requested_role != active_role) {
        active_role = requested_role;
        uwb_ranging_set_role(active_role);
        ESP_LOGW(TAG, "UWB role switched to %s", role_name(active_role));
    }
    ESP_LOGI(TAG, "sensors %s (Wi-Fi active) sensing=%" PRIu32 " ms network=%" PRIu32
             " ms antenna_delay=%u role=%s",
             streaming ? "resumed" : "paused", sensing_interval_ms,
             send_interval_ms, antenna_delay, role_name(active_role));
}

#ifdef CONFIG_UWB_MPU6050_ENABLE
static mpu6050_handle_t imu;

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
    i2c_master_bus_handle_t bus;
    ESP_RETURN_ON_ERROR(i2c_new_master_bus(&bus_config, &bus), TAG,
                        "MPU6050 I2C initialization");

    const mpu6050_config_t config = {
        .accel_range = MPU6050_ACCEL_RANGE_2G,
        .gyro_range = MPU6050_GYRO_RANGE_250DPS,
        .sample_rate_divider = 9,
    };
    esp_err_t err = mpu6050_create(bus, 0x68, &config, &imu);
    uint8_t address = 0x68;
    if (err != ESP_OK) {
        address = 0x69;
        err = mpu6050_create(bus, address, &config, &imu);
    }
    ESP_RETURN_ON_ERROR(err, TAG, "MPU6050 not found at 0x68 or 0x69");
    ESP_LOGI(TAG, "Keep MPU6050 still in any orientation: calibrating gyro");
    ESP_RETURN_ON_ERROR(mpu6050_calibrate(imu, 300, 5), TAG,
                        "MPU6050 calibration");
    ESP_LOGI(TAG, "MPU6050 ready at 0x%02x on SDA=%d SCL=%d", address,
             CONFIG_UWB_MPU6050_SDA_GPIO, CONFIG_UWB_MPU6050_SCL_GPIO);
    return ESP_OK;
}
#endif

#ifdef CONFIG_UWB_BATTERY_MONITOR_ENABLE
static esp_err_t init_battery_monitor(void)
{
    const battery_monitor_config_t config = {
        .gpio = CONFIG_UWB_BATTERY_ADC_GPIO,
        .empty_mv = CONFIG_UWB_BATTERY_EMPTY_MV,
        .full_mv = CONFIG_UWB_BATTERY_FULL_MV,
        .divider_ratio = 1.39f,
        .sample_count = 16,
    };
    return battery_monitor_init(&config);
}

static bool read_battery(float *voltage, float *percent)
{
    battery_monitor_data_t data;
    if (!voltage || !percent || battery_monitor_read(&data) != ESP_OK)
        return false;
    *voltage = data.voltage;
    *percent = data.percent;
    return true;
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
    range_filter_t *filter = filter_for_peer(peer->device_id);
    telemetry_sample_t sample = {
        .timestamp_ms = esp_timer_get_time() / 1000,
        .distance_m = median_range(filter, peer->range_m),
        .rx_power_dbm = peer->rx_power_dbm,
    };
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

#ifdef CONFIG_UWB_MPU6050_ENABLE
    if (imu) {
        mpu6050_data_t data;
        esp_err_t err = mpu6050_read(imu, &data);
        if (err == ESP_OK) {
            sample.imu_valid = true;
            sample.accel[0] = data.accel_x;
            sample.accel[1] = data.accel_y;
            sample.accel[2] = data.accel_z;
            sample.gyro[0] = data.gyro_x;
            sample.gyro[1] = data.gyro_y;
            sample.gyro[2] = data.gyro_z;
            sample.temperature_c = data.temperature;
        } else {
            ESP_LOGW(TAG, "MPU6050 read failed: %s", esp_err_to_name(err));
        }
    }
#endif

#ifdef CONFIG_UWB_BATTERY_MONITOR_ENABLE
    sample.battery_valid = read_battery(&sample.battery_voltage,
                                        &sample.battery_percent);
#endif
    telemetry_publish(&sample);

    ESP_LOGI(TAG, "distance=%.3f m speed=%+.3f m/s RX=%.2f dBm IMU=%s "
             "accel=[%.3f %.3f %.3f] g gyro=[%.3f %.3f %.3f] dps "
             "battery=%s %.2f V %.0f%%",
             sample.distance_m, sample.speed_mps, sample.rx_power_dbm,
             sample.imu_valid ? "OK" : "N/A",
             sample.accel[0], sample.accel[1], sample.accel[2],
             sample.gyro[0], sample.gyro[1], sample.gyro[2],
             sample.battery_valid ? "OK" : "N/A",
             sample.battery_voltage, sample.battery_percent);
}

static void sensing_task(void *argument)
{
    (void)argument;
#ifdef CONFIG_UWB_MPU6050_ENABLE
    esp_err_t err = init_imu();
    if (err != ESP_OK) {
        imu = NULL;
        ESP_LOGE(TAG, "IMU unavailable: %s; continuing with UWB",
                 esp_err_to_name(err));
    }
#endif
#ifdef CONFIG_UWB_BATTERY_MONITOR_ENABLE
    esp_err_t battery_err = init_battery_monitor();
    if (battery_err != ESP_OK)
        ESP_LOGE(TAG, "battery monitoring unavailable: %s",
                 esp_err_to_name(battery_err));
#endif

    uwb_ranging_config_t config = {
        .role = active_role,
        .pan_id = CONFIG_UWB_PAN_ID,
        .antenna_delay = active_antenna_delay,
        .reply_delay_us = CONFIG_UWB_REPLY_DELAY_US,
        .tag_period_ms = CONFIG_UWB_TAG_PERIOD_MS,
        .on_range = on_range,
        .on_new_device = on_new_device,
        .on_inactive_device = on_inactive_device,
    };
    memcpy(config.device_id, network.mac, sizeof(network.mac));
    ESP_LOGW(TAG, "sensing started on CPU%d as %s",
             xPortGetCoreID(),
             config.role == UWB_RANGING_ANCHOR ? "ANCHOR" : "TAG");
    esp_err_t ranging_err = uwb_ranging_run(&config);
    ESP_LOGE(TAG, "UWB stopped: %s", esp_err_to_name(ranging_err));
    vTaskDelete(NULL);
}

void app_main(void)
{
    esp_err_t err = wifi_connect(&network);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "Wi-Fi connection failed: %s", esp_err_to_name(err));
        return;
    }
    load_antenna_delay();
    ESP_LOGW(TAG, "startup role=%s (menuconfig; dashboard changes are runtime only)",
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
    };
    memcpy(telemetry_config.device_id, network.mac, sizeof(network.mac));
    err = telemetry_start(&telemetry_config);
    if (err != ESP_OK)
        ESP_LOGE(TAG, "Wi-Fi telemetry unavailable: %s", esp_err_to_name(err));

    BaseType_t sensing_core = portNUM_PROCESSORS > 1 ? 1 : tskNO_AFFINITY;
    if (xTaskCreatePinnedToCore(sensing_task, "uwb_imu_sensing", 8192, NULL,
                                5, NULL, sensing_core) != pdPASS)
        ESP_LOGE(TAG, "could not start sensing task");
}
