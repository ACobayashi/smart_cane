#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    RISK_LOW = 0,
    RISK_MEDIUM = 1,
    RISK_HIGH = 2,
} risk_level_t;

typedef enum {
    LOCATION_QUALITY_MOCK = 0,
    LOCATION_QUALITY_STALE,
    LOCATION_QUALITY_POOR,
    LOCATION_QUALITY_USABLE,
    LOCATION_QUALITY_GOOD,
} location_quality_t;

typedef struct {
    int front_cm;
    int left_cm;
    int right_cm;
    int down_cm;
    bool valid;
    int64_t timestamp_ms;
} distance_readings_t;

typedef struct {
    bool available;
    int risk_count;
    int high_count;
    int medium_count;
    risk_level_t max_level;
    int64_t updated_at_ms;
} nearby_risk_summary_t;

typedef struct {
    risk_level_t level;
    char risk_type[32];
    char direction_hint[32];
    char reason[96];
    bool ground_drop;
    bool front_obstacle;
    bool side_obstacle;
    bool realtime_medium;
    bool realtime_high;
    bool history_influenced;
    int64_t detected_at_ms;
} risk_state_t;

typedef struct {
    float lat;
    float lng;
    bool valid;
    bool mock;
    float accuracy_m;
    float hdop;
    uint8_t fix_quality;
    uint8_t satellite_count;
    location_quality_t quality;
    char provider[12];
    int64_t updated_at_ms;
} location_data_t;

typedef enum {
    TOUCH_EVENT_TAP = 0,
    TOUCH_EVENT_LONG_PRESS,
    TOUCH_EVENT_DOUBLE_CLICK,
} touch_event_type_t;

typedef void (*touch_event_callback_t)(uint8_t electrode, touch_event_type_t type, void *ctx);
typedef void (*sos_callback_t)(void *ctx);

static inline const char *risk_level_to_string(risk_level_t level)
{
    switch (level) {
    case RISK_HIGH:
        return "high";
    case RISK_MEDIUM:
        return "medium";
    case RISK_LOW:
    default:
        return "low";
    }
}

static inline risk_level_t risk_level_from_string(const char *level)
{
    if (level == NULL) {
        return RISK_LOW;
    }
    if (level[0] == 'h') {
        return RISK_HIGH;
    }
    if (level[0] == 'm') {
        return RISK_MEDIUM;
    }
    return RISK_LOW;
}

static inline const char *location_quality_to_string(location_quality_t quality)
{
    switch (quality) {
    case LOCATION_QUALITY_GOOD:
        return "good";
    case LOCATION_QUALITY_USABLE:
        return "usable";
    case LOCATION_QUALITY_POOR:
        return "poor";
    case LOCATION_QUALITY_STALE:
        return "stale";
    case LOCATION_QUALITY_MOCK:
    default:
        return "mock";
    }
}

