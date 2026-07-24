#include "risk_logic.h"

#include "config.h"
#include <math.h>

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
  if ((strcmp(candidate.riskType, "ground_drop") == 0 || strcmp(candidate.riskType, "ground_step") == 0 ||
       strcmp(candidate.riskType, "ground_step_down") == 0) &&
      strcmp(best.riskType, "ground_drop") != 0 &&
      strcmp(best.riskType, "ground_step") != 0 &&
      strcmp(best.riskType, "ground_step_down") != 0) {
    best = candidate;
    return;
  }
  if (strcmp(candidate.riskType, "front_obstacle") == 0 && strcmp(best.riskType, "front_obstacle") != 0) {
    best = candidate;
  }
}

static const char *downRiskReason = "clear";
enum GroundStepState { GROUND_NORMAL, GROUND_CANDIDATE_DOWN, GROUND_CONFIRMED, GROUND_WAIT_NEW_GROUND };
static GroundStepState groundState = GROUND_NORMAL;
static bool baselineReady = false;
static float baselineDownCm = 0.0f;
static float baselinePitchDeg = 0.0f;
static uint8_t baselineFrames = 0;
static uint8_t candidateFrames = 0;
static uint8_t groundClearFrames = 0;

void resetGroundStepDetector() {
  groundState = GROUND_NORMAL;
  baselineReady = false;
  baselineDownCm = 0.0f;
  baselinePitchDeg = 0.0f;
  baselineFrames = 0;
  candidateFrames = 0;
  groundClearFrames = 0;
}

int groundBaselineDownCm() {
  return baselineReady ? (int)roundf(baselineDownCm) : -1;
}

static int downRiskCm(const DistanceReadings &d) {
  return d.downRawCm > 0 ? d.downRawCm : d.downCm;
}

