#include "battery_monitor.h"

#include <stdbool.h>
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
#include "esp_adc/adc_oneshot.h"
#include "esp_check.h"
#include "esp_log.h"

static const char *TAG = "battery_monitor";
static adc_oneshot_unit_handle_t adc;
static adc_cali_handle_t calibration;
static adc_channel_t channel;
static battery_monitor_config_t settings;
static bool first_read = true;

esp_err_t battery_monitor_init(const battery_monitor_config_t *config)
{
    if (!config || config->full_mv <= config->empty_mv ||
        config->divider_ratio < 1.0f || config->sample_count == 0)
        return ESP_ERR_INVALID_ARG;

    adc_unit_t unit;
    ESP_RETURN_ON_ERROR(adc_oneshot_io_to_channel(config->gpio, &unit, &channel),
                        TAG, "GPIO is not ADC capable");
    if (unit != ADC_UNIT_1) {
        ESP_LOGE(TAG, "GPIO%d is not on ADC1; ADC2 conflicts with Wi-Fi",
                 config->gpio);
        return ESP_ERR_INVALID_ARG;
    }

    adc_oneshot_unit_init_cfg_t unit_config = {
        .unit_id = unit,
        .ulp_mode = ADC_ULP_MODE_DISABLE,
    };
    ESP_RETURN_ON_ERROR(adc_oneshot_new_unit(&unit_config, &adc), TAG,
                        "ADC initialization");

    adc_oneshot_chan_cfg_t channel_config = {
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    ESP_RETURN_ON_ERROR(adc_oneshot_config_channel(adc, channel, &channel_config),
                        TAG, "ADC channel configuration");

#if ADC_CALI_SCHEME_CURVE_FITTING_SUPPORTED
    adc_cali_curve_fitting_config_t calibration_config = {
        .unit_id = unit,
        .chan = channel,
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    esp_err_t err = adc_cali_create_scheme_curve_fitting(&calibration_config,
                                                          &calibration);
#elif ADC_CALI_SCHEME_LINE_FITTING_SUPPORTED
    adc_cali_line_fitting_config_t calibration_config = {
        .unit_id = unit,
        .atten = ADC_ATTEN_DB_12,
        .bitwidth = ADC_BITWIDTH_DEFAULT,
    };
    esp_err_t err = adc_cali_create_scheme_line_fitting(&calibration_config,
                                                         &calibration);
#else
    esp_err_t err = ESP_ERR_NOT_SUPPORTED;
#endif
    if (err != ESP_OK)
        ESP_LOGW(TAG, "eFuse calibration unavailable; using nominal conversion");

    settings = *config;
    first_read = true;
    ESP_LOGI(TAG, "ready on GPIO%d", settings.gpio);
    return ESP_OK;
}

esp_err_t battery_monitor_read(battery_monitor_data_t *data)
{
    if (!adc || !data) return ESP_ERR_INVALID_STATE;

    int raw_sum = 0;
    for (uint8_t i = 0; i < settings.sample_count; ++i) {
        int raw;
        ESP_RETURN_ON_ERROR(adc_oneshot_read(adc, channel, &raw), TAG,
                            "ADC read");
        raw_sum += raw;
    }
    data->raw = raw_sum / settings.sample_count;
    if (!calibration ||
        adc_cali_raw_to_voltage(calibration, data->raw, &data->pin_mv) != ESP_OK)
        data->pin_mv = data->raw * 3300 / 4095;

    float battery_mv = data->pin_mv * settings.divider_ratio;
    data->voltage = battery_mv / 1000.0f;
    data->percent = (battery_mv - settings.empty_mv) * 100.0f /
                    (settings.full_mv - settings.empty_mv);
    if (data->percent < 0) data->percent = 0;
    if (data->percent > 100) data->percent = 100;

    if (first_read) {
        ESP_LOGI(TAG, "raw=%d pin=%d mV battery=%.0f mV", data->raw,
                 data->pin_mv, battery_mv);
        if (data->raw < 10)
            ESP_LOGW(TAG, "ADC is near zero; check divider wire and common ground");
        first_read = false;
    }
    return ESP_OK;
}
