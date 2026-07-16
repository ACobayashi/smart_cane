#pragma once

#include <stdbool.h>
#include <stddef.h>

#include "app_types.h"
#include "esp_err.h"

esp_err_t communication_init(void);
bool communication_connect_wifi(void);
bool communication_network_available(void);
void communication_set_location(const location_data_t *location);
bool communication_upload_location(const location_data_t *location);
bool communication_upload_event(const char *risk_type,
                                const char *risk_level,
                                const char *direction,
                                const char *sensor,
                                int distance_mm,
                                int battery_percent,
                                const distance_readings_t *distances,
                                const char *extra);
bool communication_fetch_nearby(float lat, float lng, nearby_risk_summary_t *out);
bool communication_fetch_ai_advice(const risk_state_t *risk,
                                   const distance_readings_t *distances,
                                   const nearby_risk_summary_t *history,
                                   char *out,
                                   size_t out_size);
bool communication_send_text_command(const char *text, char *out, size_t out_size);
bool communication_espnow_send_status(const distance_readings_t *distances, const risk_state_t *risk);
nearby_risk_summary_t communication_remote_summary(void);

