#pragma once

#include <Arduino.h>
#include <Wire.h>

bool i2cBusBegin();
bool selectTcaChannel(uint8_t channel);
bool i2cProbe(uint8_t address);
void i2cScanRoot();
void i2cScanCurrentBus(const char *label);
void i2cScanTcaChannels();
