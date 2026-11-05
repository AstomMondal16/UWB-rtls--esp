#pragma once

#include "esp_err.h"

/* Synchronize the RTC-backed UTC clock: Time.Now retries, then SNTP fallback. */
esp_err_t time_sync_startup(void);
