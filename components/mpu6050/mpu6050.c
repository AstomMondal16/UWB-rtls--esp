#include "mpu6050.h"

#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define REG_SMPLRT_DIV   0x19
#define REG_CONFIG       0x1a
#define REG_GYRO_CONFIG  0x1b
#define REG_ACCEL_CONFIG 0x1c
#define REG_ACCEL_XOUT_H 0x3b
#define REG_PWR_MGMT_1   0x6b

struct mpu6050_t {
    i2c_master_dev_handle_t device;
    float accel_scale;
    float gyro_scale;
    float gyro_offset[3];
};

static esp_err_t write_register(mpu6050_handle_t handle, uint8_t reg,
                                uint8_t value)
{
    uint8_t command[] = {reg, value};
    return i2c_master_transmit(handle->device, command, sizeof(command), -1);
}

static int16_t be16(const uint8_t *bytes)
{
    return (int16_t)(((uint16_t)bytes[0] << 8) | bytes[1]);
}

esp_err_t mpu6050_create(i2c_master_bus_handle_t bus, uint8_t address,
                         const mpu6050_config_t *config,
                         mpu6050_handle_t *out_handle)
{
    if (!bus || !config || !out_handle || address < 0x68 || address > 0x69 ||
        config->accel_range > MPU6050_ACCEL_RANGE_16G ||
        config->gyro_range > MPU6050_GYRO_RANGE_2000DPS)
        return ESP_ERR_INVALID_ARG;

    mpu6050_handle_t handle = calloc(1, sizeof(*handle));
    if (!handle) return ESP_ERR_NO_MEM;

    i2c_device_config_t device_config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = address,
        .scl_speed_hz = 400000,
    };
    esp_err_t err = i2c_master_bus_add_device(bus, &device_config,
                                               &handle->device);
    if (err != ESP_OK) {
        free(handle);
        return err;
    }

    handle->accel_scale = (float)(2U << config->accel_range) / 32768.0f;
    handle->gyro_scale = (float)(250U << config->gyro_range) / 32768.0f;
    err = write_register(handle, REG_PWR_MGMT_1, 0x00);
    if (err == ESP_OK) {
        vTaskDelay(pdMS_TO_TICKS(100));
        err = write_register(handle, REG_CONFIG, 0x03);
    }
    if (err == ESP_OK)
        err = write_register(handle, REG_SMPLRT_DIV,
                             config->sample_rate_divider);
    if (err == ESP_OK)
        err = write_register(handle, REG_GYRO_CONFIG,
                             (uint8_t)config->gyro_range << 3);
    if (err == ESP_OK)
        err = write_register(handle, REG_ACCEL_CONFIG,
                             (uint8_t)config->accel_range << 3);
    if (err != ESP_OK) {
        i2c_master_bus_rm_device(handle->device);
        free(handle);
        return err;
    }

    *out_handle = handle;
    return ESP_OK;
}

esp_err_t mpu6050_read(mpu6050_handle_t handle, mpu6050_data_t *data)
{
    if (!handle || !data) return ESP_ERR_INVALID_ARG;

    uint8_t reg = REG_ACCEL_XOUT_H;
    uint8_t raw[14];
    esp_err_t err = i2c_master_transmit_receive(handle->device, &reg, 1, raw,
                                                 sizeof(raw), -1);
    if (err != ESP_OK) return err;

    data->raw_accel_x = be16(&raw[0]);
    data->raw_accel_y = be16(&raw[2]);
    data->raw_accel_z = be16(&raw[4]);
    data->raw_temperature = be16(&raw[6]);
    data->raw_gyro_x = be16(&raw[8]);
    data->raw_gyro_y = be16(&raw[10]);
    data->raw_gyro_z = be16(&raw[12]);
    data->accel_x = data->raw_accel_x * handle->accel_scale;
    data->accel_y = data->raw_accel_y * handle->accel_scale;
    data->accel_z = data->raw_accel_z * handle->accel_scale;
    data->temperature = data->raw_temperature / 340.0f + 36.53f;
    data->gyro_x = data->raw_gyro_x * handle->gyro_scale -
                   handle->gyro_offset[0];
    data->gyro_y = data->raw_gyro_y * handle->gyro_scale -
                   handle->gyro_offset[1];
    data->gyro_z = data->raw_gyro_z * handle->gyro_scale -
                   handle->gyro_offset[2];
    return ESP_OK;
}

esp_err_t mpu6050_calibrate(mpu6050_handle_t handle, uint16_t samples,
                            uint32_t delay_ms)
{
    if (!handle || samples == 0) return ESP_ERR_INVALID_ARG;

    float gyro_sum[3] = {0};
    for (uint16_t i = 0; i < samples; ++i) {
        mpu6050_data_t data;
        esp_err_t err = mpu6050_read(handle, &data);
        if (err != ESP_OK) return err;
        gyro_sum[0] += data.raw_gyro_x * handle->gyro_scale;
        gyro_sum[1] += data.raw_gyro_y * handle->gyro_scale;
        gyro_sum[2] += data.raw_gyro_z * handle->gyro_scale;
        vTaskDelay(pdMS_TO_TICKS(delay_ms));
    }
    handle->gyro_offset[0] = gyro_sum[0] / samples;
    handle->gyro_offset[1] = gyro_sum[1] / samples;
    handle->gyro_offset[2] = gyro_sum[2] / samples;
    return ESP_OK;
}

void mpu6050_delete(mpu6050_handle_t handle)
{
    if (handle) {
        i2c_master_bus_rm_device(handle->device);
        free(handle);
    }
}
