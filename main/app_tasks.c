#include "app_tasks.h"

#include <stdio.h>
#include <string.h>

#include "app_types.h"
#include "buttons.h"
#include "buzzer.h"
#include "communication.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "gps_location.h"
#include "i2c_bus.h"
#include "risk_logic.h"
#include "smartcane_config.h"
#include "tof_sensors.h"
#include "touch_input.h"
#include "vibration_motor.h"

static const char *TAG = "APP";

typedef enum {
    LAST_PATTERN_NONE = 0,
    LAST_PATTERN_OBSTACLE,
    LAST_PATTERN_GROUND_DROP,
    LAST_PATTERN_TURN_LEFT,
    LAST_PATTERN_TURN_RIGHT,
    LAST_PATTERN_STOP,
    LAST_PATTERN_SOS,
} last_feedback_pattern_t;

static SemaphoreHandle_t s_state_mutex;
static distance_readings_t s_distances = {
    .front_cm = 200,
    .left_cm = 160,
    .right_cm = 160,
    .down_cm = SMARTCANE_GROUND_BASE_CM,
    .valid = false,
};
static nearby_risk_summary_t s_history = {0};
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
static risk_state_t s_risk = {
    .level = RISK_LOW,
    .risk_type = "none",
    .direction_hint = "none",
    .reason = "boot",
};
static bool s_online_mode = true;
static last_feedback_pattern_t s_last_pattern = LAST_PATTERN_NONE;
static int64_t s_last_feedback_ms = 0;
static int64_t s_last_upload_ms = 0;

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static void copy_state(distance_readings_t *distances,
                       risk_state_t *risk,
                       nearby_risk_summary_t *history,
                       location_data_t *location)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    if (distances != NULL) {
        *distances = s_distances;
    }
    if (risk != NULL) {
        *risk = s_risk;
    }
    if (history != NULL) {
        *history = s_history;
    }
    if (location != NULL) {
        *location = s_location;
    }
    xSemaphoreGive(s_state_mutex);
}

static void set_distances(const distance_readings_t *distances)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_distances = *distances;
    xSemaphoreGive(s_state_mutex);
}

static void set_location(const location_data_t *location)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_location = *location;
    xSemaphoreGive(s_state_mutex);
}

static void set_history(const nearby_risk_summary_t *history)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_history = *history;
    xSemaphoreGive(s_state_mutex);
}

static void set_risk(const risk_state_t *risk)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_risk = *risk;
    xSemaphoreGive(s_state_mutex);
}

static bool online_mode_get(void)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    bool online = s_online_mode;
    xSemaphoreGive(s_state_mutex);
    return online;
}

static void online_mode_toggle(void)
{
    xSemaphoreTake(s_state_mutex, portMAX_DELAY);
    s_online_mode = !s_online_mode;
    ESP_LOGI(TAG, "mode switched to %s", s_online_mode ? "network" : "local");
    xSemaphoreGive(s_state_mutex);
}

static void run_pattern(last_feedback_pattern_t pattern)
{
    switch (pattern) {
    case LAST_PATTERN_OBSTACLE:
        vibration_motor_pattern_obstacle();
        break;
    case LAST_PATTERN_GROUND_DROP:
        vibration_motor_pattern_ground_drop();
        buzzer_pattern_danger();
        break;
    case LAST_PATTERN_TURN_LEFT:
        vibration_motor_pattern_turn_left();
        break;
    case LAST_PATTERN_TURN_RIGHT:
        vibration_motor_pattern_turn_right();
        break;
    case LAST_PATTERN_STOP:
        vibration_motor_pattern_stop();
        buzzer_beep(SMARTCANE_BEEP_SHORT_MS);
        break;
    case LAST_PATTERN_SOS:
        vibration_motor_pattern_sos();
        buzzer_pattern_sos();
        break;
    case LAST_PATTERN_NONE:
    default:
        ESP_LOGI(TAG, "no previous feedback pattern");
        break;
    }
}

static int event_distance_mm(const char *risk_type, const distance_readings_t *distances)
{
    if (risk_type == NULL || distances == NULL) {
        return -1;
    }
    if (strcmp(risk_type, "ground_drop") == 0) {
        return distances->down_cm * 10;
    }
    if (strcmp(risk_type, "left_obstacle") == 0) {
        return distances->left_cm * 10;
    }
    if (strcmp(risk_type, "right_obstacle") == 0) {
        return distances->right_cm * 10;
    }
    return distances->front_cm * 10;
}

