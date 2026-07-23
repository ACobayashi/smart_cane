#include <Arduino.h>
#include <WiFi.h>
#include <math.h>

#include "buttons.h"
#include "buzzer.h"
#include "config.h"
#include "data_model.h"
#include "i2c_bus.h"
#include "imu_fall.h"
#include "network_client.h"
#include "risk_logic.h"
#include "tof_sensors.h"
#include "touch_handle.h"
#include "vibration.h"

enum FeedbackCue {
  CUE_NONE,
  CUE_OBSTACLE,
  CUE_GROUND_DROP,
  CUE_TURN_LEFT,
  CUE_TURN_RIGHT,
  CUE_STOP,
  CUE_SOS,
  CUE_FRONT_LEFT,
  CUE_FRONT_RIGHT,
  CUE_FRONT_DANGER
};

static DistanceReadings distances;
static NearbyRiskSummary nearby;
static RiskState currentRisk;
static DeepRiskResult deepRisk;
static LocationData location;

static bool networkMode = true;
static bool streamMode = false;
static bool rawStreamMode = false;
static FeedbackCue lastCue = CUE_NONE;
static unsigned long lastSensorMs = 0;
static unsigned long lastStatusMs = 0;
static unsigned long lastFeedbackMs = 0;
static unsigned long lastLocationUploadMs = 0;
static unsigned long lastNearbyFetchMs = 0;
static unsigned long lastDeepRiskMs = 0;
static unsigned long lastTelemetryUploadMs = 0;
static unsigned long lastHeartbeatMs = 0;
static String serialLine;
static RiskState stableRisk;
static RiskState pendingRisk;
static bool riskStabilizerReady = false;
static uint8_t pendingRiskFrames = 0;
static uint8_t clearRiskFrames = 0;
static RiskState lastEventRisk;
static bool haveLastEventRisk = false;
static long lastEventLatCell = 0;
static long lastEventLngCell = 0;
static bool haveLastPathCell = false;
static long lastPathLatCell = 0;
static long lastPathLngCell = 0;
static bool haveLastNearbyCell = false;
static long lastNearbyLatCell = 0;
static long lastNearbyLngCell = 0;
static PathRecord pathBuffer[SMARTCANE_LOCAL_PATH_BUFFER_SIZE];
static uint8_t pathWriteIndex = 0;
static uint8_t pathCount = 0;
static unsigned long lastCompanionAlertMs = 0;
static unsigned long obstacleStartedMs = 0;
static const char *obstacleAlertType = "none";
static unsigned long approachWindowStartMs = 0;
static int approachStartFrontCm = 0;
static unsigned long lastApproachFeedbackMs = 0;
static unsigned long lastSerialCharMs = 0;

#if SMARTCANE_GNSS_ENABLED
static char gnssLine[128];
static uint8_t gnssIndex = 0;
#endif

static void printHelp();
static void printStatus();
static void printVibrationStatus();
static void printPcaProbe();
static void printSerialHeartbeat();
static void repeatLastCue();
static void handleSos();
static void handleVoiceRequest();
static void handleFallEvent(const ImuFallState &fall);
static void monitorCompanionAlerts(const RiskState &risk);
static void uploadCompanionAlert(const char *riskType, RiskLevel level, const char *reason);
static void handleButtonEvent(ButtonEventType type);
static void handleTouchEvent(uint8_t electrode, TouchEventType type);
static void processCommand(String command);
static void printSensorRiskSnapshot();
static bool recordPathPointIfMoved(const RiskState &risk);
static void publishRiskEventIfNeeded(const RiskState &risk);
static RiskState stabilizeRisk(const RiskState &measuredRisk);
static unsigned long telemetryIntervalForRisk(const RiskState &risk);

static void initLocation() {
  location.lat = SMARTCANE_MOCK_LAT;
  location.lng = SMARTCANE_MOCK_LNG;
  location.valid = true;
  location.mock = true;
  location.accuracyM = 30.0f;
  location.provider = "mock";
  location.quality = "mock";
  location.updatedAtMs = millis();
}

static void updateMockRoute() {
#if SMARTCANE_MOCK_ROUTE_ENABLED
  if (!location.mock) {
    return;
  }
  static long step = 0;
  step++;
  location.lat = SMARTCANE_MOCK_LAT + SMARTCANE_MOCK_ROUTE_STEP_DEG * step;
  location.lng = SMARTCANE_MOCK_LNG + SMARTCANE_MOCK_ROUTE_STEP_DEG * 0.6 * step;
  location.updatedAtMs = millis();
#endif
}

#if SMARTCANE_GNSS_ENABLED
static double nmeaCoordToDecimal(const char *text, const char *hemi) {
  if (text == nullptr || text[0] == '\0') {
    return 0.0;
  }
  double raw = atof(text);
  int degrees = (int)(raw / 100.0);
  double minutes = raw - degrees * 100.0;
  double value = degrees + minutes / 60.0;
  if (hemi != nullptr && (hemi[0] == 'S' || hemi[0] == 'W')) {
    value = -value;
  }
  return value;
}

static void parseGgaLine(char *line) {
  if (strncmp(line, "$GNGGA", 6) != 0 && strncmp(line, "$GPGGA", 6) != 0 &&
      strncmp(line, "$BDGGA", 6) != 0) {
    return;
  }

  char *fields[15] = {nullptr};
  uint8_t count = 0;
  char *token = strtok(line, ",");
  while (token != nullptr && count < 15) {
    fields[count++] = token;
    token = strtok(nullptr, ",");
  }
  if (count < 9 || fields[2] == nullptr || fields[4] == nullptr) {
    return;
  }

  uint8_t fix = (uint8_t)atoi(fields[6]);
  uint8_t sats = (uint8_t)atoi(fields[7]);
  float hdop = atof(fields[8]);
  if (fix == 0) {
    location.valid = true;
    location.quality = "poor";
    location.fixQuality = 0;
    location.satelliteCount = sats;
    location.hdop = hdop;
    return;
  }

  location.lat = nmeaCoordToDecimal(fields[2], fields[3]);
  location.lng = nmeaCoordToDecimal(fields[4], fields[5]);
  location.valid = true;
  location.mock = false;
  location.provider = "gnss";
  location.fixQuality = fix;
  location.satelliteCount = sats;
  location.hdop = hdop;
  location.accuracyM = hdop > 0.0f ? hdop * 5.0f : 25.0f;
  if (sats >= 8 && hdop > 0.0f && hdop <= 1.5f) {
    location.quality = "good";
  } else if (sats >= 4 && hdop <= 4.0f) {
    location.quality = "usable";
  } else {
    location.quality = "poor";
  }
  location.updatedAtMs = millis();
}

