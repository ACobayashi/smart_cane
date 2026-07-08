#pragma once

#include <Arduino.h>

#include "data_model.h"

RiskState calculateRisk(const DistanceReadings &d, const NearbyRiskSummary &history);
void printRiskState(const DistanceReadings &d, const RiskState &risk, const NearbyRiskSummary &history);
bool isGroundDropDistance(int downCm);

