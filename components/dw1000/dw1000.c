/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright (c) 2015 Thomas Trojer
 * Copyright (c) 2026 ESP-IDF port contributors
 *
 * Native ESP-IDF port of the register access, initialization, and tuning used
 * by https://github.com/thotro/arduino-dw1000.
 */
#include "dw1000.h"

#include <inttypes.h>
#include <math.h>
#include <stdbool.h>
#include <string.h>
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_log.h"
#include "esp_rom_sys.h"
#include "esp_timer.h"
#include "sdkconfig.h"
#ifdef CONFIG_UWB_CIR_ENABLE
#include "freertos/FreeRTOS.h"
#endif

#define UWB_PIN_SCK  ((gpio_num_t)CONFIG_UWB_DW1000_SCK_GPIO)
#define UWB_PIN_MISO ((gpio_num_t)CONFIG_UWB_DW1000_MISO_GPIO)
#define UWB_PIN_MOSI ((gpio_num_t)CONFIG_UWB_DW1000_MOSI_GPIO)
#define UWB_PIN_CS   ((gpio_num_t)CONFIG_UWB_DW1000_CS_GPIO)
#define UWB_PIN_IRQ  ((gpio_num_t)CONFIG_UWB_DW1000_IRQ_GPIO)
#define UWB_PIN_RST  ((gpio_num_t)CONFIG_UWB_DW1000_RST_GPIO)

#define UWB_SPI_HOST SPI2_HOST
#define UWB_SPI_CLOCK_HZ (2 * 1000 * 1000)
#define UWB_SPI_MAX_DATA 128

#define NO_SUB UINT16_MAX

enum {
    REG_DEV_ID = 0x00, REG_EUI = 0x01, REG_PANADR = 0x03, REG_SYS_CFG = 0x04,
    REG_SYS_TIME = 0x06, REG_TX_FCTRL = 0x08, REG_TX_BUFFER = 0x09,
    REG_DX_TIME = 0x0a, REG_SYS_CTRL = 0x0d, REG_SYS_MASK = 0x0e,
    REG_SYS_STATUS = 0x0f, REG_RX_FINFO = 0x10, REG_RX_BUFFER = 0x11,
    REG_RX_FQUAL = 0x12,
    REG_RX_TIME = 0x15, REG_TX_TIME = 0x17, REG_TX_ANTD = 0x18,
    REG_TX_POWER = 0x1e, REG_CHAN_CTRL = 0x1f, REG_USR_SFD = 0x21,
    REG_AGC_TUNE = 0x23, REG_ACC_MEM = 0x25,
    REG_GPIO_CTRL = 0x26, REG_DRX_TUNE = 0x27,
    REG_RF_CONF = 0x28,
    REG_TX_CAL = 0x2a, REG_FS_CTRL = 0x2b, REG_OTP_IF = 0x2d,
    REG_LDE_IF = 0x2e, REG_PMSC = 0x36,
};

#define SYS_STATUS_TXFRS  (1ULL << 7)
#define SYS_STATUS_RXFCG  (1ULL << 14)
#define SYS_STATUS_RX_ERR ((1ULL << 12) | (1ULL << 15) | (1ULL << 16) | \
                           (1ULL << 17) | (1ULL << 18) | (1ULL << 20) | \
                           (1ULL << 21) | (1ULL << 26))

static const char *TAG = "dw1000";
static uint16_t configured_antenna_delay;
static spi_device_handle_t spi;
static bool spi_bus_initialized;
static bool correct_miso_shift;

#ifdef CONFIG_UWB_CIR_ENABLE
static portMUX_TYPE cir_lock = portMUX_INITIALIZER_UNLOCKED;
static dw1000_cir_t cir_latest;
static bool cir_valid;
#endif

static size_t make_header(uint8_t reg, uint16_t sub, bool write, uint8_t out[3])
{
    if (sub == NO_SUB) {
        out[0] = reg | (write ? 0x80 : 0x00);
        return 1;
    }
    out[0] = reg | (write ? 0xc0 : 0x40);
    if (sub < 128) {
        out[1] = (uint8_t)sub;
        return 2;
    }
    out[1] = 0x80 | (uint8_t)sub;
    out[2] = (uint8_t)(sub >> 7);
    return 3;
}

