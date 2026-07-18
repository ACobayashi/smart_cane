#include "vibration.h"

#include <Adafruit_PWMServoDriver.h>

#include "config.h"
#include "i2c_bus.h"

static Adafruit_PWMServoDriver pwm(SMARTCANE_PCA9685_ADDR);
static bool pcaReady = false;
static unsigned long stopAtMs[3] = {0, 0, 0};

static const uint8_t pwmChannels[3] = {
  SMARTCANE_VIB_LEFT_CHANNEL,
  SMARTCANE_VIB_RIGHT_CHANNEL,
  SMARTCANE_VIB_CENTER_CHANNEL
};

#if SMARTCANE_MOTOR_GPIO_FALLBACK_ENABLED
static const int gpioPins[3] = {
  SMARTCANE_MOTOR_LEFT_GPIO,
  SMARTCANE_MOTOR_RIGHT_GPIO,
  SMARTCANE_MOTOR_CENTER_GPIO
};
#endif

static uint16_t levelToPwm(uint8_t level) {
  if (level > 100) level = 100;
  return (uint32_t)level * SMARTCANE_PCA9685_PWM_MAX / 100;
}

static void setMotor(uint8_t index, uint8_t level) {
  if (index >= 3) return;

  if (pcaReady) {
    pwm.setPWM(pwmChannels[index], 0, levelToPwm(level));
    return;
  }

#if SMARTCANE_MOTOR_GPIO_FALLBACK_ENABLED
  if (gpioPins[index] >= 0) {
    digitalWrite(gpioPins[index], level > 0 ? HIGH : LOW);
  }
#endif
}

static void vibrateIndex(uint8_t index, uint8_t level, uint16_t durationMs) {
  setMotor(index, level);
  stopAtMs[index] = millis() + durationMs;
}

bool vibrationBegin() {
  pcaReady = i2cProbe(SMARTCANE_PCA9685_ADDR);
  if (pcaReady) {
    pwm.begin();
    pwm.setPWMFreq(SMARTCANE_PCA9685_PWM_FREQ_HZ);
    for (uint8_t i = 0; i < 3; ++i) {
      setMotor(i, 0);
    }
    Serial.print(F("[VIB] PCA9685 OK addr=0x"));
    Serial.println(SMARTCANE_PCA9685_ADDR, HEX);
    return true;
  }

  Serial.print(F("[VIB] PCA9685 not found addr=0x"));
  Serial.println(SMARTCANE_PCA9685_ADDR, HEX);

#if SMARTCANE_MOTOR_GPIO_FALLBACK_ENABLED
  for (uint8_t i = 0; i < 3; ++i) {
    pinMode(gpioPins[i], OUTPUT);
    digitalWrite(gpioPins[i], LOW);
  }
  Serial.println(F("[VIB] GPIO fallback enabled for MOS gate tests"));
  return true;
#else
  Serial.println(F("[VIB] vibration disabled; local risk logic still runs"));
  return false;
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

void patternObstacle() {
  vibrateCenter(SMARTCANE_VIB_LEVEL_MEDIUM, 160);
}

void patternGroundDrop() {
  vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, 260);
}

void patternTurnLeft() {
  vibrateLeft(SMARTCANE_VIB_LEVEL_MEDIUM, 180);
}

void patternTurnRight() {
  vibrateRight(SMARTCANE_VIB_LEVEL_MEDIUM, 180);
}

void patternStop() {
  vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, 220);
}

void patternSos() {
  vibrateAll(SMARTCANE_VIB_LEVEL_HIGH, 280);
}
