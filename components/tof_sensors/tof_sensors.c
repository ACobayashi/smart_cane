#include "tof_sensors.h"

#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "i2c_bus.h"
#include "smartcane_config.h"

static const char *TAG = "SENSOR";

static bool s_mock_active = false;
static bool s_channel_ready[4] = {false};
static const uint8_t s_channels[4] = {
    SMARTCANE_TCA_CH_TOF_FRONT,
    SMARTCANE_TCA_CH_TOF_LEFT,
    SMARTCANE_TCA_CH_TOF_RIGHT,
    SMARTCANE_TCA_CH_TOF_DOWN,
};

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static esp_err_t vl53l1x_read_reg16(uint16_t reg, uint16_t *value)
{
    if (value == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    uint8_t reg_buf[2] = {
        (uint8_t)(reg >> 8),
        (uint8_t)(reg & 0xff),
    };
    uint8_t data[2] = {0};
    esp_err_t ret = i2c_bus_write_read(SMARTCANE_VL53L1X_ADDR, reg_buf, sizeof(reg_buf), data, sizeof(data), 50);
    if (ret != ESP_OK) {
        return ret;
    }
    *value = (uint16_t)((data[0] << 8) | data[1]);
    return ESP_OK;
}

#if !SMARTCANE_MOCK_SENSOR_MODE
static esp_err_t vl53l1x_write_reg8(uint16_t reg, uint8_t value)
{
    uint8_t data[3] = {
        (uint8_t)(reg >> 8),
        (uint8_t)(reg & 0xff),
        value,
    };
    return i2c_bus_write(SMARTCANE_VL53L1X_ADDR, data, sizeof(data), 50);
}

static bool vl53l1x_probe_and_start(uint8_t channel)
{
    if (i2c_bus_select_tca_channel(channel) != ESP_OK) {
        return false;
    }
    if (!i2c_bus_probe(SMARTCANE_VL53L1X_ADDR)) {
        return false;
    }

    /*
     * Native lightweight start sequence. Full factory calibration can be added
     * later if a project requires maximum ranging accuracy.
     */
    (void)vl53l1x_write_reg8(0x0087, 0x40);
    return true;
}
#endif

static int clamp_distance_cm(uint16_t mm)
{
    if (mm == 0 || mm > 4000) {
        return 400;
    }
    return (int)((mm + 5) / 10);
}

static bool vl53l1x_read_distance_cm(uint8_t channel, int *distance_cm)
{
    if (distance_cm == NULL) {
        return false;
    }
    if (i2c_bus_select_tca_channel(channel) != ESP_OK) {
        return false;
    }

    uint16_t range_mm = 0;
    esp_err_t ret = vl53l1x_read_reg16(0x0096, &range_mm);
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "VL53L1X read channel %u failed: %s", channel, esp_err_to_name(ret));
        return false;
    }

    *distance_cm = clamp_distance_cm(range_mm);
    return true;
}

static void fill_mock_distances(distance_readings_t *out)
{
    int64_t phase = (now_ms() / 5000) % 6;

    out->front_cm = 180;
    out->left_cm = 145;
    out->right_cm = 145;
    out->down_cm = SMARTCANE_GROUND_BASE_CM;

    if (phase == 1) {
        out->front_cm = 95;
        out->left_cm = 135;
        out->right_cm = 70;
    } else if (phase == 2) {
        out->front_cm = 45;
        out->left_cm = 65;
        out->right_cm = 140;
    } else if (phase == 3) {
        out->front_cm = 48;
        out->left_cm = 45;
        out->right_cm = 50;
    } else if (phase == 4) {
        out->down_cm = SMARTCANE_GROUND_BASE_CM + SMARTCANE_GROUND_DROP_THRESHOLD_CM + 18;
    } else if (phase == 5) {
        out->front_cm = 130;
        out->left_cm = 45;
        out->right_cm = 145;
    }

    out->valid = true;
    out->timestamp_ms = now_ms();
}

esp_err_t tof_sensors_init(void)
{
#if SMARTCANE_MOCK_SENSOR_MODE
    s_mock_active = true;
    ESP_LOGW(TAG, "ToF mock mode enabled by configuration");
    return ESP_OK;
#else
    bool all_ready = true;
    for (int i = 0; i < 4; ++i) {
        s_channel_ready[i] = vl53l1x_probe_and_start(s_channels[i]);
        ESP_LOGI(TAG, "VL53L1X channel %u %s", s_channels[i], s_channel_ready[i] ? "ready" : "missing");
        all_ready = all_ready && s_channel_ready[i];
    }

    s_mock_active = !all_ready;
    if (s_mock_active) {
        ESP_LOGW(TAG, "ToF hardware incomplete, fallback to mock ranging");
    }
    return all_ready ? ESP_OK : ESP_ERR_NOT_FOUND;
#endif
}

esp_err_t tof_sensors_read(distance_readings_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    memset(out, 0, sizeof(*out));

    if (s_mock_active) {
        fill_mock_distances(out);
        return ESP_OK;
    }

    int values[4] = {400, 400, 400, 400};
    bool ok = true;
    for (int i = 0; i < 4; ++i) {
        if (!s_channel_ready[i] || !vl53l1x_read_distance_cm(s_channels[i], &values[i])) {
            ok = false;
        }
    }

    out->front_cm = values[0];
    out->left_cm = values[1];
    out->right_cm = values[2];
    out->down_cm = values[3];
    out->valid = ok;
    out->timestamp_ms = now_ms();
    return ok ? ESP_OK : ESP_FAIL;
}

bool tof_sensors_mock_active(void)
{
    return s_mock_active;
}