static esp_err_t reg_write(uint8_t reg, uint16_t sub, const void *data, size_t len)
{
    if (len > UWB_SPI_MAX_DATA) return ESP_ERR_INVALID_SIZE;
    uint8_t tx[3 + UWB_SPI_MAX_DATA];
    size_t header_len = make_header(reg, sub, true, tx);
    memcpy(tx + header_len, data, len);
    spi_transaction_t transaction = {
        .length = (header_len + len) * 8,
        .tx_buffer = tx,
    };
    gpio_set_level(UWB_PIN_CS, 0);
    esp_err_t err = spi_device_polling_transmit(spi, &transaction);
    esp_rom_delay_us(5);
    gpio_set_level(UWB_PIN_CS, 1);
    return err;
}

static esp_err_t reg_read(uint8_t reg, uint16_t sub, void *data, size_t len)
{
    if (len > UWB_SPI_MAX_DATA) return ESP_ERR_INVALID_SIZE;
    uint8_t header[3];
    uint8_t raw[UWB_SPI_MAX_DATA];
    size_t header_len = make_header(reg, sub, false, header);
    spi_transaction_t header_transaction = {
        .length = header_len * 8,
        .tx_buffer = header,
    };
    spi_transaction_t data_transaction = {
        .rxlength = len * 8,
        .rx_buffer = raw,
    };

    /* Keep CS asserted between the DW1000 header and data. Capture the first
     * MISO bit so ESP32-S3 GPIO-matrix reads can be corrected if necessary. */
    gpio_set_level(UWB_PIN_CS, 0);
    esp_err_t err = spi_device_polling_transmit(spi, &header_transaction);
    int first_bit = gpio_get_level(UWB_PIN_MISO);
    if (err == ESP_OK)
        err = spi_device_polling_transmit(spi, &data_transaction);
    esp_rom_delay_us(5);
    gpio_set_level(UWB_PIN_CS, 1);
    ESP_RETURN_ON_ERROR(err, TAG, "SPI read");

    if (!correct_miso_shift) {
        memcpy(data, raw, len);
        return ESP_OK;
    }

    uint8_t carry = first_bit ? 0x80 : 0;
    for (size_t i = 0; i < len; ++i) {
        uint8_t next_carry = (raw[i] & 1) ? 0x80 : 0;
        ((uint8_t *)data)[i] = (raw[i] >> 1) | carry;
        carry = next_carry;
    }
    return ESP_OK;
}

static esp_err_t spi_bus_init(void)
{
    spi_bus_config_t bus = {
        .mosi_io_num = UWB_PIN_MOSI,
        .miso_io_num = UWB_PIN_MISO,
        .sclk_io_num = UWB_PIN_SCK,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = 3 + UWB_SPI_MAX_DATA,
    };
    esp_err_t err = spi_bus_initialize(UWB_SPI_HOST, &bus, SPI_DMA_CH_AUTO);
    if (err == ESP_OK) spi_bus_initialized = true;
    return err;
}

static esp_err_t spi_use_mode(uint8_t mode)
{
    spi_device_interface_config_t device = {
        .clock_speed_hz = UWB_SPI_CLOCK_HZ,
        .mode = mode,
        .spics_io_num = -1,
        .flags = SPI_DEVICE_HALFDUPLEX,
        .queue_size = 1,
    };
    return spi_bus_add_device(UWB_SPI_HOST, &device, &spi);
}

static esp_err_t write_le(uint8_t reg, uint16_t sub, uint64_t value, size_t len)
{
    uint8_t bytes[8];
    if (!len || len > sizeof(bytes)) return ESP_ERR_INVALID_SIZE;
    for (size_t i = 0; i < len; ++i) bytes[i] = value >> (8 * i);
    return reg_write(reg, sub, bytes, len);
}

static esp_err_t read_le(uint8_t reg, uint16_t sub, uint64_t *value, size_t len)
{
    uint8_t bytes[8] = {0};
    if (!value || !len || len > sizeof(bytes)) return ESP_ERR_INVALID_ARG;
    ESP_RETURN_ON_ERROR(reg_read(reg, sub, bytes, len), TAG, "register read");
    *value = 0;
    for (size_t i = 0; i < len; ++i) *value |= (uint64_t)bytes[i] << (8 * i);
    return ESP_OK;
}