static const char *updateDownRiskState(const DistanceReadings &d, const ImuFallState &imu) {
  int cm = downRiskCm(d);
  downRiskReason = "clear";
  // 400 is the invalid/no-target array sentinel; never treat it as a pit.
  if (d.downNoTarget || cm >= SMARTCANE_DOWN_NO_TARGET_CM) {
    candidateFrames = 0;
    downRiskReason = "down_400_sentinel_ignored";
    return "none";
  }
  if (!d.downValid) {
    candidateFrames = 0;
    downRiskReason = "down_sensor_unavailable";
    return "down_sensor_unavailable";
  }

  bool poseUsable = !imu.available || fabsf(imu.totalG - 1.0f) <= SMARTCANE_DOWN_MAX_SWING_G;
  float compensatedCm = (float)cm;
  if (imu.available) {
    float angleRad = (SMARTCANE_DOWN_SENSOR_MOUNT_DEG + imu.pitchDeg) * DEG_TO_RAD;
    compensatedCm = (float)cm * fmaxf(0.35f, fabsf(cosf(angleRad)));
  }
  bool poseComparable = !imu.available ||
      fabsf(imu.pitchDeg - baselinePitchDeg) <= SMARTCANE_DOWN_POSE_DELTA_DEG;

  if (!baselineReady) {
    if (!poseUsable) {
      baselineFrames = 0;
      downRiskReason = "baseline_frozen_fast_swing";
      return "none";
    }
    if (baselineFrames == 0 ||
        fabsf(compensatedCm - baselineDownCm) > SMARTCANE_DOWN_BASELINE_TOLERANCE_CM) {
      baselineDownCm = compensatedCm;
      baselinePitchDeg = imu.pitchDeg;
      baselineFrames = 1;
    } else {
      baselineDownCm = (baselineDownCm * baselineFrames + compensatedCm) / (baselineFrames + 1);
      baselinePitchDeg = (baselinePitchDeg * baselineFrames + imu.pitchDeg) / (baselineFrames + 1);
      if (baselineFrames < 255) baselineFrames++;
    }
    if (baselineFrames >= SMARTCANE_DOWN_BASELINE_STABLE_FRAMES) {
      baselineReady = true;
      groundState = GROUND_NORMAL;
      downRiskReason = "baseline_ready";
    }
    return "none";
  }

  float heightDeltaCm = compensatedCm - baselineDownCm;
  bool alarmDistance = cm > SMARTCANE_DOWN_LONG_DISTANCE_ALARM_CM;
  bool alarmDelta = poseUsable && poseComparable &&
      heightDeltaCm >= SMARTCANE_DOWN_DROP_DELTA_CM;

  if (groundState == GROUND_CONFIRMED || groundState == GROUND_WAIT_NEW_GROUND) {
    groundState = GROUND_WAIT_NEW_GROUND;
    if (fabsf(heightDeltaCm) <= SMARTCANE_DOWN_DROP_CLEAR_DELTA_CM && poseUsable) {
      if (groundClearFrames < 255) groundClearFrames++;
    } else {
      groundClearFrames = 0;
    }
    if (groundClearFrames >= SMARTCANE_DOWN_DROP_CLEAR_FRAMES) {
      resetGroundStepDetector();
      downRiskReason = "step_cleared_wait_new_baseline";
      return "none";
    }
    downRiskReason = "ground_step_down_hysteresis_hold";
    return "ground_step_down";
  }

  if (alarmDistance || alarmDelta) {
    groundState = GROUND_CANDIDATE_DOWN;
    if (candidateFrames < 255) candidateFrames++;
    if (candidateFrames >= SMARTCANE_DOWN_DROP_CONFIRM_FRAMES) {
      groundState = GROUND_CONFIRMED;
      groundClearFrames = 0;
      downRiskReason = alarmDistance ?
          "down_distance_above_150cm" : "baseline_height_delta_at_least_20cm";
      return "ground_step_down";
    }
    downRiskReason = "ground_step_down_candidate";
    return "none";
  }

  candidateFrames = 0;
  groundState = GROUND_NORMAL;
  if (poseUsable && poseComparable &&
      fabsf(heightDeltaCm) <= SMARTCANE_DOWN_BASELINE_TOLERANCE_CM) {
    if (groundClearFrames < 255) groundClearFrames++;
    if (groundClearFrames >= SMARTCANE_DOWN_BASELINE_STABLE_FRAMES) {
      baselineDownCm = (baselineDownCm * 4.0f + compensatedCm) / 5.0f;
      baselinePitchDeg = (baselinePitchDeg * 4.0f + imu.pitchDeg) / 5.0f;
    }
  } else {
    groundClearFrames = 0;
  }
  return "none";
}

RiskState calculateRisk(const DistanceReadings &d, const NearbyRiskSummary &nearby,
                        const ImuFallState &imu) {
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

  const char *downRiskType = updateDownRiskState(d, imu);
  int downCmForRisk = downRiskCm(d);

  if (strcmp(downRiskType, "down_sensor_unavailable") == 0) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_MEDIUM;
    risk.riskType = "down_sensor_unavailable";
    risk.direction = "stop";
    risk.sensor = "tof_down";
    risk.reason = downRiskReason;
    risk.distanceMm = -1;
    risk.confidence = 0.70f;
    risk.groundDrop = true;
    risk.realtimeMedium = true;
    chooseMoreSevere(best, risk);
  } else if (strcmp(downRiskType, "down_no_target") == 0) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_MEDIUM;
    risk.riskType = "down_no_target";
    risk.direction = "stop";
    risk.sensor = "tof_down";
    risk.reason = downRiskReason;
    risk.distanceMm = SMARTCANE_DOWN_NO_TARGET_CM * 10;
    risk.confidence = 0.82f;
    risk.groundDrop = true;
    risk.realtimeMedium = true;
    chooseMoreSevere(best, risk);
  } else if (strcmp(downRiskType, "ground_step_down") == 0) {
    risk = RiskState();
    risk.detectedAtMs = millis();
    risk.level = RISK_MEDIUM;
    risk.riskType = "ground_step_down";
    risk.direction = "stop";
    risk.sensor = "tof_down";
    risk.reason = downRiskReason;
    risk.distanceMm = downCmForRisk * 10;
    risk.confidence = 0.86f;
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
