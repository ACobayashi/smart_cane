#include <WiFi.h>
#include <HTTPClient.h>

#include "config.h"

struct Distances {
  int front;
  int left;
  int right;
  int down;
};

struct RiskEvent {
  bool active;
  const char *riskType;
  const char *level;
  const char *direction;
  const char *sensor;
  int distanceMm;
};

unsigned long lastSensorAt = 0;
unsigned long lastUploadAt = 0;
Distances latestDistances = {1500, 1500, 1500, 700};
RiskEvent latestRisk = {false, "", "low", "unknown", "", 0};

void connectWiFi() {
  Serial.print("Connecting Wi-Fi: ");
  Serial.println(WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  int retries = 0;
  while (WiFi.status() != WL_CONNECTED && retries < 40) {
    delay(500);
    Serial.print(".");
    retries++;
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Wi-Fi connected, IP: ");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("Wi-Fi connect failed. Check SSID/password.");
  }
}

void ensureWiFi() {
  if (WiFi.status() != WL_CONNECTED) {
    connectWiFi();
  }
}

Distances readMockDistances() {
  static int step = 0;
  step++;

  Distances d;
  d.front = 1400;
  d.left = 1100;
  d.right = 1100;
  d.down = 650;

  int phase = step % 24;
  if (phase < 6) {
    d.front = 420;
  } else if (phase < 12) {
    d.left = 460;
  } else if (phase < 18) {
    d.right = 620;
  } else {
    d.down = 1200;
  }
  return d;
}

RiskEvent evaluateRisk(const Distances &d) {
  if (d.down >= GROUND_DROP_HIGH_MM) {
    return {true, "ground_drop", "high", "down", "tof_down", d.down};
  }
  if (d.down >= GROUND_DROP_MEDIUM_MM) {
    return {true, "ground_drop", "medium", "down", "tof_down", d.down};
  }
  if (d.front < FRONT_HIGH_MM) {
    return {true, "front_obstacle", "high", "front", "tof_front", d.front};
  }
  if (d.front < FRONT_MEDIUM_MM) {
    return {true, "front_obstacle", "medium", "front", "tof_front", d.front};
  }
  if (d.left < SIDE_HIGH_MM) {
    return {true, "left_obstacle", "high", "left", "tof_left", d.left};
  }
  if (d.left < SIDE_MEDIUM_MM) {
    return {true, "left_obstacle", "medium", "left", "tof_left", d.left};
  }
  if (d.right < SIDE_HIGH_MM) {
    return {true, "right_obstacle", "high", "right", "tof_right", d.right};
  }
  if (d.right < SIDE_MEDIUM_MM) {
    return {true, "right_obstacle", "medium", "right", "tof_right", d.right};
  }
  return {false, "", "low", "unknown", "", 0};
}

String buildRiskJson(const RiskEvent &risk) {
  String json = "{";
  json += "\"device_id\":\"" + String(DEVICE_ID) + "\",";
  json += "\"risk_type\":\"" + String(risk.riskType) + "\",";
  json += "\"level\":\"" + String(risk.level) + "\",";
  json += "\"direction\":\"" + String(risk.direction) + "\",";
  json += "\"sensor\":\"" + String(risk.sensor) + "\",";
  json += "\"distance_mm\":" + String(risk.distanceMm) + ",";
  json += "\"battery\":88";
  json += "}";
  return json;
}

bool postRiskEvent(const RiskEvent &risk) {
  if (!risk.active) {
    return false;
  }
  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("Skip upload: Wi-Fi disconnected.");
    return false;
  }

  HTTPClient http;
  String url = String(SERVER_BASE_URL) + "/api/risk-events";
  String payload = buildRiskJson(risk);

  http.begin(url);
  http.addHeader("Content-Type", "application/json");
  int status = http.POST(payload);
  String response = http.getString();
  http.end();

  Serial.print("POST ");
  Serial.print(url);
  Serial.print(" status=");
  Serial.println(status);
  Serial.println(payload);
  Serial.println(response);

  return status >= 200 && status < 300;
}

void printDistances(const Distances &d) {
  Serial.print("front=");
  Serial.print(d.front);
  Serial.print(" left=");
  Serial.print(d.left);
  Serial.print(" right=");
  Serial.print(d.right);
  Serial.print(" down=");
  Serial.println(d.down);
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println();
  Serial.println("ESP32-C5 Smart Cane mock uploader");
  Serial.println("Hardware target: ESP32-C5");
  connectWiFi();
}

void loop() {
  unsigned long now = millis();

  if (now - lastSensorAt >= SENSOR_INTERVAL_MS) {
    lastSensorAt = now;

#if USE_MOCK_SENSORS
    latestDistances = readMockDistances();
#else
    // Later: replace this branch with TCA9548A + VL53L1X readings.
    latestDistances = readMockDistances();
#endif

    latestRisk = evaluateRisk(latestDistances);
    printDistances(latestDistances);

    if (latestRisk.active) {
      Serial.print("Risk: ");
      Serial.print(latestRisk.level);
      Serial.print(" ");
      Serial.print(latestRisk.riskType);
      Serial.print(" ");
      Serial.println(latestRisk.distanceMm);
    } else {
      Serial.println("Risk: none");
    }
  }

  if (now - lastUploadAt >= UPLOAD_INTERVAL_MS) {
    lastUploadAt = now;
    ensureWiFi();
    postRiskEvent(latestRisk);
  }
}
