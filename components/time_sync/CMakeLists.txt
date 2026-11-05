set(time_sync_sources)
if(CONFIG_UWB_TIME_SOURCE_API)
    list(APPEND time_sync_sources "time_sync.c")
endif()

idf_component_register(
    SRCS ${time_sync_sources}
    INCLUDE_DIRS "include"
    PRIV_REQUIRES esp_http_client esp_netif mbedtls
)

if(CONFIG_UWB_TIME_SOURCE_API)
    string(TIMESTAMP time_build_epoch "%s" UTC)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE UWB_BUILD_UNIX_SECONDS=${time_build_epoch})
endif()
