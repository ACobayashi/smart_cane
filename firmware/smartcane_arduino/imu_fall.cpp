#include "imu_fall.h"

#include <Wire.h>
#include <math.h>

#include "bmi270_config_file.h"
#include "config.h"
#include "i2c_bus.h"

// BMI270 minimal register path for acceleration-only fall detection.
static const uint8_t REG_CHIP_ID = 0x00;
static const uint8_t REG_STATUS = 0x03;
static const uint8_t REG_ACC_X_LSB = 0x0C;
static const uint8_t REG_INTERNAL_STATUS = 0x21;
static const uint8_t REG_ACC_CONF = 0x40;
static const uint8_t REG_ACC_RANGE = 0x41;
static const uint8_t REG_INIT_CTRL = 0x59;
static const uint8_t REG_INIT_ADDR_0 = 0x5B;
static const uint8_t REG_INIT_DATA = 0x5E;
static const uint8_t REG_PWR_CONF = 0x7C;
static const uint8_t REG_PWR_CTRL = 0x7D;
static const uint8_t REG_CMD = 0x7E;
static const uint8_t BMI270_CMD_SOFT_RESET = 0xB6;
static const uint8_t BMI270_INIT_OK = 0x01;
static const uint8_t BMI270_CONFIG_CHUNK_BYTES = 16;

static ImuFallState state;
static unsigned long lastSampleMs = 0;
static unsigned long impactMs = 0;
static unsigned long lyingSinceMs = 0;
static unsigned long slowLyingSinceMs = 0;
static unsigned long uprightStableSinceMs = 0;
static unsigned long slowTiltStartedMs = 0;
static bool slowTiltCandidate = false;
static unsigned long lastFallEventMs = 0;
static unsigned long lastRawStreamPrintMs = 0;
static unsigned long recoverySinceMs = 0;
static bool streamRaw = false;
static bool havePrevAccel = false;
static float prevAxG = 0.0f;
static float prevAyG = 0.0f;
static float prevAzG = 1.0f;
static float prevTotalG = 1.0f;
static float candidatePeakG = 0.0f;
static float candidateMinG = 10.0f;
static bool candidateHadFreefall = false;
static bool candidateHadImpact = false;
static bool candidateHadVerticalDrop = false;

static bool readAccel();
static void printDebugRegisters();
static void printStreamSample();

static void resetFallCandidate() {
  impactMs = 0;
  lyingSinceMs = 0;
  slowLyingSinceMs = 0;
  uprightStableSinceMs = 0;
  slowTiltStartedMs = 0;
  slowTiltCandidate = false;
  candidatePeakG = 0.0f;
  candidateMinG = 10.0f;
  candidateHadFreefall = false;
  candidateHadImpact = false;
  candidateHadVerticalDrop = false;
}

static void rememberAccel() {
  prevAxG = state.axG;
  prevAyG = state.ayG;
  prevAzG = state.azG;
  prevTotalG = state.totalG;
  havePrevAccel = true;
}

static void configureShuttleBoardPins() {
  // Release the BMI270 side-band lines. The current board has been verified at
  // I2C address 0x69 after a physical reset; driving these pins from firmware
  // is unnecessary for the Arduino runtime.
  pinMode(SMARTCANE_BM_CS_PIN, INPUT);
  pinMode(SMARTCANE_BM_SDO_PIN, INPUT);

  // G1/G2 are interrupt/general pins on the shuttle connector. Do not drive
  // them during bring-up; GPIO0 is also a boot strap pin.
  pinMode(SMARTCANE_BM_G1_PIN, INPUT);
  pinMode(SMARTCANE_BM_G2_PIN, INPUT);

  Serial.print(F("[IMU] shuttle pins CS/SDO/G1/G2="));
  Serial.print(SMARTCANE_BM_CS_PIN);
  Serial.print(F("/"));
  Serial.print(SMARTCANE_BM_SDO_PIN);
  Serial.print(F("/"));
  Serial.print(SMARTCANE_BM_G1_PIN);
  Serial.print(F("/"));
  Serial.print(SMARTCANE_BM_G2_PIN);
  Serial.println(F(" CS=input SDO=input official-pulls"));
}

