#pragma once

#include <Arduino.h>
#include <Wire.h>

bool beginI2CBus();
bool selectTcaChannel(uint8_t ch);
bool i2cDevicePresent(uint8_t address);
void printI2CDeviceStatus();