static esp_err_t otp_read(uint16_t address, uint32_t *value)
{
    ESP_RETURN_ON_ERROR(write_le(REG_OTP_IF, 0x04, address, 2), TAG, "OTP address");
    ESP_RETURN_ON_ERROR(write_le(REG_OTP_IF, 0x06, 0x03, 1), TAG, "OTP enable");
    ESP_RETURN_ON_ERROR(write_le(REG_OTP_IF, 0x06, 0x01, 1), TAG, "OTP read");
    uint64_t result;
    ESP_RETURN_ON_ERROR(read_le(REG_OTP_IF, 0x0a, &result, 4), TAG, "OTP data");
    ESP_RETURN_ON_ERROR(write_le(REG_OTP_IF, 0x06, 0, 1), TAG, "OTP disable");
    *value = (uint32_t)result;
    return ESP_OK;
}

static esp_err_t force_idle(void)
{
    return write_le(REG_SYS_CTRL, NO_SUB, 1ULL << 6, 4);
}

static esp_err_t clear_status(void)
{
    return write_le(REG_SYS_STATUS, NO_SUB, DW1000_TIMESTAMP_MASK, 5);
}

static esp_err_t set_clock(uint8_t clock)
{
    uint64_t pmsc;
    ESP_RETURN_ON_ERROR(read_le(REG_PMSC, 0x00, &pmsc, 4), TAG, "read clock");
    if (clock == 0) pmsc &= ~0x1ffULL;
    else pmsc = (pmsc & ~0x03ULL) | clock;
    return write_le(REG_PMSC, 0x00, pmsc, 2);
}

static esp_err_t load_lde_microcode(void)
{
    ESP_RETURN_ON_ERROR(write_le(REG_PMSC, 0x00, 0x0301, 2), TAG, "LDE clock");
    ESP_RETURN_ON_ERROR(write_le(REG_OTP_IF, 0x06, 0x8000, 2), TAG, "LDE load");
    esp_rom_delay_us(5000);
    return write_le(REG_PMSC, 0x00, 0x0200, 2);
}

#ifdef CONFIG_UWB_DW1000_ACTIVITY_LEDS
static esp_err_t configure_activity_leds(void)
{
    uint64_t value;
    ESP_RETURN_ON_ERROR(read_le(REG_GPIO_CTRL, 0x00, &value, 4), TAG,
                        "read GPIO mode");
    value = (value & ~(0x00000c00ULL | 0x00003000ULL)) |
            0x00000400ULL | 0x00001000ULL;
    ESP_RETURN_ON_ERROR(write_le(REG_GPIO_CTRL, 0x00, value, 4), TAG,
                        "RXLED/TXLED mode");

    ESP_RETURN_ON_ERROR(read_le(REG_PMSC, 0x00, &value, 4), TAG,
                        "read LED clock");
    /* The DW1000 LED engine needs both the GPIO debounce and kHz clocks. */
    value |= (1ULL << 18) | (1ULL << 23);
    ESP_RETURN_ON_ERROR(write_le(REG_PMSC, 0x00, value, 4), TAG,
                        "enable LED clocks");

    /* A 28 ms pulse stays visible without overlapping the ranging cycle. */
    const uint32_t led_control = 0x00000102;
    ESP_RETURN_ON_ERROR(write_le(REG_PMSC, 0x28, led_control, 4), TAG,
                        "automatic RXLED/TXLED blink");

    uint64_t gpio_mode;
    uint64_t pmsc_ctrl0;
    uint64_t pmsc_ledc;
    ESP_RETURN_ON_ERROR(read_le(REG_GPIO_CTRL, 0x00, &gpio_mode, 4), TAG,
                        "verify GPIO mode");
    ESP_RETURN_ON_ERROR(read_le(REG_PMSC, 0x00, &pmsc_ctrl0, 4), TAG,
                        "verify LED clocks");
    ESP_RETURN_ON_ERROR(read_le(REG_PMSC, 0x28, &pmsc_ledc, 4), TAG,
                        "verify LED control");
    ESP_LOGI(TAG, "BU01 LEDs enabled: RXLED=GPIO2 TXLED=GPIO3 "
                  "GPIO_MODE=0x%08" PRIx32 " PMSC_CTRL0=0x%08" PRIx32
                  " PMSC_LEDC=0x%08" PRIx32,
             (uint32_t)gpio_mode, (uint32_t)pmsc_ctrl0, (uint32_t)pmsc_ledc);
    return ESP_OK;
}
#endif