static const char *event_sensor_name(const char *risk_type, const char *source)
{
    if (source != NULL && strstr(source, "touch") != NULL) {
        return "touch";
    }
    if (source != NULL && strstr(source, "sos") != NULL) {
        return "sos_button";
    }
    if (risk_type == NULL) {
        return "unknown";
    }
    if (strcmp(risk_type, "ground_drop") == 0) {
        return "tof_down";
    }
    if (strcmp(risk_type, "left_obstacle") == 0) {
        return "tof_left";
    }
    if (strcmp(risk_type, "right_obstacle") == 0) {
        return "tof_right";
    }
    if (strcmp(risk_type, "front_obstacle") == 0) {
        return "tof_front";
    }
    return "device";
}

static void upload_current_event(const char *risk_type, const char *risk_level, const char *source)
{
    distance_readings_t distances;
    location_data_t location;
    risk_state_t risk;
    nearby_risk_summary_t history;
    copy_state(&distances, &risk, &history, &location);
    communication_set_location(&location);

    char extra[160];
    snprintf(extra,
             sizeof(extra),
             "source=%s;location=%s;reason=%s;history_count=%d;history_high=%d",
             source,
             location_quality_to_string(location.quality),
             risk.reason,
             history.risk_count,
             history.high_count);
    (void)communication_upload_event(risk_type,
                                     risk_level,
                                     risk.direction_hint,
                                     event_sensor_name(risk_type, source),
                                     event_distance_mm(risk_type, &distances),
                                     SMARTCANE_BATTERY_PERCENT_UNKNOWN,
                                     &distances,
                                     extra);
}

static void handle_sos(void *ctx)
{
    (void)ctx;
    location_data_t location;
    copy_state(NULL, NULL, NULL, &location);
    ESP_LOGW(TAG, "SOS triggered device_id=%s lat=%.6f lng=%.6f",
             SMARTCANE_DEVICE_ID,
             location.lat,
             location.lng);
    vibration_motor_pattern_sos();
    buzzer_pattern_sos();
    s_last_pattern = LAST_PATTERN_SOS;
    upload_current_event("sos", "high", "sos_button");
}

static void request_ai_advice(void)
{
    if (!online_mode_get()) {
        ESP_LOGI(TAG, "AI advice skipped in local mode");
        return;
    }

    distance_readings_t distances;
    risk_state_t risk;
    nearby_risk_summary_t history;
    location_data_t location;
    copy_state(&distances, &risk, &history, &location);
    communication_set_location(&location);

    char advice[160] = {0};
    if (communication_fetch_ai_advice(&risk, &distances, &history, advice, sizeof(advice))) {
        ESP_LOGI(TAG, "AI advice: %s", advice);
    } else {
        ESP_LOGW(TAG, "AI advice unavailable");
    }
}

static void handle_touch(uint8_t electrode, touch_event_type_t type, void *ctx)
{
    (void)ctx;
    ESP_LOGI(TAG, "touch electrode=%u event=%s", electrode, touch_input_event_name(type));

    if (electrode == 0 && type == TOUCH_EVENT_TAP) {
        distance_readings_t distances;
        risk_state_t risk;
        nearby_risk_summary_t history;
        copy_state(&distances, &risk, &history, NULL);
        risk_logic_log_state(&distances, &risk, &history);
        request_ai_advice();
        return;
    }

    if (electrode == 0 && type == TOUCH_EVENT_DOUBLE_CLICK) {
        char reply[160] = {0};
        if (communication_send_text_command("query nearby risks", reply, sizeof(reply))) {
            ESP_LOGI(TAG, "voice command demo: %s", reply);
        } else {
            ESP_LOGW(TAG, "voice command demo unavailable");
        }
        return;
    }

    if (electrode == 1 && type == TOUCH_EVENT_LONG_PRESS) {
        ESP_LOGI(TAG, "manual user_mark upload");
        upload_current_event("user_mark", "medium", "touch_e1_long_press");
        return;
    }

    if (electrode == 2 && type == TOUCH_EVENT_TAP) {
        run_pattern(s_last_pattern);
        return;
    }

    if (electrode == 3 && type == TOUCH_EVENT_TAP) {
        online_mode_toggle();
        return;
    }

    if (electrode == 4 && type == TOUCH_EVENT_TAP) {
        vibration_motor_pattern_turn_left();
        s_last_pattern = LAST_PATTERN_TURN_LEFT;
        return;
    }

    if (electrode == 5 && type == TOUCH_EVENT_TAP) {
        vibration_motor_pattern_turn_right();
        s_last_pattern = LAST_PATTERN_TURN_RIGHT;
    }
}

