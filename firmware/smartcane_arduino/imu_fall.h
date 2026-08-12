#pragma once

#include <Arduino.h>

struct ImuFallState {
  bool available = false;
  bool mock = false;
  bool fallActive = false;
  // True from the first high-motion fall candidate until the cane is again
  // held in the learned normal-use pose. The sketch uses this as the global
  // safety lock that suppresses ordinary distance feedback and uploads.
  bool fallLock = false;
  bool eventPending = false;
  uint8_t address = 0;
  int16_t axRaw = 0;
  int16_t ayRaw = 0;
  int16_t azRaw = 0;
  int16_t gxRaw = 0;
  int16_t gyRaw = 0;
  int16_t gzRaw = 0;
  float axG = 0.0f;
  float ayG = 0.0f;
  float azG = 1.0f;
  float gxDps = 0.0f;
  float gyDps = 0.0f;
  float gzDps = 0.0f;
  float gyroDps = 0.0f;
  float totalG = 1.0f;
  float pitchDeg = 0.0f;
  float rollDeg = 0.0f;
  float postureDeg = 0.0f;
  float angleChangeDeg = 0.0f;
  float tiltRateDps = 0.0f;
  // Motion at the instant the safety lock was entered. These values are kept
  // through the two-second lying confirmation for an auditable fall record.
  float triggerTotalG = 0.0f;
  float triggerGyroDps = 0.0f;
  float triggerAngleDeg = 0.0f;
  float triggerTiltRateDps = 0.0f;
  float triggerJerkGPerSec = 0.0f;
  unsigned long triggerAtMs = 0;
  float confidence = 0.0f;
  const char *stage = "idle";
  const char *reason = "not_started";
  unsigned long updatedAtMs = 0;
};

bool imuFallBegin();
void imuFallPreparePins();
bool imuFallRescan();
void imuFallUpdate();
bool imuFallConsumeEvent(ImuFallState &out);
ImuFallState imuFallCurrent();
void imuFallPrintStatus();
void imuFallPrintRaw();
void imuFallSetStream(bool enabled);