static esp_err_t reset_chip(void)
{
    gpio_config_t reset_output = {
        .pin_bit_mask = 1ULL << UWB_PIN_RST,
        .mode = GPIO_MODE_OUTPUT,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&reset_output), TAG, "reset output");
    gpio_set_level(UWB_PIN_RST, 0);
    esp_rom_delay_us(2000);
    gpio_config_t reset_float = {
        .pin_bit_mask = 1ULL << UWB_PIN_RST,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&reset_float), TAG, "release reset");
    esp_rom_delay_us(10000);
    return force_idle();
}

/* MODE_LONGDATA_RANGE_ACCURACY profile:
 * channel 2, 110 kb/s, PRF 64 MHz, preamble 2048, code 10. */
static esp_err_t configure_radio(const uint8_t eui[8], uint16_t pan_id,
                                 uint16_t short_address)
{
    uint32_t panadr = ((uint32_t)pan_id << 16) | short_address;
    uint32_t sys_cfg = (1UL << 9) | (1UL << 12) | (1UL << 18) |
                       (1UL << 22) | (1UL << 29);
    const uint8_t tx_fctrl[5] = {0, 0, 0x2a, 0, 0};
    const uint8_t chan_ctrl[4] = {0x22, 0x00, 0x8a, 0x52};
    uint8_t reversed_eui[8];
    for (size_t i = 0; i < sizeof(reversed_eui); ++i)
        reversed_eui[i] = eui[sizeof(reversed_eui) - 1 - i];

    ESP_RETURN_ON_ERROR(reg_write(REG_EUI, NO_SUB, reversed_eui, sizeof(reversed_eui)), TAG, "EUI");
    ESP_RETURN_ON_ERROR(write_le(REG_PANADR, NO_SUB, panadr, 4), TAG, "PAN");
    ESP_RETURN_ON_ERROR(write_le(REG_SYS_CFG, NO_SUB, sys_cfg, 4), TAG, "SYS_CFG");
    ESP_RETURN_ON_ERROR(write_le(REG_SYS_MASK, NO_SUB, 0, 4), TAG, "SYS_MASK");
    ESP_RETURN_ON_ERROR(reg_write(REG_TX_FCTRL, NO_SUB, tx_fctrl, sizeof(tx_fctrl)), TAG, "TX_FCTRL");
    ESP_RETURN_ON_ERROR(reg_write(REG_CHAN_CTRL, NO_SUB, chan_ctrl, sizeof(chan_ctrl)), TAG, "CHAN_CTRL");
    ESP_RETURN_ON_ERROR(write_le(REG_USR_SFD, 0x00, 0x40, 1), TAG, "SFD");
    ESP_RETURN_ON_ERROR(write_le(REG_AGC_TUNE, 0x04, 0x889b, 2), TAG, "AGC1");
    ESP_RETURN_ON_ERROR(write_le(REG_AGC_TUNE, 0x0c, 0x2502a907, 4), TAG, "AGC2");
    ESP_RETURN_ON_ERROR(write_le(REG_AGC_TUNE, 0x12, 0x0035, 2), TAG, "AGC3");
    ESP_RETURN_ON_ERROR(write_le(REG_DRX_TUNE, 0x02, 0x0016, 2), TAG, "DRX0b");
    ESP_RETURN_ON_ERROR(write_le(REG_DRX_TUNE, 0x04, 0x008d, 2), TAG, "DRX1a");
    ESP_RETURN_ON_ERROR(write_le(REG_DRX_TUNE, 0x06, 0x0064, 2), TAG, "DRX1b");
    ESP_RETURN_ON_ERROR(write_le(REG_DRX_TUNE, 0x08, 0x373b0296, 4), TAG, "DRX2");
    ESP_RETURN_ON_ERROR(write_le(REG_DRX_TUNE, 0x26, 0x0028, 2), TAG, "DRX4H");
    ESP_RETURN_ON_ERROR(write_le(REG_LDE_IF, 0x0806, 0x0d, 1), TAG, "LDE1");
    ESP_RETURN_ON_ERROR(write_le(REG_LDE_IF, 0x1806, 0x0607, 2), TAG, "LDE2");
    ESP_RETURN_ON_ERROR(write_le(REG_LDE_IF, 0x2804, 0x0666, 2), TAG, "LDE REPC");
    //
    ESP_RETURN_ON_ERROR(write_le(REG_TX_POWER, NO_SUB, 0x67676767, 4), TAG, "TX power");    //channel 2, 110 kb/s, PRF 64 MHz, preamble 2048, code 10
    //
    ESP_RETURN_ON_ERROR(write_le(REG_RF_CONF, 0x0b, 0xd8, 1), TAG, "RF RX");
    ESP_RETURN_ON_ERROR(write_le(REG_RF_CONF, 0x0c, 0x00045ca0, 4), TAG, "RF TX");
    ESP_RETURN_ON_ERROR(write_le(REG_TX_CAL, 0x0b, 0xc2, 1), TAG, "PG delay");
    ESP_RETURN_ON_ERROR(write_le(REG_FS_CTRL, 0x07, 0x08400508, 4), TAG, "PLL cfg");
    ESP_RETURN_ON_ERROR(write_le(REG_FS_CTRL, 0x0b, 0x26, 1), TAG, "PLL tune");

    uint32_t otp_xtal;
    ESP_RETURN_ON_ERROR(otp_read(0x01e, &otp_xtal), TAG, "XTAL OTP");
    uint8_t otp_xtal_low = (uint8_t)otp_xtal;
    uint8_t xtal_trim = ((otp_xtal_low ? otp_xtal_low : 0x10) & 0x1f) | 0x60;
    ESP_RETURN_ON_ERROR(write_le(REG_FS_CTRL, 0x0e, xtal_trim, 1), TAG, "XTAL trim");
    return dw1000_set_antenna_delay(configured_antenna_delay);
}