static void updateGnssLocation() {
  while (Serial1.available() > 0) {
    char c = (char)Serial1.read();
    if (c == '\n') {
      gnssLine[gnssIndex] = '\0';
      parseGgaLine(gnssLine);
      gnssIndex = 0;
    } else if (c != '\r' && gnssIndex < sizeof(gnssLine) - 1) {
      gnssLine[gnssIndex++] = c;
    }
  }
}
#else
static void updateGnssLocation() {}
#endif

static long locationToCell(double value) {
  return (long)floor(value / SMARTCANE_EVENT_LOCATION_CELL_DEG);
}

static void currentLocationCell(long &latCell, long &lngCell) {
  if (!location.valid) {
    latCell = 0;
    lngCell = 0;
    return;
  }
  latCell = locationToCell(location.lat);
  lngCell = locationToCell(location.lng);
}

static bool sameText(const char *a, const char *b) {
  if (a == nullptr) {
    a = "";
  }
  if (b == nullptr) {
    b = "";
  }
  return strcmp(a, b) == 0;
}

static bool sameRiskFingerprint(const RiskState &a, const RiskState &b) {
  return a.level == b.level &&
         sameText(a.riskType, b.riskType) &&
         sameText(a.direction, b.direction);
}

static bool hasConcreteRisk(const RiskState &risk) {
  return strcmp(risk.riskType, "none") != 0 &&
         strcmp(risk.riskType, "sensor_unreliable") != 0;
}

static bool isDistanceRiskType(const char *riskType) {
  return strcmp(riskType, "front_obstacle") == 0 ||
         strcmp(riskType, "left_obstacle") == 0 ||
         strcmp(riskType, "right_obstacle") == 0 ||
         strcmp(riskType, "down_obstacle") == 0;
}

static RiskState stabilizeRisk(const RiskState &measuredRisk) {
  if (!riskStabilizerReady) {
    stableRisk = measuredRisk;
    pendingRisk = measuredRisk;
    pendingRiskFrames = 1;
    clearRiskFrames = 0;
    riskStabilizerReady = true;
    return stableRisk;
  }

  if (sameRiskFingerprint(measuredRisk, stableRisk)) {
    stableRisk = measuredRisk;
    pendingRiskFrames = 0;
    clearRiskFrames = 0;
    return stableRisk;
  }

  if (measuredRisk.level == RISK_LOW) {
    pendingRiskFrames = 0;
    if (stableRisk.level == RISK_LOW) {
      stableRisk = measuredRisk;
      clearRiskFrames = 0;
      return stableRisk;
    }

    if (clearRiskFrames < 255) {
      clearRiskFrames++;
    }
    if (clearRiskFrames >= SMARTCANE_RISK_CLEAR_FRAMES) {
      stableRisk = measuredRisk;
      clearRiskFrames = 0;
    }
    return stableRisk;
  }

  clearRiskFrames = 0;
  if (!sameRiskFingerprint(measuredRisk, pendingRisk)) {
    pendingRisk = measuredRisk;
    pendingRiskFrames = 1;
  } else if (pendingRiskFrames < 255) {
    pendingRiskFrames++;
  }

  if (pendingRiskFrames >= SMARTCANE_RISK_CONFIRM_FRAMES) {
    stableRisk = measuredRisk;
    pendingRiskFrames = 0;
  }
  return stableRisk;
}

static unsigned long telemetryIntervalForRisk(const RiskState &risk) {
  if (risk.level == RISK_LOW && !hasConcreteRisk(risk)) {
    return SMARTCANE_TELEMETRY_LOW_RISK_INTERVAL_MS;
  }
  return SMARTCANE_TELEMETRY_RISK_INTERVAL_MS;
}

static bool locationCellChanged(long latCell,
                                long lngCell,
                                bool &hasLast,
                                long &lastLatCell,
                                long &lastLngCell) {
  if (!hasLast) {
    hasLast = true;
    lastLatCell = latCell;
    lastLngCell = lngCell;
    return true;
  }
  if (latCell != lastLatCell || lngCell != lastLngCell) {
    lastLatCell = latCell;
    lastLngCell = lngCell;
    return true;
  }
  return false;
}

static void recordPathPoint(const RiskState &risk) {
  PathRecord &record = pathBuffer[pathWriteIndex];
  record.timestampMs = millis();
  record.lat = location.lat;
  record.lng = location.lng;
  record.level = risk.level;
  strncpy(record.riskType, risk.riskType, sizeof(record.riskType) - 1);
  record.riskType[sizeof(record.riskType) - 1] = '\0';

  pathWriteIndex = (pathWriteIndex + 1) % SMARTCANE_LOCAL_PATH_BUFFER_SIZE;
  if (pathCount < SMARTCANE_LOCAL_PATH_BUFFER_SIZE) {
    pathCount++;
  }
}

static bool recordPathPointIfMoved(const RiskState &risk) {
  long latCell;
  long lngCell;
  currentLocationCell(latCell, lngCell);
  if (!locationCellChanged(latCell, lngCell, haveLastPathCell, lastPathLatCell, lastPathLngCell)) {
    return false;
  }
  recordPathPoint(risk);
  return true;
}

