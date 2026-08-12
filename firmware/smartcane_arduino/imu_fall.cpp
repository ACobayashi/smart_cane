#include "imu_fall.h"

#include <Wire.h>
#include <math.h>

#include "bmi270_config_file.h"
#include "config.h"
#include "i2c_bus.h"

// BMI270 minimal register path for acceleration + gyro fall detection.
static const uint8_t REG_CHIP_ID = 0x00;
static const uint8_t REG_STATUS = 0x03;
// BMI270 normal-data register layout (not the OIS register map):
// 0x0C..0x11 = ACC_X/Y/Z, 0x12..0x17 = GYR_X/Y/Z.
static const uint8_t REG_ACC_X_LSB = 0x0C;
static const uint8_t REG_GYR_X_LSB = 0x12;
static const uint8_t REG_INTERNAL_STATUS = 0x21;
static const uint8_t REG_ACC_CONF = 0x40;
static const uint8_t REG_ACC_RANGE = 0x41;
static const uint8_t REG_GYR_CONF = 0x42;
static const uint8_t REG_GYR_RANGE = 0x43;
static const uint8_t REG_INIT_CTRL = 0x59;
static const uint8_t REG_INIT_ADDR_0 = 0x5B;
static const uint8_t REG_INIT_DATA = 0x5E;
static const uint8_t REG_PWR_CONF = 0x7C;
static const uint8_t REG_PWR_CTRL = 0x7D;
static const uint8_t REG_CMD = 0x7E;
static const uint8_t BMI270_CMD_SOFT_RESET = 0xB6;
static const uint8_t BMI270_INIT_OK = 0x01;
static const uint8_t BMI270_CONFIG_CHUNK_BYTES = 16;

enum FallDetectorStage {
  FALL_STAGE_NORMAL,
  FALL_STAGE_CANDIDATE,
  FALL_STAGE_LYING_WAIT,
  FALL_STAGE_CONFIRMED,
  FALL_STAGE_RECOVERY
};

static ImuFallState state;
static unsigned long lastSampleMs = 0;
static FallDetectorStage fallStage = FALL_STAGE_NORMAL;
static unsigned long candidateStartedMs = 0;
static unsigned long stillLyingSinceMs = 0;
static unsigned long lastFallEventMs = 0;
static unsigned long lastRawStreamPrintMs = 0;
static unsigned long recoverySinceMs = 0;
static bool streamRaw = false;
static bool baselineReady = false;
static float baselineAxG = 0.0f;
static float baselineAyG = 0.0f;
static float baselineAzG = 1.0f;
static float baselinePostureDeg = 0.0f;
static bool normalUseReady = false;
static unsigned long normalUseSinceMs = 0;
static unsigned long lastNormalUseQualifiedMs = 0;
static float previousAngleFromBaseline = 0.0f;
static float previousTotalG = 1.0f;
static unsigned long previousMotionMs = 0;

static bool readAccel();
static void printDebugRegisters();
static void printStreamSample();

static void resetNormalUseQualification() {
  normalUseReady = false;
  normalUseSinceMs = 0;
  lastNormalUseQualifiedMs = 0;
}

static void resetFallCandidate() {
  fallStage = FALL_STAGE_NORMAL;
  candidateStartedMs = 0;
  stillLyingSinceMs = 0;
  state.fallLock = false;
  state.triggerTotalG = 0.0f;
  state.triggerGyroDps = 0.0f;
  state.triggerAngleDeg = 0.0f;
  state.triggerTiltRateDps = 0.0f;
  state.triggerJerkGPerSec = 0.0f;
  state.triggerAtMs = 0;
}

static void beginFallCandidate(unsigned long now, float angleFromBaseline, float jerkGPerSec,
                               bool accelTrigger, const char *reason) {
  fallStage = FALL_STAGE_CANDIDATE;
  candidateStartedMs = now;
  stillLyingSinceMs = 0;
  // Lock as soon as the abnormal motion sequence begins. A formal event is
  // intentionally delayed until the relative lying posture is still for 2 s.
  state.fallLock = true;
  state.triggerTotalG = state.totalG;
  state.triggerGyroDps = state.gyroDps;
  state.triggerAngleDeg = angleFromBaseline;
  state.triggerTiltRateDps = state.tiltRateDps;
  state.triggerJerkGPerSec = jerkGPerSec;
  state.triggerAtMs = now;
  state.stage = "fall_candidate";
  state.reason = reason;
  state.confidence = accelTrigger ? 0.68f : 0.60f;
  Serial.print(F("[FALL] lock candidate reason="));
  Serial.print(reason);
  Serial.print(F(" g="));
  Serial.print(state.triggerTotalG, 2);
  Serial.print(F(" gyro="));
  Serial.print(state.triggerGyroDps, 1);
  Serial.print(F(" angle="));
  Serial.print(state.triggerAngleDeg, 1);
  Serial.print(F(" tilt_rate="));
  Serial.println(state.triggerTiltRateDps, 1);
}

