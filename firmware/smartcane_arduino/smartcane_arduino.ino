#include <Arduino.h>

#include "buttons.h"
#include "buzzer.h"
#include "config.h"
#include "data_model.h"
#include "i2c_bus.h"
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
static FeedbackCue lastCue = CUE_NONE;
static unsigned long lastSensorMs = 0;
static unsigned long lastStatusMs = 0;
static unsigned long lastFeedbackMs = 0;
static unsigned long lastLocationUploadMs = 0;
static unsigned long lastNearbyFetchMs = 0;
static unsigned long lastAutoUploadMs = 0;
static unsigned long lastDeepRiskMs = 0;
static String serialLine;

static PathRecord pathBuffer[SMARTCANE_LOCAL_PATH_BUFFER_SIZE];
static uint8_t pathWriteIndex = 0;
static uint8_t pathCount = 0;

#if SMARTCANE_GNSS_ENABLED
static char gnssLine[128];
static uint8_t gnssIndex = 0;
#endif

static void printHelp();
static void printStatus();
static void repeatLastCue();
static void handleSos();
static void handleTouchEvent(uint8_t electrode, TouchEventType type);
static void processCommand(String command);

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
  if (risk.level == RISK_LOW) {
    return CUE_NONE;
  }
  if (strcmp(risk.riskType, "ground_drop") == 0) {
    return CUE_GROUND_DROP;
  }
  if (strcmp(risk.direction, "stop") == 0) {
    return CUE_STOP;
  }
  if (strcmp(risk.direction, "turn_left") == 0) {
    return risk.level == RISK_HIGH ? CUE_FRONT_LEFT : CUE_TURN_LEFT;
  }
  if (strcmp(risk.direction, "turn_right") == 0) {
    return risk.level == RISK_HIGH ? CUE_FRONT_RIGHT : CUE_TURN_RIGHT;
  }
  if (strcmp(risk.direction, "keep_left") == 0) {
    return CUE_TURN_LEFT;
  }
  if (strcmp(risk.direction, "keep_right") == 0) {
    return CUE_TURN_RIGHT;
  }
  if (risk.level == RISK_HIGH) {
    return CUE_FRONT_DANGER;
  }
  return CUE_OBSTACLE;
}

static void applyFeedbackForRisk(const RiskState &risk) {
  if (risk.level == RISK_LOW) {
    return;
  }
  unsigned long now = millis();
  if (now - lastFeedbackMs < SMARTCANE_FEEDBACK_REPEAT_MS) {
    return;
  }
  lastFeedbackMs = now;
  runCue(cueForRisk(risk), risk.level == RISK_HIGH);
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
  if (!networkMode || currentRisk.level != RISK_HIGH) {
    return;
  }
  if (strcmp(currentRisk.riskType, "history_risk") == 0) {
    return;
  }
  unsigned long now = millis();
  if (now - lastAutoUploadMs < SMARTCANE_AUTO_UPLOAD_COOLDOWN_MS) {
    return;
  }
  lastAutoUploadMs = now;
  uploadEvent(currentRisk, distances, location, "source=auto_detected");
}