static void printPathRecords() {
  Serial.println(F("[PATH] newest first"));
  for (uint8_t i = 0; i < pathCount; ++i) {
    uint8_t index = (pathWriteIndex + SMARTCANE_LOCAL_PATH_BUFFER_SIZE - 1 - i) %
                    SMARTCANE_LOCAL_PATH_BUFFER_SIZE;
    const PathRecord &record = pathBuffer[index];
    Serial.print(F("  #"));
    Serial.print(i);
    Serial.print(F(" t="));
    Serial.print(record.timestampMs);
    Serial.print(F(" lat="));
    Serial.print(record.lat, 6);
    Serial.print(F(" lng="));
    Serial.print(record.lng, 6);
    Serial.print(F(" level="));
    Serial.print(riskLevelToString(record.level));
    Serial.print(F(" type="));
    Serial.println(record.riskType);
  }
}

static void runCue(FeedbackCue cue, bool withBuzzer) {
  switch (cue) {
    case CUE_GROUND_DROP:
      patternGroundDrop();
      if (withBuzzer) beepPatternDanger();
      break;
    case CUE_TURN_LEFT:
      patternTurnLeft();
      break;
    case CUE_TURN_RIGHT:
      patternTurnRight();
      break;
    case CUE_STOP:
      patternStop();
      if (withBuzzer) beepPatternDanger();
      break;
    case CUE_SOS:
      patternSos();
      beepPatternSos();
      break;
    case CUE_FRONT_LEFT:
      vibrateCenter(SMARTCANE_VIB_LEVEL_HIGH, 220);
      patternTurnLeft();
      if (withBuzzer) beepPatternDanger();
      break;
    case CUE_FRONT_RIGHT:
      vibrateCenter(SMARTCANE_VIB_LEVEL_HIGH, 220);
      patternTurnRight();
      if (withBuzzer) beepPatternDanger();
      break;
    case CUE_FRONT_DANGER:
      vibrateCenter(SMARTCANE_VIB_LEVEL_HIGH, 240);
      if (withBuzzer) beepPatternDanger();
      break;
    case CUE_OBSTACLE:
      patternObstacle();
      break;
    case CUE_NONE:
    default:
      break;
  }
  if (cue != CUE_NONE) {
    lastCue = cue;
  }
}

static FeedbackCue cueForRisk(const RiskState &risk) {
  if (!hasConcreteRisk(risk)) {
    return CUE_NONE;
  }
  if (strcmp(risk.riskType, "fall_detected") == 0) {
    return CUE_NONE;
  }
  if (strcmp(risk.riskType, "ground_drop") == 0 || strcmp(risk.riskType, "ground_step") == 0) {
    return CUE_GROUND_DROP;
  }
  if (strcmp(risk.riskType, "down_obstacle") == 0) {
    return risk.level == RISK_HIGH ? CUE_STOP : CUE_OBSTACLE;
  }
  if (strcmp(risk.riskType, "left_obstacle") == 0) {
    return CUE_TURN_LEFT;
  }
  if (strcmp(risk.riskType, "right_obstacle") == 0) {
    return CUE_TURN_RIGHT;
  }
  if (strcmp(risk.riskType, "front_obstacle") == 0) {
    if (strcmp(risk.direction, "stop") == 0) {
      return CUE_STOP;
    }
    if (strcmp(risk.direction, "turn_left") == 0) {
      return CUE_FRONT_LEFT;
    }
    if (strcmp(risk.direction, "turn_right") == 0) {
      return CUE_FRONT_RIGHT;
    }
    if (risk.level == RISK_HIGH) {
      return CUE_FRONT_DANGER;
    }
    return CUE_OBSTACLE;
  }
  if (strcmp(risk.direction, "stop") == 0) {
    return CUE_STOP;
  }
  return CUE_OBSTACLE;
}

static void applyFeedbackForRisk(const RiskState &risk, bool force = false, bool allowBuzzer = true) {
  if (!hasConcreteRisk(risk)) {
    return;
  }
  unsigned long now = millis();
  if (!force && now - lastFeedbackMs < SMARTCANE_FEEDBACK_REPEAT_MS) {
    return;
  }
  lastFeedbackMs = now;
  bool shouldBuzz = risk.level == RISK_HIGH ||
                    strcmp(risk.riskType, "ground_drop") == 0 ||
                    strcmp(risk.riskType, "ground_step") == 0;
  runCue(cueForRisk(risk), allowBuzzer && shouldBuzz);
}

static void repeatLastCue() {
  if (lastCue == CUE_NONE) {
    Serial.println(F("[CUE] no previous cue"));
    return;
  }
  Serial.println(F("[CUE] repeat last vibration cue"));
  runCue(lastCue, false);
}

static void maybeAutoUploadRisk() {
  if (!networkMode || !hasConcreteRisk(currentRisk)) {
    return;
  }
  if (strcmp(currentRisk.riskType, "history_risk") == 0) {
    return;
  }
  if (currentRisk.level == RISK_LOW && !isDistanceRiskType(currentRisk.riskType)) {
    return;
  }
  uploadEvent(currentRisk, distances, location, "source=auto_detected_once_per_place");
}

static void uploadUserMark(const char *extra) {
  Serial.println(F("[UPLOAD] user_mark"));
  uploadRiskEvent("user_mark",
                  "medium",
                  currentRisk.direction,
                  "touch",
                  currentRisk.distanceMm,
                  distances,
                  location,
                  extra);
}

static void handleSos() {
  Serial.println(F("[SOS] HOLD 2s detected"));
  currentRisk.level = RISK_HIGH;
  currentRisk.riskType = "sos";
  currentRisk.direction = "stop";
  currentRisk.sensor = "sos_button";
  currentRisk.reason = "physical_button_long_press";
  currentRisk.confidence = 1.0f;
  runCue(CUE_SOS, true);
  recordPathPoint(currentRisk);
  uploadEvent(currentRisk, distances, location, "source=sos_button");
}

