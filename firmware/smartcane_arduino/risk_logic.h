#pragma once

#include "data_model.h"

RiskState calculateRisk(const DistanceReadings &distances, const NearbyRiskSummary &nearby);
const char *riskDirectionLabel(const RiskState &risk);
void printRiskState(const RiskState &risk);
