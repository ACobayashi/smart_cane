#pragma once

#include <Arduino.h>

#include "data_model.h"

bool connectWifi();
bool isNetworkAvailable();
void setNetworkLocation(const LocationData &location);
bool uploadLocation(const LocationData &location);
bool uploadEvent(const String &riskType,
                 const String &riskLevel,
                 int frontCm,
                 int leftCm,
                 int rightCm,
                 int downCm,
                 const String &extra);
bool fetchNearbyRisks(float lat, float lng, NearbyRiskSummary &out);
bool fetchAiAdvice(const RiskState &risk,
                   const DistanceReadings &distances,
                   const NearbyRiskSummary &history,
                   String &outAdvice);
bool sendTextVoiceCommand(const String &text, String &outReply);
