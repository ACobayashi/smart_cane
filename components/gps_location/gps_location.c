#include "gps_location.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "driver/gpio.h"
#include "driver/uart.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "smartcane_config.h"

static const char *TAG = "GPS";

static location_data_t s_location = {
    .lat = SMARTCANE_MOCK_LAT,
    .lng = SMARTCANE_MOCK_LNG,
    .valid = true,
    .mock = true,
    .accuracy_m = SMARTCANE_GPS_MOCK_ACCURACY_M,
    .hdop = 0.0f,
    .fix_quality = 0,
    .satellite_count = 0,
    .quality = LOCATION_QUALITY_MOCK,
    .provider = "mock",
};

static char s_line[160];
static size_t s_line_len = 0;

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static void use_mock_location(void)
{
    s_location.lat = SMARTCANE_MOCK_LAT;
    s_location.lng = SMARTCANE_MOCK_LNG;
    s_location.valid = true;
    s_location.mock = true;
    s_location.accuracy_m = SMARTCANE_GPS_MOCK_ACCURACY_M;
    s_location.hdop = 0.0f;
    s_location.fix_quality = 0;
    s_location.satellite_count = 0;
    s_location.quality = LOCATION_QUALITY_MOCK;
    strlcpy(s_location.provider, "mock", sizeof(s_location.provider));
    s_location.updated_at_ms = now_ms();
}

static const char *talker_to_provider(const char *line)
{
    if (line == NULL || line[0] != '$') {
        return "unknown";
    }
    if (strncmp(line + 1, "BD", 2) == 0 || strncmp(line + 1, "GB", 2) == 0) {
        return "beidou";
    }
    if (strncmp(line + 1, "GN", 2) == 0) {
        return "gnss";
    }
    if (strncmp(line + 1, "GP", 2) == 0) {
        return "gps";
    }
    return "gnss";
}

static location_quality_t calculate_quality(const location_data_t *loc)
{
    if (loc == NULL || !loc->valid) {
        return LOCATION_QUALITY_STALE;
    }
    if (loc->mock) {
        return LOCATION_QUALITY_MOCK;
    }
    if (now_ms() - loc->updated_at_ms > SMARTCANE_GPS_FIX_STALE_MS) {
        return LOCATION_QUALITY_STALE;
    }
    if (loc->satellite_count >= 8 && loc->accuracy_m > 0.0f && loc->accuracy_m <= 10.0f) {
        return LOCATION_QUALITY_GOOD;
    }
    if (loc->satellite_count >= 4 && loc->accuracy_m > 0.0f && loc->accuracy_m <= 30.0f) {
        return LOCATION_QUALITY_USABLE;
    }
    return LOCATION_QUALITY_POOR;
}

static double nmea_coord_to_decimal(const char *coord, const char *hemisphere)
{
    if (coord == NULL || hemisphere == NULL || coord[0] == '\0') {
        return NAN;
    }

    double raw = strtod(coord, NULL);
    int degrees = (int)(raw / 100.0);
    double minutes = raw - degrees * 100.0;
    double decimal = degrees + minutes / 60.0;
    if (hemisphere[0] == 'S' || hemisphere[0] == 'W') {
        decimal = -decimal;
    }
    return decimal;
}

static bool split_nmea(char *line, char *fields[], size_t max_fields, size_t *out_count)
{
    if (line == NULL || fields == NULL || out_count == NULL) {
        return false;
    }

    char *checksum = strchr(line, '*');
    if (checksum != NULL) {
        *checksum = '\0';
    }

    size_t count = 0;
    char *saveptr = NULL;
    char *token = strtok_r(line, ",", &saveptr);
    while (token != NULL && count < max_fields) {
        fields[count++] = token;
        token = strtok_r(NULL, ",", &saveptr);
    }
    *out_count = count;
    return count > 0;
}

static void parse_rmc(char *line, const char *provider)
{
    char *fields[16] = {0};
    size_t count = 0;
    if (!split_nmea(line, fields, 16, &count) || count < 7) {
        return;
    }

    if (fields[2][0] != 'A') {
        return;
    }

    double lat = nmea_coord_to_decimal(fields[3], fields[4]);
    double lng = nmea_coord_to_decimal(fields[5], fields[6]);
    if (isnan(lat) || isnan(lng)) {
        return;
    }

    s_location.lat = (float)lat;
    s_location.lng = (float)lng;
    s_location.valid = true;
    s_location.mock = false;
    strlcpy(s_location.provider, provider, sizeof(s_location.provider));
    s_location.updated_at_ms = now_ms();
    s_location.quality = calculate_quality(&s_location);
}

