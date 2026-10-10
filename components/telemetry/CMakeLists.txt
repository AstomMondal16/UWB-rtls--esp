idf_component_register(
    SRCS "telemetry.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_http_server esp_timer lwip
    PRIV_REQUIRES dw1000
)
