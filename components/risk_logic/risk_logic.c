#include "risk_logic.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"
#include "smartcane_config.h"

static const char *TAG = "LOGIC";

static int64_t now_ms(void)
{
    return esp_timer_get_time() / 1000;
}

static void set_text(char *dst, size_t dst_size, const char *src)
{
    if (dst == NULL || dst_size == 0) {
        return;
    }
    snprintf(dst, dst_size, "%s", src == NULL ? "" : src);
}

bool risk_logic_is_ground_drop(int down_cm)
{
    return down_cm > (SMARTCANE_GROUND_BASE_CM + SMARTCANE_GROUND_DROP_THRESHOLD_CM);
}

static void make_low_risk(risk_state_t *out)
{
    memset(out, 0, sizeof(*out));
    out->level = RISK_LOW;
    set_text(out->risk_type, sizeof(out->risk_type), "none");
    set_text(out->direction_hint, sizeof(out->direction_hint), "none");
    set_text(out->reason, sizeof(out->reason), "clear");
    out->detected_at_ms = now_ms();
}

static const char *front_guidance(const distance_readings_t *distances)
{
    bool left_safe = distances->left_cm > SMARTCANE_SIDE_SAFE_CM;
    bool right_safe = distances->right_cm > SMARTCANE_SIDE_SAFE_CM;

    if (!left_safe && !right_safe) {
        return "stop";
    }
    if (left_safe && distances->left_cm > distances->right_cm) {
        return "turn_left";
    }
    if (right_safe && distances->right_cm > distances->left_cm) {
        return "turn_right";
    }
    return "slow";
}

void risk_logic_calculate(const distance_readings_t *distances,
                          const nearby_risk_summary_t *history,
                          risk_state_t *out)
{
    if (distances == NULL || history == NULL || out == NULL) {
        return;
    }

    make_low_risk(out);

    bool ground_drop = risk_logic_is_ground_drop(distances->down_cm);
    bool front_danger = distances->front_cm < SMARTCANE_FRONT_DANGER_CM;
    bool front_warn = distances->front_cm < SMARTCANE_FRONT_WARN_CM;
    bool side_near = distances->left_cm < SMARTCANE_SIDE_NEAR_CM ||
                     distances->right_cm < SMARTCANE_SIDE_NEAR_CM;

    if (ground_drop) {
        out->level = RISK_HIGH;
        set_text(out->risk_type, sizeof(out->risk_type), "ground_drop");
        set_text(out->direction_hint, sizeof(out->direction_hint), "stop");
        set_text(out->reason, sizeof(out->reason), "realtime ground drop");
        out->ground_drop = true;
        out->realtime_high = true;
        return;
    }

    if (front_danger) {
        out->level = RISK_HIGH;
        set_text(out->risk_type, sizeof(out->risk_type), "front_obstacle");
        set_text(out->direction_hint, sizeof(out->direction_hint), front_guidance(distances));
        set_text(out->reason, sizeof(out->reason), "front distance below danger threshold");
        out->front_obstacle = true;
        out->realtime_high = true;
        return;
    }

    if (front_warn) {
        out->level = RISK_MEDIUM;
        set_text(out->risk_type, sizeof(out->risk_type), "front_obstacle");
        set_text(out->direction_hint, sizeof(out->direction_hint), front_guidance(distances));
        set_text(out->reason, sizeof(out->reason), "front distance below warning threshold");
        out->front_obstacle = true;
        out->realtime_medium = true;
    } else if (side_near) {
        out->level = RISK_MEDIUM;
        set_text(out->risk_type,
                 sizeof(out->risk_type),
                 distances->left_cm < distances->right_cm ? "left_obstacle" : "right_obstacle");
        set_text(out->direction_hint,
                 sizeof(out->direction_hint),
                 distances->left_cm < distances->right_cm ? "keep_right" : "keep_left");
        set_text(out->reason, sizeof(out->reason), "side distance below near threshold");
        out->side_obstacle = true;
        out->realtime_medium = true;
    }

    bool history_medium_or_high = history->available && history->max_level >= RISK_MEDIUM;
    if (out->realtime_medium && history_medium_or_high) {
        out->level = RISK_HIGH;
        out->history_influenced = true;
        set_text(out->reason, sizeof(out->reason), "realtime medium risk plus nearby history");
        return;
    }

    if (out->level == RISK_LOW && history->available && history->high_count >= 2) {
        out->level = RISK_MEDIUM;
        set_text(out->risk_type, sizeof(out->risk_type), "history_risk");
        set_text(out->direction_hint, sizeof(out->direction_hint), "slow");
        set_text(out->reason, sizeof(out->reason), "nearby history has at least two high risk events");
        out->history_influenced = true;
    }
}

void risk_logic_log_state(const distance_readings_t *distances,
                          const risk_state_t *risk,
                          const nearby_risk_summary_t *history)
{
    if (distances == NULL || risk == NULL || history == NULL) {
        return;
    }

    ESP_LOGI(TAG,
             "front=%dcm left=%dcm right=%dcm down=%dcm risk=%s type=%s hint=%s history=%d high=%d max=%s",
             distances->front_cm,
             distances->left_cm,
             distances->right_cm,
             distances->down_cm,
             risk_level_to_string(risk->level),
             risk->risk_type,
             risk->direction_hint,
             history->risk_count,
             history->high_count,
             risk_level_to_string(history->max_level));
}

