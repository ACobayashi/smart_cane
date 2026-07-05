#pragma once

// Change these before uploading to ESP32-C5.
// SERVER_BASE_URL must be your computer LAN IP, not 127.0.0.1.
// Example: "http://10.130.255.68:8000"
const char *WIFI_SSID = "YOUR_WIFI_SSID";
const char *WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";
const char *SERVER_BASE_URL = "http://10.130.255.68:8000";
const char *DEVICE_ID = "cane_001";

// Keep this true before sensors arrive. Later set it to false and replace
// readMockDistances() with real VL53L1X readings.
#define USE_MOCK_SENSORS 1

const unsigned long SENSOR_INTERVAL_MS = 300;
const unsigned long UPLOAD_INTERVAL_MS = 2000;

const int FRONT_HIGH_MM = 600;
const int FRONT_MEDIUM_MM = 1200;
const int SIDE_HIGH_MM = 500;
const int SIDE_MEDIUM_MM = 900;
const int GROUND_DROP_HIGH_MM = 1050;
const int GROUND_DROP_MEDIUM_MM = 850;
