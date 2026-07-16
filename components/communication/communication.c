#include "communication.h"

#include <stdio.h>
#include <string.h>

#include "esp_event.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_now.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "freertos/event_groups.h"
#include "nvs_flash.h"
#include "smartcane_config.h"

static const char *TAG = "COMM";

#define WIFI_CONNECTED_BIT BIT0
#define WIFI_FAIL_BIT BIT1
#define HTTP_RESPONSE_CAP 4096

typedef struct {
    char *data;
    int len;
    int cap;
} http_response_t;

typedef struct __attribute__((packed)) {
    char device_id[24];
    uint8_t message_type;
    uint8_t risk_level;
    int16_t front_cm;
    int16_t left_cm;
    int16_t right_cm;
    int16_t down_cm;
    uint32_t timestamp_ms;
} peer_status_packet_t;

static EventGroupHandle_t s_wifi_event_group = NULL;
static bool s_wifi_started = false;
static bool s_espnow_ready = false;
static int s_retry_num = 0;
static location_data_t s_location = {
    .lat = SMARTCANE_MOCK_LAT,
    .lng = SMARTCANE_MOCK_LNG,
    .valid = true,
    .mock = true,
    .accuracy_m = SMARTCANE_GPS_MOCK_ACCURACY_M,
    .hdop = 0.0f,
    .fix_quality = 0,
    .quality = LOCATION_QUALITY_MOCK,
    .provider = "mock",
};
static nearby_risk_summary_t s_remote_summary = {0};

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static void json_escape(const char *src, char *dst, size_t dst_size)
{
    if (dst == NULL || dst_size == 0) {
        return;
    }
    size_t out = 0;
    if (src == NULL) {
        dst[0] = '\0';
        return;
    }
    for (size_t i = 0; src[i] != '\0' && out + 1 < dst_size; ++i) {
        char c = src[i];
        if ((c == '"' || c == '\\') && out + 2 < dst_size) {
            dst[out++] = '\\';
            dst[out++] = c;
        } else if ((unsigned char)c >= 0x20) {
            dst[out++] = c;
        }
    }
    dst[out] = '\0';
}

static const char *json_find_value(const char *json, const char *key)
{
    if (json == NULL || key == NULL) {
        return NULL;
    }

    char pattern[64];
    snprintf(pattern, sizeof(pattern), "\"%s\"", key);
    const char *pos = strstr(json, pattern);
    if (pos == NULL) {
        return NULL;
    }
    pos = strchr(pos + strlen(pattern), ':');
    if (pos == NULL) {
        return NULL;
    }
    pos++;
    while (*pos == ' ' || *pos == '\t' || *pos == '\r' || *pos == '\n') {
        pos++;
    }
    return pos;
}

static int json_get_int(const char *json, const char *key, int default_value)
{
    const char *value = json_find_value(json, key);
    if (value == NULL) {
        return default_value;
    }
    return (int)strtol(value, NULL, 10);
}

static bool json_get_string(const char *json, const char *key, char *out, size_t out_size)
{
    if (out == NULL || out_size == 0) {
        return false;
    }
    out[0] = '\0';

    const char *value = json_find_value(json, key);
    if (value == NULL || *value != '"') {
        return false;
    }
    value++;

    size_t len = 0;
    while (*value != '\0' && *value != '"' && len + 1 < out_size) {
        if (*value == '\\' && value[1] != '\0') {
            value++;
        }
        out[len++] = *value++;
    }
    out[len] = '\0';
    return len > 0;
}

static bool wifi_credentials_configured(void)
{
    return strcmp(SMARTCANE_WIFI_SSID, "YOUR_WIFI_SSID") != 0 &&
           strlen(SMARTCANE_WIFI_SSID) > 0;
}

