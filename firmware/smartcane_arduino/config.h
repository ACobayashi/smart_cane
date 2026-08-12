#pragma once

/*
 * Arduino IDE target tested from the supplied screenshots:
 * Board: ESP32C5 Dev Module
 * Serial: 115200 baud
 */

// Device and backend.
#define SMARTCANE_BUILD_TAG "arduino-ground-sweep-filter-20260812"
#define SMARTCANE_DEVICE_ID "cane_001"
#ifndef SMARTCANE_DEVICE_NAME
#define SMARTCANE_DEVICE_NAME "智能盲杖01"
#endif
#ifndef SMARTCANE_WIFI_SSID
#define SMARTCANE_WIFI_SSID "计组课总有人抢网"
#endif
#ifndef SMARTCANE_WIFI_PASSWORD
#define SMARTCANE_WIFI_PASSWORD "20060815"
#endif
#ifndef SMARTCANE_SERVER_BASE_URL
#define SMARTCANE_SERVER_BASE_URL "http://59.110.21.95:8016"
#endif
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
// The root bus reaches TCA9548A plus the BMI270/BMM350 board. MPR121 and
// PCA9685 are behind TCA channels on the current bench wiring, so 100 kHz is
// more reliable than 400 kHz with the jumper lengths.
#define SMARTCANE_I2C_CLOCK_HZ 100000

// I2C addresses.
#define SMARTCANE_TCA9548A_ADDR 0x70
#define SMARTCANE_VL53L1X_ADDR 0x29
#define SMARTCANE_MPR121_ADDR 0x5A
#define SMARTCANE_PCA9685_ADDR 0x40
#define SMARTCANE_PCA9685_ADDR_AUTO_MIN 0x40
#define SMARTCANE_PCA9685_ADDR_AUTO_MAX 0x7E
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
// Current bench wiring keeps one physical vibration motor on PCA9685 CH0 only.
// All left/right/center/SOS cues are encoded as pulse patterns on that CH0
// motor so the cane can be tested safely before adding CH1/CH2 motors again.
// Set SMARTCANE_VIB_MOTOR_COUNT back to 3 after the other motors are wired.
#define SMARTCANE_VIB_ENABLED 1
#define SMARTCANE_VIB_USE_PCA9685 1
#define SMARTCANE_PCA9685_AUTO_DETECT 0
#define SMARTCANE_PCA9685_ON_TCA 1
#define SMARTCANE_TCA_CH_PCA9685 6
#define SMARTCANE_VIB_MOTOR_COUNT 1
#define SMARTCANE_VIB_PRIMARY_CHANNEL 0
#define SMARTCANE_VIB_LEFT_CHANNEL 0
#define SMARTCANE_VIB_RIGHT_CHANNEL 1
#define SMARTCANE_VIB_CENTER_CHANNEL 2
#define SMARTCANE_PCA9685_PWM_FREQ_HZ 50
#define SMARTCANE_PCA9685_MIN_RUN_PWM 2048
#define SMARTCANE_PCA9685_MAX_PWM 2048
#define SMARTCANE_VIB_SINGLE_PULSE_MS 150
#define SMARTCANE_VIB_SINGLE_PULSE_GAP_MS 90