static esp_err_t dw1000_init_impl(const uint8_t eui[8], uint16_t pan_id,
                                  uint16_t short_address, uint16_t antenna_delay)
{
    if (!eui) return ESP_ERR_INVALID_ARG;
    ESP_LOGI(TAG, "pins SCK=%d MISO=%d MOSI=%d CS=%d IRQ=%d RST=%d",
             UWB_PIN_SCK, UWB_PIN_MISO, UWB_PIN_MOSI, UWB_PIN_CS,
             UWB_PIN_IRQ, UWB_PIN_RST);
    configured_antenna_delay = antenna_delay;
    gpio_config_t irq_input = {
        .pin_bit_mask = 1ULL << UWB_PIN_IRQ,
        .mode = GPIO_MODE_INPUT,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&irq_input), TAG, "IRQ pin");
    gpio_config_t cs_output = {
        .pin_bit_mask = 1ULL << UWB_PIN_CS,
        .mode = GPIO_MODE_OUTPUT,
    };
    ESP_RETURN_ON_ERROR(gpio_config(&cs_output), TAG, "CS pin");
    gpio_set_level(UWB_PIN_CS, 1);

    ESP_RETURN_ON_ERROR(spi_bus_init(), TAG, "SPI bus initialization");
    uint32_t id = 0;
    uint8_t selected_mode = 0xff;
    for (uint8_t mode = 0; mode < 4; ++mode) {
        ESP_RETURN_ON_ERROR(spi_use_mode(mode), TAG, "SPI device");
        correct_miso_shift = false;
        id = dw1000_device_id();
        ESP_LOGI(TAG, "SPI2 mode %u probe: raw device=0x%08" PRIx32, mode, id);
        if (id != 0xdeca0130) {
            correct_miso_shift = true;
            id = dw1000_device_id();
            ESP_LOGI(TAG, "SPI2 mode %u corrected probe: device=0x%08" PRIx32,
                     mode, id);
        }
        if (id == 0xdeca0130) {
            selected_mode = mode;
            break;
        }
        ESP_RETURN_ON_ERROR(spi_bus_remove_device(spi), TAG, "remove SPI device");
        spi = NULL;
    }
    ESP_RETURN_ON_FALSE(selected_mode != 0xff, ESP_ERR_NOT_FOUND, TAG,
                        "DW1000 not detected in SPI modes 0-3");
    ESP_LOGI(TAG, "BU01 detected using SPI mode %u at %d Hz%s",
             selected_mode, UWB_SPI_CLOCK_HZ,
             correct_miso_shift ? " (ESP32-S3 MISO correction enabled)" : "");
    ESP_RETURN_ON_ERROR(reset_chip(), TAG, "reset");
    id = dw1000_device_id();
    ESP_RETURN_ON_FALSE(id == 0xdeca0130, ESP_ERR_NOT_FOUND, TAG,
                        "unexpected device ID 0x%08" PRIx32
                        " after reset (expected 0xdeca0130, SPI mode %u)",
                        id, selected_mode);
    ESP_RETURN_ON_ERROR(set_clock(1), TAG, "XTI clock");
    esp_rom_delay_us(5000);
    ESP_RETURN_ON_ERROR(load_lde_microcode(), TAG, "LDE microcode");
    esp_rom_delay_us(5000);
    ESP_RETURN_ON_ERROR(set_clock(0), TAG, "auto clock");
    esp_rom_delay_us(5000);
    ESP_RETURN_ON_ERROR(configure_radio(eui, pan_id, short_address), TAG, "radio config");