static void handleVoiceRequest() {
  Serial.println(F("[VOICE] button short press -> phone voice input request"));
  beep(60);
  if (!networkMode) {
    Serial.println(F("[VOICE] local mode; phone request not uploaded"));
    return;
  }
  uploadSensorFrame(currentRisk,
                    distances,
                    location,
                    imuFallCurrent(),
                    "voice_request",
                    "source=button_short_press",
                    "short_press");
}

static void handleFallEvent(const ImuFallState &fall) {
  RiskState fallRisk;
  fallRisk.level = RISK_HIGH;
  fallRisk.riskType = "fall_detected";
  fallRisk.direction = "stop";
  fallRisk.sensor = "bmi270_imu";
  fallRisk.reason = fall.reason;
  fallRisk.confidence = fall.confidence;
  fallRisk.detectedAtMs = millis();
  currentRisk = fallRisk;

  Serial.println();
  Serial.println(F("========================================"));
  Serial.println(F("!!! FALL DETECTED !!!"));
  Serial.println(F("risk=HIGH type=fall_detected sensor=BMI270"));
  Serial.println(F("action=BUZZER_ONLY notify=blind_and_companion"));
  Serial.print(F("imu g="));
  Serial.print(fall.totalG, 2);
  Serial.print(F(" pitch="));
  Serial.print(fall.pitchDeg, 1);
  Serial.print(F(" roll="));
  Serial.print(fall.rollDeg, 1);
  Serial.print(F(" stage="));
  Serial.print(fall.stage);
  Serial.print(F(" reason="));
  Serial.println(fall.reason);
  Serial.println(F("========================================"));
  Serial.println();
  buzzerSetEnabled(true);
  beepPatternSos();
  recordPathPoint(fallRisk);

  String extra = String("{\"source\":\"bmi270_imu\",\"notify\":\"blind_and_companion\",\"fall_stage\":\"") +
                 fall.stage + "\",\"total_g\":" + String(fall.totalG, 2) +
                 ",\"pitch_deg\":" + String(fall.pitchDeg, 1) +
                 ",\"roll_deg\":" + String(fall.rollDeg, 1) + "}";
  uploadRiskEvent("fall_detected",
                  "high",
                  "stop",
                  "bmi270_imu",
                  -1,
                  distances,
                  location,
                  extra.c_str());
}

static void uploadCompanionAlert(const char *riskType, RiskLevel level, const char *reason) {
  unsigned long now = millis();
  if (lastCompanionAlertMs != 0 &&
      now - lastCompanionAlertMs < SMARTCANE_COMPANION_ALERT_COOLDOWN_MS) {
    return;
  }
  lastCompanionAlertMs = now;

  Serial.print(F("[ALERT] companion "));
  Serial.print(riskType);
  Serial.print(F(" level="));
  Serial.print(riskLevelToString(level));
  Serial.print(F(" reason="));
  Serial.println(reason);

  String extra = String("{\"source\":\"tof_trend\",\"notify\":\"companion\",\"reason\":\"") +
                 reason + "\",\"front_cm\":" + String(distances.frontCm) +
                 ",\"left_cm\":" + String(distances.leftCm) +
                 ",\"right_cm\":" + String(distances.rightCm) +
                 ",\"down_cm\":" + String(distances.downCm) + "}";
  uploadRiskEvent(riskType,
                  riskLevelToString(level),
                  currentRisk.direction,
                  "tof_trend",
                  currentRisk.distanceMm,
                  distances,
                  location,
                  extra.c_str());
}

static bool isObstacleRisk(const RiskState &risk) {
  return strcmp(risk.riskType, "front_obstacle") == 0 ||
         strcmp(risk.riskType, "left_obstacle") == 0 ||
         strcmp(risk.riskType, "right_obstacle") == 0;
}

static void monitorCompanionAlerts(const RiskState &risk) {
  unsigned long now = millis();

  if (isObstacleRisk(risk)) {
    if (strcmp(obstacleAlertType, risk.riskType) != 0) {
      obstacleAlertType = risk.riskType;
      obstacleStartedMs = now;
    } else if (obstacleStartedMs != 0 &&
               now - obstacleStartedMs >= SMARTCANE_COMPANION_OBSTACLE_HOLD_MS) {
      uploadCompanionAlert("prolonged_obstacle", RISK_LOW, "same_obstacle_persisted");
      obstacleStartedMs = now;
    }
  } else {
    obstacleStartedMs = 0;
    obstacleAlertType = "none";
  }

  if (!distances.frontValid || distances.frontCm >= SMARTCANE_FRONT_WARN_CM) {
    approachWindowStartMs = 0;
    approachStartFrontCm = 0;
    lastApproachFeedbackMs = 0;
    return;
  }

  if (approachWindowStartMs == 0) {
    approachWindowStartMs = now;
    approachStartFrontCm = distances.frontCm;
    return;
  }

  if (now - approachWindowStartMs >= SMARTCANE_COMPANION_APPROACH_WINDOW_MS) {
    int dropCm = approachStartFrontCm - distances.frontCm;
    if (dropCm >= SMARTCANE_COMPANION_APPROACH_DELTA_CM) {
      Serial.print(F("[APPROACH] front decreasing "));
      Serial.print(approachStartFrontCm);
      Serial.print(F("->"));
      Serial.print(distances.frontCm);
      Serial.println(F("cm"));
      if (now - lastApproachFeedbackMs >= SMARTCANE_COMPANION_APPROACH_WINDOW_MS) {
        FeedbackCue approachCue = strcmp(risk.riskType, "front_obstacle") == 0 ? cueForRisk(risk) : CUE_FRONT_DANGER;
        if (approachCue == CUE_OBSTACLE) {
          approachCue = CUE_FRONT_DANGER;
        }
        runCue(approachCue, true);
        lastApproachFeedbackMs = now;
      }
      uploadCompanionAlert("approaching_obstacle",
                           RISK_LOW,
                           "front_distance_decreasing");
    }
    approachWindowStartMs = now;
    approachStartFrontCm = distances.frontCm;
  }
}

