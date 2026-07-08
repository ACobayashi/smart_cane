#pragma once

#include <Arduino.h>

bool beginVibration();
void updateVibration();
bool isVibrationReady();

void vibrateLeft(uint8_t level, uint16_t durationMs);
void vibrateRight(uint8_t level, uint16_t durationMs);
void vibrateCenter(uint8_t level, uint16_t durationMs);
void vibrateAll(uint8_t level, uint16_t durationMs);

void patternObstacle();
void patternGroundDrop();
void patternTurnLeft();
void patternTurnRight();
void patternStop();
void patternSos();

