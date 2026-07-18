#pragma once

#include <Arduino.h>

#include "data_model.h"

enum MockScenario {
  MOCK_SCENARIO_AUTO = 0,
  MOCK_SCENARIO_CLEAR,
  MOCK_SCENARIO_FRONT_WARN,
  MOCK_SCENARIO_FRONT_DANGER,
  MOCK_SCENARIO_GROUND_DROP,
  MOCK_SCENARIO_BLOCKED,
  MOCK_SCENARIO_LEFT_OPEN,
  MOCK_SCENARIO_RIGHT_OPEN
};

bool tofBegin();
bool tofRead(DistanceReadings &out);
bool tofMockActive();
void tofSetMockScenario(MockScenario scenario);
const char *tofMockScenarioName();