static void handleButtonEvent(ButtonEventType type) {
  Serial.print(F("[BUTTON_EVT] "));
  Serial.println(buttonEventName(type));

  if (type == BUTTON_EVENT_LONG_PRESS) {
    handleSos();
    return;
  }

  if (type == BUTTON_EVENT_DOUBLE_CLICK) {
    handleVoiceRequest();
    return;
  }

  handleVoiceRequest();
}

static void handleTouchEvent(uint8_t electrode, TouchEventType type) {
  Serial.print(F("[TOUCH_EVT] E"));
  Serial.print(electrode);
  Serial.print(F(" "));
  Serial.println(touchEventName(type));

  if (electrode == 0 && type == TOUCH_EVENT_TAP) {
    printStatus();
    if (networkMode) {
      fetchDeepRisk(currentRisk, distances, location, deepRisk);
      printDeepRisk(deepRisk);
    }
    return;
  }

  if (electrode == 1) {
    if (type == TOUCH_EVENT_LONG_PRESS) {
      uploadUserMark("source=touch_e1_long_press");
    } else if (type == TOUCH_EVENT_TAP) {
      Serial.println(F("[TOUCH] hold E1 for 1s to upload user_mark"));
    }
    return;
  }

  if (electrode == 2 && type == TOUCH_EVENT_TAP) {
    repeatLastCue();
    return;
  }

  if (electrode == 3 && type == TOUCH_EVENT_TAP) {
    networkMode = !networkMode;
    Serial.print(F("[MODE] "));
    Serial.println(networkMode ? F("network") : F("local"));
    return;
  }

  if (electrode == 4 && type == TOUCH_EVENT_TAP) {
    Serial.println(F("[TOUCH] manual left cue"));
    runCue(CUE_TURN_LEFT, false);
    return;
  }

  if (electrode == 5 && type == TOUCH_EVENT_TAP) {
    Serial.println(F("[TOUCH] manual right cue"));
    runCue(CUE_TURN_RIGHT, false);
  }
}

static void printDistances() {
  Serial.print(F("front="));
  Serial.print(distances.frontCm);
  Serial.print(distances.frontValid ? F("cm ") : F("cm? "));
  Serial.print(F("left="));
  Serial.print(distances.leftCm);
  Serial.print(distances.leftValid ? F("cm ") : F("cm? "));
  Serial.print(F("right="));
  Serial.print(distances.rightCm);
  Serial.print(distances.rightValid ? F("cm ") : F("cm? "));
  Serial.print(F("down="));
  Serial.print(distances.downCm);
  Serial.println(distances.downValid ? F("cm") : F("cm?"));
}

static void printSensorRiskSnapshot() {
  Serial.print(F("[SENSOR] "));
  printDistances();
  Serial.print(F("[RISK] "));
  printRiskState(currentRisk);
}

static void publishRiskEventIfNeeded(const RiskState &risk) {
  long latCell;
  long lngCell;
  currentLocationCell(latCell, lngCell);

  bool shouldPublish = false;
  if (!haveLastEventRisk) {
    shouldPublish = hasConcreteRisk(risk);
  } else if (!hasConcreteRisk(risk)) {
    shouldPublish = hasConcreteRisk(lastEventRisk);
  } else {
    bool samePlace = latCell == lastEventLatCell && lngCell == lastEventLngCell;
    shouldPublish = !hasConcreteRisk(lastEventRisk) ||
                    !samePlace ||
                    !sameRiskFingerprint(risk, lastEventRisk);
  }

  if (!shouldPublish) {
    return;
  }

  if (risk.level == RISK_HIGH) {
    Serial.print(F("[EVENT] "));
  } else if (risk.level == RISK_MEDIUM) {
    Serial.print(F("[HINT] "));
  } else {
    Serial.print(F("[LOW] "));
  }
  if (!hasConcreteRisk(risk)) {
    Serial.println(F("risk cleared"));
  } else if (risk.level == RISK_LOW) {
    Serial.println(F("distance risk low"));
  } else if (risk.level == RISK_MEDIUM) {
    Serial.println(F("step/drop caution"));
  } else {
    Serial.println(F("emergency risk detected"));
  }
  printSensorRiskSnapshot();

  if (hasConcreteRisk(risk)) {
    applyFeedbackForRisk(risk, true);
  }

  if (hasConcreteRisk(risk)) {
    recordPathPoint(risk);
    maybeAutoUploadRisk();
    if (risk.level == RISK_HIGH && networkMode && networkAvailable()) {
      lastDeepRiskMs = millis();
      fetchDeepRisk(risk, distances, location, deepRisk);
    }
  }

  lastEventRisk = risk;
  lastEventLatCell = latCell;
  lastEventLngCell = lngCell;
  haveLastEventRisk = true;
}

static void printStatus() {
  Serial.println(F("----- SMARTCANE STATUS -----"));
  Serial.print(F("build="));
  Serial.println(F(SMARTCANE_BUILD_TAG));
  Serial.print(F("device="));
  Serial.print(SMARTCANE_DEVICE_ID);
  Serial.print(F(" mode="));
  Serial.print(networkMode ? F("network") : F("local"));
  Serial.print(F(" wifi="));
  Serial.print(networkAvailable() ? F("ok") : F("off"));
  Serial.print(F(" tof="));
  Serial.print(tofMockActive() ? F("mock") : F("real"));
  Serial.print(F(" vib="));
  Serial.print(vibrationModeName());
  Serial.print(F(" buzzer="));
  Serial.println(buzzerIsEnabled() ? F("on") : F("off"));
  printDistances();
  printRiskState(currentRisk);
  Serial.print(F("location lat="));
  Serial.print(location.lat, 6);
  Serial.print(F(" lng="));
  Serial.print(location.lng, 6);
  Serial.print(F(" provider="));
  Serial.print(location.provider);
  Serial.print(F(" quality="));
  Serial.println(location.quality);
  printNearbySummary(nearby);
  printDeepRisk(deepRisk);
  imuFallPrintStatus();
}

