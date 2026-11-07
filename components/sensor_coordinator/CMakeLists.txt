idf_component_register(
    SRCS "sensor_coordinator.c"
    INCLUDE_DIRS "include"
    REQUIRES freertos esp_timer
)
