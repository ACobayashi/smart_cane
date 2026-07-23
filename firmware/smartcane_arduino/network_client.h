#pragma once

#include "data_model.h"
#include "imu_fall.h"

bool connectWifi();
bool networkAvailable();
void networkClientUpdate();
void printWifiDiagnostics();
void scanWifiNetworks();

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
bool uploadSensorFrame(const RiskState &risk,
                       const DistanceReadings &distances,
                       const LocationData &location,
                       const ImuFallState &fall,
                       const char *alertType,
                       const char *extra,
                       const char *buttonEvent = nullptr);

bool fetchNearbyRisks(double lat, double lng, NearbyRiskSummary &out);
bool fetchDeepRisk(const RiskState &risk,
                   const DistanceReadings &distances,
                   const LocationData &location,
                   DeepRiskResult &out);

void printNearbySummary(const NearbyRiskSummary &summary);
void printDeepRisk(const DeepRiskResult &result);
