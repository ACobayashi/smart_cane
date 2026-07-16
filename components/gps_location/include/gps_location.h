#pragma once

#include <stdbool.h>

#include "app_types.h"
#include "esp_err.h"

esp_err_t gps_location_init(void);
void gps_location_update(void);
location_data_t gps_location_get(void);
bool gps_location_has_real_fix(void);
location_quality_t gps_location_quality(void);
void gps_location_log_status(void);

