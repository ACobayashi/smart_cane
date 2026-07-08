#pragma once

#include <Arduino.h>

#include "data_model.h"

bool beginTofSensors();
bool readTofSensors(DistanceReadings &out);
bool isTofMockActive();