static void printBmiBusDiagnostic() {
  disableTcaChannels();
  bool bmm350Found = i2cProbe(0x14);
  bool bmi68Found = i2cProbe(SMARTCANE_BMI270_ADDR_PRIMARY);
  bool bmi69Found = i2cProbe(SMARTCANE_BMI270_ADDR_SECONDARY);

  Serial.print(F("[IMU_DIAG] root bmm350_0x14="));
  Serial.print(bmm350Found ? F("yes") : F("no"));
  Serial.print(F(" bmi270_0x68="));
  Serial.print(bmi68Found ? F("yes") : F("no"));
  Serial.print(F(" bmi270_0x69="));
  Serial.println(bmi69Found ? F("yes") : F("no"));

  if (bmm350Found && !bmi68Found && !bmi69Found) {
    Serial.println(F("[IMU_DIAG] BMM350 is online but BMI270 is not ACKing; check the BMI270 side of the ShuttleBoard/contact."));
  }
}

static bool writeReg(uint8_t reg, uint8_t value) {
  disableTcaChannels();
  Wire.beginTransmission(state.address);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

static bool writeRegBuffer(uint8_t reg, const uint8_t *buffer, uint8_t len) {
  disableTcaChannels();
  Wire.beginTransmission(state.address);
  Wire.write(reg);
  for (uint8_t i = 0; i < len; ++i) {
    Wire.write(buffer[i]);
  }
  return Wire.endTransmission() == 0;
}

static bool readReg(uint8_t reg, uint8_t *buffer, uint8_t len) {
  disableTcaChannels();
  Wire.beginTransmission(state.address);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }
  uint8_t got = Wire.requestFrom(state.address, len);
  if (got != len) {
    return false;
  }
  for (uint8_t i = 0; i < len; ++i) {
    buffer[i] = Wire.read();
  }
  return true;
}

static uint8_t readChipId(uint8_t address) {
  state.address = address;
  uint8_t id = 0;
  if (!readReg(REG_CHIP_ID, &id, 1)) {
    return 0;
  }
  return id;
}

static bool softResetBmi270() {
  uint8_t address = state.address;
  if (!writeReg(REG_CMD, BMI270_CMD_SOFT_RESET)) {
    return false;
  }
  delay(20);
  state.address = address;
  return readChipId(address) == SMARTCANE_BMI270_CHIP_ID;
}

static bool uploadBmi270Config() {
  if (!writeReg(REG_PWR_CONF, 0x00)) {
    state.reason = "pwr_conf_write_failed";
    return false;
  }
  delay(2);

  if (!writeReg(REG_INIT_CTRL, 0x00)) {
    state.reason = "init_ctrl_disable_failed";
    return false;
  }

  uint8_t chunk[BMI270_CONFIG_CHUNK_BYTES];
  for (uint16_t offset = 0; offset < BMI270_CONFIG_FILE_SIZE; offset += BMI270_CONFIG_CHUNK_BYTES) {
    uint8_t len = BMI270_CONFIG_CHUNK_BYTES;
    if (offset + len > BMI270_CONFIG_FILE_SIZE) {
      len = (uint8_t)(BMI270_CONFIG_FILE_SIZE - offset);
    }

    uint16_t wordOffset = offset / 2;
    uint8_t addrBytes[2] = {
      (uint8_t)(wordOffset & 0x0F),
      (uint8_t)(wordOffset >> 4)
    };
    if (!writeRegBuffer(REG_INIT_ADDR_0, addrBytes, sizeof(addrBytes))) {
      state.reason = "init_addr_write_failed";
      return false;
    }

    for (uint8_t i = 0; i < len; ++i) {
      chunk[i] = pgm_read_byte(&BMI270_CONFIG_FILE[offset + i]);
    }
    if (!writeRegBuffer(REG_INIT_DATA, chunk, len)) {
      state.reason = "config_chunk_write_failed";
      return false;
    }
  }

  if (!writeReg(REG_INIT_CTRL, 0x01)) {
    state.reason = "init_ctrl_enable_failed";
    return false;
  }
  delay(200);

  uint8_t internalStatus = 0;
  if (!readReg(REG_INTERNAL_STATUS, &internalStatus, 1)) {
    state.reason = "internal_status_read_failed";
    return false;
  }

  Serial.print(F("[IMU] config internal_status=0x"));
  if (internalStatus < 16) Serial.print('0');
  Serial.println(internalStatus, HEX);
  if ((internalStatus & 0x0F) != BMI270_INIT_OK) {
    state.reason = "config_load_failed";
    return false;
  }
  return true;
}