#ifdef CONFIG_UWB_DW1000_ACTIVITY_LEDS
    ESP_RETURN_ON_ERROR(configure_activity_leds(), TAG, "activity LEDs");
#endif
    ESP_RETURN_ON_ERROR(clear_status(), TAG, "clear status");
    ESP_LOGI(TAG, "DW1000 ready, device=0x%08" PRIx32 ", PAN=0x%04x, address=0x%04x",
             id, pan_id, short_address);
    return ESP_OK;
}

esp_err_t dw1000_deinit(void)
{
    esp_err_t err = ESP_OK;
    if (spi) {
        err = spi_bus_remove_device(spi);
        if (err == ESP_OK) spi = NULL;
    }
    if (spi_bus_initialized && !spi) {
        esp_err_t free_err = spi_bus_free(UWB_SPI_HOST);
        if (err == ESP_OK) err = free_err;
        if (free_err == ESP_OK) spi_bus_initialized = false;
    }
#ifdef CONFIG_UWB_CIR_ENABLE
    portENTER_CRITICAL(&cir_lock);
    cir_valid = false;
    portEXIT_CRITICAL(&cir_lock);
#endif
    return err;
}

esp_err_t dw1000_init(const uint8_t eui[8], uint16_t pan_id,
                      uint16_t short_address, uint16_t antenna_delay)
{
    if (spi || spi_bus_initialized) {
        esp_err_t cleanup_err = dw1000_deinit();
        if (cleanup_err != ESP_OK) return cleanup_err;
    }
    esp_err_t err = dw1000_init_impl(eui, pan_id, short_address, antenna_delay);
    if (err != ESP_OK) {
        esp_err_t cleanup_err = dw1000_deinit();
        if (cleanup_err != ESP_OK)
            ESP_LOGE(TAG, "SPI cleanup after initialization failure: %s",
                     esp_err_to_name(cleanup_err));
    }
    return err;
}

uint32_t dw1000_device_id(void)
{
    uint64_t value = 0;
    return reg_read(REG_DEV_ID, NO_SUB, &value, 4) == ESP_OK ? (uint32_t)value : 0;
}

esp_err_t dw1000_set_antenna_delay(uint16_t antenna_delay)
{
    if (!spi) return ESP_ERR_INVALID_STATE;
    ESP_RETURN_ON_ERROR(write_le(REG_TX_ANTD, NO_SUB, antenna_delay, 2), TAG,
                        "TX antenna delay");
    ESP_RETURN_ON_ERROR(write_le(REG_LDE_IF, 0x1804, antenna_delay, 2), TAG,
                        "RX antenna delay");
    configured_antenna_delay = antenna_delay;
    ESP_LOGI(TAG, "antenna delay=%u DTU", antenna_delay);
    return ESP_OK;
}

static esp_err_t wait_status(uint64_t wanted, uint64_t errors, uint32_t timeout_ms)
{
    int64_t deadline = esp_timer_get_time() + (int64_t)timeout_ms * 1000;
    do {
        uint64_t status;
        ESP_RETURN_ON_ERROR(read_le(REG_SYS_STATUS, NO_SUB, &status, 5), TAG, "status");
        if (status & errors) return ESP_FAIL;
        if (status & wanted) return ESP_OK;
        esp_rom_delay_us(100);
    } while (esp_timer_get_time() < deadline);
    return ESP_ERR_TIMEOUT;
}