static nearby_risk_summary_t merge_history(nearby_risk_summary_t backend_history)
{
    nearby_risk_summary_t remote = communication_remote_summary();
    if (!remote.available) {
        return backend_history;
    }

    backend_history.available = true;
    backend_history.risk_count += remote.risk_count;
    backend_history.high_count += remote.high_count;
    backend_history.medium_count += remote.medium_count;
    if (remote.max_level > backend_history.max_level) {
        backend_history.max_level = remote.max_level;
    }
    backend_history.updated_at_ms = now_ms();
    return backend_history;
}

static void apply_local_feedback(const distance_readings_t *distances, const risk_state_t *risk)
{
    int64_t now = now_ms();
    if (now - s_last_feedback_ms < SMARTCANE_FEEDBACK_REPEAT_MS) {
        return;
    }

    if (risk->ground_drop) {
        vibration_motor_pattern_ground_drop();
        buzzer_pattern_danger();
        s_last_pattern = LAST_PATTERN_GROUND_DROP;
        s_last_feedback_ms = now;
        return;
    }

    if (risk->front_obstacle) {
        if (distances->front_cm < SMARTCANE_FRONT_DANGER_CM) {
            vibration_motor_center(SMARTCANE_VIB_LEVEL_HIGH, 220);
            buzzer_beep(SMARTCANE_BEEP_SHORT_MS);
            s_last_pattern = LAST_PATTERN_OBSTACLE;
        } else {
            vibration_motor_pattern_obstacle();
            s_last_pattern = LAST_PATTERN_OBSTACLE;
        }

        bool left_safe = distances->left_cm > SMARTCANE_SIDE_SAFE_CM;
        bool right_safe = distances->right_cm > SMARTCANE_SIDE_SAFE_CM;
        if (!left_safe && !right_safe) {
            vibration_motor_pattern_stop();
            s_last_pattern = LAST_PATTERN_STOP;
            ESP_LOGI(TAG, "guidance=stop");
        } else if (left_safe && distances->left_cm > distances->right_cm) {
            vibration_motor_pattern_turn_left();
            s_last_pattern = LAST_PATTERN_TURN_LEFT;
            ESP_LOGI(TAG, "guidance=turn_left");
        } else if (right_safe && distances->right_cm > distances->left_cm) {
            vibration_motor_pattern_turn_right();
            s_last_pattern = LAST_PATTERN_TURN_RIGHT;
            ESP_LOGI(TAG, "guidance=turn_right");
        } else {
            ESP_LOGI(TAG, "guidance=slow");
        }
        s_last_feedback_ms = now;
        return;
    }

    if (strcmp(risk->risk_type, "history_risk") == 0) {
        vibration_motor_center(SMARTCANE_VIB_LEVEL_LOW, 120);
        s_last_pattern = LAST_PATTERN_OBSTACLE;
        s_last_feedback_ms = now;
    }
}