static void rememberAccel() {
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
  writeReg(REG_PWR_CTRL, 0x06);    // accel + gyro enable
  delay(10);
  writeReg(REG_ACC_CONF, 0xA8);   // accel normal mode, about 100 Hz ODR
  writeReg(REG_ACC_RANGE, 0x01);  // +-4 g
  writeReg(REG_GYR_CONF, 0xA8);   // gyro normal mode, about 100 Hz ODR
  writeReg(REG_GYR_RANGE, 0x00);  // +-2000 deg/s
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
  uint8_t bytes[12] = {0};
  // Read the contiguous normal-data block from ACC_X.  Starting at 0x0C
  // preserves each axis LSB/MSB shadow pair and must not be confused with
  // the separate OIS output map at the same address range.
  if (!readReg(REG_ACC_X_LSB, bytes, sizeof(bytes))) {
    state.available = false;
    state.reason = "read_failed";
    return false;
  }

  state.axRaw = (int16_t)((uint16_t)bytes[1] << 8 | bytes[0]);
  state.ayRaw = (int16_t)((uint16_t)bytes[3] << 8 | bytes[2]);
  state.azRaw = (int16_t)((uint16_t)bytes[5] << 8 | bytes[4]);
  state.gxRaw = (int16_t)((uint16_t)bytes[7] << 8 | bytes[6]);
  state.gyRaw = (int16_t)((uint16_t)bytes[9] << 8 | bytes[8]);
  state.gzRaw = (int16_t)((uint16_t)bytes[11] << 8 | bytes[10]);

  if (state.axRaw == 0 && state.ayRaw == 0 && state.azRaw == 0) {
    state.axG = 0.0f;
    state.ayG = 0.0f;
    state.azG = 0.0f;
    state.gxDps = 0.0f;
    state.gyDps = 0.0f;
    state.gzDps = 0.0f;
    state.gyroDps = 0.0f;
    state.totalG = 0.0f;
    state.pitchDeg = 0.0f;
    state.rollDeg = 0.0f;
    state.postureDeg = 0.0f;
    state.angleChangeDeg = 0.0f;
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
  // GYR_RANGE is configured to +-2000 dps, so one LSB is about 0.061 dps.
  state.gxDps = state.gxRaw * 2000.0f / 32768.0f;
  state.gyDps = state.gyRaw * 2000.0f / 32768.0f;
  state.gzDps = state.gzRaw * 2000.0f / 32768.0f;
  state.gyroDps = sqrtf(state.gxDps * state.gxDps +
                        state.gyDps * state.gyDps +
                        state.gzDps * state.gzDps);
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
  state.postureDeg = postureDeg;
  state.updatedAtMs = millis();

  unsigned long now = millis();
  if (!baselineReady) {
    baselineAxG = state.axG;
    baselineAyG = state.ayG;
    baselineAzG = state.azG;
    baselinePostureDeg = postureDeg;
    baselineReady = true;
  }

  float dot = state.axG * baselineAxG + state.ayG * baselineAyG + state.azG * baselineAzG;
  float baseMag = sqrtf(baselineAxG * baselineAxG +
                        baselineAyG * baselineAyG +
                        baselineAzG * baselineAzG);
  float denom = baseMag * safeTotal;
  float angleFromBaseline = 0.0f;
  if (denom > 0.01f) {
    float ratio = dot / denom;
    ratio = ratio > 1.0f ? 1.0f : ratio;
    ratio = ratio < -1.0f ? -1.0f : ratio;
    angleFromBaseline = acosf(ratio) * 57.2957795f;
  } else {
    angleFromBaseline = fabsf(postureDeg - baselinePostureDeg);
  }
  state.angleChangeDeg = angleFromBaseline;

  float sampleSeconds = previousMotionMs == 0 ? 0.0f : (now - previousMotionMs) / 1000.0f;
  state.tiltRateDps = sampleSeconds > 0.005f
      ? fabsf(angleFromBaseline - previousAngleFromBaseline) / sampleSeconds
      : 0.0f;
  float jerkGPerSec = sampleSeconds > 0.005f
      ? fabsf(state.totalG - previousTotalG) / sampleSeconds
      : 0.0f;
  previousAngleFromBaseline = angleFromBaseline;
  previousTotalG = state.totalG;
  previousMotionMs = now;

  bool baselineStill = state.gyroDps <= SMARTCANE_FALL_BASELINE_STILL_GYRO_DPS &&
                       state.totalG >= SMARTCANE_FALL_BASELINE_ACC_MIN_G &&
                       state.totalG <= SMARTCANE_FALL_BASELINE_ACC_MAX_G &&
                       fallStage == FALL_STAGE_NORMAL &&
                       !state.fallActive;
  if (baselineStill && angleFromBaseline <= 12.0f) {
    baselineAxG = baselineAxG * 0.96f + state.axG * 0.04f;
    baselineAyG = baselineAyG * 0.96f + state.ayG * 0.04f;
    baselineAzG = baselineAzG * 0.96f + state.azG * 0.04f;
    baselinePostureDeg = baselinePostureDeg * 0.96f + postureDeg * 0.04f;
    state.angleChangeDeg = angleFromBaseline;
  }

  // A fall may only start from a recently demonstrated normal cane-use pose.
  // A cane already lying on a desk therefore cannot manufacture a fall event.
  bool normalUseSample = fallStage == FALL_STAGE_NORMAL &&
                         baselineStill && angleFromBaseline <= SMARTCANE_FALL_CANCEL_UPRIGHT_DEG;
  if (normalUseSample) {
    if (normalUseSinceMs == 0) normalUseSinceMs = now;
    if (now - normalUseSinceMs >= SMARTCANE_FALL_NORMAL_USE_READY_MS) {
      normalUseReady = true;
      lastNormalUseQualifiedMs = now;
    }
  } else if (fallStage == FALL_STAGE_NORMAL) {
    normalUseSinceMs = 0;
    if (lastNormalUseQualifiedMs == 0 ||
        now - lastNormalUseQualifiedMs > SMARTCANE_FALL_NORMAL_USE_LAUNCH_WINDOW_MS) {
      resetNormalUseQualification();
    }
  }

  bool accelTrigger = state.totalG > SMARTCANE_FALL_ACCEL_HIGH_G ||
                       state.totalG < SMARTCANE_FALL_ACCEL_LOW_G;
  bool gyroTrigger = state.gyroDps > SMARTCANE_FALL_GYRO_TRIGGER_DPS;
  bool tiltRateTrigger = state.tiltRateDps > SMARTCANE_FALL_FAST_TILT_RATE_DPS;
  bool jerkTrigger = jerkGPerSec > 2.2f;
  // A fast large relative tilt is sufficient to start a fall candidate; an
  // impact is useful evidence but is deliberately not mandatory. This covers
  // the common soft-cushion/controlled fall where acceleration is damped.
  bool rapidTiltStart = angleFromBaseline >= SMARTCANE_FALL_FAST_ANGLE_DEG &&
      (tiltRateTrigger || gyroTrigger);
  // Some fast controlled falls settle between two 50 ms samples. The measured
  // gyro can then already be low, but a recent normal-use posture has changed
  // directly into a lying vector. This is still a tilt transition, not an
  // impact requirement; the independent two-second lying check prevents a
  // normal cane sweep from becoming a formal fall.
  bool directLyingTransitionStart = angleFromBaseline >= SMARTCANE_FALL_LYING_ANGLE_DEG &&
      (state.tiltRateDps >= 18.0f || state.gyroDps >= 18.0f);
  // If the impact and tilt arrive in separate BMI270 samples, retain the
  // impact-assisted path at a smaller angle. It remains only a fallback.
  bool impactAssistedTiltStart = (accelTrigger || jerkTrigger) &&
      angleFromBaseline >= SMARTCANE_FALL_CANDIDATE_ANGLE_DEG;
  bool abnormalMotionStart = rapidTiltStart || directLyingTransitionStart || impactAssistedTiltStart;
  // The cane is intentionally held at an angle, and BMI270 axes vary with the
  // enclosure. Only a change from the learned normal-use vector represents
  // lying down; absolute pitch/roll must never be used as the lying test.
  // Entry uses the high angle so an ordinary cane lift cannot start a lying
  // confirmation.  After that entry, a real landing may rebound or settle by
  // several degrees; retain the lock down to the separate hold threshold.
  bool lyingAngle = angleFromBaseline >= SMARTCANE_FALL_LYING_ANGLE_DEG;
  bool lyingHoldAngle = angleFromBaseline >= SMARTCANE_FALL_LYING_HOLD_ANGLE_DEG;
  bool stillLying = lyingHoldAngle &&
                    state.gyroDps < SMARTCANE_FALL_STILL_GYRO_DPS &&
                    state.totalG > SMARTCANE_FALL_STILL_ACC_MIN_G &&
                    state.totalG < SMARTCANE_FALL_STILL_ACC_MAX_G;
  bool uprightAgain = angleFromBaseline < SMARTCANE_FALL_CANCEL_UPRIGHT_DEG &&
                      state.gyroDps < SMARTCANE_FALL_STILL_GYRO_DPS &&
                      state.totalG > SMARTCANE_FALL_STILL_ACC_MIN_G &&
                      state.totalG < SMARTCANE_FALL_STILL_ACC_MAX_G;

  if (state.fallActive) {
    if (uprightAgain) {
      if (recoverySinceMs == 0) {
        recoverySinceMs = now;
      } else if (now - recoverySinceMs >= SMARTCANE_FALL_RECOVERY_MS) {
        state.fallActive = false;
        state.fallLock = false;
        state.stage = "normal_use_recovered";
        state.reason = "normal_use_pose_stable_after_fall";
        state.confidence = 0.25f;
        resetFallCandidate();
        resetNormalUseQualification();
      }
    } else {
      recoverySinceMs = 0;
      fallStage = FALL_STAGE_CONFIRMED;
      state.fallLock = true;
      state.stage = "fall_confirmed";
      state.reason = "confirmed_fall";
      state.confidence = 0.92f;
    }
    rememberAccel();
    return true;
  }

  switch (fallStage) {
    case FALL_STAGE_NORMAL:
      {
        bool normalUseArmed = normalUseReady && lastNormalUseQualifiedMs != 0 &&
            now - lastNormalUseQualifiedMs <= SMARTCANE_FALL_NORMAL_USE_LAUNCH_WINDOW_MS;
        if (normalUseArmed && abnormalMotionStart) {
          beginFallCandidate(now, angleFromBaseline, jerkGPerSec, accelTrigger,
                             rapidTiltStart ? "normal_use_rapid_tilt_lock_waiting_lying"
                                            : directLyingTransitionStart ? "normal_use_direct_lying_tilt_lock_waiting_lying"
                                            : "normal_use_impact_assisted_tilt_lock_waiting_lying");
          // The trigger sample in the user's real fall already crossed 58°.
          // Do not wait for one more 50 ms sample to notice it: that sample
          // can be a settling/rebound frame and used to release the lock back
          // to ordinary obstacle reporting before the two-second check began.
          if (lyingAngle) {
            fallStage = FALL_STAGE_LYING_WAIT;
            stillLyingSinceMs = 0;
            state.fallLock = true;
            state.stage = "fall_lying_wait";
            state.reason = "rapid_tilt_reached_lying_angle";
            state.confidence = 0.70f;
            Serial.println(F("[FALL] lying posture reached; hold still 2000ms to confirm"));
          }
        } else {
        state.stage = "normal";
        state.reason = normalUseArmed ? "normal_use" : "learning_normal_use";
        state.confidence = 0.20f;
        }
      }
      break;

    case FALL_STAGE_CANDIDATE:
      if (uprightAgain && now - candidateStartedMs > 250) {
        resetFallCandidate();
        resetNormalUseQualification();
        state.stage = "normal";
        state.reason = "candidate_cancelled_upright";
        state.confidence = 0.18f;
      } else if (lyingAngle) {
        fallStage = FALL_STAGE_LYING_WAIT;
        stillLyingSinceMs = 0;
        state.fallLock = true;
        state.stage = "fall_lying_wait";
        state.reason = "candidate_reached_lying_angle";
        state.confidence = 0.66f;
        Serial.println(F("[FALL] lying posture reached; hold still 2000ms to confirm"));
      } else if (now - candidateStartedMs > SMARTCANE_FALL_CANDIDATE_WINDOW_MS) {
        // A large normal cane swing can reach the fast-tilt threshold, but a
        // fall must become a relative lying posture promptly. Release this
        // false candidate instead of leaving ordinary feedback muted forever.
        resetFallCandidate();
        resetNormalUseQualification();
        state.stage = "normal";
        state.reason = "candidate_expired_without_lying";
        state.confidence = 0.18f;
      } else {
        state.stage = "fall_candidate";
        state.reason = "motion_candidate_window";
        state.confidence = 0.52f;
      }
      break;

    case FALL_STAGE_LYING_WAIT:
      if (uprightAgain) {
        resetFallCandidate();
        resetNormalUseQualification();
        state.stage = "normal";
        state.reason = "post_fall_cancelled_upright";
        state.confidence = 0.18f;
      } else if (!lyingHoldAngle) {
        // A fall that has crossed the 58-degree entry threshold can settle a
        // little after landing. Keep ordinary distance alerts locked until it
        // is genuinely upright again, but do not start the two-second timer
        // until the retained lying angle is present.
        stillLyingSinceMs = 0;
        state.fallLock = true;
        state.stage = "fall_lying_wait";
        state.reason = "waiting_for_retained_lying_angle";
        state.confidence = 0.60f;
      } else if (stillLying) {
        if (stillLyingSinceMs == 0) {
          stillLyingSinceMs = now;
        }
        state.fallLock = true;
        state.stage = "fall_lying_wait";
        state.reason = "still_lying_confirming";
        state.confidence = 0.78f;
        if (now - stillLyingSinceMs >= SMARTCANE_FALL_CONFIRM_MS &&
            now - lastFallEventMs >= SMARTCANE_FALL_UPLOAD_COOLDOWN_MS) {
          fallStage = FALL_STAGE_CONFIRMED;
          state.fallActive = true;
          state.fallLock = true;
          state.eventPending = true;
          state.stage = "fall_confirmed";
          state.reason = "confirmed_fast_tilt_then_still_lying";
          state.confidence = accelTrigger ? 0.92f : 0.88f;
          lastFallEventMs = now;
          recoverySinceMs = 0;
          stillLyingSinceMs = 0;
        }
      } else {
        stillLyingSinceMs = 0;
        state.fallLock = true;
        state.stage = "fall_lying_wait";
        state.reason = "waiting_for_still_lying";
        state.confidence = 0.60f;
      }
      break;

    case FALL_STAGE_CONFIRMED:
      fallStage = FALL_STAGE_RECOVERY;
      state.fallLock = true;
      state.stage = "fall_recovery";
      state.reason = "waiting_for_normal_use_pose";
      state.confidence = 0.92f;
      break;
    case FALL_STAGE_RECOVERY:
    default:
      state.fallLock = true;
      state.stage = "fall_recovery";
      state.reason = "waiting_for_normal_use_pose";
      state.confidence = 0.92f;
      break;
  }

  if (fallStage == FALL_STAGE_NORMAL && baselineStill && state.gyroDps < 8.0f &&
      state.totalG > SMARTCANE_FALL_BASELINE_ACC_MIN_G &&
      state.totalG < SMARTCANE_FALL_BASELINE_ACC_MAX_G &&
      angleFromBaseline < 8.0f) {
    // Keep normal holding posture fresh; lying posture is never learned here.
    baselinePostureDeg = baselinePostureDeg * 0.98f + postureDeg * 0.02f;
  }

  if ((fallStage == FALL_STAGE_CONFIRMED || fallStage == FALL_STAGE_RECOVERY) && !state.fallActive) {
      resetFallCandidate();
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
  Serial.print(F(" gyro="));
  Serial.print(state.gyroDps, 1);
  Serial.print(F(" angle_delta="));
  Serial.print(state.angleChangeDeg, 1);
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
  Serial.print(F(" gx="));
  Serial.print(state.gxRaw);
  Serial.print(F(" gy="));
  Serial.print(state.gyRaw);
  Serial.print(F(" gz="));
  Serial.print(state.gzRaw);
  Serial.print(F(" ax_g="));
  Serial.print(state.axG, 3);
  Serial.print(F(" ay_g="));
  Serial.print(state.ayG, 3);
  Serial.print(F(" az_g="));
  Serial.print(state.azG, 3);
  Serial.print(F(" total_g="));
  Serial.print(state.totalG, 3);
  Serial.print(F(" gyro_dps="));
  Serial.print(state.gyroDps, 1);
  Serial.print(F(" angle_delta="));
  Serial.print(state.angleChangeDeg, 1);
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
  Serial.print(F(" gyro="));
  Serial.print(state.gyroDps, 0);
  Serial.print(F(" angle="));
  Serial.print(state.angleChangeDeg, 0);
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