static void wifi_event_handler(void *arg,
                               esp_event_base_t event_base,
                               int32_t event_id,
                               void *event_data)
{
    (void)arg;
    (void)event_data;

    if (event_base == WIFI_EVENT && event_id == WIFI_EVENT_STA_DISCONNECTED) {
        if (s_retry_num < 5) {
            esp_wifi_connect();
            s_retry_num++;
            ESP_LOGW(TAG, "Wi-Fi disconnected, retry=%d", s_retry_num);
        } else {
            xEventGroupSetBits(s_wifi_event_group, WIFI_FAIL_BIT);
        }
    } else if (event_base == IP_EVENT && event_id == IP_EVENT_STA_GOT_IP) {
        s_retry_num = 0;
        xEventGroupSetBits(s_wifi_event_group, WIFI_CONNECTED_BIT);
        ESP_LOGI(TAG, "Wi-Fi got IP");
    }
}

static esp_err_t http_event_handler(esp_http_client_event_t *evt)
{
    if (evt->event_id == HTTP_EVENT_ON_DATA && evt->user_data != NULL) {
        http_response_t *resp = (http_response_t *)evt->user_data;
        int copy_len = evt->data_len;
        if (resp->len + copy_len >= resp->cap) {
            copy_len = resp->cap - resp->len - 1;
        }
        if (copy_len > 0) {
            memcpy(resp->data + resp->len, evt->data, copy_len);
            resp->len += copy_len;
            resp->data[resp->len] = '\0';
        }
    }
    return ESP_OK;
}

static bool http_request(const char *method,
                         const char *path,
                         const char *payload,
                         char *response,
                         size_t response_size,
                         int *status_code)
{
    if (!communication_connect_wifi()) {
        ESP_LOGW(TAG, "network unavailable: request skipped");
        return false;
    }

    char url[256];
    snprintf(url, sizeof(url), "%s%s", SMARTCANE_SERVER_BASE_URL, path);

    http_response_t resp = {
        .data = response,
        .len = 0,
        .cap = (int)response_size,
    };
    if (response != NULL && response_size > 0) {
        response[0] = '\0';
    }

    esp_http_client_config_t config = {
        .url = url,
        .event_handler = http_event_handler,
        .user_data = &resp,
        .timeout_ms = 8000,
    };

    esp_http_client_handle_t client = esp_http_client_init(&config);
    if (client == NULL) {
        ESP_LOGE(TAG, "esp_http_client_init failed");
        return false;
    }

    if (strcmp(method, "POST") == 0) {
        esp_http_client_set_method(client, HTTP_METHOD_POST);
        esp_http_client_set_header(client, "Content-Type", "application/json");
        if (payload != NULL) {
            esp_http_client_set_post_field(client, payload, (int)strlen(payload));
        }
    } else {
        esp_http_client_set_method(client, HTTP_METHOD_GET);
    }

    esp_err_t ret = esp_http_client_perform(client);
    int status = esp_http_client_get_status_code(client);
    if (status_code != NULL) {
        *status_code = status;
    }
    esp_http_client_cleanup(client);

    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "%s %s failed: %s", method, url, esp_err_to_name(ret));
        return false;
    }
    ESP_LOGI(TAG, "%s %s status=%d", method, url, status);
    return status >= 200 && status < 300;
}

static void espnow_send_cb(const esp_now_send_info_t *tx_info, esp_now_send_status_t status)
{
    (void)tx_info;
    ESP_LOGI(TAG, "ESP-NOW send %s", status == ESP_NOW_SEND_SUCCESS ? "success" : "failed");
}

static void espnow_recv_cb(const esp_now_recv_info_t *recv_info, const uint8_t *data, int len)
{
    (void)recv_info;
    if (data == NULL || len != sizeof(peer_status_packet_t)) {
        return;
    }

    peer_status_packet_t packet;
    memcpy(&packet, data, sizeof(packet));
    if (strncmp(packet.device_id, SMARTCANE_DEVICE_ID, sizeof(packet.device_id)) == 0) {
        return;
    }

    s_remote_summary.available = true;
    s_remote_summary.risk_count++;
    s_remote_summary.updated_at_ms = now_ms();
    s_remote_summary.max_level = (risk_level_t)packet.risk_level;
    if (packet.risk_level == RISK_HIGH) {
        s_remote_summary.high_count++;
    } else if (packet.risk_level == RISK_MEDIUM) {
        s_remote_summary.medium_count++;
    }

    ESP_LOGI(TAG, "peer=%s level=%s front=%d",
             packet.device_id,
             risk_level_to_string((risk_level_t)packet.risk_level),
             packet.front_cm);
}

