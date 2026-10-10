idf_component_register(
    SRCS "ble_provisioning.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_driver_gpio esp_wifi network_provisioning
)