static void sensor_task(void *arg)
{
    (void)arg;
    int64_t last_sensor_ms = 0;

    for (;;) {
        gps_location_update();
        location_data_t location = gps_location_get();
        set_location(&location);
        communication_set_location(&location);

        buttons_update(handle_sos, NULL);
        touch_input_update(handle_touch, NULL);

        int64_t now = now_ms();
        if (now - last_sensor_ms >= SMARTCANE_SENSOR_INTERVAL_MS) {
            last_sensor_ms = now;
            distance_readings_t distances;
            esp_err_t ret = tof_sensors_read(&distances);
            if (ret != ESP_OK) {
                ESP_LOGW(TAG, "sensor read returned %s", esp_err_to_name(ret));
            }
            set_distances(&distances);
        }

        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

static void logic_task(void *arg)
{
    (void)arg;
    for (;;) {
        distance_readings_t distances;
        nearby_risk_summary_t history;
        copy_state(&distances, NULL, &history, NULL);
        history = merge_history(history);

        risk_state_t risk;
        risk_logic_calculate(&distances, &history, &risk);
        set_risk(&risk);
        apply_local_feedback(&distances, &risk);
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

static void feedback_task(void *arg)
{
    (void)arg;
    for (;;) {
        vibration_motor_update();
        buzzer_update();
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

static void communication_task(void *arg)
{
    (void)arg;
    int64_t last_location_ms = 0;
    int64_t last_nearby_ms = 0;
    int64_t last_espnow_ms = 0;

    (void)communication_connect_wifi();

    for (;;) {
        if (online_mode_get()) {
            distance_readings_t distances;
            risk_state_t risk;
            location_data_t location;
            copy_state(&distances, &risk, NULL, &location);
            communication_set_location(&location);

            int64_t now = now_ms();
            if (now - last_location_ms >= SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS) {
                last_location_ms = now;
                (void)communication_upload_location(&location);
            }

            if (now - last_nearby_ms >= SMARTCANE_NEARBY_FETCH_INTERVAL_MS) {
                last_nearby_ms = now;
                nearby_risk_summary_t history;
                if (communication_fetch_nearby(location.lat, location.lng, &history)) {
                    set_history(&history);
                }
            }

            if (risk.level == RISK_HIGH &&
                now - s_last_upload_ms >= SMARTCANE_AUTO_UPLOAD_COOLDOWN_MS &&
                (strcmp(risk.risk_type, "ground_drop") == 0 ||
                 strcmp(risk.risk_type, "front_obstacle") == 0)) {
                s_last_upload_ms = now;
                upload_current_event(risk.risk_type, risk_level_to_string(risk.level), "auto_detected");
            }

            if (now - last_espnow_ms >= SMARTCANE_ESPNOW_STATUS_INTERVAL_MS) {
                last_espnow_ms = now;
                (void)communication_espnow_send_status(&distances, &risk);
            }
        }

        vTaskDelay(pdMS_TO_TICKS(500));
    }
}

static void debug_task(void *arg)
{
    (void)arg;
    for (;;) {
        distance_readings_t distances;
        risk_state_t risk;
        nearby_risk_summary_t history;
        location_data_t location;
        copy_state(&distances, &risk, &history, &location);
        risk_logic_log_state(&distances, &risk, &history);
        ESP_LOGI(TAG,
                 "location lat=%.6f lng=%.6f provider=%s quality=%s acc=%.1fm network=%s",
                 location.lat,
                 location.lng,
                 location.provider,
                 location_quality_to_string(location.quality),
                 location.accuracy_m,
                 communication_network_available() ? "connected" : "unavailable");
        vTaskDelay(pdMS_TO_TICKS(SMARTCANE_STATUS_INTERVAL_MS));
    }
}

esp_err_t app_tasks_start(void)
{
    s_state_mutex = xSemaphoreCreateMutex();
    if (s_state_mutex == NULL) {
        return ESP_ERR_NO_MEM;
    }

    ESP_ERROR_CHECK(i2c_bus_init());
    i2c_bus_log_device_status();
    (void)tof_sensors_init();
    (void)touch_input_init();
    (void)vibration_motor_init();
    ESP_ERROR_CHECK(buzzer_init());
    ESP_ERROR_CHECK(buttons_init());
    (void)gps_location_init();
    ESP_ERROR_CHECK(communication_init());

    ESP_LOGI(TAG, "modules initialized");
    ESP_LOGI(TAG, "ToF mock=%s touch_mpr121=%s motor_pca9685=%s",
             tof_sensors_mock_active() ? "yes" : "no",
             touch_input_mpr121_active() ? "yes" : "no",
             vibration_motor_ready() ? "yes" : "no");

    xTaskCreate(sensor_task, "sensor_task", 4096, NULL, 6, NULL);
    xTaskCreate(logic_task, "logic_task", 4096, NULL, 5, NULL);
    xTaskCreate(feedback_task, "feedback_task", 3072, NULL, 7, NULL);
    xTaskCreate(communication_task, "communication_task", 8192, NULL, 4, NULL);
    xTaskCreate(debug_task, "debug_task", 4096, NULL, 3, NULL);

    return ESP_OK;
}

