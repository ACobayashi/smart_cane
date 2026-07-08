#include "tof_sensors.h"

#include <VL53L1X.h>

#include "config.h"
#include "i2c_bus.h"

static VL53L1X tofSensors[4];
static const uint8_t tofChannels[4] = {
  TCA_CH_TOF_FRONT,
  TCA_CH_TOF_LEFT,
  TCA_CH_TOF_RIGHT,
  TCA_CH_TOF_DOWN
};

static bool tofMockActive = false;
static bool tofReady[4] = {false, false, false, false};

static int mmToCm(uint16_t mm) {
  if (mm == 0 || mm > 4000) {
    return 400;
  }
  return (int)((mm + 5) / 10);
}

static DistanceReadings readMockDistances() {
  DistanceReadings d = makeDefaultDistances();
  unsigned long phase = (millis() / 5000UL) % 6;

  d.frontCm = 180;
  d.leftCm = 145;
  d.rightCm = 145;
  d.downCm = GROUND_BASE_CM;

  if (phase == 1) {
    d.frontCm = 95;
    d.leftCm = 135;
    d.rightCm = 70;
  } else if (phase == 2) {
    d.frontCm = 45;
    d.leftCm = 65;
    d.rightCm = 140;
  } else if (phase == 3) {
    d.frontCm = 48;
    d.leftCm = 45;
    d.rightCm = 50;
  } else if (phase == 4) {
    d.downCm = GROUND_BASE_CM + GROUND_DROP_THRESHOLD_CM + 18;
  } else if (phase == 5) {
    d.frontCm = 130;
    d.leftCm = 45;
    d.rightCm = 145;
  }

  d.valid = true;
  d.timestampMs = millis();
  return d;
}

bool beginTofSensors() {
#if MOCK_SENSOR_MODE
  tofMockActive = true;
  Serial.println("ToF sensors: MOCK_SENSOR_MODE enabled");
  return true;
#else
  bool allReady = true;
  for (uint8_t i = 0; i < 4; i++) {
    if (!selectTcaChannel(tofChannels[i])) {
      allReady = false;
      continue;
    }

    tofSensors[i].setTimeout(80);
    if (!tofSensors[i].init()) {
      Serial.print("VL53L1X init failed on TCA channel ");
      Serial.println(tofChannels[i]);
      tofReady[i] = false;
      allReady = false;
      continue;
    }

    tofSensors[i].setDistanceMode(VL53L1X::Long);
    tofSensors[i].setMeasurementTimingBudget(50000);
    tofSensors[i].startContinuous(SENSOR_SAMPLE_INTERVAL_MS);
    tofReady[i] = true;

    Serial.print("VL53L1X ready on TCA channel ");
    Serial.println(tofChannels[i]);
  }

  if (!allReady) {
    tofMockActive = true;
    Serial.println("ToF sensors: one or more modules failed, fallback to mock mode");
  } else {
    tofMockActive = false;
  }

  return allReady;
#endif
}

bool readTofSensors(DistanceReadings &out) {
  if (tofMockActive) {
    out = readMockDistances();
    return true;
  }

  int values[4] = {0, 0, 0, 0};
  bool ok = true;
  for (uint8_t i = 0; i < 4; i++) {
    if (!tofReady[i] || !selectTcaChannel(tofChannels[i])) {
      ok = false;
      values[i] = 400;
      continue;
    }

    uint16_t mm = tofSensors[i].read(false);
    if (tofSensors[i].timeoutOccurred()) {
      Serial.print("VL53L1X timeout on channel ");
      Serial.println(tofChannels[i]);
      ok = false;
      values[i] = 400;
    } else {
      values[i] = mmToCm(mm);
    }
  }

  out.frontCm = values[0];
  out.leftCm = values[1];
  out.rightCm = values[2];
  out.downCm = values[3];
  out.valid = ok;
  out.timestampMs = millis();
  return ok;
}

bool isTofMockActive() {
  return tofMockActive;
}

