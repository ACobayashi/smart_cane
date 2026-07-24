#pragma once

#include <Arduino.h>

enum RiskLevel {
  RISK_LOW = 0,
  RISK_MEDIUM = 1,
  RISK_HIGH = 2
};

enum TouchEventType {
  TOUCH_EVENT_TAP = 0,
  TOUCH_EVENT_LONG_PRESS,
  TOUCH_EVENT_DOUBLE_CLICK
};

struct DistanceReadings {
  int frontCm = 400;
  int leftCm = 400;
  int rightCm = 400;
  int downCm = 45;
  int downRawCm = 45;
  bool frontValid = false;
  bool leftValid = false;
  bool rightValid = false;
  bool downValid = false;
  bool downNoTarget = false;
  const char *downStatus = "uninitialized";
  uint8_t downFailCount = 0;
  uint8_t downStableFrames = 0;
  bool valid = false;
  unsigned long timestampMs = 0;
};

struct NearbyRiskSummary {
  bool available = false;
  int riskCount = 0;
  int highCount = 0;
  int mediumCount = 0;
  RiskLevel maxLevel = RISK_LOW;
  unsigned long updatedAtMs = 0;
};

struct LocationData {
  double lat = 0.0;
  double lng = 0.0;
  bool valid = false;
  bool mock = true;
  float accuracyM = 30.0f;
  float hdop = 0.0f;
  uint8_t fixQuality = 0;
  uint8_t satelliteCount = 0;
  const char *provider = "mock";
  const char *quality = "mock";
  unsigned long updatedAtMs = 0;
};

struct RiskState {
  RiskLevel level = RISK_LOW;
  const char *riskType = "none";
  const char *direction = "none";
  const char *sensor = "device";
  const char *reason = "clear";
  int distanceMm = -1;
  float confidence = 0.2f;
  bool groundDrop = false;
  bool frontObstacle = false;
  bool sideObstacle = false;
  bool realtimeMedium = false;
  bool realtimeHigh = false;
  bool historyInfluenced = false;
  unsigned long detectedAtMs = 0;
};

struct DeepRiskResult {
  bool available = false;
  float score = 0.0f;
  float confidence = 0.0f;
  RiskLevel level = RISK_LOW;
  char model[32] = "";
};

struct PathRecord {
  unsigned long timestampMs = 0;
  double lat = 0.0;
  double lng = 0.0;
  RiskLevel level = RISK_LOW;
  char riskType[24] = "none";
};

inline const char *riskLevelToString(RiskLevel level) {
  switch (level) {
    case RISK_HIGH:
      return "high";
    case RISK_MEDIUM:
      return "medium";
    case RISK_LOW:
    default:
      return "low";
  }
}

inline RiskLevel riskLevelFromString(const String &level) {
  if (level == "high") return RISK_HIGH;
  if (level == "medium") return RISK_MEDIUM;
  return RISK_LOW;
}
