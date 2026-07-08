#pragma once

#include <Arduino.h>

#include "data_model.h"

void beginGpsLocation();
void updateGpsLocation();
LocationData getCurrentLocation();
bool hasRealGpsFix();
void printLocationStatus();

