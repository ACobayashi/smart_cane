#pragma once

/*
 * Arduino IDE target tested from the supplied screenshots:
 * Board: ESP32C5 Dev Module
 * Serial: 115200 baud
 */

// Device and backend.
#define SMARTCANE_BUILD_TAG "arduino-product-lite-net-pca9685-20260720"
#define SMARTCANE_DEVICE_ID "cane_001"
#define SMARTCANE_DEVICE_NAME "SmartCane 001"
#define SMARTCANE_WIFI_SSID "xin"
#define SMARTCANE_WIFI_PASSWORD "22222222"
#define SMARTCANE_SERVER_BASE_URL "http://10.136.79.100:8000"
#define SMARTCANE_PRODUCT_MODE 1

// Location fallback. Keep mock route disabled during bench tests so the device
// does not pretend it moved while it is sitting on the desk. The Android app's
// Amap/GPS location is the preferred product location source.
#define SMARTCANE_MOCK_LAT 31.230400
#define SMARTCANE_MOCK_LNG 121.473700
#define SMARTCANE_MOCK_ROUTE_ENABLED 0
#define SMARTCANE_MOCK_ROUTE_STEP_DEG 0.000018
#define SMARTCANE_NEARBY_RADIUS_M 80

// Optional GNSS/BeiDou serial module. Disabled by default because the current
// purchase list and screenshots only verify fallback/mobile-replaceable
// coordinates. Enable later after wiring a UART GNSS module.
#define SMARTCANE_GNSS_ENABLED 0
#define SMARTCANE_GNSS_RX_PIN 18
#define SMARTCANE_GNSS_TX_PIN 19
#define SMARTCANE_GNSS_BAUD 9600

// I2C pins from the hardware screenshots.
#define SMARTCANE_I2C_SDA_PIN 2
#define SMARTCANE_I2C_SCL_PIN 3
// Keep the same conservative I2C speed as the teacher's verified tests.
// The bus now has TCA9548A, MPR121, and PCA9685 on relatively long jumper
// wires, so 100 kHz is more reliable than 400 kHz.
#define SMARTCANE_I2C_CLOCK_HZ 100000

// I2C addresses.
#define SMARTCANE_TCA9548A_ADDR 0x70
#define SMARTCANE_VL53L1X_ADDR 0x29
#define SMARTCANE_MPR121_ADDR 0x5A
#define SMARTCANE_PCA9685_ADDR 0x40
#define SMARTCANE_BMI270_ADDR_PRIMARY 0x68
#define SMARTCANE_BMI270_ADDR_SECONDARY 0x69
#define SMARTCANE_BMI270_CHIP_ID 0x24

// ESP-SensairShuttle BMI270/BMM350 ShuttleBoard lines from the Espressif
// schematics. The current board was verified on the root I2C bus at BMI270
// address 0x69. Firmware releases these lines and probes both 0x68/0x69.
#define SMARTCANE_BM_G2_PIN 0
#define SMARTCANE_BM_G1_PIN 8
#define SMARTCANE_BM_SDO_PIN 9
#define SMARTCANE_BM_CS_PIN 10

// ToF channels from the current bench wiring screenshots.
#define SMARTCANE_TCA_CH_TOF_FRONT 2
#define SMARTCANE_TCA_CH_TOF_LEFT 3
#define SMARTCANE_TCA_CH_TOF_RIGHT 4
#define SMARTCANE_TCA_CH_TOF_DOWN 5

// HW-017/MPR121 was observed on TCA channel 7. Set to 0 if wired on root I2C.
#define SMARTCANE_TOUCH_ON_TCA 1
#define SMARTCANE_TCA_CH_TOUCH 7

// Mock mode. Product builds use real hardware only. Set
// SMARTCANE_ALLOW_MOCK_FALLBACK to 1 only for lab troubleshooting without the
// sensor board connected.
#define SMARTCANE_MOCK_SENSOR_MODE 0
#define SMARTCANE_ALLOW_MOCK_FALLBACK 0
#define SMARTCANE_MOCK_DEFAULT_SCENARIO MOCK_SCENARIO_CLEAR

// GPIO. Buzzer pin follows the supplied buzzer test screenshot.
#define SMARTCANE_BUZZER_PIN 4
// Enabled for safety alerts. Runtime commands `buzzer on/off` can mute it.
#define SMARTCANE_BUZZER_ENABLED 1
// Many 3.3V active buzzer modules are low-triggered: LOW = on, HIGH = idle.
// If your bare active buzzer is driven by a MOS and HIGH should turn it on,
// change this back to 1.
#define SMARTCANE_BUZZER_ACTIVE_HIGH 0
#define SMARTCANE_SOS_BUTTON_PIN 5
#define SMARTCANE_SOS_ACTIVE_LOW 1

// Vibration motors are driven by the blue PCA9685 board, not by ESP32 GPIO.
// This avoids GPIO8/9/10 because those belong to the BMI270/BMM350 daughter
// board path. PCA channels 8/9/10 are I2C PWM outputs and are safe to use.
#define SMARTCANE_VIB_ENABLED 1
#define SMARTCANE_VIB_USE_PCA9685 1
#define SMARTCANE_VIB_LEFT_CHANNEL 8
#define SMARTCANE_VIB_RIGHT_CHANNEL 9
#define SMARTCANE_VIB_CENTER_CHANNEL 10
#define SMARTCANE_PCA9685_PWM_FREQ_HZ 1000
#define SMARTCANE_PCA9685_MIN_RUN_PWM 1200
#define SMARTCANE_PCA9685_MAX_PWM 4095

