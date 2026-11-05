set(telemetry_tls_files)
if(CONFIG_UWB_TLS_ENABLE)
    set(telemetry_tls_files "certs/device_cert.pem" "certs/device_key.pem")
endif()

idf_component_register(
    SRCS "telemetry.c" "uwb_raw_json.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_http_server esp_https_server esp_timer lwip uwb_ranging
    PRIV_REQUIRES dw1000
    EMBED_TXTFILES ${telemetry_tls_files}
)
