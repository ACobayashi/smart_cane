#pragma once

#include "data_model.h"

bool connectWifi();
bool networkAvailable();
void networkClientUpdate();

bool uploadLocation(const LocationData &location);
bool uploadRiskEvent(const char *riskType,
                     const char *riskLevel,
                     const char *direction,
                     const char *sensor,
                     int distanceMm,
                     const DistanceReadings &distances,
                     const LocationData &location,
                     const char *extra);
bool uploadEvent(const RiskState &risk,
                 const DistanceReadings &distances,
                 const LocationData &location,
                 const char *extra);

bool fetchNearbyRisks(double lat, double lng, NearbyRiskSummary &out);
bool fetchDeepRisk(const RiskState &risk,
                   const DistanceReadings &distances,
                   const LocationData &location,
                   DeepRiskResult &out);

void printNearbySummary(const NearbyRiskSummary &summary);
void printDeepRisk(const DeepRiskResult &result);
