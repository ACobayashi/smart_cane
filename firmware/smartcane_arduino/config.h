#pragma once

/*
 * Arduino IDE target tested from the supplied screenshots:
 * Board: ESP32C5 Dev Module
 * Serial: 115200 baud
 */

// Device and backend.
#define SMARTCANE_DEVICE_ID "cane_001"
#define SMARTCANE_WIFI_SSID "YOUR_WIFI_SSID"
#define SMARTCANE_WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#define SMARTCANE_SERVER_BASE_URL "http://192.168.1.100:8000"

// Location fallback. The Arduino build records walked route by periodically
// uploading this simulated/mobile-replaceable location to the backend.
#define SMARTCANE_MOCK_LAT 31.230400
#define SMARTCANE_MOCK_LNG 121.473700
#define SMARTCANE_MOCK_ROUTE_ENABLED 1
#define SMARTCANE_MOCK_ROUTE_STEP_DEG 0.000018
#define SMARTCANE_NEARBY_RADIUS_M 80

// Optional GNSS/BeiDou serial module. Disabled by default because the current
// purchase list and screenshots only verify simulated/mobile-replaceable
// coordinates. Enable later after wiring a UART GNSS module.
#define SMARTCANE_GNSS_ENABLED 0
#define SMARTCANE_GNSS_RX_PIN 18
#define SMARTCANE_GNSS_TX_PIN 19
#define SMARTCANE_GNSS_BAUD 9600

// I2C pins from the hardware screenshots.
#define SMARTCANE_I2C_SDA_PIN 2
#define SMARTCANE_I2C_SCL_PIN 3
#define SMARTCANE_I2C_CLOCK_HZ 400000

// I2C addresses.
#define SMARTCANE_TCA9548A_ADDR 0x70
#define SMARTCANE_VL53L1X_ADDR 0x29
#define SMARTCANE_MPR121_ADDR 0x5A
#define SMARTCANE_PCA9685_ADDR 0x40

// ToF channels from the current bench wiring screenshots.
#define SMARTCANE_TCA_CH_TOF_FRONT 2
#define SMARTCANE_TCA_CH_TOF_LEFT 3
#define SMARTCANE_TCA_CH_TOF_RIGHT 4
#define SMARTCANE_TCA_CH_TOF_DOWN 5

// HW-017/MPR121 was observed on TCA channel 7. Set to 0 if wired on root I2C.
#define SMARTCANE_TOUCH_ON_TCA 1
#define SMARTCANE_TCA_CH_TOUCH 7

// Mock mode. 0 means prefer real sensors and automatically fall back to mock
// if the TCA/VL53L1X chain is incomplete.
#define SMARTCANE_MOCK_SENSOR_MODE 0

// GPIO. Buzzer pin follows the supplied buzzer test screenshot.
#define SMARTCANE_BUZZER_PIN 4
#define SMARTCANE_BUZZER_ACTIVE_HIGH 1
#define SMARTCANE_SOS_BUTTON_PIN 5
#define SMARTCANE_SOS_ACTIVE_LOW 1

// Vibration. Preferred path is PCA9685 + MOS driver. GPIO fallback is for
// bench testing only and should still drive MOS gates, not motors directly.
#define SMARTCANE_VIB_LEFT_CHANNEL 0
#define SMARTCANE_VIB_RIGHT_CHANNEL 1
#define SMARTCANE_VIB_CENTER_CHANNEL 2
#define SMARTCANE_PCA9685_PWM_FREQ_HZ 160
#define SMARTCANE_PCA9685_PWM_MAX 4095
#define SMARTCANE_MOTOR_GPIO_FALLBACK_ENABLED 0
#define SMARTCANE_MOTOR_LEFT_GPIO 6
#define SMARTCANE_MOTOR_RIGHT_GPIO 7
#define SMARTCANE_MOTOR_CENTER_GPIO 10

// Timing.
#define SMARTCANE_SENSOR_INTERVAL_MS 100
#define SMARTCANE_STATUS_INTERVAL_MS 1000
#define SMARTCANE_FEEDBACK_REPEAT_MS 800
#define SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS 5000
#define SMARTCANE_NEARBY_FETCH_INTERVAL_MS 10000
#define SMARTCANE_AUTO_UPLOAD_COOLDOWN_MS 8000
#define SMARTCANE_DEEP_RISK_INTERVAL_MS 12000
#define SMARTCANE_SERIAL_COMMANDS_ENABLED 1
#define SMARTCANE_WIFI_CONNECT_TIMEOUT_MS 10000
#define SMARTCANE_SOS_HOLD_MS 2000
#define SMARTCANE_BUTTON_DEBOUNCE_MS 40
#define SMARTCANE_TOUCH_LONG_PRESS_MS 1000
#define SMARTCANE_TOUCH_DOUBLE_CLICK_MS 350

// Risk thresholds, centimeters.
#define SMARTCANE_FRONT_WARN_CM 120
#define SMARTCANE_FRONT_DANGER_CM 60
#define SMARTCANE_SIDE_SAFE_CM 90
#define SMARTCANE_SIDE_NEAR_CM 55
#define SMARTCANE_GROUND_BASE_CM 45
#define SMARTCANE_GROUND_DROP_THRESHOLD_CM 30

// Sensor limitations and filtering.
#define SMARTCANE_TOF_MIN_VALID_MM 20
#define SMARTCANE_TOF_MAX_VALID_MM 4000
#define SMARTCANE_TOF_FILTER_ALPHA_PERCENT 45
#define SMARTCANE_TOF_FAILS_BEFORE_INVALID 3

// Feedback strengths.
#define SMARTCANE_VIB_LEVEL_LOW 35
#define SMARTCANE_VIB_LEVEL_MEDIUM 65
#define SMARTCANE_VIB_LEVEL_HIGH 100
#define SMARTCANE_BEEP_SHORT_MS 120

// Local route/risk record shown with the Serial command: path
#define SMARTCANE_LOCAL_PATH_BUFFER_SIZE 50
#define SMARTCANE_BATTERY_PERCENT_UNKNOWN -1
