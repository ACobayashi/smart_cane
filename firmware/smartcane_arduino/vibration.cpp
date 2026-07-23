#include "vibration.h"

#include "config.h"
#include "i2c_bus.h"

static unsigned long stopAtMs[3] = {0, 0, 0};
static bool pcaReady = false;
static bool pcaDetectedOnTca = SMARTCANE_PCA9685_ON_TCA;
static int8_t pcaDetectedChannel = SMARTCANE_TCA_CH_PCA9685;
static uint8_t pcaDetectedAddress = SMARTCANE_PCA9685_ADDR;

static const uint8_t motorChannels[3] = {
  SMARTCANE_VIB_LEFT_CHANNEL,
  SMARTCANE_VIB_RIGHT_CHANNEL,
  SMARTCANE_VIB_CENTER_CHANNEL
};

static const uint8_t PCA9685_MODE1 = 0x00;
static const uint8_t PCA9685_MODE2 = 0x01;
static const uint8_t PCA9685_LED0_ON_L = 0x06;
static const uint8_t PCA9685_PRE_SCALE = 0xFE;
static const uint8_t PCA9685_MODE1_RESTART = 0x80;
static const uint8_t PCA9685_MODE1_AI = 0x20;
static const uint8_t PCA9685_MODE1_SLEEP = 0x10;
static const uint8_t PCA9685_MODE2_OUTDRV = 0x04;

static uint16_t levelToPwm(uint8_t level) {
  if (level == 0) {
    return 0;
  }
  uint8_t clamped = level > 100 ? 100 : level;
  return map(clamped,
             1,
             100,
             SMARTCANE_PCA9685_MIN_RUN_PWM,
             SMARTCANE_PCA9685_MAX_PWM);
}

static bool selectPcaBus() {
  if (pcaDetectedOnTca) {
    return selectTcaChannel((uint8_t)pcaDetectedChannel);
  }
  return disableTcaChannels();
}

static bool probePcaOnCurrentBus() {
  return i2cProbe(pcaDetectedAddress);
}

static bool probePcaAddressOnCurrentBus(uint8_t address) {
  if (address == SMARTCANE_MPR121_ADDR ||
      address == SMARTCANE_TCA9548A_ADDR ||
      address == SMARTCANE_BMI270_ADDR_PRIMARY ||
      address == SMARTCANE_BMI270_ADDR_SECONDARY ||
      address == 0x7E) {
    return false;
  }
  if (i2cProbe(address)) {
    pcaDetectedAddress = address;
    return true;
  }
  return false;
}

static bool probePcaRangeOnCurrentBus() {
  if (probePcaAddressOnCurrentBus(SMARTCANE_PCA9685_ADDR)) {
    return true;
  }
  for (uint8_t addr = SMARTCANE_PCA9685_ADDR_AUTO_MIN; addr <= SMARTCANE_PCA9685_ADDR_AUTO_MAX; ++addr) {
    if (addr == SMARTCANE_PCA9685_ADDR) {
      continue;
    }
    if (probePcaAddressOnCurrentBus(addr)) {
      return true;
    }
  }
  return false;
}

static bool locatePcaBus() {
#if SMARTCANE_PCA9685_AUTO_DETECT
  if (SMARTCANE_PCA9685_ON_TCA && selectTcaChannel(SMARTCANE_TCA_CH_PCA9685) && probePcaRangeOnCurrentBus()) {
    pcaDetectedOnTca = true;
    pcaDetectedChannel = SMARTCANE_TCA_CH_PCA9685;
    return true;
  }

  for (uint8_t ch = 0; ch < 8; ++ch) {
    if (ch == SMARTCANE_TCA_CH_PCA9685) {
      continue;
    }
    if (selectTcaChannel(ch) && probePcaRangeOnCurrentBus()) {
      pcaDetectedOnTca = true;
      pcaDetectedChannel = ch;
      return true;
    }
  }

  if (disableTcaChannels() && probePcaRangeOnCurrentBus()) {
    pcaDetectedOnTca = false;
    pcaDetectedChannel = -1;
    return true;
  }

  return false;
#else
  pcaDetectedOnTca = SMARTCANE_PCA9685_ON_TCA;
  pcaDetectedChannel = SMARTCANE_TCA_CH_PCA9685;
  return selectPcaBus() && probePcaOnCurrentBus();
#endif
}

static bool pcaWrite8(uint8_t reg, uint8_t value) {
  if (!selectPcaBus()) {
    return false;
  }
  Wire.beginTransmission(pcaDetectedAddress);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

static bool pcaRead8(uint8_t reg, uint8_t &value) {
  if (!selectPcaBus()) {
    return false;
  }
  Wire.beginTransmission(pcaDetectedAddress);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) {
    return false;
  }
  if (Wire.requestFrom((int)pcaDetectedAddress, 1) != 1) {
    return false;
  }
  value = Wire.read();
  return true;
}