static void printVibrationStatus() {
  Serial.print(F("[VIB] build="));
  Serial.print(F(SMARTCANE_BUILD_TAG));
  Serial.print(F(" mode="));
  Serial.print(vibrationModeName());
  Serial.print(F(" pca_ch L/R/C="));
  Serial.print(SMARTCANE_VIB_LEFT_CHANNEL);
  Serial.print(F("/"));
  Serial.print(SMARTCANE_VIB_RIGHT_CHANNEL);
  Serial.print(F("/"));
  Serial.println(SMARTCANE_VIB_CENTER_CHANNEL);
}

static void printPcaProbe() {
  Serial.print(F("[PCA] addr=0x"));
  Serial.print(SMARTCANE_PCA9685_ADDR, HEX);
  Serial.print(F(" i2c_clock="));
  Serial.println(SMARTCANE_I2C_CLOCK_HZ);

  auto printCandidateScan = [](const char *label) {
    bool anySeen = false;
    bool anyUsable = false;
    Serial.print(F("[PCA] "));
    Serial.print(label);
    Serial.print(F(" candidates:"));
    for (uint8_t addr = SMARTCANE_PCA9685_ADDR_AUTO_MIN; addr <= SMARTCANE_PCA9685_ADDR_AUTO_MAX; ++addr) {
      if (i2cProbe(addr)) {
        bool ignored = false;
        anySeen = true;
        Serial.print(F(" 0x"));
        Serial.print(addr, HEX);
        if (addr == SMARTCANE_MPR121_ADDR) {
          Serial.print(F("(touch-ignore)"));
          ignored = true;
        } else if (addr == SMARTCANE_TCA9548A_ADDR) {
          Serial.print(F("(tca-ignore)"));
          ignored = true;
        } else if (addr == SMARTCANE_BMI270_ADDR_PRIMARY || addr == SMARTCANE_BMI270_ADDR_SECONDARY) {
          Serial.print(F("(imu-ignore)"));
          ignored = true;
        } else if (addr == 0x7E) {
          Serial.print(F("(reserved-ignore)"));
          ignored = true;
        }
        if (!ignored) {
          anyUsable = true;
        }
      }
    }
    if (!anySeen) {
      Serial.print(F(" none"));
    } else if (!anyUsable) {
      Serial.print(F(" no-usable-pca"));
    }
    Serial.println();
    return anyUsable;
  };

  disableTcaChannels();
  bool foundAny = printCandidateScan("root");

  for (uint8_t ch = 0; ch < 8; ++ch) {
    if (!selectTcaChannel(ch)) {
      continue;
    }
    char label[12];
    snprintf(label, sizeof(label), "TCA CH%u", ch);
    foundAny = printCandidateScan(label) || foundAny;
  }

  if (!foundAny) {
    Serial.println(F("[PCA] no usable PCA9685 address in 0x40-0x7E on root/TCA buses"));
  }
}

static void printSerialHeartbeat() {
  Serial.print(F("[SYS] alive build="));
  Serial.print(F(SMARTCANE_BUILD_TAG));
  Serial.print(F(" wifi="));
  if (networkAvailable()) {
    Serial.print(F("ok ip="));
    Serial.print(WiFi.localIP());
  } else {
    Serial.print(F("off"));
  }
  Serial.print(F(" server="));
  Serial.print(F(SMARTCANE_SERVER_BASE_URL));
  Serial.print(F(" risk="));
  Serial.print(riskLevelToString(currentRisk.level));
  Serial.print(F(" type="));
  Serial.print(currentRisk.riskType);
  Serial.print(F(" imu="));
  Serial.print(imuFallCurrent().available ? F("yes") : F("no"));
  Serial.print(F(" vib="));
  Serial.print(vibrationModeName());
  Serial.println(F(" cmd=status/wifi/read"));
}

