#include "network_client.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WiFi.h>
#include <string.h>

#include "config.h"

static unsigned long lastWifiRetryMs = 0;
static unsigned long lastNetworkUnavailableLogMs = 0;

static bool wifiConfigured() {
  String ssid = SMARTCANE_WIFI_SSID;
  return ssid.length() > 0 && ssid != "YOUR_WIFI_SSID";
}

static String makeUrl(const char *path) {
  String base = SMARTCANE_SERVER_BASE_URL;
  if (base.endsWith("/")) {
    base.remove(base.length() - 1);
  }
  return base + path;
}

static void printNetworkUnavailable() {
  unsigned long now = millis();
  if (lastNetworkUnavailableLogMs != 0 &&
      now - lastNetworkUnavailableLogMs < SMARTCANE_NETWORK_UNAVAILABLE_LOG_INTERVAL_MS) {
    return;
  }
  lastNetworkUnavailableLogMs = now;
  Serial.println(F("[NET] network unavailable"));
}

static bool postJson(const char *path, const String &body, String *responseOut = nullptr) {
  if (!networkAvailable()) {
    printNetworkUnavailable();
    return false;
  }

  HTTPClient http;
  String url = makeUrl(path);
  if (!http.begin(url)) {
    Serial.print(F("[NET] HTTP begin failed "));
    Serial.println(url);
    return false;
  }
  http.setTimeout(SMARTCANE_HTTP_TIMEOUT_MS);
  http.setReuse(false);
  http.addHeader("Content-Type", "application/json");
  http.addHeader("Connection", "close");

  int code = http.POST(body);
  String response = http.getString();
  http.end();

  if (code < 200 || code >= 300) {
    Serial.print(F("[NET] POST "));
    Serial.print(path);
    Serial.print(F(" failed code="));
    Serial.print(code);
    Serial.print(F(" body="));
    Serial.println(response);
    return false;
  }

  if (responseOut != nullptr) {
    *responseOut = response;
  }
  if (strncmp(path, "/api/sensor-frames", strlen("/api/sensor-frames")) != 0) {
    Serial.print(F("[NET] POST "));
    Serial.print(path);
    Serial.println(F(" OK"));
  }
  return true;
}

static bool getJson(const String &url, String &responseOut) {
  if (!networkAvailable()) {
    printNetworkUnavailable();
    return false;
  }

  HTTPClient http;
  if (!http.begin(url)) {
    Serial.print(F("[NET] HTTP begin failed "));
    Serial.println(url);
    return false;
  }
  http.setTimeout(SMARTCANE_HTTP_TIMEOUT_MS);
  http.setReuse(false);
  http.addHeader("Connection", "close");
  int code = http.GET();
  responseOut = http.getString();
  http.end();

  if (code < 200 || code >= 300) {
    Serial.print(F("[NET] GET failed code="));
    Serial.print(code);
    Serial.print(F(" body="));
    Serial.println(responseOut);
    return false;
  }
  return true;
}

bool connectWifi() {
  if (!wifiConfigured()) {
    Serial.println(F("[NET] Wi-Fi not configured; local mode still works"));
    return false;
  }

  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }

  Serial.print(F("[NET] connecting Wi-Fi SSID="));
  Serial.println(SMARTCANE_WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(SMARTCANE_WIFI_SSID, SMARTCANE_WIFI_PASSWORD);

  unsigned long start = millis();
  while (WiFi.status() != WL_CONNECTED &&
         millis() - start < SMARTCANE_WIFI_CONNECT_TIMEOUT_MS) {
    delay(100);
    Serial.print(F("."));
  }
  Serial.println();

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println(F("[NET] Wi-Fi connect failed; network unavailable"));
    lastWifiRetryMs = millis();
    return false;
  }

  Serial.print(F("[NET] Wi-Fi OK ip="));
  Serial.println(WiFi.localIP());
  return true;
}

bool networkAvailable() {
  return wifiConfigured() && WiFi.status() == WL_CONNECTED;
}

void networkClientUpdate() {
  if (networkAvailable() || !wifiConfigured()) {
    return;
  }
  unsigned long now = millis();
  if (now - lastWifiRetryMs >= 15000) {
    lastWifiRetryMs = now;
    WiFi.disconnect(false);
    WiFi.begin(SMARTCANE_WIFI_SSID, SMARTCANE_WIFI_PASSWORD);
    Serial.println(F("[NET] Wi-Fi retry started"));
  }
}