static bool pcaSetPwmFreq(uint16_t freqHz) {
  uint8_t oldMode = 0;
  if (!pcaRead8(PCA9685_MODE1, oldMode)) {
    return false;
  }
  float prescaleValue = 25000000.0f;
  prescaleValue /= 4096.0f;
  prescaleValue /= (float)freqHz;
  prescaleValue -= 1.0f;
  uint8_t prescale = (uint8_t)(prescaleValue + 0.5f);

  uint8_t sleepMode = (oldMode & 0x7F) | PCA9685_MODE1_SLEEP;
  if (!pcaWrite8(PCA9685_MODE1, sleepMode)) return false;
  if (!pcaWrite8(PCA9685_PRE_SCALE, prescale)) return false;
  if (!pcaWrite8(PCA9685_MODE1, oldMode)) return false;
  delay(5);
  return pcaWrite8(PCA9685_MODE1, oldMode | PCA9685_MODE1_RESTART | PCA9685_MODE1_AI);
}

static bool pcaInitDevice() {
  if (!selectPcaBus()) {
    return false;
  }
  if (!pcaWrite8(PCA9685_MODE1, PCA9685_MODE1_AI)) return false;
  if (!pcaWrite8(PCA9685_MODE2, PCA9685_MODE2_OUTDRV)) return false;
  return pcaSetPwmFreq(SMARTCANE_PCA9685_PWM_FREQ_HZ);
}

static bool pcaSetPwm(uint8_t channel, uint16_t on, uint16_t off) {
  if (!selectPcaBus()) {
    return false;
  }
  uint8_t reg = PCA9685_LED0_ON_L + 4 * channel;
  Wire.beginTransmission(pcaDetectedAddress);
  Wire.write(reg);
  Wire.write(on & 0xFF);
  Wire.write(on >> 8);
  Wire.write(off & 0xFF);
  Wire.write(off >> 8);
  return Wire.endTransmission() == 0;
}

static uint8_t pcaRawWrite8(uint8_t reg, uint8_t value) {
  if (!selectPcaBus()) {
    return 4;
  }
  Wire.beginTransmission(pcaDetectedAddress);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission();
}

static uint8_t pcaRawSetPwm(uint8_t channel, uint16_t on, uint16_t off) {
  if (!selectPcaBus()) {
    return 4;
  }
  uint8_t reg = PCA9685_LED0_ON_L + 4 * channel;
  Wire.beginTransmission(pcaDetectedAddress);
  Wire.write(reg);
  Wire.write(on & 0xFF);
  Wire.write(on >> 8);
  Wire.write(off & 0xFF);
  Wire.write(off >> 8);
  return Wire.endTransmission();
}

static void resetPcaToConfiguredBus() {
  pcaDetectedAddress = SMARTCANE_PCA9685_ADDR;
  pcaDetectedOnTca = SMARTCANE_PCA9685_ON_TCA;
  pcaDetectedChannel = SMARTCANE_TCA_CH_PCA9685;
}

static bool pcaConfiguredIicInit() {
  resetPcaToConfiguredBus();
  Serial.print(F("[VIB] PCA9685 IIC addr=0x"));
  Serial.print(pcaDetectedAddress, HEX);
  Serial.print(F(" bus="));
  if (pcaDetectedOnTca) {
    Serial.print(F("TCA CH"));
    Serial.print(pcaDetectedChannel);
  } else {
    Serial.print(F("root"));
  }
  Serial.println(F(" freq=50Hz pwm=2048"));

  uint8_t errMode2 = pcaRawWrite8(PCA9685_MODE2, PCA9685_MODE2_OUTDRV);
  uint8_t errSleep = pcaRawWrite8(PCA9685_MODE1, PCA9685_MODE1_SLEEP | PCA9685_MODE1_AI);
  uint8_t errPrescale = pcaRawWrite8(PCA9685_PRE_SCALE, 121);
  uint8_t errWake = pcaRawWrite8(PCA9685_MODE1, PCA9685_MODE1_AI);
  delay(5);
  uint8_t errRestart = pcaRawWrite8(PCA9685_MODE1, PCA9685_MODE1_RESTART | PCA9685_MODE1_AI);

  bool ok = errMode2 == 0 && errSleep == 0 && errPrescale == 0 && errWake == 0 && errRestart == 0;
  Serial.print(F("[VIB] init err mode2/sleep/prescale/wake/restart="));
  Serial.print(errMode2);
  Serial.print(F("/"));
  Serial.print(errSleep);
  Serial.print(F("/"));
  Serial.print(errPrescale);
  Serial.print(F("/"));
  Serial.print(errWake);
  Serial.print(F("/"));
  Serial.print(errRestart);
  Serial.println(ok ? F(" OK") : F(" FAIL"));
  if (ok) {
    pcaReady = true;
  }
  return ok;
}

static void writeMotorChannel(uint8_t channel, uint16_t pwmValue) {
  if (!pcaReady) {
    return;
  }
  if (!selectPcaBus()) {
    return;
  }
  pcaSetPwm(channel, 0, pwmValue);
}