esp_err_t dw1000_transmit(const void *data, size_t length,
                          uint64_t delayed_time, uint64_t *actual_tx_time)
{
    if (!data || !length || length > 125) return ESP_ERR_INVALID_ARG;
    ESP_RETURN_ON_ERROR(force_idle(), TAG, "TX idle");
    ESP_RETURN_ON_ERROR(clear_status(), TAG, "TX clear");
    ESP_RETURN_ON_ERROR(reg_write(REG_TX_BUFFER, NO_SUB, data, length), TAG, "TX buffer");

    size_t frame_length = length + 2;
    uint8_t tx_fctrl[5] = {frame_length & 0xff, (frame_length >> 8) & 0x03, 0x2a, 0, 0};
    ESP_RETURN_ON_ERROR(reg_write(REG_TX_FCTRL, NO_SUB, tx_fctrl, sizeof(tx_fctrl)), TAG, "TX control");
    uint32_t sys_ctrl = 1UL << 1;
    if (delayed_time) {
        delayed_time &= DW1000_TIMESTAMP_MASK & ~0x1ffULL;
        ESP_RETURN_ON_ERROR(write_le(REG_DX_TIME, NO_SUB, delayed_time, 5), TAG, "delayed TX");
        sys_ctrl |= 1UL << 2;
    }
    ESP_RETURN_ON_ERROR(write_le(REG_SYS_CTRL, NO_SUB, sys_ctrl, 4), TAG, "start TX");
    esp_err_t err = wait_status(SYS_STATUS_TXFRS, 0, 100);
    if (err == ESP_OK && actual_tx_time) {
        uint64_t timestamp;
        err = read_le(REG_TX_TIME, 0x00, &timestamp, 5);
        if (err == ESP_OK) *actual_tx_time = timestamp & DW1000_TIMESTAMP_MASK;
    }
    force_idle();
    clear_status();
    return err;
}

esp_err_t dw1000_receive(void *data, size_t capacity, size_t *length,
                         uint64_t *rx_time, uint32_t timeout_ms)
{
    if (!data || !capacity) return ESP_ERR_INVALID_ARG;
    if (length) *length = 0;
    ESP_RETURN_ON_ERROR(force_idle(), TAG, "RX idle");
    ESP_RETURN_ON_ERROR(clear_status(), TAG, "RX clear");
    ESP_RETURN_ON_ERROR(write_le(REG_SYS_CTRL, NO_SUB, 1UL << 8, 4), TAG, "start RX");

    esp_err_t ret = wait_status(SYS_STATUS_RXFCG, SYS_STATUS_RX_ERR, timeout_ms);
    if (ret != ESP_OK) {
        force_idle();
        clear_status();
        return ret;
    }
    uint64_t frame_info;
    ESP_GOTO_ON_ERROR(read_le(REG_RX_FINFO, NO_SUB, &frame_info, 4), done, TAG, "RX info");
    size_t received = frame_info & 0x3ff;
    if (received <= 2 || received > 127) {
        ret = ESP_ERR_INVALID_SIZE;
        goto done;
    }
    received -= 2;
    if (received > capacity) {
        ret = ESP_ERR_INVALID_SIZE;
        goto done;
    }
    ESP_GOTO_ON_ERROR(reg_read(REG_RX_BUFFER, NO_SUB, data, received), done, TAG, "RX buffer");
    if (rx_time) {
        uint64_t timestamp;
        ESP_GOTO_ON_ERROR(read_le(REG_RX_TIME, 0x00, &timestamp, 5), done, TAG, "RX time");
        *rx_time = timestamp & DW1000_TIMESTAMP_MASK;
    }
    if (length) *length = received;
done:
    force_idle();
    clear_status();
    return ret;
}

float dw1000_receive_power(void)
{
    uint64_t cir_power = 0;
    uint64_t frame_info = 0;
    if (read_le(REG_RX_FQUAL, 0x06, &cir_power, 2) != ESP_OK ||
        read_le(REG_RX_FINFO, NO_SUB, &frame_info, 4) != ESP_OK) return NAN;
    uint16_t c = cir_power;
    uint16_t n = (frame_info >> 20) & 0x0fff;
    if (!c || !n) return NAN;
    float power = 10.0f * log10f((float)c * 131072.0f / ((float)n * n)) - 121.74f;
    return power <= -88.0f ? power : power + (power + 88.0f) * 1.1667f;
}