static bool detectAndConfigureBmi270() {
  configureShuttleBoardPins();
  delay(80);
  uint8_t id = readChipId(SMARTCANE_BMI270_ADDR_PRIMARY);
  if (id != SMARTCANE_BMI270_CHIP_ID) {
    id = readChipId(SMARTCANE_BMI270_ADDR_SECONDARY);
  }

  if (id != SMARTCANE_BMI270_CHIP_ID) {
    printBmiBusDiagnostic();
    state.available = false;
    state.mock = false;
    state.address = 0;
    state.reason = "bmi270_not_found";
    state.stage = "idle";
    return false;
  }

  state.available = false;
  state.mock = false;
  state.reason = "bmi270_detected";
  state.stage = "initializing";
  if (!softResetBmi270()) {
    state.reason = "soft_reset_failed";
    state.stage = "idle";
    return false;
  }
  if (!uploadBmi270Config()) {
    state.stage = "idle";
    printDebugRegisters();
    return false;
  }

  writeReg(REG_PWR_CONF, 0x00);
  delay(10);
  writeReg(REG_PWR_CTRL, 0x04);    // accel enable
  delay(10);
  writeReg(REG_ACC_CONF, 0xA8);   // accel normal mode, about 100 Hz ODR
  writeReg(REG_ACC_RANGE, 0x01);  // +-4 g
  delay(80);
  state.available = true;
  state.reason = "bmi270_ready";
  state.stage = "normal";
  if (!readAccel()) {
    state.reason = "bmi270_found_accel_read_failed";
    state.stage = "idle";
    return false;
  }
  state.reason = "bmi270_ready";
  return true;
}