bool uploadLocation(const LocationData &location) {
  DynamicJsonDocument doc(512);
  doc["device_id"] = SMARTCANE_DEVICE_ID;
  doc["device_name"] = SMARTCANE_DEVICE_NAME;
  doc["firmware_build"] = SMARTCANE_BUILD_TAG;
  doc["lat"] = location.lat;
  doc["lng"] = location.lng;
  doc["source"] = location.mock ? "mock" : "gps";
  doc["provider"] = location.provider;
  doc["quality"] = location.quality;
  doc["accuracy_m"] = location.accuracyM;
  doc["hdop"] = location.hdop;
  doc["fix_quality"] = location.fixQuality;
  doc["satellite_count"] = location.satelliteCount;

  String body;
  serializeJson(doc, body);
  return postJson("/api/locations", body);
}

bool uploadRiskEvent(const char *riskType,
                     const char *riskLevel,
                     const char *direction,
                     const char *sensor,
                     int distanceMm,
                     const DistanceReadings &distances,
                     const LocationData &location,
                     const char *extra) {
  DynamicJsonDocument doc(768);
  doc["device_id"] = SMARTCANE_DEVICE_ID;
  doc["device_name"] = SMARTCANE_DEVICE_NAME;
  doc["lat"] = location.lat;
  doc["lng"] = location.lng;
  doc["risk_type"] = riskType;
  doc["risk_level"] = riskLevel;
  doc["level"] = riskLevel;
  doc["direction"] = direction;
  doc["sensor"] = sensor;
  doc["distance_mm"] = distanceMm;
  doc["battery"] = SMARTCANE_BATTERY_PERCENT_UNKNOWN;
  doc["front_cm"] = distances.frontCm;
  doc["left_cm"] = distances.leftCm;
  doc["right_cm"] = distances.rightCm;
  doc["down_cm"] = distances.downCm;
  doc["extra_json"] = extra;

  String body;
  serializeJson(doc, body);
  return postJson("/api/risk-events", body);
}

bool uploadEvent(const RiskState &risk,
                 const DistanceReadings &distances,
                 const LocationData &location,
                 const char *extra) {
  return uploadRiskEvent(risk.riskType,
                         riskLevelToString(risk.level),
                         risk.direction,
                         risk.sensor,
                         risk.distanceMm,
                         distances,
                         location,
                         extra);
}

bool uploadSensorFrame(const RiskState &risk,
                       const DistanceReadings &distances,
                       const LocationData &location,
                       const ImuFallState &fall,
                       const char *alertType,
                       const char *extra,
                       const char *buttonEvent) {
  DynamicJsonDocument doc(1024);
  doc["device_id"] = SMARTCANE_DEVICE_ID;
  doc["device_name"] = SMARTCANE_DEVICE_NAME;
  doc["firmware_build"] = SMARTCANE_BUILD_TAG;
  doc["lat"] = location.lat;
  doc["lng"] = location.lng;
  doc["front_cm"] = distances.frontCm;
  doc["left_cm"] = distances.leftCm;
  doc["right_cm"] = distances.rightCm;
  doc["down_cm"] = distances.downCm;
  doc["battery"] = SMARTCANE_BATTERY_PERCENT_UNKNOWN;
  doc["source"] = "esp32c5";
  doc["location_provider"] = location.provider;
  doc["location_quality"] = location.quality;
  doc["manual_risk_type"] = risk.level == RISK_LOW ? "none" : risk.riskType;

  if (alertType != nullptr && alertType[0] != '\0') {
    doc["alert_type"] = alertType;
  }
  if (buttonEvent != nullptr && buttonEvent[0] != '\0') {
    doc["button_event"] = buttonEvent;
  }

  doc["accel_x_g"] = fall.axG;
  doc["accel_y_g"] = fall.ayG;
  doc["accel_z_g"] = fall.azG;
  doc["accel_total_g"] = fall.totalG;
  bool fallAlert = alertType != nullptr && strcmp(alertType, "fall_detected") == 0;
  doc["fall_detected"] = fallAlert || strcmp(risk.riskType, "fall_detected") == 0;
  doc["fall_stage"] = fall.stage;
  doc["fall_confidence"] = fall.confidence;

  if (extra != nullptr && extra[0] != '\0') {
    doc["extra"] = extra;
  }

  String body;
  serializeJson(doc, body);
  return postJson("/api/sensor-frames?lite=1", body);
}