// Timing.
#define SMARTCANE_SENSOR_INTERVAL_MS 100
#define SMARTCANE_STATUS_INTERVAL_MS 1000
#define SMARTCANE_STREAM_INTERVAL_MS 3000
#define SMARTCANE_PERIODIC_SERIAL_STATUS_ENABLED 0
#define SMARTCANE_FEEDBACK_REPEAT_MS 800
#define SMARTCANE_RISK_FEEDBACK_REARM_CLEAR_MS 3000
#define SMARTCANE_RISK_PERSISTENT_FEEDBACK_MS 3000
#define SMARTCANE_RISK_PERSISTENT_REPEAT_MS 1200
#define SMARTCANE_TELEMETRY_LOW_RISK_INTERVAL_MS 30000
#define SMARTCANE_TELEMETRY_RISK_INTERVAL_MS 5000
#define SMARTCANE_TELEMETRY_UPLOAD_INTERVAL_MS SMARTCANE_TELEMETRY_LOW_RISK_INTERVAL_MS
#define SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS 5000
#define SMARTCANE_NEARBY_FETCH_INTERVAL_MS 10000
#define SMARTCANE_AUTO_UPLOAD_COOLDOWN_MS 8000
#define SMARTCANE_DEEP_RISK_INTERVAL_MS 12000
#define SMARTCANE_NETWORK_UNAVAILABLE_LOG_INTERVAL_MS 30000
#define SMARTCANE_SERIAL_COMMANDS_ENABLED 1
#define SMARTCANE_SERIAL_HEARTBEAT_ENABLED 1
#define SMARTCANE_SERIAL_HEARTBEAT_INTERVAL_MS 5000
#define SMARTCANE_RISK_CONFIRM_FRAMES 2
#define SMARTCANE_RISK_CLEAR_FRAMES 8
#define SMARTCANE_WIFI_CONNECT_TIMEOUT_MS 15000
#define SMARTCANE_WIFI_DIAG_ON_CONNECT 1
#define SMARTCANE_HTTP_TIMEOUT_MS 2500
#define SMARTCANE_SENSOR_FRAME_HTTP_TIMEOUT_MS 1200
#define SMARTCANE_DEEP_RISK_HTTP_TIMEOUT_MS 5000
#define SMARTCANE_HTTP_FAIL_LOG_INTERVAL_MS 5000
#define SMARTCANE_SOS_HOLD_MS 2000
#define SMARTCANE_BUTTON_DEBOUNCE_MS 40
#define SMARTCANE_TOUCH_LONG_PRESS_MS 1000
#define SMARTCANE_TOUCH_DOUBLE_CLICK_MS 350
#define SMARTCANE_BUTTON_DOUBLE_CLICK_MS 450

// Risk thresholds, centimeters.
// The cane is normally held at an angle, so front/down warnings need a little
// more reach, while side warnings are moderate to stay responsive without
// marking every sweep as a map risk.
#define SMARTCANE_FRONT_WARN_CM 120
#define SMARTCANE_FRONT_DANGER_CM 40
#define SMARTCANE_SIDE_SAFE_CM 80
#define SMARTCANE_SIDE_ALERT_CM 35
// SIDE_SAFE is only for choosing a safer turn direction.  It must never be
// used as the entry threshold for a left/right obstacle alert.
#define SMARTCANE_SIDE_NEAR_CM SMARTCANE_SIDE_ALERT_CM
#define SMARTCANE_SIDE_DANGER_CM SMARTCANE_SIDE_ALERT_CM
#define SMARTCANE_SIDE_BLOCKED_CM 28
#define SMARTCANE_GROUND_BASE_CM 55

// Posture-aware down-facing ground detector.  The sensor is carried at the
// same angle as the cane: no absolute down distance is a step rule.  400 cm
// is the VL53L1X invalid/no-target sentinel and is never a drop.
#define SMARTCANE_DOWN_NO_TARGET_CM 400
#define SMARTCANE_STEP_UP_ENTER_CM 9
#define SMARTCANE_STEP_DOWN_ENTER_CM 11
#define SMARTCANE_DEEP_DROP_CM 30
#define SMARTCANE_STEP_CLEAR_CM 5
#define SMARTCANE_DOWN_BASELINE_TOLERANCE_CM 3
#define SMARTCANE_DOWN_BASELINE_STABLE_FRAMES 7
#define SMARTCANE_STEP_CONFIRM_SAMPLES 2
#define SMARTCANE_STEP_HISTORY_SAMPLES 3
#define SMARTCANE_STEP_REBASE_STABLE_FRAMES 4
#define SMARTCANE_STEP_REBASE_MIN_HOLD_MS 700
// A hand sweep must settle in normal use before its down-facing samples may
// contribute to a step candidate. This removes the stale-candidate path where
// a brief upward sweep was reported as a drop after the hand stopped.
#define SMARTCANE_STEP_NORMAL_POSE_SETTLE_MS 250
#define SMARTCANE_DOWN_NORMAL_POSE_DELTA_DEG 18.0f
#define SMARTCANE_DOWN_MOTION_POSE_DELTA_DEG 10.0f
#define SMARTCANE_DOWN_MOTION_GYRO_DPS 35.0f
#define SMARTCANE_DOWN_NORMAL_G_DELTA 0.18f
// These are fixed hardware mounting offsets, not a per-use cane-angle input.
// Keep 0/0 when the ToF optical axis is aligned with the BMI270 board axis.
#define SMARTCANE_DOWN_SENSOR_MOUNT_PITCH_DEG 0.0f
#define SMARTCANE_DOWN_SENSOR_MOUNT_ROLL_DEG 0.0f
#define SMARTCANE_FRONT_BUZZ_CM SMARTCANE_FRONT_WARN_CM
#define SMARTCANE_SIDE_BUZZ_CM SMARTCANE_SIDE_ALERT_CM