static void processCommand(String command) {
  command.trim();
  command.toLowerCase();
  if (command.length() == 0) {
    return;
  }

  if (command == "help" || command == "?") {
    printHelp();
  } else if (command == "status") {
    printStatus();
  } else if (command == "read") {
    tofRead(distances);
    currentRisk = stabilizeRisk(calculateRisk(distances, nearby));
    printSensorRiskSnapshot();
  } else if (command == "raw" || command == "tofraw") {
    tofPrintRawReadings();
  } else if (command == "scan") {
    imuFallPreparePins();
    delay(80);
    i2cScanRoot();
    i2cScanTcaChannels();
  } else if (command == "wifi") {
    printWifiDiagnostics();
  } else if (command == "wifiscan") {
    scanWifiNetworks();
  } else if (command == "pca" || command == "pca9685") {
    printPcaProbe();
  } else if (command == "pca init" || command == "vib init" || command == "motor init") {
    vibrationBegin();
  } else if (command == "touchraw") {
    touchPrintRaw();
  } else if (command == "imu") {
    imuFallPrintStatus();
  } else if (command == "imurescan") {
    imuFallRescan();
    imuFallPrintStatus();
  } else if (command == "imuraw") {
    imuFallPrintRaw();
  } else if (command == "imustream" || command == "imustream on") {
    imuFallSetStream(true);
  } else if (command == "imustream off") {
    imuFallSetStream(false);
  } else if (command == "vib" || command == "vibration" || command == "motor" || command == "vib status") {
    printVibrationStatus();
  } else if (command == "vib left" || command == "motor 1" || command == "m1" || command == "1") {
    Serial.println(F("[CMD] PCA9685 IIC motor 1 / left"));
    vibrationPcaIicMotor(0, 700);
  } else if (command == "vib right" || command == "motor 2" || command == "m2" || command == "2") {
    Serial.println(F("[CMD] PCA9685 IIC motor 2 / right"));
    vibrationPcaIicMotor(1, 700);
  } else if (command == "vib center" || command == "vib centre" || command == "motor 3" || command == "m3" || command == "3") {
    Serial.println(F("[CMD] PCA9685 IIC motor 3 / center"));
    vibrationPcaIicMotor(2, 700);
  } else if (command == "vib all" || command == "motor all" || command == "mall" || command == "a") {
    Serial.println(F("[CMD] PCA9685 IIC motor all"));
    vibrationPcaIicMotor(0, 700);
    vibrationPcaIicMotor(1, 700);
    vibrationPcaIicMotor(2, 700);
  } else if (command == "vib stop" || command == "motor stop" || command == "mstop") {
    Serial.println(F("[CMD] PCA9685 IIC motor stop"));
    vibrationPcaIicStop();
  } else if (command == "beep") {
    Serial.println(F("[CMD] beep short"));
    beep(160);
  } else if (command == "beep danger") {
    Serial.println(F("[CMD] beep danger"));
    beepPatternDanger();
  } else if (command == "beep sos") {
    Serial.println(F("[CMD] beep sos"));
    beepPatternSos();
  } else if (command == "buzzer on") {
    buzzerSetEnabled(true);
  } else if (command == "buzzer off") {
    buzzerSetEnabled(false);
  } else if (command == "stream" || command == "stream on") {
    streamMode = true;
    rawStreamMode = false;
    Serial.println(F("[STREAM] on"));
  } else if (command == "stream raw" || command == "rawstream") {
    streamMode = false;
    rawStreamMode = true;
    Serial.println(F("[STREAM] raw on"));
  } else if (command == "stream off") {
    streamMode = false;
    rawStreamMode = false;
    Serial.println(F("[STREAM] off"));
  } else if (command == "nearby") {
    fetchNearbyRisks(location.lat, location.lng, nearby);
    printNearbySummary(nearby);
  } else if (command == "deep") {
    fetchDeepRisk(currentRisk, distances, location, deepRisk);
    printDeepRisk(deepRisk);
  } else if (command == "mark" || command == "upload") {
    uploadUserMark("source=serial_command");
  } else if (command == "sos") {
    handleSos();
  } else if (command == "btn" || command == "button") {
    handleButtonEvent(BUTTON_EVENT_CLICK);
  } else if (command == "btndouble" || command == "button double") {
    handleButtonEvent(BUTTON_EVENT_DOUBLE_CLICK);
  } else if (command == "btnlong" || command == "button long") {
    handleButtonEvent(BUTTON_EVENT_LONG_PRESS);
  } else if (command == "mode") {
    networkMode = !networkMode;
    Serial.print(F("[MODE] "));
    Serial.println(networkMode ? F("network") : F("local"));
  } else if (command == "path") {
    printPathRecords();
  } else if (command.startsWith("t") && command.length() >= 2) {
    uint8_t electrode = command.charAt(1) - '0';
    TouchEventType eventType = command.endsWith("long") ? TOUCH_EVENT_LONG_PRESS : TOUCH_EVENT_TAP;
    handleTouchEvent(electrode, eventType);
  } else {
    Serial.print(F("[SERIAL] unknown command: "));
    Serial.println(command);
    printHelp();
  }
}

static bool isImmediateSerialCommand(const String &command) {
  return command == "1" || command == "2" || command == "3" || command == "a" ||
         command == "m1" || command == "m2" || command == "m3" ||
         command == "mall" || command == "mstop" ||
         command == "status" || command == "help" || command == "?" ||
         command == "read" || command == "raw" || command == "scan" ||
         command == "wifi" || command == "wifiscan" ||
         command == "vib" || command == "pca" || command == "imu" ||
         command == "imuraw" || command == "imurescan" ||
         command == "beep" || command == "nearby" || command == "deep" ||
         command == "mark" || command == "sos" || command == "btn" ||
         command == "btndouble" || command == "btnlong" ||
         command == "mode" || command == "path";
}

static void processBufferedSerialLine() {
  serialLine.trim();
  serialLine.toLowerCase();
  if (serialLine.length() == 0) {
    return;
  }
  Serial.print(F("[SERIAL] command="));
  Serial.println(serialLine);
  processCommand(serialLine);
  serialLine = "";
}

static void handleSerialInput() {
#if SMARTCANE_SERIAL_COMMANDS_ENABLED
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      processBufferedSerialLine();
    } else if (serialLine.length() < 80) {
      serialLine += c;
      lastSerialCharMs = millis();
      String command = serialLine;
      command.trim();
      command.toLowerCase();
      if (isImmediateSerialCommand(command)) {
        serialLine = command;
        processBufferedSerialLine();
      }
    }
  }

  if (serialLine.length() > 0 && millis() - lastSerialCharMs >= 500) {
    processBufferedSerialLine();
  }
#endif
}

static void printHelp() {
  Serial.println(F("[HELP] commands:"));
  Serial.println(F("  Serial Monitor can use New Line or No line ending"));
  Serial.println(F("  status        print sensor, risk, location, nearby history"));
  Serial.println(F("  read          print one sensor/risk snapshot"));
  Serial.println(F("  raw           print raw VL53L1X millimeter readings"));
  Serial.println(F("  stream on/off print live sensor snapshots for bench testing"));
  Serial.println(F("  stream raw    print live raw VL53L1X millimeter readings"));
  Serial.println(F("  scan          scan root I2C and TCA channels"));
  Serial.println(F("  wifi|wifiscan print Wi-Fi status or scan target hotspot"));
  Serial.println(F("  touchraw      print MPR121 touched/filter/baseline values"));
  Serial.println(F("  imu|imurescan print BMI270 fall detector status/rescan"));
  Serial.println(F("  imuraw        print one BMI270 raw accel sample"));
  Serial.println(F("  imustream on/off print brief BMI270 fall state"));
  Serial.println(F("  vib left|right|center|all|stop|status"));
  Serial.println(F("  pca|pca init  probe/reinitialize PCA9685 on configured TCA channel"));
  Serial.println(F("  m1/m2/m3/mall or 1/2/3/a drive motors; works even without New Line"));
  Serial.println(F("  beep|beep danger|beep sos|buzzer on|buzzer off"));
  Serial.println(F("  nearby        fetch /api/risks/nearby"));
  Serial.println(F("  deep          call backend /api/ai/deep-risk"));
  Serial.println(F("  mark          upload user_mark risk event"));
  Serial.println(F("  sos           trigger SOS action from serial"));
  Serial.println(F("  btn|btndouble  request phone voice input"));
  Serial.println(F("  btnlong        trigger SOS action"));
  Serial.println(F("  mode          toggle local/network mode"));
  Serial.println(F("  path          print local route ring buffer"));
  Serial.println(F("  t0 t1long t2 t3 t4 t5 run touch actions"));
}