static bool readAccel() {
  uint8_t bytes[6] = {0};
  if (!readReg(REG_ACC_X_LSB, bytes, sizeof(bytes))) {
    state.available = false;
    state.reason = "read_failed";
    return false;
  }

  state.axRaw = (int16_t)((uint16_t)bytes[1] << 8 | bytes[0]);
  state.ayRaw = (int16_t)((uint16_t)bytes[3] << 8 | bytes[2]);
  state.azRaw = (int16_t)((uint16_t)bytes[5] << 8 | bytes[4]);

  if (state.axRaw == 0 && state.ayRaw == 0 && state.azRaw == 0) {
    state.axG = 0.0f;
    state.ayG = 0.0f;
    state.azG = 0.0f;
    state.totalG = 0.0f;
    state.pitchDeg = 0.0f;
    state.rollDeg = 0.0f;
    state.confidence = 0.0f;
    state.stage = "zero_data";
    state.reason = "accel_zero_data";
    state.updatedAtMs = millis();
    return true;
  }

  // ACC_RANGE is configured to +-4 g, so 1 g is 8192 LSB.
  state.axG = state.axRaw / 8192.0f;
  state.ayG = state.ayRaw / 8192.0f;
  state.azG = state.azRaw / 8192.0f;
  state.totalG = sqrtf(state.axG * state.axG + state.ayG * state.ayG + state.azG * state.azG);

  float safeTotal = state.totalG < 0.01f ? 0.01f : state.totalG;
  float zRatio = fabsf(state.azG) / safeTotal;
  zRatio = zRatio > 1.0f ? 1.0f : zRatio;
  float tiltDeg = acosf(zRatio) * 57.2957795f;
  state.pitchDeg = atan2f(state.axG, sqrtf(state.ayG * state.ayG + state.azG * state.azG)) * 57.2957795f;
  state.rollDeg = atan2f(state.ayG, state.azG) * 57.2957795f;
  float pitchAbsDeg = fabsf(state.pitchDeg);
  float rollAbsDeg = fabsf(state.rollDeg);
  float postureDeg = tiltDeg;
  if (pitchAbsDeg > postureDeg) postureDeg = pitchAbsDeg;
  if (rollAbsDeg > postureDeg) postureDeg = rollAbsDeg;
  state.updatedAtMs = millis();

  unsigned long now = millis();
  float accelDeltaG = 0.0f;
  float verticalDeltaG = 0.0f;
  if (havePrevAccel) {
    float dx = state.axG - prevAxG;
    float dy = state.ayG - prevAyG;
    float dz = state.azG - prevAzG;
    accelDeltaG = sqrtf(dx * dx + dy * dy + dz * dz);
    verticalDeltaG = fabsf(dz);
  }

  bool stableGravity = state.totalG >= SMARTCANE_FALL_STABLE_MIN_G &&
                       state.totalG <= SMARTCANE_FALL_STABLE_MAX_G;
  bool stillNow = havePrevAccel &&
                  stableGravity &&
                  accelDeltaG <= SMARTCANE_FALL_STILL_DELTA_G;
  bool lying = stableGravity &&
               (tiltDeg >= SMARTCANE_FALL_LIE_TILT_DEG ||
                postureDeg >= SMARTCANE_FALL_POSTURE_DEG);
  bool uprightUsage = stillNow && postureDeg <= 35.0f;
  if (uprightUsage) {
    if (uprightStableSinceMs == 0) {
      uprightStableSinceMs = now;
    }
  }
  bool wasUprightStable = uprightStableSinceMs != 0 &&
                           now - uprightStableSinceMs >= SMARTCANE_FALL_SLOW_UPRIGHT_MS;
  bool slowTiltMotion = wasUprightStable &&
                        postureDeg >= 45.0f &&
                        havePrevAccel &&
                        accelDeltaG >= SMARTCANE_FALL_SLOW_MIN_MOTION_G;
  if (slowTiltMotion) {
    slowTiltCandidate = true;
    slowTiltStartedMs = now;
  }
  if (slowTiltCandidate &&
      slowTiltStartedMs != 0 &&
      now - slowTiltStartedMs > SMARTCANE_FALL_SLOW_TILT_WINDOW_MS) {
    slowTiltCandidate = false;
    slowTiltStartedMs = 0;
    slowLyingSinceMs = 0;
  }

  bool freefall = state.totalG <= SMARTCANE_FALL_FREEFALL_G;
  bool hardImpact = state.totalG >= SMARTCANE_FALL_IMPACT_G;
  bool abruptVertical = havePrevAccel &&
                        accelDeltaG >= SMARTCANE_FALL_JERK_G &&
                        verticalDeltaG >= SMARTCANE_FALL_VERTICAL_DELTA_G &&
                        state.totalG >= SMARTCANE_FALL_VERTICAL_TRIGGER_G;
  bool fallMotionCandidate = freefall || hardImpact || abruptVertical;

  if (state.fallActive) {
    bool uprightStable = stillNow && postureDeg <= (SMARTCANE_FALL_POSTURE_DEG - 25.0f);
    if (uprightStable) {
      if (recoverySinceMs == 0) {
        recoverySinceMs = now;
      } else if (now - recoverySinceMs >= SMARTCANE_FALL_RECOVERY_MS) {
        state.fallActive = false;
        state.stage = "recovered";
        state.reason = "upright_still_after_fall";
        state.confidence = 0.25f;
        resetFallCandidate();
      }
    } else {
      recoverySinceMs = 0;
      state.stage = "confirmed";
      state.reason = "confirmed_fall";
      state.confidence = 0.92f;
    }
    rememberAccel();
    return true;
  }

  if (fallMotionCandidate) {
    if (impactMs == 0 || now - impactMs > SMARTCANE_FALL_CONFIRM_WINDOW_MS) {
      resetFallCandidate();
    }
    impactMs = now;
    lyingSinceMs = 0;
    slowLyingSinceMs = 0;
    candidatePeakG = state.totalG > candidatePeakG ? state.totalG : candidatePeakG;
    candidateMinG = state.totalG < candidateMinG ? state.totalG : candidateMinG;
    candidateHadFreefall = candidateHadFreefall || freefall;
    candidateHadImpact = candidateHadImpact || hardImpact;
    candidateHadVerticalDrop = candidateHadVerticalDrop || abruptVertical;
    state.stage = freefall ? "freefall_candidate" :
                  (hardImpact ? "impact_candidate" : "vertical_drop_candidate");
    state.reason = "large_motion_candidate";
    state.confidence = 0.45f;
  } else if (impactMs != 0 && now - impactMs <= SMARTCANE_FALL_CONFIRM_WINDOW_MS) {
    candidatePeakG = state.totalG > candidatePeakG ? state.totalG : candidatePeakG;
    candidateMinG = state.totalG < candidateMinG ? state.totalG : candidateMinG;
    bool diagonalDropSignature = candidateHadVerticalDrop &&
                                 candidatePeakG >= SMARTCANE_FALL_VERTICAL_PEAK_G &&
                                 candidateMinG <= SMARTCANE_FALL_VERTICAL_MIN_G;
    bool strongFallSignature = candidateHadFreefall ||
                               candidateHadImpact ||
                               diagonalDropSignature;

    if (strongFallSignature && lying && stillNow) {
      if (lyingSinceMs == 0) {
        lyingSinceMs = now;
      }
      state.stage = "still_lying_candidate";
      state.reason = "large_motion_then_still_tilted";
      state.confidence = 0.72f;
      if (now - lyingSinceMs >= SMARTCANE_FALL_LIE_MS &&
          now - lastFallEventMs >= SMARTCANE_FALL_UPLOAD_COOLDOWN_MS) {
        state.fallActive = true;
        state.eventPending = true;
        state.stage = "confirmed";
        state.reason = "confirmed_fall";
        state.confidence = 0.92f;
        lastFallEventMs = now;
        recoverySinceMs = 0;
        resetFallCandidate();
      }
    } else {
      if (!stillNow) {
        lyingSinceMs = 0;
      }
      state.stage = "motion_candidate";
      state.reason = strongFallSignature ? "waiting_for_still_tilted" : "reject_sweep_or_small_motion";
      state.confidence = strongFallSignature ? 0.55f : 0.25f;
    }
  } else {
    if (impactMs != 0) {
      resetFallCandidate();
    }
    if (slowTiltCandidate && lying && stillNow && now - lastFallEventMs >= SMARTCANE_FALL_UPLOAD_COOLDOWN_MS) {
      if (slowLyingSinceMs == 0) {
        slowLyingSinceMs = now;
      }
      state.stage = "slow_lying_candidate";
      state.reason = "slow_tilt_then_still_lying";
      state.confidence = 0.62f;
      if (now - slowLyingSinceMs >= SMARTCANE_FALL_SLOW_LIE_MS) {
        state.fallActive = true;
        state.eventPending = true;
        state.stage = "confirmed";
        state.reason = "confirmed_slow_fall";
        state.confidence = 0.86f;
        lastFallEventMs = now;
        recoverySinceMs = 0;
        resetFallCandidate();
      }
    } else {
      if (!slowTiltCandidate) {
        slowLyingSinceMs = 0;
      }
      state.stage = "normal";
      state.reason = "normal_motion";
      state.confidence = 0.2f;
    }
  }

  rememberAccel();
  return true;
}

