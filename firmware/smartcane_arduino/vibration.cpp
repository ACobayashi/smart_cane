#include "vibration.h"

#include <Adafruit_PWMServoDriver.h>

#include "config.h"
#include "i2c_bus.h"

struct MotorState {
  uint8_t channel;
  bool active;
  unsigned long stopAtMs;
};

static Adafruit_PWMServoDriver pwm = Adafruit_PWMServoDriver(PCA9685_I2C_ADDR);
static bool pcaReady = false;
static MotorState motors[3] = {
  {VIB_LEFT_CHANNEL, false, 0},
  {VIB_RIGHT_CHANNEL, false, 0},
  {VIB_CENTER_CHANNEL, false, 0}
};

static uint16_t levelToPwm(uint8_t level) {
  if (level > 100) {
    level = 100;
  }
  return (uint16_t)((uint32_t)level * PCA9685_PWM_MAX / 100);
}

static void setMotor(uint8_t index, uint8_t level, uint16_t durationMs) {
  if (index >= 3) {
    return;
  }
  if (durationMs > 300) {
    durationMs = 300;
  }

  uint16_t pwmValue = levelToPwm(level);
  motors[index].active = pwmValue > 0 && durationMs > 0;
  motors[index].stopAtMs = millis() + durationMs;

  if (pcaReady) {
    pwm.setPWM(motors[index].channel, 0, pwmValue);
  } else {
    Serial.print("Vibration mock channel=");
    Serial.print(motors[index].channel);
    Serial.print(" level=");
    Serial.print(level);
    Serial.print(" duration=");
    Serial.println(durationMs);
  }
}

static void stopMotor(uint8_t index) {
  if (index >= 3) {
    return;
  }
  motors[index].active = false;
  if (pcaReady) {
    pwm.setPWM(motors[index].channel, 0, 0);
  }
}

bool beginVibration() {
  pcaReady = i2cDevicePresent(PCA9685_I2C_ADDR);
  if (pcaReady) {
    pwm.begin();
    pwm.setPWMFreq(PCA9685_PWM_FREQ_HZ);
    delay(10);
    for (uint8_t i = 0; i < 3; i++) {
      stopMotor(i);
    }
    Serial.print("PCA9685 ready, PWM freq=");
    Serial.println(PCA9685_PWM_FREQ_HZ);
  } else {
    Serial.println("PCA9685 missing, vibration will be printed as mock output");
  }
  return pcaReady;
}

void updateVibration() {
  unsigned long now = millis();
  for (uint8_t i = 0; i < 3; i++) {
    if (motors[i].active && (long)(now - motors[i].stopAtMs) >= 0) {
      stopMotor(i);
    }
  }
}

bool isVibrationReady() {
  return pcaReady;
}

void vibrateLeft(uint8_t level, uint16_t durationMs) {
  setMotor(0, level, durationMs);
}

void vibrateRight(uint8_t level, uint16_t durationMs) {
  setMotor(1, level, durationMs);
}

void vibrateCenter(uint8_t level, uint16_t durationMs) {
  setMotor(2, level, durationMs);
}

void vibrateAll(uint8_t level, uint16_t durationMs) {
  vibrateLeft(level, durationMs);
  vibrateRight(level, durationMs);
  vibrateCenter(level, durationMs);
}

void patternObstacle() {
  vibrateCenter(VIB_LEVEL_MEDIUM, 160);
}

void patternGroundDrop() {
  vibrateCenter(VIB_LEVEL_HIGH, 260);
  vibrateLeft(VIB_LEVEL_HIGH, 240);
  vibrateRight(VIB_LEVEL_HIGH, 240);
}

void patternTurnLeft() {
  vibrateLeft(VIB_LEVEL_MEDIUM, 180);
}

void patternTurnRight() {
  vibrateRight(VIB_LEVEL_MEDIUM, 180);
}

void patternStop() {
  vibrateAll(VIB_LEVEL_HIGH, 220);
}

void patternSos() {
  vibrateAll(VIB_LEVEL_HIGH, 300);
}
