#pragma once

#include "data_model.h"
#include "imu_fall.h"

RiskState calculateRisk(const DistanceReadings &distances, const NearbyRiskSummary &nearby,
                        const ImuFallState &imu);
void resetGroundStepDetector();
int groundBaselineDownCm();
const char *riskDirectionLabel(const RiskState &risk);
void printRiskState(const RiskState &risk);