static void parse_gga(char *line, const char *provider)
{
    char *fields[16] = {0};
    size_t count = 0;
    if (!split_nmea(line, fields, 16, &count) || count < 9) {
        return;
    }

    int fix_quality = atoi(fields[6]);
    if (fix_quality <= 0) {
        return;
    }

    double lat = nmea_coord_to_decimal(fields[2], fields[3]);
    double lng = nmea_coord_to_decimal(fields[4], fields[5]);
    if (isnan(lat) || isnan(lng)) {
        return;
    }

    s_location.lat = (float)lat;
    s_location.lng = (float)lng;
    s_location.valid = true;
    s_location.mock = false;
    s_location.fix_quality = (uint8_t)fix_quality;
    s_location.satellite_count = (uint8_t)atoi(fields[7]);
    double hdop = strtod(fields[8], NULL);
    s_location.hdop = hdop > 0.0 ? (float)hdop : 0.0f;
    s_location.accuracy_m = hdop > 0.0 ? (float)(hdop * 5.0) : 10.0f;
    strlcpy(s_location.provider, provider, sizeof(s_location.provider));
    s_location.updated_at_ms = now_ms();
    s_location.quality = calculate_quality(&s_location);
}

static void parse_line(const char *line)
{
    if (line == NULL || line[0] != '$') {
        return;
    }

    char copy[160];
    strlcpy(copy, line, sizeof(copy));
    const char *provider = talker_to_provider(copy);

    if (strncmp(copy + 3, "RMC", 3) == 0) {
        parse_rmc(copy, provider);
    } else if (strncmp(copy + 3, "GGA", 3) == 0) {
        parse_gga(copy, provider);
    }
}

esp_err_t gps_location_init(void)
{
    uart_config_t uart_config = {
        .baud_rate = SMARTCANE_GPS_BAUD,
        .data_bits = UART_DATA_8_BITS,
        .parity = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
        .source_clk = UART_SCLK_DEFAULT,
    };

    uart_port_t port = (uart_port_t)SMARTCANE_GPS_UART_NUM;
    esp_err_t ret = uart_driver_install(port, 2048, 0, 0, NULL, 0);
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        ESP_LOGE(TAG, "uart_driver_install failed: %s", esp_err_to_name(ret));
        return ret;
    }

    ESP_ERROR_CHECK_WITHOUT_ABORT(uart_param_config(port, &uart_config));
    ESP_ERROR_CHECK_WITHOUT_ABORT(uart_set_pin(port,
                                               SMARTCANE_GPS_TX_GPIO,
                                               SMARTCANE_GPS_RX_GPIO,
                                               UART_PIN_NO_CHANGE,
                                               UART_PIN_NO_CHANGE));

#if SMARTCANE_GPS_MOCK_FALLBACK
    use_mock_location();
#endif
    ESP_LOGI(TAG, "GPS UART ready port=%d rx=%d tx=%d baud=%d",
             SMARTCANE_GPS_UART_NUM,
             SMARTCANE_GPS_RX_GPIO,
             SMARTCANE_GPS_TX_GPIO,
             SMARTCANE_GPS_BAUD);
    return ESP_OK;
}

void gps_location_update(void)
{
    uint8_t byte = 0;
    uart_port_t port = (uart_port_t)SMARTCANE_GPS_UART_NUM;
    while (uart_read_bytes(port, &byte, 1, 0) == 1) {
        if (byte == '\n' || byte == '\r') {
            if (s_line_len > 0) {
                s_line[s_line_len] = '\0';
                parse_line(s_line);
                s_line_len = 0;
            }
        } else if (s_line_len < sizeof(s_line) - 1) {
            s_line[s_line_len++] = (char)byte;
        } else {
            s_line_len = 0;
        }
    }

#if SMARTCANE_GPS_MOCK_FALLBACK
    if (!s_location.valid ||
        (!s_location.mock && now_ms() - s_location.updated_at_ms > SMARTCANE_GPS_FIX_STALE_MS)) {
        use_mock_location();
    }
#else
    s_location.quality = calculate_quality(&s_location);
#endif
}

location_data_t gps_location_get(void)
{
    gps_location_update();
    return s_location;
}

bool gps_location_has_real_fix(void)
{
    return s_location.valid && !s_location.mock;
}

location_quality_t gps_location_quality(void)
{
    s_location.quality = calculate_quality(&s_location);
    return s_location.quality;
}

void gps_location_log_status(void)
{
    location_data_t loc = gps_location_get();
    ESP_LOGI(TAG, "location lat=%.6f lng=%.6f provider=%s quality=%s acc=%.1fm hdop=%.1f sats=%u",
             loc.lat,
             loc.lng,
             loc.provider,
             location_quality_to_string(loc.quality),
             loc.accuracy_m,
             loc.hdop,
             loc.satellite_count);
}

