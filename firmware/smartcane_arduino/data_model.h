#pragma once

#include <Arduino.h>

enum RiskLevel {
  RISK_LOW = 0,
  RISK_MEDIUM = 1,
  RISK_HIGH = 2
};

struct DistanceReadings {
  int frontCm;
  int leftCm;
  int rightCm;
  int downCm;
  bool valid;
  unsigned long timestampMs;
};

struct NearbyRiskSummary {
  bool available;
  int riskCount;
  int highCount;
  int mediumCount;
  RiskLevel maxLevel;
  String recentEventsJson;
  unsigned long updatedAtMs;
};

struct RiskState {
  RiskLevel level;
  String riskType;
  String directionHint;
  String reason;
  bool groundDrop;
  bool frontObstacle;
  bool sideObstacle;
  bool realtimeMedium;
  bool realtimeHigh;
  bool historyInfluenced;
  unsigned long detectedAtMs;
};

struct LocationData {
  float lat;
  float lng;
  bool valid;
  bool mock;
  float accuracyM;
  uint8_t satelliteCount;
  unsigned long updatedAtMs;
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
  if (level == "high") {
    return RISK_HIGH;
  }
  if (level == "medium") {
    return RISK_MEDIUM;
  }
  return RISK_LOW;
}

inline DistanceReadings makeDefaultDistances() {
  DistanceReadings d;
  d.frontCm = 200;
  d.leftCm = 160;
  d.rightCm = 160;
  d.downCm = 45;
  d.valid = false;
  d.timestampMs = 0;
  return d;
}

inline NearbyRiskSummary makeEmptyNearbyRiskSummary() {
  NearbyRiskSummary summary;
  summary.available = false;
  summary.riskCount = 0;
  summary.highCount = 0;
  summary.mediumCount = 0;
  summary.maxLevel = RISK_LOW;
  summary.recentEventsJson = "[]";
  summary.updatedAtMs = 0;
  return summary;
}

inline LocationData makeMockLocation() {
  LocationData location;
  location.lat = 0.0f;
  location.lng = 0.0f;
  location.valid = false;
  location.mock = true;
  location.accuracyM = 0.0f;
  location.satelliteCount = 0;
  location.updatedAtMs = 0;
  return location;
}
