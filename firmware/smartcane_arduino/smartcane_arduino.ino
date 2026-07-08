#include <Arduino.h>

#include "buttons.h"
#include "buzzer.h"
#include "config.h"
#include "data_model.h"
#include "gps_location.h"
#include "i2c_bus.h"
#include "network_client.h"
#include "risk_logic.h"
#include "tof_sensors.h"
#include "touch_handle.h"
#include "vibration.h"

enum LastFeedbackPattern {
  LAST_PATTERN_NONE,
  LAST_PATTERN_OBSTACLE,
  LAST_PATTERN_GROUND_DROP,
  LAST_PATTERN_TURN_LEFT,
  LAST_PATTERN_TURN_RIGHT,
  LAST_PATTERN_STOP,
  LAST_PATTERN_SOS
};

static DistanceReadings latestDistances = makeDefaultDistances();
static NearbyRiskSummary nearbySummary = makeEmptyNearbyRiskSummary();
static LocationData latestLocation = makeMockLocation();
static RiskState latestRisk;

static bool onlineMode = NETWORK_MODE_DEFAULT;
static unsigned long lastSensorAt = 0;
static unsigned long lastStatusAt = 0;
static unsigned long lastFeedbackAt = 0;
static unsigned long lastAutoUploadAt = 0;
static unsigned long lastNearbyFetchAt = 0;
static unsigned long lastLocationUploadAt = 0;
static LastFeedbackPattern lastPattern = LAST_PATTERN_NONE;

static void runPattern(LastFeedbackPattern pattern) {
  switch (pattern) {
    case LAST_PATTERN_OBSTACLE:
      patternObstacle();
      break;
    case LAST_PATTERN_GROUND_DROP:
      patternGroundDrop();
      beepPatternDanger();
      break;
    case LAST_PATTERN_TURN_LEFT:
      patternTurnLeft();
      break;
    case LAST_PATTERN_TURN_RIGHT:
      patternTurnRight();
      break;
    case LAST_PATTERN_STOP:
      patternStop();
      beep(BEEP_SHORT_MS);
      break;
    case LAST_PATTERN_SOS:
      patternSos();
      beepPatternSos();
      break;
    case LAST_PATTERN_NONE:
    default:
      Serial.println("No previous feedback pattern to repeat");
      break;
  }
}

static String buildExtra(const String &source) {
  String extra = "source=";
  extra += source;
  extra += ";location=";
  extra += (latestLocation.mock ? "mock" : "gps");
  extra += ";reason=";
  extra += latestRisk.reason;
  extra += ";history_count=";
  extra += String(nearbySummary.riskCount);
  extra += ";history_high=";
  extra += String(nearbySummary.highCount);
  return extra;
}

static void uploadCurrentEvent(const String &riskType, const String &riskLevel, const String &source) {
  setNetworkLocation(latestLocation);
  uploadEvent(riskType,
              riskLevel,
              latestDistances.frontCm,
              latestDistances.leftCm,
              latestDistances.rightCm,
              latestDistances.downCm,
              buildExtra(source));
}

static void fetchNearbyIfNeeded(bool force) {
  if (!onlineMode) {
    return;
  }
  unsigned long now = millis();
  if (!force && (now - lastNearbyFetchAt) < NEARBY_FETCH_INTERVAL_MS) {
    return;
  }
  lastNearbyFetchAt = now;
  setNetworkLocation(latestLocation);
  fetchNearbyRisks(latestLocation.lat, latestLocation.lng, nearbySummary);
}

static void uploadLocationIfNeeded(bool force) {
  if (!onlineMode || !latestLocation.valid) {
    return;
  }
  unsigned long now = millis();
  if (!force && (now - lastLocationUploadAt) < LOCATION_UPLOAD_INTERVAL_MS) {
    return;
  }
  lastLocationUploadAt = now;
  uploadLocation(latestLocation);
}

static void requestAiAdvice() {
  if (!onlineMode) {
    Serial.println("AI advice skipped: local mode");
    return;
  }
  setNetworkLocation(latestLocation);
  String advice;
  if (fetchAiAdvice(latestRisk, latestDistances, nearbySummary, advice)) {
    Serial.print("AI advice: ");
    Serial.println(advice);
  } else {
    Serial.println("AI advice unavailable");
  }
}

