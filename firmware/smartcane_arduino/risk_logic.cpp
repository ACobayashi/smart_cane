#include "risk_logic.h"

#include "config.h"

static bool isHistoricalMediumOrHigh(const NearbyRiskSummary &nearby) {
  return nearby.available && (nearby.maxLevel == RISK_MEDIUM || nearby.maxLevel == RISK_HIGH);
}

static void applyBestSide(RiskState &risk, const DistanceReadings &d) {
  if (d.leftValid && d.rightValid) {
    if (d.leftCm < SMARTCANE_SIDE_SAFE_CM && d.rightCm < SMARTCANE_SIDE_SAFE_CM) {
      risk.direction = "stop";
      return;
    }
    if (d.leftCm > d.rightCm && d.leftCm > SMARTCANE_SIDE_SAFE_CM) {
      risk.direction = "turn_left";
      return;
    }
    if (d.rightCm > d.leftCm && d.rightCm > SMARTCANE_SIDE_SAFE_CM) {
      risk.direction = "turn_right";
      return;
    }
  }
  risk.direction = "slow";
}

RiskState calculateRisk(const DistanceReadings &d, const NearbyRiskSummary &nearby) {
  RiskState risk;
  risk.detectedAtMs = millis();

  if (!d.valid) {
    risk.level = nearby.highCount >= 2 ? RISK_MEDIUM : RISK_LOW;
    risk.riskType = risk.level == RISK_MEDIUM ? "history_risk" : "sensor_unreliable";
    risk.direction = risk.level == RISK_MEDIUM ? "slow" : "none";
    risk.sensor = "tof";
    risk.reason = "tof_unavailable";
    risk.confidence = risk.level == RISK_MEDIUM ? 0.45f : 0.15f;
    risk.historyInfluenced = risk.level == RISK_MEDIUM;
    return risk;
  }

  if (d.downValid && d.downCm > SMARTCANE_GROUND_BASE_CM + SMARTCANE_GROUND_DROP_THRESHOLD_CM) {
    risk.level = RISK_HIGH;
    risk.riskType = "ground_drop";
    risk.direction = "stop";
    risk.sensor = "tof_down";
    risk.reason = "down_distance_exceeds_ground_baseline";
    risk.distanceMm = d.downCm * 10;
    risk.confidence = 0.95f;
    risk.groundDrop = true;
    risk.realtimeHigh = true;
    return risk;
  }

  if (d.frontValid && d.frontCm < SMARTCANE_FRONT_DANGER_CM) {
    risk.level = RISK_HIGH;
    risk.riskType = "front_obstacle";
    risk.sensor = "tof_front";
    risk.reason = "front_distance_below_danger_threshold";
    risk.distanceMm = d.frontCm * 10;
    risk.confidence = 0.9f;
    risk.frontObstacle = true;
    risk.realtimeHigh = true;
    applyBestSide(risk, d);
    return risk;
  }

  if (d.frontValid && d.frontCm < SMARTCANE_FRONT_WARN_CM) {
    risk.level = RISK_MEDIUM;
    risk.riskType = "front_obstacle";
    risk.sensor = "tof_front";
    risk.reason = "front_distance_below_warn_threshold";
    risk.distanceMm = d.frontCm * 10;
    risk.confidence = 0.72f;
    risk.frontObstacle = true;
    risk.realtimeMedium = true;
    applyBestSide(risk, d);

    if (isHistoricalMediumOrHigh(nearby)) {
      risk.level = RISK_HIGH;
      risk.reason = "front_warn_plus_nearby_history";
      risk.historyInfluenced = true;
      risk.confidence = 0.82f;
    }
    return risk;
  }

  if (d.leftValid && d.leftCm < SMARTCANE_SIDE_NEAR_CM) {
    risk.level = RISK_MEDIUM;
    risk.riskType = "left_obstacle";
    risk.direction = "keep_right";
    risk.sensor = "tof_left";
    risk.reason = "left_side_too_close";
    risk.distanceMm = d.leftCm * 10;
    risk.confidence = 0.62f;
    risk.sideObstacle = true;
    risk.realtimeMedium = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.level = RISK_HIGH;
      risk.historyInfluenced = true;
      risk.reason = "left_side_close_plus_nearby_history";
    }
    return risk;
  }

  if (d.rightValid && d.rightCm < SMARTCANE_SIDE_NEAR_CM) {
    risk.level = RISK_MEDIUM;
    risk.riskType = "right_obstacle";
    risk.direction = "keep_left";
    risk.sensor = "tof_right";
    risk.reason = "right_side_too_close";
    risk.distanceMm = d.rightCm * 10;
    risk.confidence = 0.62f;
    risk.sideObstacle = true;
    risk.realtimeMedium = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.level = RISK_HIGH;
      risk.historyInfluenced = true;
      risk.reason = "right_side_close_plus_nearby_history";
    }
    return risk;
  }

  if (nearby.available && nearby.highCount >= 2) {
    risk.level = RISK_MEDIUM;
    risk.riskType = "history_risk";
    risk.direction = "slow";
    risk.sensor = "backend_history";
    risk.reason = "nearby_history_high_count";
    risk.confidence = 0.55f;
    risk.historyInfluenced = true;
    return risk;
  }

  risk.level = RISK_LOW;
  risk.riskType = "none";
  risk.direction = "none";
  risk.sensor = "tof";
  risk.reason = "clear";
  risk.confidence = d.valid ? 0.7f : 0.2f;
  return risk;
}

const char *riskDirectionLabel(const RiskState &risk) {
  return risk.direction;
}

void printRiskState(const RiskState &risk) {
  Serial.print(F("risk="));
  Serial.print(riskLevelToString(risk.level));
  Serial.print(F(" type="));
  Serial.print(risk.riskType);
  Serial.print(F(" direction="));
  Serial.print(risk.direction);
  Serial.print(F(" sensor="));
  Serial.print(risk.sensor);
  Serial.print(F(" confidence="));
  Serial.print(risk.confidence, 2);
  Serial.print(F(" reason="));
  Serial.println(risk.reason);
}