static void setMotor(uint8_t index, uint8_t level) {
  if (index >= 3) {
    return;
  }
  if (pcaReady) {
    writeMotorChannel(motorChannels[index], levelToPwm(level));
  }
}

static void vibrateIndex(uint8_t index, uint8_t level, uint16_t durationMs) {
  if (!pcaReady || index >= 3) {
    return;
  }
  setMotor(index, level);
  stopAtMs[index] = millis() + durationMs;
}

bool vibrationBegin() {
#if !SMARTCANE_VIB_ENABLED
  Serial.println(F("[VIB] disabled"));
  return false;
#else
  if (!locatePcaBus()) {
    pcaReady = false;
    Serial.print(F("[VIB] PCA9685 not found at 0x"));
    Serial.println(SMARTCANE_PCA9685_ADDR, HEX);
    return false;
  }

  if (!pcaInitDevice()) {
    pcaReady = false;
    Serial.println(F("[VIB] PCA9685 init failed"));
    return false;
  }

  pcaReady = true;
  vibrationStopAll();

  Serial.print(F("[VIB] PCA9685 ready addr=0x"));
  Serial.print(pcaDetectedAddress, HEX);
  Serial.print(F(" bus="));
#if SMARTCANE_PCA9685_ON_TCA
  if (pcaDetectedOnTca) {
    Serial.print(F("TCA CH"));
    Serial.print(pcaDetectedChannel);
  } else {
    Serial.print(F("root"));
  }
#else
  Serial.print(F("root"));
#endif
  Serial.print(F(" channels L/R/C="));
  Serial.print(SMARTCANE_VIB_LEFT_CHANNEL);
  Serial.print(F("/"));
  Serial.print(SMARTCANE_VIB_RIGHT_CHANNEL);
  Serial.print(F("/"));
  Serial.println(SMARTCANE_VIB_CENTER_CHANNEL);
  return true;
#endif
}

void vibrationUpdate() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < 3; ++i) {
    if (stopAtMs[i] != 0 && (long)(now - stopAtMs[i]) >= 0) {
      stopAtMs[i] = 0;
      setMotor(i, 0);
    }
  }
}

bool vibrationReady() {
  return pcaReady;
}

const char *vibrationModeName() {
#if !SMARTCANE_VIB_ENABLED
  return "disabled";
#else
  return pcaReady ? "pca9685-tca6-ch0-1-2" : "pca9685-not-found";
#endif
}

void vibrateLeft(uint8_t level, uint16_t durationMs) {
  vibrateIndex(0, level, durationMs);
}

void vibrateRight(uint8_t level, uint16_t durationMs) {
  vibrateIndex(1, level, durationMs);
}

void vibrateCenter(uint8_t level, uint16_t durationMs) {
  vibrateIndex(2, level, durationMs);
}

void vibrateAll(uint8_t level, uint16_t durationMs) {
  vibrateLeft(level, durationMs);
  vibrateRight(level, durationMs);
  vibrateCenter(level, durationMs);
}

void vibrationStopAll() {
  for (uint8_t i = 0; i < 3; ++i) {
    stopAtMs[i] = 0;
    setMotor(i, 0);
  }
}

bool vibrationPcaIicMotor(uint8_t motorIndex, uint16_t durationMs) {
  if (motorIndex >= 3) {
    return false;
  }
  bool initOk = pcaReady || pcaConfiguredIicInit();
  uint8_t channel = motorChannels[motorIndex];
  uint8_t err = pcaRawSetPwm(channel, 0, SMARTCANE_PCA9685_MIN_RUN_PWM);
  Serial.print(F("[VIB] motor_index="));
  Serial.print(motorIndex + 1);
  Serial.print(F(" pca_channel="));
  Serial.print(channel);
  Serial.print(F(" setPWM(0,2048) err="));
  Serial.println(err);
  if (initOk && err == 0) {
    pcaReady = true;
    stopAtMs[motorIndex] = millis() + durationMs;
    return true;
  }
  return false;
}

bool vibrationPcaIicStop() {
  bool anyOk = false;
  bool initOk = pcaReady || pcaConfiguredIicInit();
  for (uint8_t i = 0; i < 3; ++i) {
    stopAtMs[i] = 0;
    uint8_t err = pcaRawSetPwm(motorChannels[i], 0, 0);
    anyOk = anyOk || (err == 0);
    Serial.print(F("[VIB] stop pca_channel="));
    Serial.print(motorChannels[i]);
    Serial.print(F(" err="));
    Serial.println(err);
  }
  return initOk && anyOk;
}

void patternObstacle() {
  vibrateCenter(SMARTCANE_VIB_LEVEL_MEDIUM, 180);
}

void patternGroundDrop() {
  vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, 300);
}

void patternTurnLeft() {
  vibrateLeft(SMARTCANE_VIB_LEVEL_MEDIUM, 220);
}

void patternTurnRight() {
  vibrateRight(SMARTCANE_VIB_LEVEL_MEDIUM, 220);
}

void patternStop() {
  vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, 260);
}

void patternSos() {
  vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, 350);
}
