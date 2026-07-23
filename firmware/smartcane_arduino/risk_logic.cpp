#include "risk_logic.h"

#include "config.h"

static bool isHistoricalMediumOrHigh(const NearbyRiskSummary &nearby) {
  return nearby.available && (nearby.maxLevel == RISK_MEDIUM || nearby.maxLevel == RISK_HIGH);
}

static void applyBestSide(RiskState &risk, const DistanceReadings &d) {
  if (d.leftValid && d.rightValid) {
    if (d.leftCm < SMARTCANE_SIDE_BLOCKED_CM && d.rightCm < SMARTCANE_SIDE_BLOCKED_CM) {
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
  if (d.leftValid && !d.rightValid) {
    risk.direction = d.leftCm > SMARTCANE_SIDE_SAFE_CM ? "turn_left" : "slow";
    return;
  }
  if (!d.leftValid && d.rightValid) {
    risk.direction = d.rightCm > SMARTCANE_SIDE_SAFE_CM ? "turn_right" : "slow";
    return;
  }
  risk.direction = "slow";
}

static void chooseMoreSevere(RiskState& best, const RiskState& candidate) {
    // 当前还没有风险时，即使候选风险是 LOW，也要保存下来。
    if (strcmp(best.riskType, "none") == 0 &&
        strcmp(candidate.riskType, "none") != 0) {
        best = candidate;
        return;
    }

    if (candidate.level > best.level) {
        best = candidate;
        return;
    }
  if (candidate.level < best.level) {
    return;
  }
  if (strcmp(candidate.direction, "stop") == 0 && strcmp(best.direction, "stop") != 0) {
    best = candidate;
    return;
  }
  if ((strcmp(candidate.riskType, "ground_drop") == 0 || strcmp(candidate.riskType, "ground_step") == 0) &&
      strcmp(best.riskType, "ground_drop") != 0 &&
      strcmp(best.riskType, "ground_step") != 0) {
    best = candidate;
    return;
  }
  if (strcmp(candidate.riskType, "front_obstacle") == 0 && strcmp(best.riskType, "front_obstacle") != 0) {
    best = candidate;
  }
}

static bool isDownNoTargetCm(int cm) {
  return cm >= SMARTCANE_DOWN_NO_TARGET_CM;
}

static bool updateDownDropDisturbance(const DistanceReadings &d) {
  static int lastUsableDownCm = 0;
  static bool hasLastUsableDown = false;
  static unsigned long dropHoldUntilMs = 0;

  unsigned long now = millis();
  if (!d.downValid || d.downCm <= 0 || isDownNoTargetCm(d.downCm)) {
    hasLastUsableDown = false;
    dropHoldUntilMs = 0;
    return false;
  }

  if (d.downCm <= SMARTCANE_DOWN_DROP_CM) {
    lastUsableDownCm = d.downCm;
    hasLastUsableDown = true;
    dropHoldUntilMs = 0;
    return false;
  }

  bool jumpedFromNormalGround =
      hasLastUsableDown &&
      lastUsableDownCm <= SMARTCANE_DOWN_DROP_CM &&
      d.downCm > SMARTCANE_DOWN_DROP_CM &&
      d.downCm - lastUsableDownCm >= SMARTCANE_DOWN_DISTURBANCE_CM;

  if (jumpedFromNormalGround) {
    dropHoldUntilMs = now + SMARTCANE_DOWN_EVENT_HOLD_MS;
  }

  bool holdActive = d.downCm > SMARTCANE_DOWN_DROP_CM &&
                    (long)(dropHoldUntilMs - now) > 0;

  lastUsableDownCm = d.downCm;
  hasLastUsableDown = true;
  return jumpedFromNormalGround || holdActive;
}

RiskState calculateRisk(const DistanceReadings &d, const NearbyRiskSummary &nearby) {
  RiskState risk;
  risk.detectedAtMs = millis();

  if (!d.valid) {
    risk.level = RISK_LOW;
    risk.riskType = "sensor_unreliable";
    risk.direction = "none";
    risk.sensor = "tof";
    risk.reason = "tof_unavailable";
    risk.confidence = 0.15f;
    risk.historyInfluenced = false;
    return risk;
  }

  RiskState best;
  best.detectedAtMs = risk.detectedAtMs;
  best.level = RISK_LOW;
  best.riskType = "none";
  best.direction = "none";
  best.sensor = "tof";
  best.reason = "clear";
  best.confidence = d.valid ? 0.7f : 0.2f;

  bool downDropEvent = updateDownDropDisturbance(d);

  if (d.downValid && d.downCm > 0 && d.downCm <= SMARTCANE_DOWN_OBSTACLE_CM) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_LOW;
    risk.riskType = "down_obstacle";
    risk.direction = "slow";
    risk.sensor = "tof_down";
    risk.reason = "down_distance_below_obstacle_threshold";
    risk.distanceMm = d.downCm * 10;
    risk.confidence = 0.65f;
    risk.groundDrop = true;
    chooseMoreSevere(best, risk);
  }

  bool downDropNow = d.downValid &&
                     d.downCm > SMARTCANE_DOWN_DROP_CM &&
                     d.downCm <= SMARTCANE_DOWN_NO_TARGET_CM;
  if (downDropEvent || downDropNow) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_MEDIUM;
    risk.riskType = "ground_drop";
    risk.direction = "stop";
    risk.sensor = "tof_down";
    risk.reason = downDropNow ? "down_distance_above_drop_threshold" : "down_distance_jump_above_drop_threshold";
    risk.distanceMm = d.downCm * 10;
    risk.confidence = downDropNow ? 0.78f : 0.86f;
    risk.groundDrop = true;
    risk.realtimeMedium = true;
    chooseMoreSevere(best, risk);
  }

  if (d.frontValid && d.frontCm < SMARTCANE_FRONT_DANGER_CM) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_HIGH;
    risk.riskType = "front_obstacle";
    risk.sensor = "tof_front";
    risk.reason = "front_distance_below_danger_threshold";
    risk.distanceMm = d.frontCm * 10;
    risk.confidence = 0.88f;
    risk.frontObstacle = true;
    risk.realtimeHigh = true;
    applyBestSide(risk, d);
    chooseMoreSevere(best, risk);
  }

  if (d.frontValid && d.frontCm < SMARTCANE_FRONT_WARN_CM) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_LOW;
    risk.riskType = "front_obstacle";
    risk.sensor = "tof_front";
    risk.reason = "front_distance_below_warn_threshold";
    risk.distanceMm = d.frontCm * 10;
    risk.confidence = 0.45f;
    risk.frontObstacle = true;
    applyBestSide(risk, d);

    if (isHistoricalMediumOrHigh(nearby)) {
      risk.reason = "front_warn_plus_nearby_history";
      risk.historyInfluenced = true;
      risk.confidence = 0.55f;
    }
    chooseMoreSevere(best, risk);
  }

  if (d.leftValid && d.leftCm < SMARTCANE_SIDE_NEAR_CM) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_LOW;
    risk.riskType = "left_obstacle";
    risk.direction = "keep_right";
    risk.sensor = "tof_left";
    risk.reason = "left_side_too_close";
    risk.distanceMm = d.leftCm * 10;
    risk.confidence = d.leftCm < SMARTCANE_SIDE_DANGER_CM ? 0.60f : 0.42f;
    risk.sideObstacle = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.historyInfluenced = true;
      risk.reason = "left_side_close_plus_nearby_history";
    }
    chooseMoreSevere(best, risk);
  }

  if (d.rightValid && d.rightCm < SMARTCANE_SIDE_NEAR_CM) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_LOW;
    risk.riskType = "right_obstacle";
    risk.direction = "keep_left";
    risk.sensor = "tof_right";
    risk.reason = "right_side_too_close";
    risk.distanceMm = d.rightCm * 10;
    risk.confidence = d.rightCm < SMARTCANE_SIDE_DANGER_CM ? 0.60f : 0.42f;
    risk.sideObstacle = true;
    if (isHistoricalMediumOrHigh(nearby)) {
      risk.historyInfluenced = true;
      risk.reason = "right_side_close_plus_nearby_history";
    }
    chooseMoreSevere(best, risk);
  }

  if (strcmp(best.riskType, "none") != 0) {
      return best;
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