static void init_espnow(void)
{
#if SMARTCANE_ESPNOW_ENABLED
    esp_err_t ret = esp_now_init();
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "ESP-NOW init failed: %s", esp_err_to_name(ret));
        return;
    }
    s_espnow_ready = true;
    (void)esp_now_register_send_cb(espnow_send_cb);
    (void)esp_now_register_recv_cb(espnow_recv_cb);

    esp_now_peer_info_t peer = {0};
    memset(peer.peer_addr, 0xff, ESP_NOW_ETH_ALEN);
    peer.channel = 0;
    peer.ifidx = WIFI_IF_STA;
    peer.encrypt = false;
    ret = esp_now_add_peer(&peer);
    if (ret != ESP_OK && ret != ESP_ERR_ESPNOW_EXIST) {
        ESP_LOGW(TAG, "ESP-NOW broadcast peer failed: %s", esp_err_to_name(ret));
    }
    ESP_LOGI(TAG, "ESP-NOW ready");
#endif
}

esp_err_t communication_init(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "nvs_flash_init failed: %s", esp_err_to_name(ret));
        return ret;
    }

    ESP_ERROR_CHECK(esp_netif_init());
    ret = esp_event_loop_create_default();
    if (ret != ESP_OK && ret != ESP_ERR_INVALID_STATE) {
        return ret;
    }
    esp_netif_create_default_wifi_sta();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    s_wifi_event_group = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_event_handler_instance_register(WIFI_EVENT,
                                                        ESP_EVENT_ANY_ID,
                                                        &wifi_event_handler,
                                                        NULL,
                                                        NULL));
    ESP_ERROR_CHECK(esp_event_handler_instance_register(IP_EVENT,
                                                        IP_EVENT_STA_GOT_IP,
                                                        &wifi_event_handler,
                                                        NULL,
                                                        NULL));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());
    s_wifi_started = true;
    ESP_LOGI(TAG, "Wi-Fi driver started");

    init_espnow();
    return ESP_OK;
}

bool communication_connect_wifi(void)
{
    if (!wifi_credentials_configured()) {
        ESP_LOGW(TAG, "Wi-Fi credentials not configured");
        return false;
    }
    if (!s_wifi_started) {
        return false;
    }
    if (communication_network_available()) {
        return true;
    }

    wifi_config_t wifi_config = {0};
    strlcpy((char *)wifi_config.sta.ssid, SMARTCANE_WIFI_SSID, sizeof(wifi_config.sta.ssid));
    strlcpy((char *)wifi_config.sta.password, SMARTCANE_WIFI_PASSWORD, sizeof(wifi_config.sta.password));
    wifi_config.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;

    s_retry_num = 0;
    xEventGroupClearBits(s_wifi_event_group, WIFI_CONNECTED_BIT | WIFI_FAIL_BIT);
    ESP_ERROR_CHECK_WITHOUT_ABORT(esp_wifi_set_config(WIFI_IF_STA, &wifi_config));
    ESP_ERROR_CHECK_WITHOUT_ABORT(esp_wifi_connect());

    EventBits_t bits = xEventGroupWaitBits(s_wifi_event_group,
                                           WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
                                           pdFALSE,
                                           pdFALSE,
                                           pdMS_TO_TICKS(SMARTCANE_WIFI_CONNECT_TIMEOUT_MS));
    return (bits & WIFI_CONNECTED_BIT) != 0;
}

bool communication_network_available(void)
{
    if (s_wifi_event_group == NULL) {
        return false;
    }
    return (xEventGroupGetBits(s_wifi_event_group) & WIFI_CONNECTED_BIT) != 0;
}

