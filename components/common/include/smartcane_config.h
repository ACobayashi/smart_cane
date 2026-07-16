#pragma once

#include <stdbool.h>
#include <stdint.h>

/* Device identity and backend */
#define SMARTCANE_DEVICE_ID              "cane_001"
#define SMARTCANE_WIFI_SSID              "YOUR_WIFI_SSID"
#define SMARTCANE_WIFI_PASSWORD          "YOUR_WIFI_PASSWORD"
#define SMARTCANE_SERVER_BASE_URL        "http://192.168.1.100:8000"

/* Location fallback */
#define SMARTCANE_MOCK_LAT               31.230400f
#define SMARTCANE_MOCK_LNG               121.473700f
#define SMARTCANE_NEARBY_RADIUS_M        80

/* Mock support for bench demos without sensor hardware */
#define SMARTCANE_MOCK_SENSOR_MODE       1
#define SMARTCANE_GPS_MOCK_FALLBACK      1

/* I2C */
#define SMARTCANE_I2C_PORT               0
#define SMARTCANE_I2C_SDA_GPIO           8
#define SMARTCANE_I2C_SCL_GPIO           9
#define SMARTCANE_I2C_CLOCK_HZ           400000

#define SMARTCANE_TCA9548A_ADDR          0x70
#define SMARTCANE_VL53L1X_ADDR           0x29
#define SMARTCANE_MPR121_ADDR            0x5A
#define SMARTCANE_PCA9685_ADDR           0x40

#define SMARTCANE_TCA_CH_TOF_FRONT       0
#define SMARTCANE_TCA_CH_TOF_LEFT        1
#define SMARTCANE_TCA_CH_TOF_RIGHT       2
#define SMARTCANE_TCA_CH_TOF_DOWN        3

/* GPIO */
#define SMARTCANE_SOS_BUTTON_GPIO        4
#define SMARTCANE_SOS_ACTIVE_LOW         1
#define SMARTCANE_BUZZER_GPIO            5
#define SMARTCANE_BUZZER_ACTIVE_HIGH     1

/* Optional GPIO fallback touch inputs. Set to -1 to disable. */
#define SMARTCANE_TOUCH_E0_GPIO          -1
#define SMARTCANE_TOUCH_E1_GPIO          -1
#define SMARTCANE_TOUCH_E2_GPIO          -1
#define SMARTCANE_TOUCH_E3_GPIO          -1
#define SMARTCANE_TOUCH_E4_GPIO          -1
#define SMARTCANE_TOUCH_E5_GPIO          -1
#define SMARTCANE_TOUCH_ACTIVE_LOW       1

/* GPS UART */
#define SMARTCANE_GPS_UART_NUM           1
#define SMARTCANE_GPS_RX_GPIO            18
#define SMARTCANE_GPS_TX_GPIO            19
#define SMARTCANE_GPS_BAUD               9600
#define SMARTCANE_GPS_FIX_STALE_MS       10000
#define SMARTCANE_GPS_MOCK_ACCURACY_M    30.0f

/* PCA9685 motor channels */
#define SMARTCANE_VIB_LEFT_CHANNEL       0
#define SMARTCANE_VIB_RIGHT_CHANNEL      1
#define SMARTCANE_VIB_CENTER_CHANNEL     2
#define SMARTCANE_PCA9685_PWM_MAX        4095
#define SMARTCANE_PCA9685_PWM_FREQ_HZ    160

/* Timing */
#define SMARTCANE_SENSOR_INTERVAL_MS     100
#define SMARTCANE_STATUS_INTERVAL_MS     1000
#define SMARTCANE_FEEDBACK_REPEAT_MS     800
#define SMARTCANE_AUTO_UPLOAD_COOLDOWN_MS 8000
#define SMARTCANE_NEARBY_FETCH_INTERVAL_MS 10000
#define SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS 15000
#define SMARTCANE_WIFI_CONNECT_TIMEOUT_MS 10000
#define SMARTCANE_SOS_HOLD_MS            2000
#define SMARTCANE_BUTTON_DEBOUNCE_MS     40
#define SMARTCANE_TOUCH_LONG_PRESS_MS    1000
#define SMARTCANE_TOUCH_DOUBLE_CLICK_MS  350

/* Risk thresholds, centimeters */
#define SMARTCANE_FRONT_WARN_CM          120
#define SMARTCANE_FRONT_DANGER_CM        60
#define SMARTCANE_SIDE_SAFE_CM           90
#define SMARTCANE_SIDE_NEAR_CM           55
#define SMARTCANE_GROUND_BASE_CM         45
#define SMARTCANE_GROUND_DROP_THRESHOLD_CM 30

/* Feedback strengths */
#define SMARTCANE_VIB_LEVEL_LOW          35
#define SMARTCANE_VIB_LEVEL_MEDIUM       65
#define SMARTCANE_VIB_LEVEL_HIGH         100
#define SMARTCANE_BEEP_SHORT_MS          120

/* ESP-NOW local collaboration */
#define SMARTCANE_ESPNOW_ENABLED         1
#define SMARTCANE_ESPNOW_STATUS_INTERVAL_MS 5000
#define SMARTCANE_REMOTE_STATUS_TIMEOUT_MS 15000

/* Optional telemetry. The current BOM has no battery ADC circuit, so keep -1
 * until a voltage divider or fuel-gauge module is added and calibrated. */
#define SMARTCANE_BATTERY_PERCENT_UNKNOWN -1

