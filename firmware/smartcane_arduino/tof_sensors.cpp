#include "tof_sensors.h"

#include <VL53L1X.h>

#include "config.h"
#include "i2c_bus.h"

static VL53L1X sensors[4];
static bool sensorReady[4] = {false, false, false, false};
static bool mockActive = false;
static MockScenario mockScenario = MOCK_SCENARIO_AUTO;
static int filteredCm[4] = {400, 400, 400, SMARTCANE_GROUND_BASE_CM};
static uint8_t failCount[4] = {0, 0, 0, 0};

static const uint8_t tofChannels[4] = {
  SMARTCANE_TCA_CH_TOF_FRONT,
  SMARTCANE_TCA_CH_TOF_LEFT,
  SMARTCANE_TCA_CH_TOF_RIGHT,
  SMARTCANE_TCA_CH_TOF_DOWN
};

static const char *tofNames[4] = {"front", "left", "right", "down"};

static int mmToCm(uint16_t mm) {
  if (mm < SMARTCANE_TOF_MIN_VALID_MM || mm > SMARTCANE_TOF_MAX_VALID_MM) {
    return -1;
  }
  return (int)((mm + 5) / 10);
}

static int filterCm(uint8_t index, int cm) {
  if (cm < 0) {
    failCount[index]++;
    if (failCount[index] >= SMARTCANE_TOF_FAILS_BEFORE_INVALID) {
      return 400;
    }
    return filteredCm[index];
  }

  failCount[index] = 0;
  int alpha = SMARTCANE_TOF_FILTER_ALPHA_PERCENT;
  filteredCm[index] = (filteredCm[index] * (100 - alpha) + cm * alpha) / 100;
  return filteredCm[index];
}

static bool beginOneSensor(uint8_t index) {
  if (!selectTcaChannel(tofChannels[index])) {
    return false;
  }
  if (!i2cProbe(SMARTCANE_VL53L1X_ADDR)) {
    return false;
  }

  sensors[index].setTimeout(80);
  if (!sensors[index].init()) {
    return false;
  }

  sensors[index].setDistanceMode(VL53L1X::Long);
  sensors[index].setMeasurementTimingBudget(50000);
  sensors[index].startContinuous(80);
  return true;
}

bool tofBegin() {
#if SMARTCANE_MOCK_SENSOR_MODE
  mockActive = true;
  Serial.println(F("[TOF] forced mock mode"));
  return true;
#else
  bool allReady = true;
  for (uint8_t i = 0; i < 4; ++i) {
    sensorReady[i] = beginOneSensor(i);
    Serial.print(F("[TOF] "));
    Serial.print(tofNames[i]);
    Serial.print(F(" TCA CH"));
    Serial.print(tofChannels[i]);
    Serial.println(sensorReady[i] ? F(" OK") : F(" FAILED"));
    allReady = allReady && sensorReady[i];
  }

  mockActive = !allReady;
  if (mockActive) {
    Serial.println(F("[TOF] incomplete hardware, fallback to mock distances"));
  }
  return allReady;
#endif
}

static void fillMock(DistanceReadings &out) {
  MockScenario active = mockScenario;
  if (active == MOCK_SCENARIO_AUTO) {
    active = (MockScenario)((millis() / 6000) % 7 + 1);
  }

  out.frontCm = 180;
  out.leftCm = 150;
  out.rightCm = 150;
  out.downCm = SMARTCANE_GROUND_BASE_CM;

  switch (active) {
    case MOCK_SCENARIO_FRONT_WARN:
      out.frontCm = 95;
      out.leftCm = 130;
      out.rightCm = 75;
      break;
    case MOCK_SCENARIO_FRONT_DANGER:
      out.frontCm = 45;
      out.leftCm = 65;
      out.rightCm = 140;
      break;
    case MOCK_SCENARIO_GROUND_DROP:
      out.downCm = SMARTCANE_GROUND_BASE_CM + SMARTCANE_GROUND_DROP_THRESHOLD_CM + 25;
      break;
    case MOCK_SCENARIO_BLOCKED:
      out.frontCm = 42;
      out.leftCm = 45;
      out.rightCm = 48;
      break;
    case MOCK_SCENARIO_LEFT_OPEN:
      out.frontCm = 55;
      out.leftCm = 160;
      out.rightCm = 55;
      break;
    case MOCK_SCENARIO_RIGHT_OPEN:
      out.frontCm = 55;
      out.leftCm = 55;
      out.rightCm = 160;
      break;
    case MOCK_SCENARIO_CLEAR:
    case MOCK_SCENARIO_AUTO:
    default:
      break;
  }

  out.frontValid = true;
  out.leftValid = true;
  out.rightValid = true;
  out.downValid = true;
  out.valid = true;
  out.timestampMs = millis();
}

bool tofRead(DistanceReadings &out) {
  out.timestampMs = millis();
  if (mockActive) {
    fillMock(out);
    return true;
  }

  int values[4] = {400, 400, 400, SMARTCANE_GROUND_BASE_CM};
  bool valids[4] = {false, false, false, false};
  bool anyValid = false;

  for (uint8_t i = 0; i < 4; ++i) {
    if (!sensorReady[i] || !selectTcaChannel(tofChannels[i])) {
      values[i] = filterCm(i, -1);
      continue;
    }

    uint16_t rawMm = sensors[i].read(false);
    bool ok = !sensors[i].timeoutOccurred();
    int cm = ok ? mmToCm(rawMm) : -1;
    values[i] = filterCm(i, cm);
    valids[i] = (cm >= 0);
    anyValid = anyValid || valids[i];
  }

  out.frontCm = values[0];
  out.leftCm = values[1];
  out.rightCm = values[2];
  out.downCm = values[3];
  out.frontValid = valids[0];
  out.leftValid = valids[1];
  out.rightValid = valids[2];
  out.downValid = valids[3];
  out.valid = anyValid;
  return anyValid;
}

bool tofMockActive() {
  return mockActive;
}

void tofSetMockScenario(MockScenario scenario) {
  mockActive = true;
  mockScenario = scenario;
  Serial.print(F("[TOF] mock scenario="));
  Serial.println(tofMockScenarioName());
}

const char *tofMockScenarioName() {
  switch (mockScenario) {
    case MOCK_SCENARIO_CLEAR:
      return "clear";
    case MOCK_SCENARIO_FRONT_WARN:
      return "front_warn";
    case MOCK_SCENARIO_FRONT_DANGER:
      return "front_danger";
    case MOCK_SCENARIO_GROUND_DROP:
      return "ground_drop";
    case MOCK_SCENARIO_BLOCKED:
      return "blocked";
    case MOCK_SCENARIO_LEFT_OPEN:
      return "left_open";
    case MOCK_SCENARIO_RIGHT_OPEN:
      return "right_open";
    case MOCK_SCENARIO_AUTO:
    default:
      return "auto";
  }
}