static void printDebugRegisters() {
  if (state.address == 0) {
    return;
  }
  uint8_t status = 0;
  uint8_t internalStatus = 0;
  uint8_t pwrConf = 0;
  uint8_t pwrCtrl = 0;
  uint8_t accConf = 0;
  uint8_t accRange = 0;
  readReg(REG_STATUS, &status, 1);
  readReg(REG_INTERNAL_STATUS, &internalStatus, 1);
  readReg(REG_PWR_CONF, &pwrConf, 1);
  readReg(REG_PWR_CTRL, &pwrCtrl, 1);
  readReg(REG_ACC_CONF, &accConf, 1);
  readReg(REG_ACC_RANGE, &accRange, 1);

  Serial.print(F("[IMU_REG] status=0x"));
  if (status < 16) Serial.print('0');
  Serial.print(status, HEX);
  Serial.print(F(" internal=0x"));
  if (internalStatus < 16) Serial.print('0');
  Serial.print(internalStatus, HEX);
  Serial.print(F(" pwr_conf=0x"));
  if (pwrConf < 16) Serial.print('0');
  Serial.print(pwrConf, HEX);
  Serial.print(F(" pwr_ctrl=0x"));
  if (pwrCtrl < 16) Serial.print('0');
  Serial.print(pwrCtrl, HEX);
  Serial.print(F(" acc_conf=0x"));
  if (accConf < 16) Serial.print('0');
  Serial.print(accConf, HEX);
  Serial.print(F(" acc_range=0x"));
  if (accRange < 16) Serial.print('0');
  Serial.println(accRange, HEX);
}

void imuFallPreparePins() {
#if SMARTCANE_IMU_ENABLED
  configureShuttleBoardPins();
#endif
}

bool imuFallBegin() {
#if !SMARTCANE_IMU_ENABLED
  state.available = false;
  state.reason = "disabled";
  Serial.println(F("[IMU] disabled"));
  return false;
#else
  if (!detectAndConfigureBmi270()) {
    Serial.println(F("[IMU] BMI270 not found; run 'scan' and check root for 0x68/0x69"));
    return false;
  }

  Serial.print(F("[IMU] BMI270 OK addr=0x"));
  Serial.println(state.address, HEX);
  return true;
#endif
}