#ifdef CONFIG_UWB_CIR_ENABLE
esp_err_t dw1000_capture_cir(uint16_t peer_address, uint8_t frame_type)
{
    /* Only the sensing task writes these. Keep large captures off its stack. */
    static dw1000_cir_t capture;
    static int64_t last_attempt_ms;
    static bool attempted;
    int64_t started_us = esp_timer_get_time();
    int64_t now = started_us / 1000;
    if (attempted && now - last_attempt_ms < CONFIG_UWB_CIR_INTERVAL_MS)
        return ESP_OK;
    attempted = true;
    last_attempt_ms = now;

    uint64_t first_path, frame_info, timestamp, clocks;
    ESP_RETURN_ON_ERROR(read_le(REG_RX_TIME, 0x05, &first_path, 2), TAG, "CIR first path");
    ESP_RETURN_ON_ERROR(read_le(REG_RX_FINFO, NO_SUB, &frame_info, 4), TAG, "CIR preamble");
    ESP_RETURN_ON_ERROR(read_le(REG_RX_TIME, 0x00, &timestamp, 5), TAG, "CIR timestamp");
    unsigned first = first_path >> 6;
    unsigned preamble_count = (frame_info >> 20) & 0x0fff;
    if (first >= 1016 || !preamble_count) return ESP_ERR_INVALID_RESPONSE;
    unsigned start = first > 16 ? first - 16 : 0;
    if (start > 1016 - CONFIG_UWB_CIR_SAMPLES)
        start = 1016 - CONFIG_UWB_CIR_SAMPLES;

    ESP_RETURN_ON_ERROR(read_le(REG_PMSC, 0x00, &clocks, 2), TAG, "CIR clocks");
    /* RXCLKS=PLL, FACE=1, AMCE=1, as in Decawave's READ_ACC_ON sequence. */
    esp_err_t err = write_le(REG_PMSC, 0x00, (clocks & ~0x004cULL) | 0x8048, 2);
    uint8_t raw[UWB_SPI_MAX_DATA];
    for (unsigned offset = 0; err == ESP_OK && offset < CONFIG_UWB_CIR_SAMPLES;) {
        unsigned count = CONFIG_UWB_CIR_SAMPLES - offset;
        if (count > (sizeof(raw) - 1) / 4) count = (sizeof(raw) - 1) / 4;
        /* Every SPI accumulator read starts with one dummy byte. */
        err = reg_read(REG_ACC_MEM, (start + offset) * 4, raw, count * 4 + 1);
        if (err != ESP_OK) break;
        for (unsigned i = 0; i < count; ++i) {
            const uint8_t *sample = raw + 1 + i * 4;
            capture.iq[offset + i][0] = (int16_t)((uint16_t)sample[0] | ((uint16_t)sample[1] << 8));
            capture.iq[offset + i][1] = (int16_t)((uint16_t)sample[2] | ((uint16_t)sample[3] << 8));
        }
        offset += count;
    }
    /* Restore even after a failed enable/read; preserve activity LED settings. */
    esp_err_t restore = write_le(REG_PMSC, 0x00, clocks, 2);
    if (err != ESP_OK) return err;
    if (restore != ESP_OK) return restore;

    capture.timestamp_ms = now;
    capture.capture_us = (uint32_t)(esp_timer_get_time() - started_us);
    capture.rx_timestamp = timestamp & DW1000_TIMESTAMP_MASK;
    capture.peer_address = peer_address;
    capture.frame_type = frame_type;
    capture.first_path_index = first_path;
    capture.start_index = start;
    capture.preamble_count = preamble_count;
    ++capture.sequence;
    portENTER_CRITICAL(&cir_lock);
    cir_latest = capture;
    cir_valid = true;
    portEXIT_CRITICAL(&cir_lock);
    return ESP_OK;
}

bool dw1000_get_cir(dw1000_cir_t *snapshot)
{
    if (!snapshot) return false;
    portENTER_CRITICAL(&cir_lock);
    bool valid = cir_valid;
    if (valid) *snapshot = cir_latest;
    portEXIT_CRITICAL(&cir_lock);
    return valid;
}
#endif

uint64_t dw1000_future_tx_time(uint32_t delay_us)
{
    uint64_t now = 0;
    if (read_le(REG_SYS_TIME, NO_SUB, &now, 5) != ESP_OK) return 0;
    uint64_t delay_ticks = (uint64_t)delay_us * 638976ULL / 10ULL;
    return (now + delay_ticks) & DW1000_TIMESTAMP_MASK & ~0x1ffULL;
}

uint64_t dw1000_expected_tx_time(uint64_t delayed_time)
{
    return (delayed_time + configured_antenna_delay) & DW1000_TIMESTAMP_MASK;
}

uint64_t dw1000_timestamp_diff(uint64_t newer, uint64_t older)
{
    return (newer - older) & DW1000_TIMESTAMP_MASK;
}

double dw1000_ticks_to_meters(double ticks)
{
    return ticks * (299702547.0 / (499200000.0 * 128.0));
}