static void maybeUploadAutoRisk() {
  if (!onlineMode) {
    return;
  }
  if (latestRisk.level != RISK_HIGH) {
    return;
  }

  unsigned long now = millis();
  if ((now - lastAutoUploadAt) < AUTO_UPLOAD_COOLDOWN_MS) {
    return;
  }

  if (latestRisk.riskType == "ground_drop" || latestRisk.riskType == "front_obstacle") {
    lastAutoUploadAt = now;
    uploadCurrentEvent(latestRisk.riskType, riskLevelToString(latestRisk.level), "auto_detected");
  }
}

static void applyLocalFeedback() {
  unsigned long now = millis();
  if ((now - lastFeedbackAt) < FEEDBACK_REPEAT_MS) {
    return;
  }

  if (latestRisk.groundDrop) {
    patternGroundDrop();
    beepPatternDanger();
    lastPattern = LAST_PATTERN_GROUND_DROP;
    lastFeedbackAt = now;
    return;
  }

  if (latestRisk.frontObstacle) {
    if (latestDistances.frontCm < FRONT_DANGER_CM) {
      vibrateCenter(VIB_LEVEL_HIGH, 220);
      beep(BEEP_SHORT_MS);
      lastPattern = LAST_PATTERN_OBSTACLE;
    } else {
      patternObstacle();
      lastPattern = LAST_PATTERN_OBSTACLE;
    }

    bool leftSafe = latestDistances.leftCm > SIDE_SAFE_CM;
    bool rightSafe = latestDistances.rightCm > SIDE_SAFE_CM;

    if (!leftSafe && !rightSafe) {
      patternStop();
      lastPattern = LAST_PATTERN_STOP;
      Serial.println("Guidance: stop, both sides are narrow");
    } else if (leftSafe && latestDistances.leftCm > latestDistances.rightCm) {
      patternTurnLeft();
      lastPattern = LAST_PATTERN_TURN_LEFT;
      Serial.println("Guidance: turn left");
    } else if (rightSafe && latestDistances.rightCm > latestDistances.leftCm) {
      patternTurnRight();
      lastPattern = LAST_PATTERN_TURN_RIGHT;
      Serial.println("Guidance: turn right");
    } else {
      Serial.println("Guidance: slow down");
    }

    lastFeedbackAt = now;
    return;
  }

  if (latestRisk.riskType == "history_risk") {
    vibrateCenter(VIB_LEVEL_LOW, 120);
    lastPattern = LAST_PATTERN_OBSTACLE;
    lastFeedbackAt = now;
  }
}

static void handleSos() {
  Serial.println("SOS triggered: long press detected");
  Serial.print("SOS device_id=");
  Serial.print(DEVICE_ID);
  Serial.print(" lat=");
  Serial.print(latestLocation.lat, 6);
  Serial.print(" lng=");
  Serial.println(latestLocation.lng, 6);

  patternSos();
  beepPatternSos();
  lastPattern = LAST_PATTERN_SOS;
  uploadCurrentEvent("sos", "high", "sos_button");
}