bool imuFallRescan() {
#if !SMARTCANE_IMU_ENABLED
  Serial.println(F("[IMU] disabled"));
  return false;
#else
  Serial.println(F("[IMU] rescan isolated root bus"));
  bool ok = detectAndConfigureBmi270();
  Serial.print(F("[IMU] rescan result="));
  Serial.print(ok ? F("ready") : F("failed"));
  Serial.print(F(" addr=0x"));
  Serial.print(state.address, HEX);
  Serial.print(F(" reason="));
  Serial.println(state.reason);
  return ok;
#endif
}

void imuFallUpdate() {
  unsigned long now = millis();
  if (now - lastSampleMs < SMARTCANE_IMU_SAMPLE_INTERVAL_MS) {
    return;
  }
  lastSampleMs = now;
  if (!state.available || state.mock) {
    return;
  }
  if (readAccel() && streamRaw &&
      now - lastRawStreamPrintMs >= SMARTCANE_IMU_STREAM_INTERVAL_MS) {
    lastRawStreamPrintMs = now;
    printStreamSample();
  }
}

bool imuFallConsumeEvent(ImuFallState &out) {
  if (!state.eventPending) {
    return false;
  }
  out = state;
  state.eventPending = false;
  return true;
}

ImuFallState imuFallCurrent() {
  return state;
}

void imuFallClear() {
  state.fallActive = false;
  state.eventPending = false;
  state.stage = state.available ? "normal" : "idle";
  state.reason = state.available ? "manual_clear" : state.reason;
  impactMs = 0;
  lyingSinceMs = 0;
  slowLyingSinceMs = 0;
  uprightStableSinceMs = 0;
  slowTiltStartedMs = 0;
  slowTiltCandidate = false;
  recoverySinceMs = 0;
  candidatePeakG = 0.0f;
  candidateMinG = 10.0f;
  candidateHadFreefall = false;
  candidateHadImpact = false;
  candidateHadVerticalDrop = false;
}

void imuFallPrintStatus() {
  Serial.print(F("[IMU] available="));
  Serial.print(state.available ? F("yes") : F("no"));
  Serial.print(F(" mock="));
  Serial.print(state.mock ? F("yes") : F("no"));
  Serial.print(F(" addr=0x"));
  Serial.print(state.address, HEX);
  Serial.print(F(" fall="));
  Serial.print(state.fallActive ? F("yes") : F("no"));
  Serial.print(F(" stage="));
  Serial.print(state.stage);
  Serial.print(F(" total_g="));
  Serial.print(state.totalG, 2);
  Serial.print(F(" pitch="));
  Serial.print(state.pitchDeg, 1);
  Serial.print(F(" roll="));
  Serial.print(state.rollDeg, 1);
  Serial.print(F(" confidence="));
  Serial.print(state.confidence, 2);
  Serial.print(F(" reason="));
  Serial.println(state.reason);
}

void imuFallPrintRaw() {
  if (state.available && !state.mock) {
    readAccel();
  }
  Serial.print(F("[IMU_RAW] ax="));
  Serial.print(state.axRaw);
  Serial.print(F(" ay="));
  Serial.print(state.ayRaw);
  Serial.print(F(" az="));
  Serial.print(state.azRaw);
  Serial.print(F(" ax_g="));
  Serial.print(state.axG, 3);
  Serial.print(F(" ay_g="));
  Serial.print(state.ayG, 3);
  Serial.print(F(" az_g="));
  Serial.print(state.azG, 3);
  Serial.print(F(" total_g="));
  Serial.print(state.totalG, 3);
  Serial.print(F(" pitch="));
  Serial.print(state.pitchDeg, 1);
  Serial.print(F(" roll="));
  Serial.print(state.rollDeg, 1);
  Serial.print(F(" stage="));
  Serial.print(state.stage);
  Serial.print(F(" reason="));
  Serial.println(state.reason);
#if SMARTCANE_IMU_RAW_PRINT_REGS
  printDebugRegisters();
#endif
}

static void printStreamSample() {
  Serial.print(F("[IMU] g="));
  Serial.print(state.totalG, 2);
  Serial.print(F(" pitch="));
  Serial.print(state.pitchDeg, 0);
  Serial.print(F(" roll="));
  Serial.print(state.rollDeg, 0);
  Serial.print(F(" stage="));
  Serial.print(state.stage);
  Serial.print(F(" reason="));
  Serial.println(state.reason);
}

void imuFallSetStream(bool enabled) {
  streamRaw = enabled;
  lastRawStreamPrintMs = 0;
  Serial.print(F("[IMU_STREAM] "));
  Serial.println(streamRaw ? F("on") : F("off"));
}
