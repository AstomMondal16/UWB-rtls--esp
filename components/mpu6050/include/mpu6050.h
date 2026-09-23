#pragma once

#include <stdint.h>
#include "driver/i2c_master.h"
#include "esp_err.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    MPU6050_ACCEL_RANGE_2G,
    MPU6050_ACCEL_RANGE_4G,
    MPU6050_ACCEL_RANGE_8G,
    MPU6050_ACCEL_RANGE_16G,
} mpu6050_accel_range_t;

typedef enum {
    MPU6050_GYRO_RANGE_250DPS,
    MPU6050_GYRO_RANGE_500DPS,
    MPU6050_GYRO_RANGE_1000DPS,
    MPU6050_GYRO_RANGE_2000DPS,
} mpu6050_gyro_range_t;

typedef struct {
    mpu6050_accel_range_t accel_range;
    mpu6050_gyro_range_t gyro_range;
    uint8_t sample_rate_divider;
} mpu6050_config_t;

typedef struct {
    int16_t raw_accel_x;
    int16_t raw_accel_y;
    int16_t raw_accel_z;
    int16_t raw_temperature;
    int16_t raw_gyro_x;
    int16_t raw_gyro_y;
    int16_t raw_gyro_z;
    float accel_x;
    float accel_y;
    float accel_z;
    float gyro_x;
    float gyro_y;
    float gyro_z;
    float temperature;
} mpu6050_data_t;

typedef struct mpu6050_t *mpu6050_handle_t;

esp_err_t mpu6050_create(i2c_master_bus_handle_t bus, uint8_t address,
                         const mpu6050_config_t *config,
                         mpu6050_handle_t *out_handle);
esp_err_t mpu6050_read(mpu6050_handle_t handle, mpu6050_data_t *data);
esp_err_t mpu6050_calibrate(mpu6050_handle_t handle, uint16_t samples,
                            uint32_t delay_ms);
void mpu6050_delete(mpu6050_handle_t handle);

#ifdef __cplusplus
}
#endif