bool fetchNearbyRisks(double lat, double lng, NearbyRiskSummary &out) {
  String url = makeUrl("/api/risks/nearby?lat=");
  url += String(lat, 6);
  url += "&lng=";
  url += String(lng, 6);
  url += "&radius=";
  url += String(SMARTCANE_NEARBY_RADIUS_M);

  String response;
  if (!getJson(url, response)) {
    out.available = false;
    return false;
  }

  DynamicJsonDocument doc(2048);
  DeserializationError error = deserializeJson(doc, response);
  if (error) {
    Serial.print(F("[NET] nearby JSON parse failed: "));
    Serial.println(error.c_str());
    out.available = false;
    return false;
  }

  out.available = true;
  out.riskCount = doc["risk_count"] | 0;
  out.highCount = doc["high_count"] | 0;
  out.mediumCount = doc["medium_count"] | 0;
  const char *maxLevel = doc["max_level"] | "low";
  out.maxLevel = riskLevelFromString(String(maxLevel));
  out.updatedAtMs = millis();
  Serial.println(F("[NET] nearby risks updated"));
  return true;
}

bool fetchDeepRisk(const RiskState &risk,
                   const DistanceReadings &distances,
                   const LocationData &location,
                   DeepRiskResult &out) {
  DynamicJsonDocument doc(768);
  doc["device_id"] = SMARTCANE_DEVICE_ID;
  doc["lat"] = location.lat;
  doc["lng"] = location.lng;
  doc["risk_type"] = risk.riskType;
  doc["risk_level"] = riskLevelToString(risk.level);
  doc["front_cm"] = distances.frontCm;
  doc["left_cm"] = distances.leftCm;
  doc["right_cm"] = distances.rightCm;
  doc["down_cm"] = distances.downCm;
  doc["accuracy_m"] = location.accuracyM;
  doc["location_quality"] = location.quality;
  doc["nearby_radius_m"] = SMARTCANE_NEARBY_RADIUS_M;

  String body;
  serializeJson(doc, body);

  String response;
  if (!postJson("/api/ai/deep-risk", body, &response)) {
    out.available = false;
    return false;
  }

  DynamicJsonDocument res(2048);
  DeserializationError error = deserializeJson(res, response);
  if (error) {
    Serial.print(F("[NET] deep-risk JSON parse failed: "));
    Serial.println(error.c_str());
    out.available = false;
    return false;
  }

  JsonObject deep = res["deep_learning"];
  out.available = true;
  out.score = deep["score"] | 0.0f;
  out.confidence = deep["confidence"] | 0.0f;
  const char *level = deep["level"] | "low";
  out.level = riskLevelFromString(String(level));
  const char *model = deep["model"] | "";
  strncpy(out.model, model, sizeof(out.model) - 1);
  out.model[sizeof(out.model) - 1] = '\0';
  Serial.println(F("[NET] deep risk updated"));
  return true;
}

void printNearbySummary(const NearbyRiskSummary &summary) {
  Serial.print(F("nearby.available="));
  Serial.print(summary.available ? F("yes") : F("no"));
  Serial.print(F(" risk_count="));
  Serial.print(summary.riskCount);
  Serial.print(F(" high="));
  Serial.print(summary.highCount);
  Serial.print(F(" medium="));
  Serial.print(summary.mediumCount);
  Serial.print(F(" max="));
  Serial.println(riskLevelToString(summary.maxLevel));
}

void printDeepRisk(const DeepRiskResult &result) {
  Serial.print(F("deep.available="));
  Serial.print(result.available ? F("yes") : F("no"));
  Serial.print(F(" level="));
  Serial.print(riskLevelToString(result.level));
  Serial.print(F(" score="));
  Serial.print(result.score, 3);
  Serial.print(F(" confidence="));
  Serial.print(result.confidence, 3);
  Serial.print(F(" model="));
  Serial.println(result.model);
}