void setup() {
  Serial.begin(115200);
  unsigned long serialWaitStartMs = millis();
  while (!Serial && millis() - serialWaitStartMs < 2000) {
    delay(10);
  }
  Serial.println();
  Serial.println(F("ESP32-C5 Smart Cane Arduino START"));
  Serial.print(F("Build: "));
  Serial.println(F(SMARTCANE_BUILD_TAG));
  Serial.println(F("Board: ESP32C5 Dev Module, baud: 115200"));
  Serial.println(F("[SERIAL] boot ok; wait init or type status/wifi/read after startup"));
  Serial.flush();

  initLocation();
  buzzerBegin();
  Serial.flush();
  imuFallPreparePins();
  delay(80);
  Serial.flush();
  i2cBusBegin();
  Serial.flush();
  imuFallBegin();
  Serial.flush();
  tofBegin();
  Serial.flush();
  touchBegin();
  Serial.flush();
  vibrationBegin();
  Serial.flush();
  buttonsBegin();
  Serial.flush();

#if SMARTCANE_GNSS_ENABLED
  Serial1.begin(SMARTCANE_GNSS_BAUD, SERIAL_8N1, SMARTCANE_GNSS_RX_PIN, SMARTCANE_GNSS_TX_PIN);
  Serial.println(F("[GNSS] enabled on Serial1"));
#else
  Serial.println(F("[GNSS] disabled; backend will prefer recent Android/Amap location"));
#endif

  connectWifi();
  if (networkMode && networkAvailable()) {
    uploadLocation(location);
    fetchNearbyRisks(location.lat, location.lng, nearby);
    currentLocationCell(lastNearbyLatCell, lastNearbyLngCell);
    haveLastNearbyCell = true;
  }

  tofRead(distances);
  currentRisk = stabilizeRisk(calculateRisk(distances, nearby));
  recordPathPointIfMoved(currentRisk);
#if !SMARTCANE_PRODUCT_MODE
  printHelp();
#endif
  printStatus();
  Serial.println(F("[SERIAL] ready; commands: status wifi wifiscan read beep m1 m2 m3"));
  printSerialHeartbeat();
  publishRiskEventIfNeeded(currentRisk);
}

void loop() {
  unsigned long now = millis();

  buzzerUpdate();
  vibrationUpdate();
  buttonsUpdate(handleButtonEvent);
  touchUpdate(handleTouchEvent);
  handleSerialInput();
  imuFallUpdate();
  ImuFallState fall;
  if (imuFallConsumeEvent(fall)) {
    handleFallEvent(fall);
  }
  updateGnssLocation();
  networkClientUpdate();

  if (now - lastSensorMs >= SMARTCANE_SENSOR_INTERVAL_MS) {
    lastSensorMs = now;
    tofRead(distances);
    currentRisk = stabilizeRisk(calculateRisk(distances, nearby));
    publishRiskEventIfNeeded(currentRisk);
    monitorCompanionAlerts(currentRisk);
    applyFeedbackForRisk(currentRisk, false, false);
  }

#if SMARTCANE_PERIODIC_SERIAL_STATUS_ENABLED
  if (now - lastStatusMs >= SMARTCANE_STATUS_INTERVAL_MS) {
    lastStatusMs = now;
    Serial.print(F("[SENSOR] "));
    printDistances();
    Serial.print(F("[RISK] "));
    printRiskState(currentRisk);
  }
#endif

  if (streamMode && now - lastStatusMs >= SMARTCANE_STREAM_INTERVAL_MS) {
    lastStatusMs = now;
    printSensorRiskSnapshot();
  }

  if (rawStreamMode && now - lastStatusMs >= SMARTCANE_STREAM_INTERVAL_MS) {
    lastStatusMs = now;
    tofPrintRawReadings();
  }

#if SMARTCANE_SERIAL_HEARTBEAT_ENABLED
  if (!streamMode && !rawStreamMode &&
      now - lastHeartbeatMs >= SMARTCANE_SERIAL_HEARTBEAT_INTERVAL_MS) {
    lastHeartbeatMs = now;
    printSerialHeartbeat();
  }
#endif

  if (now - lastLocationUploadMs >= SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS) {
    lastLocationUploadMs = now;
    updateMockRoute();
    bool moved = recordPathPointIfMoved(currentRisk);
    if (networkMode && networkAvailable() && moved) {
      uploadLocation(location);
    }
  }

  if (networkMode && networkAvailable() &&
      now - lastTelemetryUploadMs >= telemetryIntervalForRisk(currentRisk)) {
    lastTelemetryUploadMs = now;
    uploadSensorFrame(currentRisk,
                      distances,
                      location,
                      imuFallCurrent(),
                      nullptr,
                      "source=periodic_real_frame");
  }

  if (networkMode && networkAvailable() && now - lastNearbyFetchMs >= SMARTCANE_NEARBY_FETCH_INTERVAL_MS) {
    lastNearbyFetchMs = now;
    long latCell;
    long lngCell;
    currentLocationCell(latCell, lngCell);
    if (locationCellChanged(latCell,
                            lngCell,
                            haveLastNearbyCell,
                            lastNearbyLatCell,
                            lastNearbyLngCell)) {
      fetchNearbyRisks(location.lat, location.lng, nearby);
    }
  }

  // Deep-risk analysis is intentionally event-triggered in
  // publishRiskEventIfNeeded(), so standing still in the same risk area does
  // not keep calling the backend/LLM.
}
