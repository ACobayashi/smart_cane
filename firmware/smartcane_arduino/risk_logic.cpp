#include "risk_logic.h"

#include "config.h"

bool isGroundDropDistance(int downCm) {
  return downCm > (GROUND_BASE_CM + GROUND_DROP_THRESHOLD_CM);
}

static RiskState makeLowRisk() {
  RiskState state;
  state.level = RISK_LOW;
  state.riskType = "none";
  state.directionHint = "none";
  state.reason = "clear";
  state.groundDrop = false;
  state.frontObstacle = false;
  state.sideObstacle = false;
  state.realtimeMedium = false;
  state.realtimeHigh = false;
  state.historyInfluenced = false;
  state.detectedAtMs = millis();
  return state;
}

RiskState calculateRisk(const DistanceReadings &d, const NearbyRiskSummary &history) {
  RiskState state = makeLowRisk();
  state.detectedAtMs = millis();

  bool groundDrop = isGroundDropDistance(d.downCm);
  bool frontDanger = d.frontCm < FRONT_DANGER_CM;
  bool frontWarn = d.frontCm < FRONT_WARN_CM;
  bool sideNear = d.leftCm < SIDE_NEAR_CM || d.rightCm < SIDE_NEAR_CM;

  if (groundDrop) {
    state.level = RISK_HIGH;
    state.riskType = "ground_drop";
    state.directionHint = "stop";
    state.reason = "realtime ground drop";
    state.groundDrop = true;
    state.realtimeHigh = true;
    return state;
  }

  if (frontDanger) {
    state.level = RISK_HIGH;
    state.riskType = "front_obstacle";
    state.directionHint = "avoid";
    state.reason = "front distance below danger threshold";
    state.frontObstacle = true;
    state.realtimeHigh = true;
    return state;
  }

  if (frontWarn) {
    state.level = RISK_MEDIUM;
    state.riskType = "front_obstacle";
    state.directionHint = "slow";
    state.reason = "front distance below warning threshold";
    state.frontObstacle = true;
    state.realtimeMedium = true;
  } else if (sideNear) {
    state.level = RISK_MEDIUM;
    state.riskType = d.leftCm < d.rightCm ? "left_obstacle" : "right_obstacle";
    state.directionHint = d.leftCm < d.rightCm ? "keep_right" : "keep_left";
    state.reason = "side distance below near threshold";
    state.sideObstacle = true;
    state.realtimeMedium = true;
  }

  bool historyMediumOrHigh = history.available && history.maxLevel >= RISK_MEDIUM;
  if (state.realtimeMedium && historyMediumOrHigh) {
    state.level = RISK_HIGH;
    state.historyInfluenced = true;
    state.reason = "realtime medium risk plus nearby history";
    return state;
  }

  if (state.level == RISK_LOW && history.available && history.highCount >= 2) {
    state.level = RISK_MEDIUM;
    state.riskType = "history_risk";
    state.directionHint = "slow";
    state.reason = "nearby history has at least two high risk events";
    state.historyInfluenced = true;
    return state;
  }

  return state;
}

void printRiskState(const DistanceReadings &d, const RiskState &risk, const NearbyRiskSummary &history) {
  Serial.print("DIST cm front=");
  Serial.print(d.frontCm);
  Serial.print(" left=");
  Serial.print(d.leftCm);
  Serial.print(" right=");
  Serial.print(d.rightCm);
  Serial.print(" down=");
  Serial.print(d.downCm);
  Serial.print(" | risk=");
  Serial.print(riskLevelToString(risk.level));
  Serial.print(" type=");
  Serial.print(risk.riskType);
  Serial.print(" hint=");
  Serial.print(risk.directionHint);
  Serial.print(" reason=");
  Serial.print(risk.reason);
  Serial.print(" | history available=");
  Serial.print(history.available ? "yes" : "no");
  Serial.print(" count=");
  Serial.print(history.riskCount);
  Serial.print(" high=");
  Serial.print(history.highCount);
  Serial.print(" max=");
  Serial.println(riskLevelToString(history.maxLevel));
}