// Timing.
#define SMARTCANE_SENSOR_INTERVAL_MS 500
#define SMARTCANE_STATUS_INTERVAL_MS 1000
#define SMARTCANE_STREAM_INTERVAL_MS 3000
#define SMARTCANE_PERIODIC_SERIAL_STATUS_ENABLED 0
#define SMARTCANE_FEEDBACK_REPEAT_MS 800
#define SMARTCANE_TELEMETRY_LOW_RISK_INTERVAL_MS 30000
#define SMARTCANE_TELEMETRY_RISK_INTERVAL_MS 5000
#define SMARTCANE_TELEMETRY_UPLOAD_INTERVAL_MS SMARTCANE_TELEMETRY_LOW_RISK_INTERVAL_MS
#define SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS 5000
#define SMARTCANE_NEARBY_FETCH_INTERVAL_MS 10000
#define SMARTCANE_AUTO_UPLOAD_COOLDOWN_MS 8000
#define SMARTCANE_DEEP_RISK_INTERVAL_MS 12000
#define SMARTCANE_NETWORK_UNAVAILABLE_LOG_INTERVAL_MS 30000
#define SMARTCANE_SERIAL_COMMANDS_ENABLED 1
#define SMARTCANE_RISK_CONFIRM_FRAMES 2
#define SMARTCANE_RISK_CLEAR_FRAMES 8
#define SMARTCANE_WIFI_CONNECT_TIMEOUT_MS 10000
#define SMARTCANE_HTTP_TIMEOUT_MS 5000
#define SMARTCANE_SOS_HOLD_MS 2000
#define SMARTCANE_BUTTON_DEBOUNCE_MS 40
#define SMARTCANE_TOUCH_LONG_PRESS_MS 1000
#define SMARTCANE_TOUCH_DOUBLE_CLICK_MS 350
#define SMARTCANE_BUTTON_DOUBLE_CLICK_MS 450

// Risk thresholds, centimeters.
#define SMARTCANE_FRONT_WARN_CM 80
#define SMARTCANE_FRONT_DANGER_CM 45
#define SMARTCANE_SIDE_SAFE_CM 70
#define SMARTCANE_SIDE_NEAR_CM 35
#define SMARTCANE_SIDE_DANGER_CM 25
#define SMARTCANE_SIDE_BLOCKED_CM 35
#define SMARTCANE_GROUND_BASE_CM 45
#define SMARTCANE_GROUND_DROP_THRESHOLD_CM 45
#define SMARTCANE_DOWN_OBSTACLE_CM 20

// Sensor limitations and filtering.
#define SMARTCANE_TOF_MIN_VALID_MM 20
#define SMARTCANE_TOF_MAX_VALID_MM 4000
#define SMARTCANE_TOF_FILTER_ALPHA_PERCENT 45
#define SMARTCANE_TOF_FAILS_BEFORE_INVALID 3
#define SMARTCANE_TOF_SINGLE_SHOT_READ 1
#define SMARTCANE_TOF_TIMING_BUDGET_US 33000
#define SMARTCANE_TOF_CONTINUOUS_PERIOD_MS 80

// Feedback strengths.
#define SMARTCANE_VIB_LEVEL_LOW 35
#define SMARTCANE_VIB_LEVEL_MEDIUM 65
#define SMARTCANE_VIB_LEVEL_HIGH 100
#define SMARTCANE_BEEP_SHORT_MS 120

// Local route/risk record shown with the Serial command: path
#define SMARTCANE_LOCAL_PATH_BUFFER_SIZE 50
#define SMARTCANE_EVENT_LOCATION_CELL_DEG 0.00010
#define SMARTCANE_BATTERY_PERCENT_UNKNOWN -1

// Built-in ESP-SensairShuttle BMI270 fall-detection path. BMM350 can help
// heading later, but it cannot provide user location; phone/Amap remains the
// location source.
#define SMARTCANE_IMU_ENABLED 1
#define SMARTCANE_IMU_SAMPLE_INTERVAL_MS 50
#define SMARTCANE_IMU_STREAM_INTERVAL_MS 500
#define SMARTCANE_IMU_RAW_PRINT_REGS 0
#define SMARTCANE_FALL_IMPACT_G 2.35f
#define SMARTCANE_FALL_FREEFALL_G 0.35f
#define SMARTCANE_FALL_JERK_G 1.10f
#define SMARTCANE_FALL_VERTICAL_DELTA_G 0.55f
#define SMARTCANE_FALL_VERTICAL_PEAK_G 1.45f
#define SMARTCANE_FALL_VERTICAL_MIN_G 1.00f
#define SMARTCANE_FALL_STILL_DELTA_G 0.10f
#define SMARTCANE_FALL_STABLE_MIN_G 0.72f
#define SMARTCANE_FALL_STABLE_MAX_G 1.28f
#define SMARTCANE_FALL_LIE_TILT_DEG 62.0f
#define SMARTCANE_FALL_POSTURE_DEG 70.0f
#define SMARTCANE_FALL_LIE_MS 1200
#define SMARTCANE_FALL_CONFIRM_WINDOW_MS 3500
#define SMARTCANE_FALL_RECOVERY_MS 5000
#define SMARTCANE_FALL_UPLOAD_COOLDOWN_MS 30000

// Only these escalated obstacle states are sent to the companion side.
#define SMARTCANE_COMPANION_OBSTACLE_HOLD_MS 12000
#define SMARTCANE_COMPANION_APPROACH_WINDOW_MS 9000
#define SMARTCANE_COMPANION_APPROACH_DELTA_CM 35
#define SMARTCANE_COMPANION_ALERT_COOLDOWN_MS 30000
