#pragma once

#include <Arduino.h>

// -------- Device identity and network --------
// SERVER_BASE_URL must be a LAN IP or reachable host from the ESP32, not
// 127.0.0.1 unless the backend runs on the ESP32 itself.
#define WIFI_SSID "YOUR_WIFI_SSID"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#define SERVER_BASE_URL "http://192.168.1.100:8000"
#define DEVICE_ID "cane_001"

// Demo location. Used when GPS has no fix or USE_GPS_MODULE is disabled.
static const float MOCK_LAT = 31.230400f;
static const float MOCK_LNG = 121.473700f;
static const uint16_t NEARBY_RADIUS_M = 80;

// Keep this enabled for serial/backend demos without sensors attached.
#define MOCK_SENSOR_MODE 1
#define MOCK_TOUCH_SERIAL 1
#define NETWORK_MODE_DEFAULT 1

// -------- GPS / GNSS --------
// Use a UART GPS module such as ATGM336H, NEO-6M, NEO-M8N, or compatible NMEA module.
#define USE_GPS_MODULE 1
#define GPS_MOCK_FALLBACK 1
static const uint8_t GPS_SERIAL_PORT = 1;
static const uint8_t GPS_RX_PIN = 18;  // ESP32 RX, connect to GPS TX
static const uint8_t GPS_TX_PIN = 19;  // ESP32 TX, connect to GPS RX if needed
static const uint32_t GPS_BAUD = 9600;
static const unsigned long LOCATION_UPLOAD_INTERVAL_MS = 15000;
static const unsigned long GPS_FIX_STALE_MS = 10000;
static const float GPS_MOCK_ACCURACY_M = 30.0f;

// -------- I2C pins and addresses --------
static const uint8_t I2C_SDA_PIN = 8;
static const uint8_t I2C_SCL_PIN = 9;
static const uint32_t I2C_CLOCK_HZ = 400000UL;

static const uint8_t TCA9548A_I2C_ADDR = 0x70;
static const uint8_t MPR121_I2C_ADDR = 0x5A;
static const uint8_t PCA9685_I2C_ADDR = 0x40;

static const uint8_t TCA_CH_TOF_FRONT = 0;
static const uint8_t TCA_CH_TOF_LEFT = 1;
static const uint8_t TCA_CH_TOF_RIGHT = 2;
static const uint8_t TCA_CH_TOF_DOWN = 3;

// -------- Other GPIO --------
static const uint8_t SOS_BUTTON_PIN = 4;
static const bool SOS_BUTTON_ACTIVE_LOW = true;
static const uint8_t BUZZER_PIN = 5;
static const bool BUZZER_ACTIVE_HIGH = true;

// Audio interface is cloud-assisted in this version. ESP32-C5 does not run
// large local ASR; phone/I2S audio can upload to backend voice endpoints.
static const int SPEAKER_RESERVED_PIN = -1;
static const int MIC_RESERVED_PIN = -1;
static const bool CLOUD_VOICE_ENABLED = true;

// -------- PCA9685 vibration motor channels --------
static const uint8_t VIB_LEFT_CHANNEL = 0;
static const uint8_t VIB_RIGHT_CHANNEL = 1;
static const uint8_t VIB_CENTER_CHANNEL = 2;
static const uint16_t PCA9685_PWM_MAX = 4095;
static const uint16_t PCA9685_PWM_FREQ_HZ = 160;

// -------- Timing --------
static const unsigned long SENSOR_SAMPLE_INTERVAL_MS = 100;
static const unsigned long SERIAL_STATUS_INTERVAL_MS = 1000;
static const unsigned long FEEDBACK_REPEAT_MS = 800;
static const unsigned long AUTO_UPLOAD_COOLDOWN_MS = 8000;
static const unsigned long NEARBY_FETCH_INTERVAL_MS = 10000;
static const unsigned long WIFI_CONNECT_TIMEOUT_MS = 10000;
static const unsigned long SOS_HOLD_MS = 2000;
static const unsigned long BUTTON_DEBOUNCE_MS = 40;
static const unsigned long TOUCH_LONG_PRESS_MS = 1000;
static const unsigned long TOUCH_DOUBLE_CLICK_MS = 350;

// -------- Risk thresholds, centimeters --------
static const int FRONT_WARN_CM = 120;
static const int FRONT_DANGER_CM = 60;
static const int SIDE_SAFE_CM = 90;
static const int SIDE_NEAR_CM = 55;
static const int GROUND_BASE_CM = 45;
static const int GROUND_DROP_THRESHOLD_CM = 30;

// -------- Feedback strengths --------
static const uint8_t VIB_LEVEL_LOW = 35;
static const uint8_t VIB_LEVEL_MEDIUM = 65;
static const uint8_t VIB_LEVEL_HIGH = 100;
static const uint16_t BEEP_SHORT_MS = 120;