static void uploadUserMark(const char *extra) {
  Serial.println(F("[UPLOAD] user_mark"));
  uploadRiskEvent("user_mark",
                  riskLevelToString(currentRisk.level),
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

static void printStatus() {
  Serial.println(F("----- SMARTCANE STATUS -----"));
  Serial.print(F("device="));
  Serial.print(SMARTCANE_DEVICE_ID);
  Serial.print(F(" mode="));
  Serial.print(networkMode ? F("network") : F("local"));
  Serial.print(F(" wifi="));
  Serial.print(networkAvailable() ? F("ok") : F("off"));
  Serial.print(F(" tof="));
  Serial.println(tofMockActive() ? F("mock") : F("real"));
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
}

static MockScenario parseMockScenario(const String &name) {
  if (name == "clear") return MOCK_SCENARIO_CLEAR;
  if (name == "warn" || name == "front_warn") return MOCK_SCENARIO_FRONT_WARN;
  if (name == "danger" || name == "front_danger") return MOCK_SCENARIO_FRONT_DANGER;
  if (name == "drop" || name == "ground_drop") return MOCK_SCENARIO_GROUND_DROP;
  if (name == "blocked" || name == "stop") return MOCK_SCENARIO_BLOCKED;
  if (name == "left" || name == "left_open") return MOCK_SCENARIO_LEFT_OPEN;
  if (name == "right" || name == "right_open") return MOCK_SCENARIO_RIGHT_OPEN;
  return MOCK_SCENARIO_AUTO;
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
  } else if (command == "scan") {
    i2cScanRoot();
    i2cScanTcaChannels();
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
  } else if (command == "mode") {
    networkMode = !networkMode;
    Serial.print(F("[MODE] "));
    Serial.println(networkMode ? F("network") : F("local"));
  } else if (command == "path") {
    printPathRecords();
  } else if (command.startsWith("mock")) {
    String arg = command.substring(4);
    arg.trim();
    tofSetMockScenario(parseMockScenario(arg));
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

static void handleSerialInput() {
#if SMARTCANE_SERIAL_COMMANDS_ENABLED
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      processCommand(serialLine);
      serialLine = "";
    } else if (serialLine.length() < 80) {
      serialLine += c;
    }
  }
#endif
}

static void printHelp() {
  Serial.println(F("[HELP] commands:"));
  Serial.println(F("  status        print sensor, risk, location, nearby history"));
  Serial.println(F("  scan          scan root I2C and TCA channels"));
  Serial.println(F("  mock auto|clear|warn|danger|drop|blocked|left|right"));
  Serial.println(F("  nearby        fetch /api/risks/nearby"));
  Serial.println(F("  deep          call backend /api/ai/deep-risk"));
  Serial.println(F("  mark          upload user_mark risk event"));
  Serial.println(F("  sos           simulate SOS long press"));
  Serial.println(F("  mode          toggle local/network mode"));
  Serial.println(F("  path          print local route ring buffer"));
  Serial.println(F("  t0 t1long t2 t3 t4 t5 simulate touch events"));
}

void setup() {
  Serial.begin(115200);
  delay(250);
  Serial.println();
  Serial.println(F("ESP32-C5 Smart Cane Arduino START"));
  Serial.println(F("Board: ESP32C5 Dev Module, baud: 115200"));

  initLocation();
  i2cBusBegin();
  tofBegin();
  touchBegin();
  vibrationBegin();
  buzzerBegin();
  buttonsBegin();

#if SMARTCANE_GNSS_ENABLED
  Serial1.begin(SMARTCANE_GNSS_BAUD, SERIAL_8N1, SMARTCANE_GNSS_RX_PIN, SMARTCANE_GNSS_TX_PIN);
  Serial.println(F("[GNSS] enabled on Serial1"));
#else
  Serial.println(F("[GNSS] disabled; using mock/mobile-replaceable location"));
#endif

  connectWifi();
  if (networkMode && networkAvailable()) {
    uploadLocation(location);
    fetchNearbyRisks(location.lat, location.lng, nearby);
  }

  tofRead(distances);
  currentRisk = calculateRisk(distances, nearby);
  recordPathPoint(currentRisk);
  printHelp();
  printStatus();
}

void loop() {
  unsigned long now = millis();

  buzzerUpdate();
  vibrationUpdate();
  buttonsUpdate(handleSos);
  touchUpdate(handleTouchEvent);
  handleSerialInput();
  updateGnssLocation();
  networkClientUpdate();

  if (now - lastSensorMs >= SMARTCANE_SENSOR_INTERVAL_MS) {
    lastSensorMs = now;
    tofRead(distances);
    currentRisk = calculateRisk(distances, nearby);
    applyFeedbackForRisk(currentRisk);
    maybeAutoUploadRisk();
  }

  if (now - lastStatusMs >= SMARTCANE_STATUS_INTERVAL_MS) {
    lastStatusMs = now;
    Serial.print(F("[SENSOR] "));
    printDistances();
    Serial.print(F("[RISK] "));
    printRiskState(currentRisk);
  }

  if (now - lastLocationUploadMs >= SMARTCANE_LOCATION_UPLOAD_INTERVAL_MS) {
    lastLocationUploadMs = now;
    updateMockRoute();
    recordPathPoint(currentRisk);
    if (networkMode) {
      uploadLocation(location);
    }
  }

  if (networkMode && now - lastNearbyFetchMs >= SMARTCANE_NEARBY_FETCH_INTERVAL_MS) {
    lastNearbyFetchMs = now;
    fetchNearbyRisks(location.lat, location.lng, nearby);
  }

  if (networkMode && now - lastDeepRiskMs >= SMARTCANE_DEEP_RISK_INTERVAL_MS) {
    lastDeepRiskMs = now;
    if (currentRisk.level != RISK_LOW || (nearby.available && nearby.riskCount > 0)) {
      fetchDeepRisk(currentRisk, distances, location, deepRisk);
    }
  }
}