void communication_set_location(const location_data_t *location)
{
    if (location != NULL && location->valid) {
        s_location = *location;
    }
}

bool communication_upload_location(const location_data_t *location)
{
    if (location == NULL || !location->valid) {
        return false;
    }
    communication_set_location(location);

    char payload[512];
    snprintf(payload,
             sizeof(payload),
             "{\"device_id\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,"
             "\"source\":\"%s\",\"provider\":\"%s\",\"quality\":\"%s\","
             "\"accuracy_m\":%.1f,\"hdop\":%.1f,\"fix_quality\":%u,\"satellite_count\":%u}",
             SMARTCANE_DEVICE_ID,
             location->lat,
             location->lng,
             location->mock ? "mock" : "gps",
             location->provider,
             location_quality_to_string(location->quality),
             location->accuracy_m,
             location->hdop,
             location->fix_quality,
             location->satellite_count);

    char response[HTTP_RESPONSE_CAP];
    int status = 0;
    bool ok = http_request("POST", "/api/locations", payload, response, sizeof(response), &status);
    return ok;
}

bool communication_upload_event(const char *risk_type,
                                const char *risk_level,
                                const char *direction,
                                const char *sensor,
                                int distance_mm,
                                int battery_percent,
                                const distance_readings_t *distances,
                                const char *extra)
{
    if (risk_type == NULL || risk_level == NULL || distances == NULL) {
        return false;
    }

    char extra_escaped[192];
    json_escape(extra == NULL ? "" : extra, extra_escaped, sizeof(extra_escaped));

    char payload[896];
    snprintf(payload,
             sizeof(payload),
             "{\"device_id\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,"
             "\"risk_type\":\"%s\",\"risk_level\":\"%s\",\"level\":\"%s\","
             "\"direction\":\"%s\",\"sensor\":\"%s\",\"distance_mm\":%d,\"battery\":%d,"
             "\"front_cm\":%d,\"left_cm\":%d,\"right_cm\":%d,\"down_cm\":%d,"
             "\"extra_json\":\"%s\"}",
             SMARTCANE_DEVICE_ID,
             s_location.lat,
             s_location.lng,
             risk_type,
             risk_level,
             risk_level,
             direction == NULL ? "none" : direction,
             sensor == NULL ? "unknown" : sensor,
             distance_mm,
             battery_percent,
             distances->front_cm,
             distances->left_cm,
             distances->right_cm,
             distances->down_cm,
             extra_escaped);

    char response[HTTP_RESPONSE_CAP];
    int status = 0;
    bool ok = http_request("POST", "/api/risk-events", payload, response, sizeof(response), &status);
    return ok;
}

bool communication_fetch_nearby(float lat, float lng, nearby_risk_summary_t *out)
{
    if (out == NULL) {
        return false;
    }

    char path[160];
    snprintf(path,
             sizeof(path),
             "/api/risks/nearby?lat=%.6f&lng=%.6f&radius=%d",
             lat,
             lng,
             SMARTCANE_NEARBY_RADIUS_M);
    char response[HTTP_RESPONSE_CAP];
    int status = 0;
    if (!http_request("GET", path, NULL, response, sizeof(response), &status)) {
        return false;
    }

    out->available = true;
    out->risk_count = json_get_int(response, "risk_count", 0);
    out->high_count = json_get_int(response, "high_count", 0);
    out->medium_count = json_get_int(response, "medium_count", 0);
    char max_level[16];
    if (!json_get_string(response, "max_level", max_level, sizeof(max_level))) {
        strlcpy(max_level, "low", sizeof(max_level));
    }
    out->max_level = risk_level_from_string(max_level);
    out->updated_at_ms = now_ms();
    return true;
}