// Sensor limitations and filtering.
#define SMARTCANE_TOF_MIN_VALID_MM 20
#define SMARTCANE_TOF_MAX_VALID_MM 4000
#define SMARTCANE_TOF_FILTER_ALPHA_PERCENT 45
#define SMARTCANE_TOF_FAILS_BEFORE_INVALID 3
#define SMARTCANE_TOF_SINGLE_SHOT_READ 1
#define SMARTCANE_TOF_TIMING_BUDGET_US 20000
#define SMARTCANE_TOF_CONTINUOUS_PERIOD_MS 25

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
#define SMARTCANE_FALL_ACCEL_HIGH_G 1.22f
#define SMARTCANE_FALL_ACCEL_LOW_G 0.85f
#define SMARTCANE_FALL_GYRO_TRIGGER_DPS 70.0f
#define SMARTCANE_FALL_FAST_ANGLE_DEG 45.0f
#define SMARTCANE_FALL_FAST_TILT_RATE_DPS 85.0f
#define SMARTCANE_FALL_LYING_ANGLE_DEG 58.0f
#define SMARTCANE_FALL_STILL_GYRO_DPS 22.0f
#define SMARTCANE_FALL_STILL_ACC_MIN_G 0.78f
#define SMARTCANE_FALL_STILL_ACC_MAX_G 1.22f
#define SMARTCANE_FALL_CONFIRM_MS 2000
#define SMARTCANE_FALL_CANDIDATE_WINDOW_MS 1100
#define SMARTCANE_FALL_CANCEL_UPRIGHT_DEG 20.0f
#define SMARTCANE_FALL_BASELINE_STILL_GYRO_DPS 18.0f
#define SMARTCANE_FALL_BASELINE_ACC_MIN_G 0.90f
#define SMARTCANE_FALL_BASELINE_ACC_MAX_G 1.10f
#define SMARTCANE_FALL_NORMAL_USE_READY_MS 500
// Once a normal-use posture has been held for READY_MS, keep that qualification
// for this short launch window.  The first fall sample is necessarily no longer
// still/upright, so clearing it on that same sample would miss the fall.
#define SMARTCANE_FALL_NORMAL_USE_LAUNCH_WINDOW_MS 1200
#define SMARTCANE_FALL_RECOVERY_MS 1200
#define SMARTCANE_FALL_UPLOAD_COOLDOWN_MS 3000
#define SMARTCANE_FALL_ALERT_BUZZ_MS 2000
#define SMARTCANE_FALL_ALERT_VIB_MS 2000

// Only these escalated obstacle states are sent to the companion side.
#define SMARTCANE_COMPANION_OBSTACLE_HOLD_MS 12000
#define SMARTCANE_COMPANION_APPROACH_WINDOW_MS 2500
#define SMARTCANE_COMPANION_APPROACH_DELTA_CM 15
#define SMARTCANE_COMPANION_ALERT_COOLDOWN_MS 30000
