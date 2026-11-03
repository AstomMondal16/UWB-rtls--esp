idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "."
    REQUIRES battery_monitor mpu6050 nvs_flash sensor_coordinator telemetry uwb_ranging wifi
)