static void handleTouchEvent(uint8_t electrode, TouchEventType type) {
  Serial.print("Touch action electrode=");
  Serial.print(electrode);
  Serial.print(" event=");
  Serial.println(touchEventName(type));

  if (electrode == 0 && type == TOUCH_EVENT_TAP) {
    Serial.println("Touch E0: query current road risk");
    printRiskState(latestDistances, latestRisk, nearbySummary);
    requestAiAdvice();
    return;
  }

  if (electrode == 0 && type == TOUCH_EVENT_DOUBLE_CLICK) {
    Serial.println("Touch E0 double click: send cloud voice text command demo");
    String reply;
    if (sendTextVoiceCommand("query nearby risks", reply)) {
      Serial.print("Voice command result: ");
      Serial.println(reply);
    } else {
      Serial.println("Voice command unavailable");
    }
    return;
  }

  if (electrode == 1 && type == TOUCH_EVENT_LONG_PRESS) {
    Serial.println("Touch E1: upload user_mark risk point");
    uploadCurrentEvent("user_mark", "medium", "touch_e1_long_press");
    return;
  }

  if (electrode == 2 && type == TOUCH_EVENT_TAP) {
    Serial.println("Touch E2: repeat last feedback pattern");
    runPattern(lastPattern);
    return;
  }

  if (electrode == 3 && type == TOUCH_EVENT_TAP) {
    onlineMode = !onlineMode;
    Serial.print("Touch E3: mode switched to ");
    Serial.println(onlineMode ? "network mode" : "local mode");
    if (onlineMode) {
      connectWifi();
      fetchNearbyIfNeeded(true);
    }
    return;
  }

  if (electrode == 4 && type == TOUCH_EVENT_TAP) {
    Serial.println("Touch E4: left/previous item");
    vibrateLeft(VIB_LEVEL_LOW, 120);
    lastPattern = LAST_PATTERN_TURN_LEFT;
    return;
  }

  if (electrode == 5 && type == TOUCH_EVENT_TAP) {
    Serial.println("Touch E5: right/next item");
    vibrateRight(VIB_LEVEL_LOW, 120);
    lastPattern = LAST_PATTERN_TURN_RIGHT;
    return;
  }

  if (type == TOUCH_EVENT_DOUBLE_CLICK) {
    Serial.println("Touch double click acknowledged");
  } else if (type == TOUCH_EVENT_LONG_PRESS) {
    Serial.println("Touch long press acknowledged");
  }
}

static void printBootHelp() {
  Serial.println();
  Serial.println("ESP32-C5 collaborative tactile smart cane");
  Serial.print("Device ID: ");
  Serial.println(DEVICE_ID);
  Serial.print("Mock sensor mode: ");
  Serial.println(isTofMockActive() ? "active" : "inactive");
  Serial.print("Network mode: ");
  Serial.println(onlineMode ? "enabled" : "disabled");
  printLocationStatus();
  Serial.println("Touch map:");
  Serial.println("  E0 tap: query current risk + AI advice");
  Serial.println("  E0 double click: cloud voice text command demo");
  Serial.println("  E1 long press: upload user_mark");
  Serial.println("  E2 tap: repeat last feedback");
  Serial.println("  E3 tap: local/network mode");
  Serial.println("  E4/E5 tap: previous/next or left/right");
  Serial.println("Serial touch mock: 0-5 tap, A-F long press, a-f double click, U=user_mark, R=repeat, M=mode");
  Serial.println();
}

void setup() {
  Serial.begin(115200);
  delay(500);

  beginI2CBus();
  printI2CDeviceStatus();
  beginTofSensors();
  beginGpsLocation();
  beginTouchHandle();
  beginVibration();
  beginBuzzer();
  beginButtons();

  latestRisk = calculateRisk(latestDistances, nearbySummary);
  latestLocation = getCurrentLocation();
  setNetworkLocation(latestLocation);
  printBootHelp();

  if (onlineMode) {
    connectWifi();
    uploadLocationIfNeeded(true);
    fetchNearbyIfNeeded(true);
  }
}

void loop() {
  updateVibration();
  updateBuzzer();
  updateGpsLocation();
  latestLocation = getCurrentLocation();
  setNetworkLocation(latestLocation);
  updateButtons(handleSos);
  updateTouchHandle(handleTouchEvent);
  uploadLocationIfNeeded(false);
  fetchNearbyIfNeeded(false);

  unsigned long now = millis();
  if ((now - lastSensorAt) >= SENSOR_SAMPLE_INTERVAL_MS) {
    lastSensorAt = now;
    readTofSensors(latestDistances);
    latestRisk = calculateRisk(latestDistances, nearbySummary);
    applyLocalFeedback();
    maybeUploadAutoRisk();
  }

  if ((now - lastStatusAt) >= SERIAL_STATUS_INTERVAL_MS) {
    lastStatusAt = now;
    printRiskState(latestDistances, latestRisk, nearbySummary);
    printLocationStatus();
  }
}
