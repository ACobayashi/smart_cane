#include "network_client.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <WiFi.h>

#include "config.h"

static LocationData networkLocation = {
  MOCK_LAT,
  MOCK_LNG,
  true,
  true,
  GPS_MOCK_ACCURACY_M,
  0,
  0
};

static bool credentialsConfigured() {
  String ssid = WIFI_SSID;
  return ssid.length() > 0 && ssid != "YOUR_WIFI_SSID";
}

static bool postJson(const String &path, const String &payload, String &response, int &status) {
  if (!connectWifi()) {
    Serial.println("network unavailable: POST skipped");
    return false;
  }

  HTTPClient http;
  String url = String(SERVER_BASE_URL) + path;
  http.begin(url);
  http.addHeader("Content-Type", "application/json");
  status = http.POST(payload);
  response = http.getString();
  http.end();

  Serial.print("POST ");
  Serial.print(url);
  Serial.print(" status=");
  Serial.println(status);
  if (response.length() > 0) {
    Serial.println(response);
  }

  return status >= 200 && status < 300;
}

bool connectWifi() {
  if (!credentialsConfigured()) {
    Serial.println("network unavailable: Wi-Fi credentials are not configured");
    return false;
  }

  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }

  Serial.print("Connecting Wi-Fi: ");
  Serial.println(WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

  unsigned long startAt = millis();
  while (WiFi.status() != WL_CONNECTED && (millis() - startAt) < WIFI_CONNECT_TIMEOUT_MS) {
    delay(100);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Wi-Fi connected, IP=");
    Serial.println(WiFi.localIP());
    return true;
  }

  Serial.println("network unavailable: Wi-Fi connect failed");
  return false;
}

bool isNetworkAvailable() {
  return WiFi.status() == WL_CONNECTED;
}

void setNetworkLocation(const LocationData &location) {
  if (!location.valid) {
    return;
  }
  networkLocation = location;
}

bool uploadLocation(const LocationData &location) {
  if (!location.valid) {
    return false;
  }
  setNetworkLocation(location);

  StaticJsonDocument<384> doc;
  doc["device_id"] = DEVICE_ID;
  doc["lat"] = location.lat;
  doc["lng"] = location.lng;
  doc["source"] = location.mock ? "mock" : "gps";
  doc["accuracy_m"] = location.accuracyM;
  doc["satellite_count"] = location.satelliteCount;

  String payload;
  serializeJson(doc, payload);

  String response;
  int status = 0;
  bool ok = postJson("/api/locations", payload, response, status);
  if (!ok) {
    Serial.println("network unavailable: location upload failed");
  }
  return ok;
}

bool uploadEvent(const String &riskType,
                 const String &riskLevel,
                 int frontCm,
                 int leftCm,
                 int rightCm,
                 int downCm,
                 const String &extra) {
  StaticJsonDocument<512> doc;
  doc["device_id"] = DEVICE_ID;
  doc["lat"] = networkLocation.lat;
  doc["lng"] = networkLocation.lng;
  doc["risk_type"] = riskType;
  doc["risk_level"] = riskLevel;
  doc["front_cm"] = frontCm;
  doc["left_cm"] = leftCm;
  doc["right_cm"] = rightCm;
  doc["down_cm"] = downCm;
  doc["extra_json"] = extra;

  String payload;
  serializeJson(doc, payload);

  Serial.println(payload);
  String response;
  int status = 0;
  if (!postJson("/api/events", payload, response, status)) {
    Serial.println("network unavailable: upload failed or server rejected event");
    return false;
  }
  return true;
}

