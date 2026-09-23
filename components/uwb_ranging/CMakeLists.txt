idf_component_register(
    SRCS "uwb_ranging.c"
    INCLUDE_DIRS "include"
    REQUIRES dw1000 esp_timer
)