bool communication_fetch_ai_advice(const risk_state_t *risk,
                                   const distance_readings_t *distances,
                                   const nearby_risk_summary_t *history,
                                   char *out,
                                   size_t out_size)
{
    if (risk == NULL || distances == NULL || history == NULL || out == NULL || out_size == 0) {
        return false;
    }

    char reason_escaped[128];
    json_escape(risk->reason, reason_escaped, sizeof(reason_escaped));

    char payload[896];
    snprintf(payload,
             sizeof(payload),
             "{\"device_id\":\"%s\",\"lat\":%.6f,\"lng\":%.6f,"
             "\"risk_type\":\"%s\",\"risk_level\":\"%s\","
             "\"front_cm\":%d,\"left_cm\":%d,\"right_cm\":%d,\"down_cm\":%d,"
             "\"accuracy_m\":%.1f,\"location_quality\":\"%s\","
             "\"nearby_radius_m\":%d,\"extra\":\"%s\"}",
             SMARTCANE_DEVICE_ID,
             s_location.lat,
             s_location.lng,
             risk->risk_type,
             risk_level_to_string(risk->level),
             distances->front_cm,
             distances->left_cm,
             distances->right_cm,
             distances->down_cm,
             s_location.accuracy_m,
             location_quality_to_string(s_location.quality),
             SMARTCANE_NEARBY_RADIUS_M,
             reason_escaped);

    char response[HTTP_RESPONSE_CAP];
    int status = 0;
    bool ok = http_request("POST", "/api/ai/advice", payload, response, sizeof(response), &status);
    if (!ok) {
        return false;
    }

    return json_get_string(response, "advice", out, out_size);
}

bool communication_send_text_command(const char *text, char *out, size_t out_size)
{
    if (text == NULL || out == NULL || out_size == 0) {
        return false;
    }

    char text_escaped[160];
    json_escape(text, text_escaped, sizeof(text_escaped));

    char payload[384];
    snprintf(payload,
             sizeof(payload),
             "{\"device_id\":\"%s\",\"text\":\"%s\",\"lat\":%.6f,\"lng\":%.6f}",
             SMARTCANE_DEVICE_ID,
             text_escaped,
             s_location.lat,
             s_location.lng);

    char response[HTTP_RESPONSE_CAP];
    int status = 0;
    bool ok = http_request("POST", "/api/voice/text-command", payload, response, sizeof(response), &status);
    if (!ok) {
        return false;
    }

    char intent[32] = "unknown";
    char reply[96] = "";
    (void)json_get_string(response, "intent", intent, sizeof(intent));
    (void)json_get_string(response, "reply", reply, sizeof(reply));
    snprintf(out,
             out_size,
             "intent=%s reply=%s",
             intent,
             reply);
    return true;
}

bool communication_espnow_send_status(const distance_readings_t *distances, const risk_state_t *risk)
{
    if (!s_espnow_ready || distances == NULL || risk == NULL) {
        return false;
    }

    peer_status_packet_t packet = {0};
    strlcpy(packet.device_id, SMARTCANE_DEVICE_ID, sizeof(packet.device_id));
    packet.message_type = 1;
    packet.risk_level = (uint8_t)risk->level;
    packet.front_cm = (int16_t)distances->front_cm;
    packet.left_cm = (int16_t)distances->left_cm;
    packet.right_cm = (int16_t)distances->right_cm;
    packet.down_cm = (int16_t)distances->down_cm;
    packet.timestamp_ms = (uint32_t)now_ms();

    uint8_t broadcast[ESP_NOW_ETH_ALEN];
    memset(broadcast, 0xff, sizeof(broadcast));
    esp_err_t ret = esp_now_send(broadcast, (const uint8_t *)&packet, sizeof(packet));
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "ESP-NOW send failed: %s", esp_err_to_name(ret));
        return false;
    }
    return true;
}

nearby_risk_summary_t communication_remote_summary(void)
{
    if (s_remote_summary.available &&
        now_ms() - s_remote_summary.updated_at_ms > SMARTCANE_REMOTE_STATUS_TIMEOUT_MS) {
        s_remote_summary.available = false;
        s_remote_summary.risk_count = 0;
        s_remote_summary.high_count = 0;
        s_remote_summary.medium_count = 0;
        s_remote_summary.max_level = RISK_LOW;
    }
    return s_remote_summary;
}
