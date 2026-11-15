idf_component_register(
    SRCS "telemetry.c" "uwb_raw_json.c" "device_setup.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_http_server esp_https_server esp_timer lwip uwb_ranging
    PRIV_REQUIRES dw1000 device_identity nvs_flash
)