bool fetchAiAdvice(const RiskState &risk,
                   const DistanceReadings &distances,
                   const NearbyRiskSummary &history,
                   String &outAdvice) {
  StaticJsonDocument<768> doc;
  doc["device_id"] = DEVICE_ID;
  doc["lat"] = networkLocation.lat;
  doc["lng"] = networkLocation.lng;
  doc["risk_type"] = risk.riskType;
  doc["risk_level"] = riskLevelToString(risk.level);
  doc["front_cm"] = distances.frontCm;
  doc["left_cm"] = distances.leftCm;
  doc["right_cm"] = distances.rightCm;
  doc["down_cm"] = distances.downCm;
  doc["nearby_radius_m"] = NEARBY_RADIUS_M;

  String extra = "history_count=";
  extra += String(history.riskCount);
  extra += ";history_high=";
  extra += String(history.highCount);
  extra += ";reason=";
  extra += risk.reason;
  doc["extra"] = extra;

  String payload;
  serializeJson(doc, payload);

  String response;
  int status = 0;
  if (!postJson("/api/ai/advice", payload, response, status)) {
    Serial.println("network unavailable: AI advice failed");
    return false;
  }

  StaticJsonDocument<2048> respDoc;
  DeserializationError err = deserializeJson(respDoc, response);
  if (err) {
    Serial.print("AI advice parse failed: ");
    Serial.println(err.c_str());
    return false;
  }

  outAdvice = String((const char *)(respDoc["advice"] | ""));
  return outAdvice.length() > 0;
}

bool fetchNearbyRisks(float lat, float lng, NearbyRiskSummary &out) {
  if (!connectWifi()) {
    Serial.println("network unavailable: fetch nearby risks skipped");
    return false;
  }

  String url = String(SERVER_BASE_URL) + "/api/risks/nearby?lat=";
  url += String(lat, 6);
  url += "&lng=";
  url += String(lng, 6);
  url += "&radius=";
  url += String(NEARBY_RADIUS_M);

  HTTPClient http;
  http.begin(url);
  int status = http.GET();
  String response = http.getString();
  http.end();

  Serial.print("GET ");
  Serial.print(url);
  Serial.print(" status=");
  Serial.println(status);

  if (status < 200 || status >= 300) {
    Serial.println("network unavailable: nearby risk request failed");
    return false;
  }

  StaticJsonDocument<2048> doc;
  DeserializationError err = deserializeJson(doc, response);
  if (err) {
    Serial.print("nearby risk parse failed: ");
    Serial.println(err.c_str());
    return false;
  }

  out.available = true;
  out.riskCount = doc["risk_count"] | 0;
  out.highCount = doc["high_count"] | 0;
  out.mediumCount = doc["medium_count"] | 0;
  out.maxLevel = riskLevelFromString(String((const char *)(doc["max_level"] | "low")));
  out.updatedAtMs = millis();

  out.recentEventsJson = "[]";
  if (doc["recent_events"].is<JsonArray>()) {
    serializeJson(doc["recent_events"], out.recentEventsJson);
  }

  Serial.print("Nearby risks: count=");
  Serial.print(out.riskCount);
  Serial.print(" high=");
  Serial.print(out.highCount);
  Serial.print(" medium=");
  Serial.print(out.mediumCount);
  Serial.print(" max=");
  Serial.println(riskLevelToString(out.maxLevel));
  return true;
}

bool sendTextVoiceCommand(const String &text, String &outReply) {
  StaticJsonDocument<384> doc;
  doc["device_id"] = DEVICE_ID;
  doc["text"] = text;
  doc["lat"] = networkLocation.lat;
  doc["lng"] = networkLocation.lng;

  String payload;
  serializeJson(doc, payload);

  String response;
  int status = 0;
  if (!postJson("/api/voice/text-command", payload, response, status)) {
    Serial.println("network unavailable: voice text command failed");
    return false;
  }

  StaticJsonDocument<1024> respDoc;
  DeserializationError err = deserializeJson(respDoc, response);
  if (err) {
    Serial.print("voice command parse failed: ");
    Serial.println(err.c_str());
    return false;
  }

  const char *reply = respDoc["reply"] | "";
  const char *intent = respDoc["intent"] | "unknown";
  outReply = String("intent=");
  outReply += intent;
  outReply += " reply=";
  outReply += reply;
  return true;
}
